package com.ameme.android.data.transport.channel

import java.io.Closeable
import java.net.InetAddress
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AgentLocalNodeChannelErrorCode {
    INVALID_CHANNEL_MESSAGE,
    CHANNEL_AUTH_FAILED,
    CHANNEL_BINDING_FAILED,
    CHANNEL_SEQUENCE_INVALID,
    CHANNEL_REPLAY,
    CHANNEL_PAYLOAD_INVALID,
}

class AgentLocalNodeChannelViolation(
    val code: AgentLocalNodeChannelErrorCode,
) : IllegalArgumentException(code.name)

class AndroidLocalNodePairingMaterial(
    val channelProtocol: String,
    val endpointRef: String,
    val credentialRef: String,
    val expectedDeviceId: String,
    val sessionBindingRef: String,
    val pairingId: String,
    val host: String,
    val port: Int,
    val tlsCertificateSha256: String,
) {
    init {
        AndroidLocalNodeChannelCodec.validatePairingMaterial(this)
    }

    internal fun document(): CanonicalJsonObject = StrictCanonicalJson.obj(
        "channel_protocol" to StrictCanonicalJson.string(channelProtocol),
        "endpoint_ref" to StrictCanonicalJson.string(endpointRef),
        "credential_ref" to StrictCanonicalJson.string(credentialRef),
        "expected_device_id" to StrictCanonicalJson.string(expectedDeviceId),
        "session_binding_ref" to StrictCanonicalJson.string(sessionBindingRef),
        "pairing_id" to StrictCanonicalJson.string(pairingId),
        "host" to StrictCanonicalJson.string(host),
        "port" to StrictCanonicalJson.integer(port.toLong()),
        "tls_certificate_sha256" to StrictCanonicalJson.string(tlsCertificateSha256),
    )

    fun canonicalBytes(): ByteArray = StrictCanonicalJson.canonicalBytes(document())

    override fun toString(): String =
        "AndroidLocalNodePairingMaterial(endpoint=<redacted>, credential=<reference>, " +
            "device=<redacted>, pin=<redacted>)"
}

class VerifiedClientHello internal constructor(
    internal val document: CanonicalJsonObject,
    val clientNonce: String,
) {
    override fun toString(): String = "VerifiedClientHello(binding=<redacted>)"
}

class VerifiedServerHello internal constructor(
    internal val document: CanonicalJsonObject,
    val serverNonce: String,
    val sessionId: String,
    val supportedOperations: Set<String>,
) {
    override fun toString(): String = "VerifiedServerHello(session=<redacted>)"
}

class ParsedAgentLocalNodeRequestFrame internal constructor(
    internal val document: CanonicalJsonObject,
    applicationLine: ByteArray,
    val sessionId: String,
    val sequence: Long,
    val nonce: String,
    val idempotencyRef: String,
    val requestId: String,
    val operation: String,
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
        "ParsedAgentLocalNodeRequestFrame(session=<redacted>, sequence=$sequence, " +
            "application=<${if (closed) "cleared" else "redacted"}>)"
}

class ParsedAgentLocalNodeResponseFrame internal constructor(
    applicationLine: ByteArray,
    val sessionId: String,
    val sequence: Long,
    val nonce: String,
) : Closeable {
    private val applicationBytes = applicationLine.copyOf()
    private var closed = false

    fun applicationLineCopy(): ByteArray {
        check(!closed) { "application response has been cleared" }
        return applicationBytes.copyOf()
    }

    override fun close() {
        applicationBytes.fill(0)
        closed = true
    }

    override fun toString(): String =
        "ParsedAgentLocalNodeResponseFrame(session=<redacted>, sequence=$sequence, " +
            "application=<${if (closed) "cleared" else "redacted"}>)"
}

object AndroidLocalNodeChannelCodec {
    const val CHANNEL_PROTOCOL_VERSION = "ameme.agent-local-node.channel.v1"
    const val APPLICATION_PROTOCOL_VERSION = "ameme.agent-local-node.v1"
    const val MAX_PAIRING_MATERIAL_BYTES = 8_192
    const val MAX_CHANNEL_LINE_BYTES = 786_432
    private const val MAX_REQUEST_BYTES = 65_536
    private const val MAX_RESPONSE_BYTES = 524_288

