package com.ameme.android.data.transport.channel

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

class AndroidLocalNodeTlsListenerException : RuntimeException(
    "android_local_node_tls_listener_failed",
)

class AndroidLocalNodeApplicationRequest internal constructor(
    val requestId: String,
    val operation: String,
    val sequence: Long,
    val idempotencyRef: String,
    applicationLine: ByteArray,
) : Closeable {
    private val applicationBytes = applicationLine.copyOf()
    private var closed = false

    fun applicationLineCopy(): ByteArray {
        check(!closed) { "application request has been cleared" }
        return applicationBytes.copyOf()
    }

    override fun close() {
        applicationBytes.fill(0)
        closed = true
    }

    override fun toString(): String =
        "AndroidLocalNodeApplicationRequest(request=<redacted>, sequence=$sequence, " +
            "application=<${if (closed) "cleared" else "redacted"}>)"
}

fun interface AndroidLocalNodeApplicationRequestHandler {
    /** Return one strict `ameme.agent-local-node.v1` application response line. */
    fun handle(request: AndroidLocalNodeApplicationRequest): ByteArray
}

interface AndroidLocalNodeChannelIdentitySource {
    fun nextNonce(): String

    fun nextSessionId(): String
}

data class SingleConnectionTlsResult(
    val requestsHandled: Int,
)

/**
 * Explicit, one-shot TLS 1.3 server boundary for a paired Host.
 *
 * Nothing starts this listener implicitly. Pairing material, secret, socket factory and the
 * application handler are all injected by the Android host lifecycle owner.
 */
