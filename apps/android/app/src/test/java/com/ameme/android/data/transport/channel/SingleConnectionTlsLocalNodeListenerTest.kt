package com.ameme.android.data.transport.channel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleConnectionTlsLocalNodeListenerTest {
    private val secret = "synthetic-pairing-secret-32-bytes-minimum-value".encodeToByteArray()
    private val pairing = AndroidLocalNodePairingMaterial(
        channelProtocol = AndroidLocalNodeChannelCodec.CHANNEL_PROTOCOL_VERSION,
        endpointRef = "endpoint-ref:synthetic/android",
        credentialRef = "credential-ref:env/SYNTHETIC_SECRET",
        expectedDeviceId = "device_synthetic_001",
        sessionBindingRef = "session-binding-ref:synthetic/session",
        pairingId = "pair_synthetic_001",
        host = "127.0.0.1",
        port = 44_321,
        tlsCertificateSha256 = "sha256_" + "a".repeat(64),
    )

    @Test
    fun injectedTls13ListenerServesExactlyOneAuthenticatedConnection() {
        val clientHello = AndroidLocalNodeChannelCodec.verifyClientHello(
            AgentLocalNodeChannelCodecTest.PYTHON_CLIENT_HELLO.encodeToByteArray(),
            pairing,
            secret,
        )
        val serverHello = AndroidLocalNodeChannelCodec.verifyServerHello(
            AgentLocalNodeChannelCodecTest.PYTHON_SERVER_HELLO.encodeToByteArray(),
            clientHello,
            pairing,
            secret,
        )
        val sessionKey = AndroidLocalNodeChannelCodec.deriveSessionKey(clientHello, serverHello, secret)
        val requestLine = AndroidLocalNodeChannelCodec.buildRequestFrame(
            AgentLocalNodeChannelCodecTest.PYTHON_APPLICATION_REQUEST.encodeToByteArray(),
            sessionKey,
            serverHello.sessionId,
            sequence = 1,
            nonce = "nonce_" + "4".repeat(64),
        )
        sessionKey.fill(0)
        val input = AgentLocalNodeChannelCodecTest.PYTHON_CLIENT_HELLO.encodeToByteArray() +
            byteArrayOf('\n'.code.toByte()) + requestLine + byteArrayOf('\n'.code.toByte())
        val socket = FakeSslSocket(input)
        val serverSocket = FakeSslServerSocket(socket)
        val factory = FakeSslServerSocketFactory(serverSocket)
        var handledApplication: ByteArray? = null
        val identity = DeterministicIdentitySource()
        val listener = SingleConnectionTlsLocalNodeListener(
            pairing = pairing,
            pairingSecret = secret,
            sslServerSocketFactory = factory,
            applicationRequestHandler = AndroidLocalNodeApplicationRequestHandler { request ->
                handledApplication = request.applicationLineCopy()
                assertEquals("req_create_event", request.requestId)
                assertEquals("create_event", request.operation)
                assertFalse(request.toString().contains("Synthetic milestone"))
                AgentLocalNodeChannelCodecTest.PYTHON_APPLICATION_RESPONSE.encodeToByteArray()
            },
            supportedOperations = setOf("create_event"),
            identitySource = identity,
        )

        val result = listener.serveSingleConnection()

        assertEquals(1, result.requestsHandled)
        assertArrayEquals(
            AgentLocalNodeChannelCodecTest.PYTHON_APPLICATION_REQUEST.encodeToByteArray(),
            handledApplication,
        )
        handledApplication?.fill(0)
        assertEquals(1, serverSocket.acceptCount)
        assertTrue(serverSocket.closed)
        assertTrue(socket.handshakeStarted)
        assertTrue(socket.closed)
        assertArrayEquals(arrayOf("TLSv1.3"), serverSocket.enabledProtocols)
        assertArrayEquals(arrayOf("TLSv1.3"), socket.enabledProtocols)
        assertEquals(5_000, socket.soTimeout)
        assertEquals(44_321, factory.boundPort)
        assertEquals("127.0.0.1", factory.boundAddress?.hostAddress)
        val outputLines = socket.output.toString(Charsets.UTF_8)
            .split('\n')
            .filter(String::isNotEmpty)
        assertEquals(2, outputLines.size)
        assertEquals(AgentLocalNodeChannelCodecTest.PYTHON_SERVER_HELLO, outputLines[0])
        assertEquals(AgentLocalNodeChannelCodecTest.PYTHON_RESPONSE_FRAME, outputLines[1])
        assertTrue(listener.toString().contains("state=closed"))
        assertFalse(listener.toString().contains(pairing.host))
        assertFailsState { listener.serveSingleConnection() }
    }

    @Test
    fun invalidSecretAndNonTlsFactoryFailBeforeApplicationHandling() {
        var handled = false
        try {
            SingleConnectionTlsLocalNodeListener(
                pairing,
                byteArrayOf(1),
                FakeSslServerSocketFactory(FakeSslServerSocket(FakeSslSocket(byteArrayOf()))),
                AndroidLocalNodeApplicationRequestHandler {
                    handled = true
                    byteArrayOf()
                },
                setOf("create_event"),
            )
            throw AssertionError("expected channel auth failure")
        } catch (failure: AgentLocalNodeChannelViolation) {
            assertEquals(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED, failure.code)
        }
        assertFalse(handled)
    }

    private fun assertFailsState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // One-shot listener cannot be reopened.
        }
    }

    private class DeterministicIdentitySource : AndroidLocalNodeChannelIdentitySource {
        private val nonces = ArrayDeque(
            listOf(
                "nonce_" + "2".repeat(64),
                "nonce_" + "5".repeat(64),
            ),
        )

        override fun nextNonce(): String = nonces.removeFirst()

        override fun nextSessionId(): String = "sess_synthetic_001"
    }

    private class FakeSslServerSocketFactory(
        private val server: FakeSslServerSocket,
    ) : SSLServerSocketFactory() {
        var boundPort: Int? = null
        var boundAddress: InetAddress? = null

        override fun getDefaultCipherSuites(): Array<String> = emptyArray()

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun createServerSocket(port: Int): ServerSocket = server

        override fun createServerSocket(port: Int, backlog: Int): ServerSocket = server

        override fun createServerSocket(
            port: Int,
            backlog: Int,
            ifAddress: InetAddress,
        ): ServerSocket {
            boundPort = port
            boundAddress = ifAddress
            return server
        }
    }

    private class FakeSslServerSocket(
        private val connection: FakeSslSocket,
    ) : SSLServerSocket() {
        private var protocols = arrayOf("TLSv1.3")
        private var clientMode = false
        private var needAuth = false
        private var wantAuth = false
        private var sessionCreation = true
        private var reuse = false
        var acceptCount = 0
        var closed = false

        override fun accept(): Socket {
            acceptCount += 1
            return connection
        }

        override fun close() {
            closed = true
        }

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun getEnabledCipherSuites(): Array<String> = emptyArray()

        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit

        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")

        override fun getEnabledProtocols(): Array<String> = protocols.copyOf()

        override fun setEnabledProtocols(protocols: Array<out String>) {
            this.protocols = protocols.map(String::toString).toTypedArray()
        }

        override fun setNeedClientAuth(need: Boolean) {
            needAuth = need
        }

        override fun getNeedClientAuth(): Boolean = needAuth

        override fun setWantClientAuth(want: Boolean) {
            wantAuth = want
        }

        override fun getWantClientAuth(): Boolean = wantAuth

        override fun setUseClientMode(mode: Boolean) {
            clientMode = mode
        }

        override fun getUseClientMode(): Boolean = clientMode

        override fun setEnableSessionCreation(flag: Boolean) {
            sessionCreation = flag
        }

        override fun getEnableSessionCreation(): Boolean = sessionCreation

        override fun setReuseAddress(on: Boolean) {
            reuse = on
        }

        override fun getReuseAddress(): Boolean = reuse
    }

    private class FakeSslSocket(inputBytes: ByteArray) : SSLSocket() {
        private val input = ByteArrayInputStream(inputBytes)
        val output = ByteArrayOutputStream()
        private var protocols = arrayOf("TLSv1.3")
        private var clientMode = false
        private var needAuth = false
        private var wantAuth = false
        private var sessionCreation = true
        private var timeout = 0
        var handshakeStarted = false
        var closed = false

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun close() {
            closed = true
        }

        override fun setSoTimeout(timeout: Int) {
            this.timeout = timeout
        }

        override fun getSoTimeout(): Int = timeout

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun getEnabledCipherSuites(): Array<String> = emptyArray()

        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit

        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")

        override fun getEnabledProtocols(): Array<String> = protocols.copyOf()

        override fun setEnabledProtocols(protocols: Array<out String>) {
            this.protocols = protocols.map(String::toString).toTypedArray()
        }

        override fun getSession(): SSLSession = throw UnsupportedOperationException()

        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun startHandshake() {
            handshakeStarted = true
        }

        override fun setUseClientMode(mode: Boolean) {
            clientMode = mode
        }

        override fun getUseClientMode(): Boolean = clientMode

        override fun setNeedClientAuth(need: Boolean) {
            needAuth = need
        }

        override fun getNeedClientAuth(): Boolean = needAuth

        override fun setWantClientAuth(want: Boolean) {
            wantAuth = want
        }

        override fun getWantClientAuth(): Boolean = wantAuth

        override fun setEnableSessionCreation(flag: Boolean) {
            sessionCreation = flag
        }

        override fun getEnableSessionCreation(): Boolean = sessionCreation
    }
}
