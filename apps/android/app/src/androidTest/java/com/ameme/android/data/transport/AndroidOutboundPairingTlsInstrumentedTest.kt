package com.ameme.android.data.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.transport.channel.AndroidAuthenticatedPairingClientFactory
import com.ameme.android.data.transport.channel.AndroidLocalNodeChannelCodec
import com.ameme.android.data.transport.channel.AndroidLocalNodeNetworkClient
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial
import com.ameme.android.data.transport.channel.AndroidPairingBootstrapHandler
import com.ameme.android.data.transport.channel.AndroidPairingBootstrapNetworkClient
import com.ameme.android.data.transport.channel.SingleConnectionTlsLocalNodeListener
import com.ameme.android.data.transport.channel.SingleConnectionTlsResult
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loopback transport proof for the real Android Keystore, TLS 1.3, QR bootstrap, issued
 * application credential, and reconnect client. This is not an optical camera or LAN-device Gate.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOutboundPairingTlsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun qrBootstrapIssuesASeparateCredentialThatAuthenticatesTheApplicationChannel() = runBlocking {
        val manager = AgentPairingManager(
            context = context,
            enableDeveloperCredential = false,
        )
        val credentialStore = AndroidKeystorePairingCredentialStore(context)
        runCatching(manager::revoke)
        runCatching(credentialStore::clearAndVerify)
        val executor = Executors.newSingleThreadExecutor()
        val activeListener = AtomicReference<SingleConnectionTlsLocalNodeListener?>()
        var serverFuture: Future<List<SingleConnectionTlsResult>>? = null
        var connector: ProductionPairingExperienceConnector? = null
        try {
            val port = reserveLoopbackPort()
            val created = manager.create(host = LOOPBACK, port = port)
            val firstListenerReady = CountDownLatch(1)
            serverFuture = executor.submit<List<SingleConnectionTlsResult>> {
                buildList {
                    repeat(2) { phase ->
                        val active = requireNotNull(manager.loadActive())
                        val listener = active.use { pairing ->
                            val secrets = pairing.credentials
                                .filter { it.kind == AgentPairingCredentialKind.DeviceBootstrapV2 }
                                .map(ActiveAgentPairingCredential::secretCopy)
                            try {
                                SingleConnectionTlsLocalNodeListener(
                                    pairing = pairing.material.toChannelPairing(),
                                    pairingSecrets = secrets,
                                    sslServerSocketFactory = NotifyingSslServerSocketFactory(
                                        delegate = manager.sslServerSocketFactory(),
                                        onBound = {
                                            if (phase == 0) firstListenerReady.countDown()
                                        },
                                    ),
                                    applicationRequestHandler = {
                                        error("unexpected application frame in authentication test")
                                    },
                                    supportedOperations = setOf(
                                        AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT,
                                    ),
                                    bootstrapHandler = AndroidPairingBootstrapHandler(
                                        manager::provisionBootstrap,
                                    ),
                                )
                            } finally {
                                secrets.forEach { it.fill(0) }
                            }
                        }
                        activeListener.set(listener)
                        try {
                            add(listener.serveSingleConnection())
                        } finally {
                            activeListener.compareAndSet(listener, null)
                        }
                    }
                }
            }
            assertTrue(
                "TLS bootstrap listener did not bind",
                firstListenerReady.await(5, TimeUnit.SECONDS),
            )

            connector = ProductionPairingExperienceConnector(
                lanConnector = UnavailableLanConnector,
                credentialStore = credentialStore,
                bootstrapProvisioner = AndroidPairingBootstrapNetworkClient(),
                clientFactory = AndroidAuthenticatedPairingClientFactory { pairing, secret ->
                    AndroidLocalNodeNetworkClient(pairing, secret)
                },
                clock = Clock.systemUTC(),
                retryDelaysMillis = listOf(100, 250, 500),
            )
            val candidate = connector.resolvePairingPayload(created.pairingQrPayload())
            val connection = connector.connect(candidate)

            assertFalse(connection.simulated)
            assertEquals(created.material.pairingId, connection.id)
            assertEquals(
                listOf("写入结构化工作记录"),
                connection.capabilities,
            )
            manager.loadActive().use { active ->
                val verified = requireNotNull(active)
                assertEquals(1, verified.credentials.size)
                assertEquals(
                    AgentPairingCredentialKind.DeviceBootstrapV2,
                    verified.credentials.single().kind,
                )
            }

            connector.disconnect(connection)
            val results = requireNotNull(serverFuture).get(10, TimeUnit.SECONDS)
            assertEquals(2, results.size)
            assertTrue(results.first().bootstrapProvisioned)
            assertFalse(results.last().bootstrapProvisioned)
            assertEquals(0, results.last().requestsHandled)
            assertTrue(credentialStore.loadActive(Clock.systemUTC().instant()) == null)
        } finally {
            runCatching {
                runBlocking { connector?.clearLocalCredentials() }
            }
            activeListener.getAndSet(null)?.close()
            serverFuture?.cancel(true)
            executor.shutdownNow()
            runCatching(credentialStore::clearAndVerify)
            runCatching(manager::revoke)
        }
    }

    private fun AgentPairingMaterial.toChannelPairing(): AndroidLocalNodePairingMaterial =
        AndroidLocalNodePairingMaterial(
            channelProtocol = channelProtocol,
            endpointRef = endpointRef,
            credentialRef = credentialRef,
            expectedDeviceId = expectedDeviceId,
            sessionBindingRef = sessionBindingRef,
            pairingId = pairingId,
            host = host,
            port = port,
            tlsCertificateSha256 = tlsCertificateSha256,
        )

    private fun reserveLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use(ServerSocket::getLocalPort)

    private object UnavailableLanConnector : PairingExperienceConnector {
        override val simulated: Boolean = false

        override suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate =
            throw PairingExperienceException(PairingExperienceFailure.NoDeviceFound)

        override suspend fun connect(
            candidate: PairingExperienceCandidate,
        ): PairingExperienceConnection =
            throw PairingExperienceException(PairingExperienceFailure.AuthorizationRequired)

        override suspend fun disconnect(connection: PairingExperienceConnection) = Unit
    }

    private class NotifyingSslServerSocketFactory(
        private val delegate: SSLServerSocketFactory,
        private val onBound: () -> Unit,
    ) : SSLServerSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createServerSocket(): ServerSocket = delegate.createServerSocket()

        override fun createServerSocket(port: Int): ServerSocket =
            delegate.createServerSocket(port)

        override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
            delegate.createServerSocket(port, backlog)

        override fun createServerSocket(
            port: Int,
            backlog: Int,
            ifAddress: InetAddress,
        ): ServerSocket =
            delegate.createServerSocket(port, backlog, ifAddress).also { onBound() }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
