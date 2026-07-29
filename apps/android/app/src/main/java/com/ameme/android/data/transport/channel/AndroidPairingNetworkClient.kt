package com.ameme.android.data.transport.channel

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AndroidPairingClientKey {
    fun publicKeyX963(): ByteArray

    fun thumbprint(): String

    fun signPossession(payload: ByteArray): ByteArray
}

class AndroidPairingIssuedCredential(
    val pairing: AndroidLocalNodePairingMaterial,
    val credentialId: String,
    channelSecret: ByteArray,
    val clientKeyThumbprint: String,
    val expiresAt: Instant,
) : AutoCloseable {
    private val secret = channelSecret.copyOf()

    fun channelSecretCopy(): ByteArray = secret.copyOf()

    override fun close() = secret.fill(0)

    override fun toString(): String =
        "AndroidPairingIssuedCredential(pairing=<redacted>, credential=<redacted>)"
}

fun interface AndroidPairingBootstrapProvisioner {
    suspend fun provision(
        pairingPayload: String,
        clientKey: AndroidPairingClientKey,
    ): AndroidPairingIssuedCredential
}

interface AndroidAuthenticatedPairingClient : Closeable {
    val pairing: AndroidLocalNodePairingMaterial

    val supportedOperations: Set<String>

    suspend fun connect()
}

fun interface AndroidAuthenticatedPairingClientFactory {
    fun create(
        pairing: AndroidLocalNodePairingMaterial,
        channelSecret: ByteArray,
    ): AndroidAuthenticatedPairingClient
}

/**
 * Runs the QR bootstrap only over a fresh TLS 1.3 connection pinned to the certificate in the
 * signed envelope. The QR secret is never accepted by the application channel.
 */
class AndroidPairingBootstrapNetworkClient(
    private val connectionFactory: PairingTlsLineConnectionFactory =
        PinnedPairingTlsLineConnectionFactory(),
    private val clock: Clock = Clock.systemUTC(),
) : AndroidPairingBootstrapProvisioner {
    override suspend fun provision(
        pairingPayload: String,
        clientKey: AndroidPairingClientKey,
    ): AndroidPairingIssuedCredential = withContext(Dispatchers.IO) {
        AgentPairingEnvelope.parse(pairingPayload, clock.instant()).use { envelope ->
            val publicKey = clientKey.publicKeyX963()
            val expectedThumbprint = digest(publicKey)
            try {
                check(
                    MessageDigest.isEqual(
                        expectedThumbprint.encodeToByteArray(),
                        clientKey.thumbprint().encodeToByteArray(),
                    ),
                ) { "pairing_client_key_binding_failed" }
                val clientNonce = newNonce()
                val possession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
                    pairing = envelope.pairing,
                    bootstrapId = envelope.bootstrapId,
                    clientPublicKeyX963 = publicKey,
                    clientNonce = clientNonce,
                )
                val signature = try {
                    clientKey.signPossession(possession)
                } finally {
                    possession.fill(0)
                }
                val clientLine = try {
                    AgentPairingBootstrapV2Codec.buildClientHello(
                        pairing = envelope.pairing,
                        bootstrapId = envelope.bootstrapId,
                        bootstrapSecret = envelope.bootstrapSecret,
                        clientPublicKeyX963 = publicKey,
                        clientNonce = clientNonce,
                        possessionSignatureDer = signature,
                    )
                } finally {
                    signature.fill(0)
                }
                val verifiedClient = try {
                    AgentPairingBootstrapV2Codec.verifyClientHello(
                        line = clientLine,
                        pairing = envelope.pairing,
                        expectedBootstrapId = envelope.bootstrapId,
                        bootstrapSecret = envelope.bootstrapSecret,
                    )
                } catch (failure: Throwable) {
                    clientLine.fill(0)
                    throw failure
                }
                verifiedClient.use { hello ->
                    connectionFactory.connect(envelope.pairing).use { connection ->
                        try {
                            connection.sendLine(
                                clientLine,
                                AgentPairingBootstrapV2Codec.MAX_LINE_BYTES,
                            )
                        } finally {
                            clientLine.fill(0)
                        }
                        val serverLine = connection.receiveLine(
                            AgentPairingBootstrapV2Codec.MAX_LINE_BYTES,
                        )
                        val verifiedServer = try {
                            AgentPairingBootstrapV2Codec.verifyServerHello(
                                line = serverLine,
                                expectedClientHello = hello,
                                pairing = envelope.pairing,
                                bootstrapSecret = envelope.bootstrapSecret,
                            )
                        } finally {
                            serverLine.fill(0)
                        }
                        verifiedServer.use { issued ->
                            val now = clock.instant()
                            check(
                                issued.credentialExpiresAt.isAfter(now) &&
                                    !issued.credentialExpiresAt.isAfter(envelope.pairingExpiresAt),
                            ) { "pairing_credential_expiry_invalid" }
                            val secret = issued.credentialSecretCopy()
                            try {
                                AndroidPairingIssuedCredential(
                                    pairing = envelope.pairing,
                                    credentialId = issued.credentialId,
                                    channelSecret = secret,
                                    clientKeyThumbprint = issued.clientKeyThumbprint,
                                    expiresAt = issued.credentialExpiresAt,
                                )
                            } finally {
                                secret.fill(0)
                            }
                        }
                    }
                }
            } finally {
                publicKey.fill(0)
            }
        }
    }
}