    private val noncePattern = Regex("nonce_[0-9a-f]{64}")
    private val digestPattern = Regex("sha256_[0-9a-f]{64}")
    private val proofPattern = Regex("hmac_[0-9a-f]{64}")
    private val idempotencyPattern = Regex("idem_[0-9a-f]{64}")
    private val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val referencePattern = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,239}")
    private val hostPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
    private val operations = setOf(
        "get_event",
        "create_event",
        "append_revision",
        "undo_capture",
        "visible_events",
        "set_policy_blocked",
    )

    private val pairingKeys = setOf(
        "channel_protocol",
        "endpoint_ref",
        "credential_ref",
        "expected_device_id",
        "session_binding_ref",
        "pairing_id",
        "host",
        "port",
        "tls_certificate_sha256",
    )
    private val clientHelloKeys = setOf(
        "channel_protocol",
        "message_type",
        "pairing_id",
        "expected_device_id",
        "session_binding_ref",
        "tls_certificate_sha256",
        "client_nonce",
        "sequence",
        "proof",
    )
    private val serverHelloKeys = setOf(
        "channel_protocol",
        "message_type",
        "pairing_id",
        "device_id",
        "session_binding_ref",
        "tls_certificate_sha256",
        "client_nonce",
        "server_nonce",
        "session_id",
        "supported_operations",
        "sequence",
        "proof",
    )
    private val requestFrameKeys = setOf(
        "channel_protocol",
        "message_type",
        "session_id",
        "sequence",
        "nonce",
        "idempotency_ref",
        "application_protocol",
        "application_digest",
        "application_b64",
        "proof",
    )
    private val responseFrameKeys = setOf(
        "channel_protocol",
        "message_type",
        "session_id",
        "sequence",
        "request_nonce",
        "nonce",
        "idempotency_ref",
        "application_protocol",
        "application_digest",
        "application_b64",
        "proof",
    )

    fun parsePairingMaterial(line: ByteArray): AndroidLocalNodePairingMaterial {
        val document = parseObject(line, MAX_PAIRING_MATERIAL_BYTES)
        exact(document, pairingKeys)
        return try {
            AndroidLocalNodePairingMaterial(
                channelProtocol = document.string("channel_protocol"),
                endpointRef = document.string("endpoint_ref"),
                credentialRef = document.string("credential_ref"),
                expectedDeviceId = document.string("expected_device_id"),
                sessionBindingRef = document.string("session_binding_ref"),
                pairingId = document.string("pairing_id"),
                host = document.string("host"),
                port = Math.toIntExact(document.long("port")),
                tlsCertificateSha256 = document.string("tls_certificate_sha256"),
            )
        } catch (_: Exception) {
            fail(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE)
        }
    }

    internal fun validatePairingMaterial(pairing: AndroidLocalNodePairingMaterial) {
        if (pairing.channelProtocol != CHANNEL_PROTOCOL_VERSION) invalid()
        reference(pairing.endpointRef, "endpoint-ref:")
        reference(pairing.credentialRef, "credential-ref:")
        identifier(pairing.expectedDeviceId)
        reference(pairing.sessionBindingRef, "session-binding-ref:")
        identifier(pairing.pairingId)
        if (!isValidHost(pairing.host)) invalid()
        if (pairing.port !in 1..65_535) invalid()
        digest(pairing.tlsCertificateSha256)
    }

    internal fun validateSupportedOperations(values: Set<String>) {
        validateOperations(values)
    }

    fun buildClientHello(
        pairing: AndroidLocalNodePairingMaterial,
        secret: ByteArray,
        clientNonce: String,
    ): ByteArray {
        validatePairingMaterial(pairing)
        requireSecret(secret)
        val document = StrictCanonicalJson.obj(
            "channel_protocol" to StrictCanonicalJson.string(CHANNEL_PROTOCOL_VERSION),
            "message_type" to StrictCanonicalJson.string("client_hello"),
            "pairing_id" to StrictCanonicalJson.string(pairing.pairingId),
            "expected_device_id" to StrictCanonicalJson.string(pairing.expectedDeviceId),
            "session_binding_ref" to StrictCanonicalJson.string(pairing.sessionBindingRef),
            "tls_certificate_sha256" to StrictCanonicalJson.string(pairing.tlsCertificateSha256),
            "client_nonce" to StrictCanonicalJson.string(nonce(clientNonce)),
            "sequence" to StrictCanonicalJson.integer(0),
        )
        return canonicalWithProof(document, secret, "client-hello")
    }

    fun verifyClientHello(
        line: ByteArray,
        pairing: AndroidLocalNodePairingMaterial,
        secret: ByteArray,
    ): VerifiedClientHello {
        validatePairingMaterial(pairing)
        requireSecret(secret)
        val document = parseObject(line, MAX_CHANNEL_LINE_BYTES)
        exact(document, clientHelloKeys)
        if (
            document.string("channel_protocol") != CHANNEL_PROTOCOL_VERSION ||
            document.string("message_type") != "client_hello" ||
            document.string("pairing_id") != pairing.pairingId ||
            document.string("expected_device_id") != pairing.expectedDeviceId ||
            document.string("session_binding_ref") != pairing.sessionBindingRef ||
            document.string("tls_certificate_sha256") != pairing.tlsCertificateSha256 ||
            document.long("sequence") != 0L
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val clientNonce = nonce(document.string("client_nonce"))
        verifyProof(document, secret, "client-hello")
        return VerifiedClientHello(document, clientNonce)
    }

    fun buildServerHello(
        clientHello: VerifiedClientHello,
        pairing: AndroidLocalNodePairingMaterial,
        secret: ByteArray,
        serverNonce: String,
        sessionId: String,
        supportedOperations: Set<String>,
    ): ByteArray {
        validatePairingMaterial(pairing)
        requireSecret(secret)
        val sortedOperations = validateOperations(supportedOperations)
        val document = StrictCanonicalJson.obj(
            "channel_protocol" to StrictCanonicalJson.string(CHANNEL_PROTOCOL_VERSION),
            "message_type" to StrictCanonicalJson.string("server_hello"),
            "pairing_id" to StrictCanonicalJson.string(pairing.pairingId),
            "device_id" to StrictCanonicalJson.string(pairing.expectedDeviceId),
            "session_binding_ref" to StrictCanonicalJson.string(pairing.sessionBindingRef),
            "tls_certificate_sha256" to StrictCanonicalJson.string(pairing.tlsCertificateSha256),
            "client_nonce" to StrictCanonicalJson.string(nonce(clientHello.clientNonce)),
            "server_nonce" to StrictCanonicalJson.string(nonce(serverNonce)),
            "session_id" to StrictCanonicalJson.string(identifier(sessionId)),
            "supported_operations" to StrictCanonicalJson.array(
                sortedOperations.map(StrictCanonicalJson::string),
            ),
            "sequence" to StrictCanonicalJson.integer(0),
        )
        return canonicalWithProof(document, secret, "server-hello")
    }

    fun verifyServerHello(
        line: ByteArray,
        clientHello: VerifiedClientHello,
        pairing: AndroidLocalNodePairingMaterial,
        secret: ByteArray,
    ): VerifiedServerHello {
        validatePairingMaterial(pairing)
        requireSecret(secret)
        val document = parseObject(line, MAX_CHANNEL_LINE_BYTES)
        exact(document, serverHelloKeys)
        if (
            document.string("channel_protocol") != CHANNEL_PROTOCOL_VERSION ||
            document.string("message_type") != "server_hello" ||
            document.string("pairing_id") != pairing.pairingId ||
            document.string("device_id") != pairing.expectedDeviceId ||
            document.string("session_binding_ref") != pairing.sessionBindingRef ||
            document.string("tls_certificate_sha256") != pairing.tlsCertificateSha256 ||
            document.string("client_nonce") != clientHello.clientNonce ||
            document.long("sequence") != 0L
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val serverNonce = nonce(document.string("server_nonce"))
        val sessionId = identifier(document.string("session_id"))
        val supportedOperations = validateOperations(document.stringList("supported_operations").toSet())
        if (document.stringList("supported_operations") != supportedOperations) invalid()
        verifyProof(document, secret, "server-hello")
        return VerifiedServerHello(document, serverNonce, sessionId, supportedOperations.toSet())
    }

    fun deriveSessionKey(
        clientHello: VerifiedClientHello,
        serverHello: VerifiedServerHello,
        secret: ByteArray,
    ): ByteArray {
        requireSecret(secret)
        val material = StrictCanonicalJson.obj(
            "channel_protocol" to StrictCanonicalJson.string(CHANNEL_PROTOCOL_VERSION),
            "pairing_id" to StrictCanonicalJson.string(serverHello.document.string("pairing_id")),
            "device_id" to StrictCanonicalJson.string(serverHello.document.string("device_id")),
            "session_binding_ref" to StrictCanonicalJson.string(
                serverHello.document.string("session_binding_ref"),
            ),
            "tls_certificate_sha256" to StrictCanonicalJson.string(
                serverHello.document.string("tls_certificate_sha256"),
            ),
            "client_nonce" to StrictCanonicalJson.string(clientHello.clientNonce),
            "server_nonce" to StrictCanonicalJson.string(serverHello.serverNonce),
            "session_id" to StrictCanonicalJson.string(serverHello.sessionId),
        )
        return hmac(secret, "session-key\u0000".encodeToByteArray() + StrictCanonicalJson.canonicalBytes(material))
    }

    fun buildRequestFrame(
        applicationLine: ByteArray,
        sessionKey: ByteArray,
        sessionId: String,
        sequence: Long,
        nonce: String,
    ): ByteArray {
        requireSecret(sessionKey)
        val application = validateApplicationRequest(applicationLine)
        val document = StrictCanonicalJson.obj(
            "channel_protocol" to StrictCanonicalJson.string(CHANNEL_PROTOCOL_VERSION),
            "message_type" to StrictCanonicalJson.string("request"),
            "session_id" to StrictCanonicalJson.string(identifier(sessionId)),
            "sequence" to StrictCanonicalJson.integer(validSequence(sequence)),
            "nonce" to StrictCanonicalJson.string(nonce(nonce)),
            "idempotency_ref" to StrictCanonicalJson.string(application.idempotencySlot),
            "application_protocol" to StrictCanonicalJson.string(APPLICATION_PROTOCOL_VERSION),
            "application_digest" to StrictCanonicalJson.string(sha256(applicationLine)),
            "application_b64" to StrictCanonicalJson.string(
                Base64.getEncoder().encodeToString(applicationLine),
            ),
        )
        return canonicalWithProof(document, sessionKey, "request-frame")
    }

    fun parseRequestFrame(
        line: ByteArray,
        sessionKey: ByteArray,
        expectedSessionId: String,
        expectedSequence: Long,
        seenNonces: MutableSet<String>,
    ): ParsedAgentLocalNodeRequestFrame {
        requireSecret(sessionKey)
        val document = parseObject(line, MAX_CHANNEL_LINE_BYTES)
        exact(document, requestFrameKeys)
        if (
            document.string("channel_protocol") != CHANNEL_PROTOCOL_VERSION ||
            document.string("message_type") != "request" ||
            document.string("session_id") != expectedSessionId ||
            document.string("application_protocol") != APPLICATION_PROTOCOL_VERSION
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val sequence = validSequence(document.long("sequence"))
        if (sequence != expectedSequence) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_SEQUENCE_INVALID)
        }
        val requestNonce = nonce(document.string("nonce"))
        if (requestNonce in seenNonces) fail(AgentLocalNodeChannelErrorCode.CHANNEL_REPLAY)
        verifyProof(document, sessionKey, "request-frame")
        val applicationLine = decodeApplication(document.string("application_b64"), MAX_REQUEST_BYTES)
        try {
            if (digest(document.string("application_digest")) != sha256(applicationLine)) {
                fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
            }
            val application = validateApplicationRequest(applicationLine)
            val idempotencyRef = idempotency(document.string("idempotency_ref"))
            if (idempotencyRef != application.idempotencySlot) {
                fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
            }
            seenNonces += requestNonce
            return ParsedAgentLocalNodeRequestFrame(
                document = document,
                applicationLine = applicationLine,
                sessionId = document.string("session_id"),
                sequence = sequence,
                nonce = requestNonce,
                idempotencyRef = idempotencyRef,
                requestId = application.requestId,
                operation = application.operation,
            )
        } finally {
            applicationLine.fill(0)
        }
    }

    fun buildResponseFrame(
        applicationLine: ByteArray,
        requestFrame: ParsedAgentLocalNodeRequestFrame,
        sessionKey: ByteArray,
        nonce: String,
    ): ByteArray {
        requireSecret(sessionKey)
        parseApplicationDocument(applicationLine, MAX_RESPONSE_BYTES)
        val document = StrictCanonicalJson.obj(
            "channel_protocol" to StrictCanonicalJson.string(CHANNEL_PROTOCOL_VERSION),
            "message_type" to StrictCanonicalJson.string("response"),
            "session_id" to StrictCanonicalJson.string(identifier(requestFrame.sessionId)),
            "sequence" to StrictCanonicalJson.integer(validSequence(requestFrame.sequence)),
            "request_nonce" to StrictCanonicalJson.string(nonce(requestFrame.nonce)),
            "nonce" to StrictCanonicalJson.string(nonce(nonce)),
            "idempotency_ref" to StrictCanonicalJson.string(idempotency(requestFrame.idempotencyRef)),
            "application_protocol" to StrictCanonicalJson.string(APPLICATION_PROTOCOL_VERSION),
            "application_digest" to StrictCanonicalJson.string(sha256(applicationLine)),
            "application_b64" to StrictCanonicalJson.string(
                Base64.getEncoder().encodeToString(applicationLine),
            ),
        )
        return canonicalWithProof(document, sessionKey, "response-frame")
    }

    fun parseResponseFrame(
        line: ByteArray,
        sessionKey: ByteArray,
        requestFrame: ParsedAgentLocalNodeRequestFrame,
        seenNonces: MutableSet<String>,
    ): ParsedAgentLocalNodeResponseFrame {
        requireSecret(sessionKey)
        val document = parseObject(line, MAX_CHANNEL_LINE_BYTES)
        exact(document, responseFrameKeys)
        if (
            document.string("channel_protocol") != CHANNEL_PROTOCOL_VERSION ||
            document.string("message_type") != "response" ||
            document.string("session_id") != requestFrame.sessionId ||
            document.long("sequence") != requestFrame.sequence ||
            document.string("request_nonce") != requestFrame.nonce ||
            document.string("idempotency_ref") != requestFrame.idempotencyRef ||
            document.string("application_protocol") != APPLICATION_PROTOCOL_VERSION
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val sequence = validSequence(document.long("sequence"))
        idempotency(document.string("idempotency_ref"))
        val responseNonce = nonce(document.string("nonce"))
        if (responseNonce == requestFrame.nonce || responseNonce in seenNonces) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_REPLAY)
        }
        verifyProof(document, sessionKey, "response-frame")
        val applicationLine = decodeApplication(document.string("application_b64"), MAX_RESPONSE_BYTES)
        try {
            if (digest(document.string("application_digest")) != sha256(applicationLine)) {
                fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
            }
            validateApplicationResponse(applicationLine, requestFrame.requestId)
            seenNonces += responseNonce
            return ParsedAgentLocalNodeResponseFrame(
                applicationLine = applicationLine,
                sessionId = requestFrame.sessionId,
                sequence = sequence,
                nonce = responseNonce,
            )
        } finally {
            applicationLine.fill(0)
        }
    }

    private fun parseObject(line: ByteArray, maximum: Int): CanonicalJsonObject = try {
        StrictCanonicalJson.parseLine(line, maximum) as? CanonicalJsonObject ?: invalid()
    } catch (violation: AgentLocalNodeChannelViolation) {
        throw violation
    } catch (_: Exception) {
        fail(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE)
    }

    private fun exact(document: CanonicalJsonObject, keys: Set<String>) {
        if (document.values.keys != keys) invalid()
    }

    private fun canonicalWithProof(
        document: CanonicalJsonObject,
        key: ByteArray,
        label: String,
    ): ByteArray {
        val proof = proof(key, label, document)
        return StrictCanonicalJson.canonicalBytes(
            CanonicalJsonObject(document.values + ("proof" to StrictCanonicalJson.string(proof))),
        )
    }

    private fun verifyProof(document: CanonicalJsonObject, key: ByteArray, label: String) {
        val supplied = (document.values["proof"] as? CanonicalJsonString)?.value
            ?: fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        if (!proofPattern.matches(supplied)) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        }
        val unsigned = CanonicalJsonObject(document.values - "proof")
        val expected = proof(key, label, unsigned)
        if (!java.security.MessageDigest.isEqual(supplied.encodeToByteArray(), expected.encodeToByteArray())) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        }
    }

    private fun proof(key: ByteArray, label: String, document: CanonicalJsonObject): String {
        val message = label.encodeToByteArray() + byteArrayOf(0) +
            StrictCanonicalJson.canonicalBytes(document)
        return "hmac_" + hmac(key, message).toHex()
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun requireSecret(secret: ByteArray) {
        if (secret.size !in 32..256) fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
    }

    private fun sha256(value: ByteArray): String = "sha256_" + Crypto.sha256(value).toHex()

    private fun nonce(value: String): String =
        value.takeIf(noncePattern::matches) ?: invalid()

    private fun digest(value: String): String =
        value.takeIf(digestPattern::matches) ?: invalid()

    private fun idempotency(value: String): String =
        value.takeIf(idempotencyPattern::matches) ?: invalid()

    private fun identifier(value: String): String =
        value.takeIf(identifierPattern::matches) ?: invalid()

    private fun reference(value: String, prefix: String): String {
        if (!value.startsWith(prefix) || !referencePattern.matches(value.removePrefix(prefix))) invalid()
        return value
    }

    private fun validSequence(value: Long): Long {
        if (value !in 1..MAX_SAFE_INTEGER) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_SEQUENCE_INVALID)
        }
        return value
    }

    private fun validateOperations(values: Set<String>): List<String> {
        if (values.isEmpty() || !operations.containsAll(values)) invalid()
        return values.sorted()
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.contains(':')) {
            return try {
                !InetAddress.getByName(host).isAnyLocalAddress
            } catch (_: Exception) {
                false
            }
        }
        if (host == "0.0.0.0") return false
        return hostPattern.matches(host)
    }

    private fun decodeApplication(value: String, maximum: Int): ByteArray {
        if (value.length % 4 != 0 || !BASE64_PATTERN.matches(value)) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        }
        if (decoded.isEmpty() || decoded.size > maximum) {
            decoded.fill(0)
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        }
        return decoded
    }

    private fun parseApplicationDocument(line: ByteArray, maximum: Int): CanonicalJsonObject = try {
        StrictCanonicalJson.parseLine(line, maximum) as? CanonicalJsonObject
            ?: fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
    } catch (violation: AgentLocalNodeChannelViolation) {
        throw violation
    } catch (_: Exception) {
        fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
    }

    private fun validateApplicationRequest(line: ByteArray): ApplicationRequestView {
        val document = parseApplicationDocument(line, MAX_REQUEST_BYTES)
        return try {
            ApplicationProtocolValidator.validateRequest(document)
        } catch (violation: AgentLocalNodeChannelViolation) {
            if (violation.code == AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID) {
                throw violation
            }
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        } catch (_: Exception) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        }
    }

    private fun validateApplicationResponse(line: ByteArray, requestId: String) {
        try {
            ApplicationProtocolValidator.validateResponse(
                parseApplicationDocument(line, MAX_RESPONSE_BYTES),
                requestId,
            )
        } catch (violation: AgentLocalNodeChannelViolation) {
            if (violation.code == AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID) {
                throw violation
            }
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        } catch (_: Exception) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_PAYLOAD_INVALID)
        }
    }

    private fun invalid(): Nothing = fail(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE)

    private fun fail(code: AgentLocalNodeChannelErrorCode): Nothing =
        throw AgentLocalNodeChannelViolation(code)

    private val BASE64_PATTERN = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}

