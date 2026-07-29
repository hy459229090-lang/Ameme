package com.ameme.android.data.transport

import android.content.Context
import com.ameme.android.data.transport.channel.AndroidAuthenticatedPairingClient
import com.ameme.android.data.transport.channel.AndroidAuthenticatedPairingClientFactory
import com.ameme.android.data.transport.channel.AndroidLocalNodeChannelCodec
import com.ameme.android.data.transport.channel.AndroidLocalNodeNetworkClient
import com.ameme.android.data.transport.channel.AndroidPairingBootstrapNetworkClient
import com.ameme.android.data.transport.channel.AndroidPairingBootstrapProvisioner
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Release-path ordinary-user connector.
 *
 * DNS-SD may only resolve bounded display metadata. QR is the only path in this slice that can
 * provision a credential, and it returns a connection only after a fresh application-channel
 * authentication succeeds. Account-device authorization remains closed.
 */
class ProductionPairingExperienceConnector internal constructor(
    private val lanConnector: PairingExperienceConnector,
    private val credentialStore: AndroidPairingCredentialStoring,
    private val bootstrapProvisioner: AndroidPairingBootstrapProvisioner,
    private val clientFactory: AndroidAuthenticatedPairingClientFactory,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelaysMillis: List<Long> = listOf(100, 250, 500),
) : PairingExperienceConnector {
    init {
        require(
            retryDelaysMillis.size <= 3 &&
                retryDelaysMillis.all { it in 0..1_000 },
        )
    }

    constructor(
        context: Context,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        lanConnector = NsdPairingExperienceConnector(context),
        credentialStore = AndroidKeystorePairingCredentialStore(context, clock),
        bootstrapProvisioner = AndroidPairingBootstrapNetworkClient(clock = clock),
        clientFactory = AndroidAuthenticatedPairingClientFactory { pairing, secret ->
            AndroidLocalNodeNetworkClient(pairing, secret)
        },
        clock = clock,
        retryDelaysMillis = listOf(100, 250, 500),
    )

    private val pendingPayloads = ConcurrentHashMap<String, PendingPayload>()
    private val activeClients = ConcurrentHashMap<String, AndroidAuthenticatedPairingClient>()

    override val simulated: Boolean = false

    override suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate =
        when (method) {
            PairingExperienceMethod.LanDiscovery -> lanConnector.resolve(method)
            PairingExperienceMethod.QrCode -> throw PairingExperienceException(
                PairingExperienceFailure.QrScannerUnavailable,
            )
            PairingExperienceMethod.AccountDevice -> throw PairingExperienceException(
                PairingExperienceFailure.AccountSignInRequired,
            )
        }

    override suspend fun resolvePairingPayload(payload: String): PairingExperienceCandidate {
        val envelope = try {
            AgentPairingEnvelope.parse(payload, clock.instant())
        } catch (failure: Throwable) {
            throw PairingExperienceException(
                PairingExperienceFailure.InvalidPairingPayload,
                failure,
            )
        }
        return envelope.use {
            val candidate = PairingExperienceCandidate(
                id = it.pairing.pairingId,
                deviceName = "Ameme 设备",
                agentName = "Ameme Local Node",
                method = PairingExperienceMethod.QrCode,
                capabilities = listOf("写入结构化工作记录"),
                simulated = false,
                authorizationExpiresAt = it.expiresAt,
            )
            // Keep only the candidate currently visible to the user. This bounds short-lived
            // bootstrap material in process memory and makes an older confirmation fail closed.
            pendingPayloads.clear()
            pendingPayloads[candidate.id] = PendingPayload(
                payload = payload,
                expiresAt = it.expiresAt,
            )
            candidate
        }
    }

    override suspend fun connect(
        candidate: PairingExperienceCandidate,
    ): PairingExperienceConnection = withContext(Dispatchers.IO) {
        if (candidate.simulated) {
            throw PairingExperienceException(PairingExperienceFailure.CandidateUnavailable)
        }
        when (candidate.method) {
            PairingExperienceMethod.LanDiscovery -> lanConnector.connect(candidate)
            PairingExperienceMethod.AccountDevice -> throw PairingExperienceException(
                PairingExperienceFailure.AccountSignInRequired,
            )
            PairingExperienceMethod.QrCode -> connectQrCandidate(candidate)
        }
    }

    override suspend fun disconnect(
        connection: PairingExperienceConnection,
    ) = withContext(Dispatchers.IO) {
        activeClients.remove(connection.id)?.close()
        credentialStore.clearAndVerify()
    }

    override suspend fun restoreConnection(): PairingExperienceConnection? =
        withContext(Dispatchers.IO) {
            var active = try {
                credentialStore.loadActive(clock.instant())
            } catch (failure: Throwable) {
                throw PairingExperienceException(
                    PairingExperienceFailure.CandidateUnavailable,
                    failure,
                )
            }
            if (active == null) {
                val pending = try {
                    credentialStore.loadPending(now = clock.instant())
                } catch (failure: Throwable) {
                    throw PairingExperienceException(
                        PairingExperienceFailure.CandidateUnavailable,
                        failure,
                    )
                } ?: return@withContext null
                provisionPending(pending)
                active = credentialStore.loadActive(clock.instant())
                    ?: throw PairingExperienceException(
                        PairingExperienceFailure.CandidateUnavailable,
                    )
            }
            active.use { connectStored(it) }
        }

    override suspend fun clearLocalCredentials() = withContext(Dispatchers.IO) {
        activeClients.values.forEach { runCatching(it::close) }
        activeClients.clear()
        pendingPayloads.clear()
        credentialStore.clearAndVerify()
    }

    private suspend fun connectQrCandidate(
        candidate: PairingExperienceCandidate,
    ): PairingExperienceConnection {
        val now = clock.instant()
        if (candidate.authorizationExpiresAt?.isAfter(now) != true) {
            pendingPayloads.remove(candidate.id)
            throw PairingExperienceException(PairingExperienceFailure.CandidateUnavailable)
        }
        val inMemory = pendingPayloads.remove(candidate.id)
        val pending = try {
            if (inMemory != null && inMemory.expiresAt.isAfter(now)) {
                credentialStore.savePending(inMemory.payload, now)
            } else {
                credentialStore.loadPending(candidate.id, now)
            }
        } catch (failure: Throwable) {
            throw PairingExperienceException(
                PairingExperienceFailure.AuthorizationRequired,
                failure,
            )
        } ?: throw PairingExperienceException(PairingExperienceFailure.CandidateUnavailable)
        provisionPending(pending)
        val active = try {
            credentialStore.loadActive(clock.instant())
        } catch (failure: Throwable) {
            throw PairingExperienceException(
                PairingExperienceFailure.AuthorizationRequired,
                failure,
            )
        } ?: throw PairingExperienceException(PairingExperienceFailure.AuthorizationRequired)
        return active.use { connectStored(it) }
    }

    private suspend fun provisionPending(
        pending: AndroidPendingPairingCredential,
    ) {
        val clientKey = try {
            credentialStore.clientKey(pending)
        } catch (failure: Throwable) {
            throw PairingExperienceException(
                PairingExperienceFailure.AuthorizationRequired,
                failure,
            )
        }
        val issued = try {
            bootstrapProvisioner.provision(pending.pairingPayload, clientKey)
        } catch (failure: Throwable) {
            throw PairingExperienceException(
                PairingExperienceFailure.ConnectionFailed,
                failure,
            )
        }
        issued.use {
            try {
                credentialStore.saveActive(it)
            } catch (failure: Throwable) {
                throw PairingExperienceException(
                    PairingExperienceFailure.AuthorizationRequired,
                    failure,
                )
            }
        }
    }

    private suspend fun connectStored(
        stored: AndroidStoredPairingCredential,
    ): PairingExperienceConnection {
        if (!stored.expiresAt.isAfter(clock.instant())) {
            credentialStore.clearAndVerify()
            throw PairingExperienceException(PairingExperienceFailure.CandidateUnavailable)
        }
        var lastFailure: Throwable? = null
        for (attempt in 0..retryDelaysMillis.size) {
            val secret = stored.channelSecretCopy()
            val client = try {
                clientFactory.create(stored.pairing, secret)
            } finally {
                secret.fill(0)
            }
            try {
                client.connect()
                if (
                    AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT !in
                    client.supportedOperations
                ) {
                    throw IllegalStateException("pairing_create_event_capability_missing")
                }
                activeClients.put(stored.pairing.pairingId, client)?.close()
                val connectedAt = clock.instant()
                return PairingExperienceConnection(
                    id = stored.pairing.pairingId,
                    deviceName = "Ameme 设备",
                    agentName = "Ameme Local Node",
                    method = PairingExperienceMethod.QrCode,
                    capabilities = listOf("写入结构化工作记录"),
                    connectedAt = connectedAt,
                    expiresAt = stored.expiresAt,
                    simulated = false,
                )
            } catch (failure: Throwable) {
                client.close()
                lastFailure = failure
                if (attempt < retryDelaysMillis.size) {
                    delay(retryDelaysMillis[attempt])
                }
            }
        }
        throw PairingExperienceException(
            PairingExperienceFailure.ConnectionFailed,
            lastFailure,
        )
    }

    private data class PendingPayload(
        val payload: String,
        val expiresAt: Instant,
    )
}