/**
 * Authenticates the separately issued credential on the frozen application channel. Connection
 * state exists only after TLS pinning, mutual HMAC hello verification, and capability negotiation.
 */
class AndroidLocalNodeNetworkClient(
    override val pairing: AndroidLocalNodePairingMaterial,
    channelSecret: ByteArray,
    private val connectionFactory: PairingTlsLineConnectionFactory =
        PinnedPairingTlsLineConnectionFactory(),
) : AndroidAuthenticatedPairingClient {
    private val lock = Any()
    private var secret = channelSecret.copyOf()
    private var connection: PairingTlsLineConnection? = null
    private var sessionKey: ByteArray? = null

    @Volatile
    override var supportedOperations: Set<String> = emptySet()
        private set

    init {
        require(secret.size in 32..256)
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(connection == null) { "pairing_application_client_already_connected" }
            check(secret.size in 32..256) { "pairing_application_credential_unavailable" }
        }
        val nextConnection = connectionFactory.connect(pairing)
        try {
            val clientLine = AndroidLocalNodeChannelCodec.buildClientHello(
                pairing = pairing,
                secret = secret,
                clientNonce = newNonce(),
            )
            val verifiedClient = try {
                AndroidLocalNodeChannelCodec.verifyClientHello(
                    line = clientLine,
                    pairing = pairing,
                    secret = secret,
                )
            } catch (failure: Throwable) {
                clientLine.fill(0)
                throw failure
            }
            try {
                nextConnection.sendLine(
                    clientLine,
                    AndroidLocalNodeChannelCodec.MAX_CHANNEL_LINE_BYTES,
                )
            } finally {
                clientLine.fill(0)
            }
            val serverLine = nextConnection.receiveLine(
                AndroidLocalNodeChannelCodec.MAX_CHANNEL_LINE_BYTES,
            )
            val verifiedServer = try {
                AndroidLocalNodeChannelCodec.verifyServerHello(
                    line = serverLine,
                    clientHello = verifiedClient,
                    pairing = pairing,
                    secret = secret,
                )
            } finally {
                serverLine.fill(0)
            }
            val derived = AndroidLocalNodeChannelCodec.deriveSessionKey(
                clientHello = verifiedClient,
                serverHello = verifiedServer,
                secret = secret,
            )
            synchronized(lock) {
                connection = nextConnection
                sessionKey = derived
                supportedOperations = verifiedServer.supportedOperations
                secret.fill(0)
                secret = byteArrayOf()
            }
        } catch (failure: Throwable) {
            nextConnection.close()
            close()
            throw failure
        }
    }

    override fun close() {
        val oldConnection: PairingTlsLineConnection?
        synchronized(lock) {
            oldConnection = connection
            connection = null
            sessionKey?.fill(0)
            sessionKey = null
            supportedOperations = emptySet()
            secret.fill(0)
            secret = byteArrayOf()
        }
        runCatching { oldConnection?.close() }
    }

    override fun toString(): String =
        "AndroidLocalNodeNetworkClient(pairing=<redacted>, connected=${connection != null})"
}