private data class ApplicationRequestView(
    val requestId: String,
    val operation: String,
    val idempotencySlot: String,
)

private object ApplicationProtocolValidator {
    private const val protocol = "ameme.agent-local-node.v1"
    private const val maxPayloadBytes = 32_768
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val digestPattern = Regex("sha256_[0-9a-f]{64}")
    private val slotPattern = Regex("idem_[0-9a-f]{64}")
    private val operations = setOf(
        "get_event",
        "create_event",
        "append_revision",
        "undo_capture",
        "visible_events",
        "set_policy_blocked",
    )
    private val requestKeys = setOf("protocol_version", "request_id", "control", "payload")
    private val controlKeys = setOf(
        "caller_id",
        "grant_id",
        "purpose",
        "spaces",
        "memory_types",
        "operation",
        "idempotency_slot",
        "payload_digest",
    )
    private val responseKeys = setOf(
        "protocol_version",
        "request_id",
        "status",
        "result",
        "result_digest",
        "error",
    )
    private val retryableErrors = mapOf(
        "AUTH_REQUIRED" to false,
        "GRANT_REVOKED" to false,
        "GRANT_EXPIRED" to false,
        "PURPOSE_DENIED" to false,
        "SPACE_DENIED" to false,
        "DATA_TYPE_DENIED" to false,
        "SCHEMA_UNSUPPORTED" to false,
        "INVALID_REQUEST" to false,
        "PAYLOAD_TOO_LARGE" to false,
        "PAYLOAD_DIGEST_MISMATCH" to false,
        "RESULT_DIGEST_MISMATCH" to false,
        "SCOPE_MISMATCH" to false,
        "NOT_VISIBLE" to false,
        "OPERATION_UNSUPPORTED" to false,
        "REVISION_CONFLICT" to false,
        "IDEMPOTENCY_CONFLICT" to false,
        "TEMPORARILY_UNAVAILABLE" to true,
        "INTERNAL_ERROR" to true,
        "RESPONSE_REQUEST_MISMATCH" to false,
    )