class SingleConnectionTlsLocalNodeListener(
    private val pairing: AndroidLocalNodePairingMaterial,
    pairingSecret: ByteArray,
    private val sslServerSocketFactory: SSLServerSocketFactory,
    private val applicationRequestHandler: AndroidLocalNodeApplicationRequestHandler,
    private val supportedOperations: Set<String>,
    private val identitySource: AndroidLocalNodeChannelIdentitySource = SecureChannelIdentitySource(),
) : Closeable {
    private val secret = pairingSecret.copyOf()
    private val used = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()
    private var activeServer: SSLServerSocket? = null
    private var activeConnection: SSLSocket? = null

    init {
        if (secret.size !in 32..256) {
            secret.fill(0)
            throw AgentLocalNodeChannelViolation(
                AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED,
            )
        }
        try {
            AndroidLocalNodeChannelCodec.validatePairingMaterial(pairing)
            AndroidLocalNodeChannelCodec.validateSupportedOperations(supportedOperations)
        } catch (failure: Exception) {
            secret.fill(0)
            throw failure
        }
    }

    fun serveSingleConnection(): SingleConnectionTlsResult {
        if (!used.compareAndSet(false, true) || closed.get()) {
            throw IllegalStateException("android_local_node_tls_listener_unavailable")
        }
        return try {
            val server = sslServerSocketFactory.createServerSocket(
                pairing.port,
                1,
                InetAddress.getByName(pairing.host),
            ) as? SSLServerSocket ?: throw AndroidLocalNodeTlsListenerException()
            synchronized(stateLock) { activeServer = server }
            server.use {
                configureServer(it)
                val connection = it.accept() as? SSLSocket
                    ?: throw AndroidLocalNodeTlsListenerException()
                synchronized(stateLock) { activeConnection = connection }
                connection.use(::serveAuthenticatedConnection)
            }
        } catch (_: Exception) {
            throw AndroidLocalNodeTlsListenerException()
        } finally {
            synchronized(stateLock) {
                activeConnection = null
                activeServer = null
            }
            close()
        }
    }

    private fun configureServer(server: SSLServerSocket) {
        if (TLS_1_3 !in server.supportedProtocols) throw AndroidLocalNodeTlsListenerException()
        server.enabledProtocols = arrayOf(TLS_1_3)
        server.useClientMode = false
        server.needClientAuth = false
        server.wantClientAuth = false
        server.enableSessionCreation = true
    }

    private fun serveAuthenticatedConnection(connection: SSLSocket): SingleConnectionTlsResult {
        if (TLS_1_3 !in connection.supportedProtocols) throw AndroidLocalNodeTlsListenerException()
        connection.enabledProtocols = arrayOf(TLS_1_3)
        connection.useClientMode = false
        connection.needClientAuth = false
        connection.wantClientAuth = false
        connection.enableSessionCreation = true
        connection.soTimeout = IO_TIMEOUT_MILLIS
        connection.startHandshake()

        val input = connection.inputStream
        val output = connection.outputStream
        val clientLine = readLine(input, allowCleanEnd = false)
            ?: throw AndroidLocalNodeTlsListenerException()
        val clientHello = try {
            AndroidLocalNodeChannelCodec.verifyClientHello(
                clientLine,
                pairing,
                secret,
            )
        } finally {
            clientLine.fill(0)
        }
        val serverNonce = identitySource.nextNonce()
        val sessionId = identitySource.nextSessionId()
        val serverLine = AndroidLocalNodeChannelCodec.buildServerHello(
            clientHello = clientHello,
            pairing = pairing,
            secret = secret,
            serverNonce = serverNonce,
            sessionId = sessionId,
            supportedOperations = supportedOperations,
        )
        try {
            val serverHello = AndroidLocalNodeChannelCodec.verifyServerHello(
                serverLine,
                clientHello,
                pairing,
                secret,
            )
            writeLine(output, serverLine)
            val sessionKey = AndroidLocalNodeChannelCodec.deriveSessionKey(
                clientHello,
                serverHello,
                secret,
            )
            return try {
                serveFrames(input, output, serverHello, sessionKey)
            } finally {
                sessionKey.fill(0)
            }
        } finally {
            serverLine.fill(0)
        }
    }

    private fun serveFrames(
        input: InputStream,
        output: OutputStream,
        serverHello: VerifiedServerHello,
        sessionKey: ByteArray,
    ): SingleConnectionTlsResult {
        val seenRequestNonces = mutableSetOf<String>()
        val seenResponseNonces = mutableSetOf<String>()
        var expectedSequence = 1L
        var handled = 0
        while (true) {
            val requestLine = readLine(input, allowCleanEnd = true) ?: break
            val requestFrame = try {
                AndroidLocalNodeChannelCodec.parseRequestFrame(
                    line = requestLine,
                    sessionKey = sessionKey,
                    expectedSessionId = serverHello.sessionId,
                    expectedSequence = expectedSequence,
                    seenNonces = seenRequestNonces,
                )
            } finally {
                requestLine.fill(0)
            }
            requestFrame.use { frame ->
                val applicationLine = frame.applicationLineCopy()
                val applicationRequest = AndroidLocalNodeApplicationRequest(
                    requestId = frame.requestId,
                    operation = frame.operation,
                    sequence = frame.sequence,
                    idempotencyRef = frame.idempotencyRef,
                    applicationLine = applicationLine,
                )
                applicationLine.fill(0)
                val responseLine = applicationRequest.use(applicationRequestHandler::handle)
                try {
                    val responseNonce = identitySource.nextNonce()
                    if (responseNonce == frame.nonce || !seenResponseNonces.add(responseNonce)) {
                        throw AgentLocalNodeChannelViolation(
                            AgentLocalNodeChannelErrorCode.CHANNEL_REPLAY,
                        )
                    }
                    val responseFrame = AndroidLocalNodeChannelCodec.buildResponseFrame(
                        applicationLine = responseLine,
                        requestFrame = frame,
                        sessionKey = sessionKey,
                        nonce = responseNonce,
                    )
                    try {
                        writeLine(output, responseFrame)
                    } finally {
                        responseFrame.fill(0)
                    }
                } finally {
                    responseLine.fill(0)
                }
            }
            handled += 1
            if (expectedSequence == Long.MAX_VALUE) {
                throw AgentLocalNodeChannelViolation(
                    AgentLocalNodeChannelErrorCode.CHANNEL_SEQUENCE_INVALID,
                )
            }
            expectedSequence += 1
        }
        return SingleConnectionTlsResult(handled)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) secret.fill(0)
        synchronized(stateLock) {
            runCatching { activeConnection?.close() }
            runCatching { activeServer?.close() }
        }
    }

    override fun toString(): String =
        "SingleConnectionTlsLocalNodeListener(endpoint=<redacted>, device=<redacted>, " +
            "state=${if (closed.get()) "closed" else "ready"})"

    private fun readLine(input: InputStream, allowCleanEnd: Boolean): ByteArray? {
        val output = ByteArrayOutputStream()
        while (output.size() <= AndroidLocalNodeChannelCodec.MAX_CHANNEL_LINE_BYTES) {
            val next = input.read()
            if (next < 0) {
                if (output.size() == 0 && allowCleanEnd) return null
                throw AndroidLocalNodeTlsListenerException()
            }
            if (next == '\n'.code) {
                if (output.size() == 0) throw AndroidLocalNodeTlsListenerException()
                return output.toByteArray()
            }
            output.write(next)
        }
        throw AndroidLocalNodeTlsListenerException()
    }

    private fun writeLine(output: OutputStream, line: ByteArray) {
        if (
            line.isEmpty() ||
            line.size > AndroidLocalNodeChannelCodec.MAX_CHANNEL_LINE_BYTES ||
            line.any { it == '\n'.code.toByte() }
        ) {
            throw AndroidLocalNodeTlsListenerException()
        }
        output.write(line)
        output.write('\n'.code)
        output.flush()
    }

    private class SecureChannelIdentitySource : AndroidLocalNodeChannelIdentitySource {
        private val secureRandom = SecureRandom()

        override fun nextNonce(): String = "nonce_" + randomHex(32)

        override fun nextSessionId(): String = "sess_" + randomHex(32)

        private fun randomHex(size: Int): String {
            val bytes = ByteArray(size)
            secureRandom.nextBytes(bytes)
            return try {
                bytes.toHex()
            } finally {
                bytes.fill(0)
            }
        }
    }

    private companion object {
        const val TLS_1_3 = "TLSv1.3"
        const val IO_TIMEOUT_MILLIS = 5_000
    }
}