interface PairingTlsLineConnection : Closeable {
    fun sendLine(line: ByteArray, maximum: Int)

    fun receiveLine(maximum: Int): ByteArray
}

fun interface PairingTlsLineConnectionFactory {
    fun connect(pairing: AndroidLocalNodePairingMaterial): PairingTlsLineConnection
}

class PinnedPairingTlsLineConnectionFactory(
    private val timeoutMillis: Int = 5_000,
) : PairingTlsLineConnectionFactory {
    init {
        require(timeoutMillis in 100..5_000)
    }

    override fun connect(pairing: AndroidLocalNodePairingMaterial): PairingTlsLineConnection {
        AndroidLocalNodeChannelCodec.validatePairingMaterial(pairing)
        val trustManager = CertificatePinTrustManager(pairing.tlsCertificateSha256)
        val context = SSLContext.getInstance(TLS_1_3).apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val rawSocket = Socket()
        try {
            rawSocket.connect(InetSocketAddress(pairing.host, pairing.port), timeoutMillis)
            val tlsSocket = context.socketFactory.createSocket(
                rawSocket,
                pairing.host,
                pairing.port,
                true,
            ) as? SSLSocket ?: error("pairing_tls_socket_unavailable")
            tlsSocket.enabledProtocols = arrayOf(TLS_1_3)
            tlsSocket.useClientMode = true
            tlsSocket.needClientAuth = false
            tlsSocket.wantClientAuth = false
            tlsSocket.enableSessionCreation = true
            tlsSocket.soTimeout = timeoutMillis
            tlsSocket.startHandshake()
            return SocketPairingTlsLineConnection(tlsSocket)
        } catch (failure: Throwable) {
            runCatching { rawSocket.close() }
            throw failure
        }
    }

    private class CertificatePinTrustManager(
        private val expectedPin: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("pairing_client_certificate_rejected")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("pairing_certificate_missing")
            certificate.checkValidity()
            val actualPin = digest(certificate.encoded)
            if (
                !MessageDigest.isEqual(
                    actualPin.encodeToByteArray(),
                    expectedPin.encodeToByteArray(),
                )
            ) {
                throw java.security.cert.CertificateException("pairing_certificate_pin_mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class SocketPairingTlsLineConnection(
        private val socket: SSLSocket,
    ) : PairingTlsLineConnection {
        override fun sendLine(line: ByteArray, maximum: Int) {
            require(line.isNotEmpty() && line.size <= maximum && !line.contains(0x0A))
            socket.outputStream.write(line)
            socket.outputStream.write(0x0A)
            socket.outputStream.flush()
        }

        override fun receiveLine(maximum: Int): ByteArray {
            require(maximum in 1..AndroidLocalNodeChannelCodec.MAX_CHANNEL_LINE_BYTES)
            val output = ByteArrayOutputStream(minOf(maximum, 16_384))
            while (output.size() <= maximum) {
                val next = socket.inputStream.read()
                if (next < 0) error("pairing_tls_connection_closed")
                if (next == 0x0A) {
                    check(output.size() > 0) { "pairing_tls_empty_line" }
                    return output.toByteArray()
                }
                output.write(next)
            }
            output.reset()
            error("pairing_tls_line_too_large")
        }

        override fun close() = socket.close()
    }
}

private fun newNonce(): String {
    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
    return try {
        "nonce_" + bytes.joinToString("") { "%02x".format(it) }
    } finally {
        bytes.fill(0)
    }
}

private fun digest(value: ByteArray): String =
    "sha256_" + MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

private const val TLS_1_3 = "TLSv1.3"