    fun validateRequest(request: CanonicalJsonObject): ApplicationRequestView {
        exact(request, requestKeys)
        if (request.string("protocol_version") != protocol) invalid()
        val requestId = id(request.string("request_id"))
        val control = request.obj("control")
        exact(control, controlKeys)
        id(control.string("caller_id"))
        id(control.string("grant_id"))
        id(control.string("purpose"))
        val spaces = scope(control.array("spaces"), false)
        val memoryTypes = scope(control.array("memory_types"), true)
        val operation = control.string("operation").takeIf { it in operations } ?: invalid()
        val slot = control.string("idempotency_slot").takeIf(slotPattern::matches) ?: invalid()
        val payloadDigest = control.string("payload_digest").takeIf(digestPattern::matches) ?: invalid()
        val payload = request.obj("payload")
        if (StrictCanonicalJson.canonicalBytes(payload).size > maxPayloadBytes) invalid()
        val requestedScope = validatePayload(operation, payload)
        if (spaces != requestedScope.first || memoryTypes != requestedScope.second) invalid()
        if (payloadDigest != StrictCanonicalJson.digest(payload)) invalid()
        return ApplicationRequestView(requestId, operation, slot)
    }

    fun validateResponse(response: CanonicalJsonObject, requestId: String) {
        exact(response, responseKeys)
        if (response.string("protocol_version") != protocol) invalid()
        if (id(response.string("request_id")) != requestId) invalid()
        when (response.string("status")) {
            "ok" -> {
                val result = response.values["result"] as? CanonicalJsonObject ?: invalid()
                if (response.values["error"] !== CanonicalJsonNull) invalid()
                val expected = StrictCanonicalJson.digest(result)
                if (response.string("result_digest") != expected) invalid()
            }
            "error" -> {
                if (response.values["result"] !== CanonicalJsonNull) invalid()
                if (response.values["result_digest"] !== CanonicalJsonNull) invalid()
                val error = response.obj("error")
                exact(error, setOf("code", "retryable"))
                val code = error.string("code")
                val retryable = (error.values["retryable"] as? CanonicalJsonBoolean)?.value ?: invalid()
                if (retryableErrors[code] != retryable) invalid()
            }
            else -> invalid()
        }
    }

