package com.ameme.android.data.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real local-network discovery boundary for ordinary Agent connections. */
class NsdPairingExperienceConnector(
    context: Context,
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
) : PairingExperienceConnector {
    private val nsdManager = requireNotNull(
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager,
    )
    private val resolvedServices = ConcurrentHashMap<String, NsdServiceInfo>()

    override val simulated: Boolean = false

    override suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate {
        return when (method) {
            PairingExperienceMethod.LanDiscovery -> discoverOne()
            PairingExperienceMethod.QrCode -> throw PairingExperienceException(
                PairingExperienceFailure.QrScannerUnavailable,
            )
            PairingExperienceMethod.AccountDevice -> throw PairingExperienceException(
                PairingExperienceFailure.AccountSignInRequired,
            )
        }
    }

    override suspend fun connect(candidate: PairingExperienceCandidate): PairingExperienceConnection {
        check(!candidate.simulated) { "A real connector cannot accept simulated candidates" }
        check(candidate.method == PairingExperienceMethod.LanDiscovery) {
            "NSD connector only accepts LAN candidates"
        }
        check(resolvedServices.containsKey(candidate.id)) {
            "The discovered candidate is no longer available"
        }
        // Discovery is intentionally separate from authentication. A visible DNS-SD service
        // proves only that a peer advertised itself; it does not grant access to Personal data.
        throw PairingExperienceException(PairingExperienceFailure.AuthorizationRequired)
    }

    override suspend fun disconnect(connection: PairingExperienceConnection) = Unit

    private suspend fun discoverOne(): PairingExperienceCandidate =
        suspendCancellableCoroutine { continuation ->
            var completed = false
            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun stopDiscovery() {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }

            fun fail(failure: PairingExperienceFailure, cause: Throwable? = null) {
                if (completed) return
                completed = true
                stopDiscovery()
                continuation.resumeWithException(PairingExperienceException(failure, cause))
            }

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    fail(PairingExperienceFailure.DiscoveryUnavailable)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (completed) return
                    val candidate = runCatching {
                        PairingExperienceServiceMetadata.candidate(
                            serviceName = serviceInfo.serviceName,
                            attributes = serviceInfo.attributes.toStringAttributes(),
                        )
                    }.getOrElse {
                        fail(PairingExperienceFailure.DiscoveryUnavailable, it)
                        return
                    }
                    resolvedServices[candidate.id] = serviceInfo
                    completed = true
                    stopDiscovery()
                    continuation.resume(candidate)
                }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (completed || serviceInfo.serviceType != SERVICE_TYPE) return
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                        .onFailure { fail(PairingExperienceFailure.DiscoveryUnavailable, it) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    fail(PairingExperienceFailure.DiscoveryUnavailable)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (!completed) fail(PairingExperienceFailure.DiscoveryUnavailable)
                }
            }

            continuation.invokeOnCancellation { stopDiscovery() }
            runCatching {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure { fail(PairingExperienceFailure.DiscoveryUnavailable, it) }

            // Do not leave a discovery listener alive indefinitely if the network is quiet.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!completed) fail(PairingExperienceFailure.NoDeviceFound)
            }, discoveryTimeoutMillis)
        }

    companion object {
        const val SERVICE_TYPE = "_ameme-agent._tcp."
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 5_000L
    }
}

internal object PairingExperienceServiceMetadata {
    fun candidate(
        serviceName: String,
        attributes: Map<String, String>,
    ): PairingExperienceCandidate {
        val id = sanitizeIdentifier(attributes[KEY_DEVICE_ID].orEmpty().ifBlank { serviceName })
        val deviceName = bounded(attributes[KEY_DEVICE_NAME].orEmpty().ifBlank { serviceName }, 80)
        val agentName = bounded(attributes[KEY_AGENT_NAME].orEmpty().ifBlank { "Ameme Agent" }, 80)
        val capabilities = attributes[KEY_CAPABILITIES]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.take(8)
            ?.map { bounded(it, 80) }
            ?.ifEmpty { null }
            ?: listOf("能力待授权确认")
        return PairingExperienceCandidate(
            id = id,
            deviceName = deviceName,
            agentName = agentName,
            method = PairingExperienceMethod.LanDiscovery,
            capabilities = capabilities,
            simulated = false,
        )
    }

    private fun sanitizeIdentifier(value: String): String {
        val normalized = value.filter { it.isLetterOrDigit() || it in "._:-" }.take(127)
        return if (normalized.firstOrNull()?.isLetterOrDigit() == true) normalized else "nsd-agent"
    }

    private fun bounded(value: String, limit: Int): String = value.take(limit).ifBlank { "未命名设备" }

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_AGENT_NAME = "agent_name"
    private const val KEY_CAPABILITIES = "capabilities"
}

private fun Map<String, ByteArray>.toStringAttributes(): Map<String, String> =
    mapNotNull { (key, value) ->
        val decoded = value.toString(Charsets.UTF_8).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        key to decoded.take(512)
    }.toMap()
