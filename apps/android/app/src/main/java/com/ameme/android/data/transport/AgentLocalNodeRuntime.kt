package com.ameme.android.data.transport

import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.data.transport.channel.AndroidLocalNodeApplicationRequest
import com.ameme.android.data.transport.channel.AndroidLocalNodeApplicationRequestHandler
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial
import com.ameme.android.data.transport.channel.SingleConnectionTlsLocalNodeListener
import java.io.Closeable
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocketFactory
import kotlinx.coroutines.runBlocking

enum class AgentLocalNodeRuntimeState {
    Starting,
    Listening,
    ConnectionHandled,
    RecoverableError,
    Stopped,
}

/**
 * Android lifecycle owner for the paired Agent Local Node channel.
 *
 * The paired TLS/HMAC channel is the MVP root of trust. Inner caller/grant identifiers are still
 * checked and durably bound to idempotency, but a future shared account Grant registry must replace
 * this pairing-scoped claim binding before cross-account or unattended internet transport exists.
 */
class AgentLocalNodeRuntime private constructor(
    private val repository: LocalMemoryRepository,
    private val pairing: ActiveAgentPairing,
    private val accessGrantPolicy: AgentAccessGrantPolicy,
    private val sslServerSocketFactory: SSLServerSocketFactory,
    private val clock: Clock,
    private val onStateChanged: (AgentLocalNodeRuntimeState) -> Unit,
    private val onEventPersisted: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val listener = AtomicReference<SingleConnectionTlsLocalNodeListener?>(null)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ameme-agent-local-node").apply { isDaemon = true }
    }
    private val grantedMemoryTypes = accessGrantPolicy.dataTypes.intersect(
        setOf(
            MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT,
            MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_REVISION,
        ),
    )
    private val grantedOperations = buildSet {
        if (
            MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT in grantedMemoryTypes &&
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT in accessGrantPolicy.operations
        ) {
            add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT)
        }
        if (
            MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_REVISION in grantedMemoryTypes &&
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION in accessGrantPolicy.operations
        ) {
            add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION)
        }
        if (
            grantedMemoryTypes.isNotEmpty() &&
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE in accessGrantPolicy.operations
        ) {
            add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE)
        }
        if (
            MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT in grantedMemoryTypes &&
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_VISIBLE_EVENTS in accessGrantPolicy.operations
        ) {
            add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_VISIBLE_EVENTS)
        }
    }

    private val channelPairing = AndroidLocalNodePairingMaterial(
        channelProtocol = pairing.material.channelProtocol,
        endpointRef = pairing.material.endpointRef,
        credentialRef = pairing.material.credentialRef,
        expectedDeviceId = pairing.material.expectedDeviceId,
        sessionBindingRef = pairing.material.sessionBindingRef,
        pairingId = pairing.material.pairingId,
        host = pairing.material.host,
        port = pairing.material.port,
        tlsCertificateSha256 = pairing.material.tlsCertificateSha256,
    )

    private fun start() {
        onStateChanged(AgentLocalNodeRuntimeState.Starting)
        executor.execute(::serveLoop)
    }

    private fun serveLoop() {
        while (!closed.get() && pairing.expiresAt.isAfter(clock.instant())) {
            val next = try {
                SingleConnectionTlsLocalNodeListener(
                    pairing = channelPairing,
                    pairingSecret = pairing.secret,
                    sslServerSocketFactory = sslServerSocketFactory,
                    applicationRequestHandler = AndroidLocalNodeApplicationRequestHandler(::handleApplicationRequest),
                    supportedOperations = grantedOperations,
                )
            } catch (_: Exception) {
                signal(AgentLocalNodeRuntimeState.RecoverableError)
                break
            }
            listener.set(next)
            try {
                signal(AgentLocalNodeRuntimeState.Listening)
                next.serveSingleConnection()
                if (!closed.get()) signal(AgentLocalNodeRuntimeState.ConnectionHandled)
            } catch (_: Exception) {
                if (!closed.get()) {
                    signal(AgentLocalNodeRuntimeState.RecoverableError)
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                listener.compareAndSet(next, null)
                next.close()
            }
        }
        signal(AgentLocalNodeRuntimeState.Stopped)
    }

    private fun handleApplicationRequest(outer: AndroidLocalNodeApplicationRequest): ByteArray {
        val applicationLine = outer.applicationLineCopy()
        val request = try {
            AgentLocalNodeApplicationCodec.decodeRequestEnvelope(applicationLine)
        } finally {
            applicationLine.fill(0)
        }
        val control = request.control
        if (
            outer.requestId != control.requestId ||
            outer.operation != control.operation ||
            outer.idempotencyRef != control.idempotencySlot
        ) {
            request.close()
            throw IllegalArgumentException("agent_local_node_outer_inner_binding_mismatch")
        }
        val verifiedSession = VerifiedAgentLocalNodeSession(
            callerId = control.callerId,
            grantId = control.grantId,
            purpose = control.purpose,
            allowedSpaces = setOf(LocalEventDatabase.DEFAULT_SPACE_ID),
            allowedMemoryTypes = grantedMemoryTypes,
            allowedOperations = grantedOperations,
            allowedSensitivities = setOf("public", "personal", "confidential"),
            allowedDataClasses = setOf("structured"),
            expiresAt = pairing.expiresAt,
            accessGrant = accessGrantPolicy.bind(
                callerId = control.callerId,
                grantId = control.grantId,
            ),
        )
        val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
            repository = repository,
            repositorySpaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
            verifiedSession = verifiedSession,
            idempotencyRegistry = repository.durableAgentIdempotencyRegistry(),
            accessAuditSink = repository.durableAgentAccessAuditSink(),
            clock = clock,
        )
        val response = runBlocking { endpoint.exchange(request) }
        return try {
            val encoded = AgentLocalNodeApplicationCodec.encodeResponseEnvelope(response)
            if (
                response.status == AgentLocalNodeStatus.Ok &&
                control.operation in setOf(
                    MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT,
                    MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION,
                    MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE,
                )
            ) {
                runCatching(onEventPersisted)
            }
            encoded
        } finally {
            response.close()
            endpoint.close()
        }
    }

    private fun signal(state: AgentLocalNodeRuntimeState) {
        runCatching { onStateChanged(state) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.getAndSet(null)?.close()
        executor.shutdownNow()
        pairing.close()
        signal(AgentLocalNodeRuntimeState.Stopped)
    }

    /**
     * Deletion/revocation boundary: stop accepting work and wait until the single request worker
     * has actually exited. A timeout is reported to the caller instead of being mislabeled as a
     * completed local-authorization shutdown.
     */
    fun closeAndAwait(timeoutMillis: Long = CLOSE_AWAIT_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis in 1..30_000)
        close()
        return try {
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        private const val RETRY_DELAY_MILLIS = 250L
        private const val CLOSE_AWAIT_TIMEOUT_MILLIS = 5_000L

        fun launch(
            repository: LocalMemoryRepository,
            pairing: ActiveAgentPairing,
            accessGrantPolicy: AgentAccessGrantPolicy = pairing.accessGrantPolicy,
            sslServerSocketFactory: SSLServerSocketFactory,
            clock: Clock = Clock.systemUTC(),
            onStateChanged: (AgentLocalNodeRuntimeState) -> Unit = {},
            onEventPersisted: () -> Unit = {},
        ): AgentLocalNodeRuntime = AgentLocalNodeRuntime(
            repository = repository,
            pairing = pairing,
            accessGrantPolicy = accessGrantPolicy,
            sslServerSocketFactory = sslServerSocketFactory,
            clock = clock,
            onStateChanged = onStateChanged,
            onEventPersisted = onEventPersisted,
        ).also(AgentLocalNodeRuntime::start)
    }
}