    private fun validatePayload(
        operation: String,
        payload: CanonicalJsonObject,
    ): Pair<List<String>, List<String>> = when (operation) {
        "get_event" -> {
            shape(payload, setOf("event_id", "space", "memory_type"))
            id(payload.string("event_id"))
            val space = id(payload.string("space"))
            val type = enum(payload.string("memory_type"), setOf("event", "revision"))
            listOf(space) to listOf(type)
        }
        "create_event" -> {
            shape(
                payload,
                setOf(
                    "space",
                    "memory_type",
                    "content",
                    "event_type",
                    "evidence_state",
                    "fact_status",
                    "sensitivity",
                    "data_class",
                    "now",
                ),
                setOf("event_time"),
            )
            val space = id(payload.string("space"))
            val type = enum(payload.string("memory_type"), setOf("event"))
            bounded(payload.string("content"), 4_000)
            enum(
                payload.string("event_type"),
                setOf(
                    "activity",
                    "communication",
                    "decision",
                    "result",
                    "state_change",
                    "milestone",
                    "experience",
                ),
            )
            enum(payload.string("evidence_state"), setOf("observed", "user_asserted", "inferred"))
            enum(
                payload.string("fact_status"),
                setOf("confirmed", "user_asserted", "low_confidence_candidate"),
            )
            enum(payload.string("sensitivity"), setOf("public", "personal", "confidential", "restricted"))
            enum(payload.string("data_class"), setOf("structured"))
            timestamp(payload.string("now"))
            payload.values["event_time"]?.let { timestamp((it as? CanonicalJsonString)?.value ?: invalid()) }
            listOf(space) to listOf(type)
        }
        "append_revision" -> {
            shape(
                payload,
                setOf("event_id", "space", "memory_type", "content", "evidence_state", "fact_status", "now"),
            )
            id(payload.string("event_id"))
            val space = id(payload.string("space"))
            val type = enum(payload.string("memory_type"), setOf("revision"))
            bounded(payload.string("content"), 4_000)
            enum(payload.string("evidence_state"), setOf("observed", "user_asserted", "inferred"))
            enum(
                payload.string("fact_status"),
                setOf("confirmed", "user_asserted", "low_confidence_candidate"),
            )
            timestamp(payload.string("now"))
            listOf(space) to listOf(type)
        }
        "undo_capture" -> {
            shape(payload, setOf("undo_token", "space", "memory_type", "now"))
            id(payload.string("undo_token"))
            val space = id(payload.string("space"))
            val type = enum(payload.string("memory_type"), setOf("event", "revision"))
            timestamp(payload.string("now"))
            listOf(space) to listOf(type)
        }
        "visible_events" -> {
            shape(
                payload,
                setOf("spaces", "memory_types", "allow_high_risk", "limit"),
                setOf("query", "start_at", "end_at"),
            )
            val spaces = scope(payload.array("spaces"), false)
            val types = scope(payload.array("memory_types"), true)
            if (payload.values["allow_high_risk"] !is CanonicalJsonBoolean) invalid()
            if (payload.long("limit") !in 1..100) invalid()
            payload.values["query"]?.let { bounded((it as? CanonicalJsonString)?.value ?: invalid(), 1_000, true) }
            payload.values["start_at"]?.let { timestamp((it as? CanonicalJsonString)?.value ?: invalid()) }
            payload.values["end_at"]?.let { timestamp((it as? CanonicalJsonString)?.value ?: invalid()) }
            spaces to types
        }
        "set_policy_blocked" -> {
            shape(payload, setOf("event_id", "space", "memory_type"))
            id(payload.string("event_id"))
            val space = id(payload.string("space"))
            val type = enum(payload.string("memory_type"), setOf("event"))
            listOf(space) to listOf(type)
        }
        else -> invalid()
    }

    private fun exact(value: CanonicalJsonObject, keys: Set<String>) {
        if (value.values.keys != keys) invalid()
    }

    private fun shape(value: CanonicalJsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        if (!value.values.keys.containsAll(required) || !required.plus(optional).containsAll(value.values.keys)) invalid()
    }

    private fun id(value: String): String = value.takeIf(idPattern::matches) ?: invalid()

    private fun bounded(value: String, maximum: Int, allowEmpty: Boolean = false): String {
        val length = value.codePointCount(0, value.length)
        if (length > maximum || (!allowEmpty && value.isEmpty())) invalid()
        return value
    }

    private fun enum(value: String, allowed: Set<String>): String = value.takeIf { it in allowed } ?: invalid()

    private fun scope(value: CanonicalJsonArray, memoryTypes: Boolean): List<String> {
        if (value.values.isEmpty() || value.values.size > 8) invalid()
        val items = value.values.map { (it as? CanonicalJsonString)?.value ?: invalid() }
        items.forEach { if (memoryTypes) enum(it, setOf("event", "revision")) else id(it) }
        if (items != items.sorted() || items.toSet().size != items.size) invalid()
        return items
    }

    private fun timestamp(value: String) {
        bounded(value, 64)
        try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            invalid()
        }
    }

    private fun invalid(): Nothing =
        throw IllegalArgumentException("invalid_application_message")
}

private fun CanonicalJsonObject.string(key: String): String =
    (values[key] as? CanonicalJsonString)?.value ?: invalidChannelField()

private fun CanonicalJsonObject.long(key: String): Long = try {
    (values[key] as? CanonicalJsonInteger)?.value?.longValueExact() ?: invalidChannelField()
} catch (_: ArithmeticException) {
    invalidChannelField()
}

private fun CanonicalJsonObject.obj(key: String): CanonicalJsonObject =
    values[key] as? CanonicalJsonObject ?: invalidChannelField()

private fun CanonicalJsonObject.array(key: String): CanonicalJsonArray =
    values[key] as? CanonicalJsonArray ?: invalidChannelField()

private fun CanonicalJsonObject.stringList(key: String): List<String> =
    array(key).values.map { (it as? CanonicalJsonString)?.value ?: invalidChannelField() }

private fun invalidChannelField(): Nothing = throw AgentLocalNodeChannelViolation(
    AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE,
)
