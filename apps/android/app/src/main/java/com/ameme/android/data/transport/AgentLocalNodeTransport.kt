package com.ameme.android.data.transport

import java.io.Closeable

/**
 * Application-layer port for a future authenticated Agent-to-Android Local Node transport.
 *
 * This deliberately does not define a LAN wire format or implementation. Those remain blocked
 * on the accepted device discovery, authentication, encryption, replay, and lifecycle design.
 */
interface AgentLocalNodeTransport : Closeable {
    /**
     * Exchanges one application request. Implementations consume and clear the request payload
     * before this call returns (including failure paths); callers must not reuse the request.
     */
    suspend fun exchange(request: AgentLocalNodeRequest): AgentLocalNodeResponse

    override fun close() = Unit
}

/**
 * Content-free metadata that may be used for routing, audit, and retry control.
 *
 * Every field is an untrusted request claim. Possession of this object is not authentication;
 * an endpoint must bind it to a separately verified session or grant before repository access.
 */
data class AgentLocalNodeControl(
    val protocolVersion: String,
    val requestId: String,
    val callerId: String,
    val grantId: String,
    val purpose: String,
    val spaces: Set<String>,
    val memoryTypes: Set<String>,
    val operation: String,
    val idempotencySlot: String,
    val payloadDigest: String,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION) {
            "unsupported Agent Local Node protocol version"
        }
        requireIdentifier(requestId, "requestId")
        requireIdentifier(callerId, "callerId")
        requireIdentifier(grantId, "grantId")
        requireIdentifier(purpose, "purpose")
        require(spaces.size in 1..MAX_SCOPE_ITEMS && spaces.all(IDENTIFIER::matches)) {
            "spaces must contain explicit values"
        }
        require(memoryTypes.size in 1..MAX_SCOPE_ITEMS && memoryTypes.all(MEMORY_TYPES::contains)) {
            "memoryTypes must contain explicit values"
        }
        require(operation in OPERATIONS) { "operation is outside Agent Local Node v1" }
        require(
            idempotencySlot.startsWith("idem_") &&
                idempotencySlot.removePrefix("idem_").matches(HEX_64),
        ) {
            "idempotencySlot must be a domain-separated SHA-256 slot"
        }
        require(
            payloadDigest.startsWith("sha256_") &&
                payloadDigest.removePrefix("sha256_").matches(HEX_64),
        ) {
            "payloadDigest must be a SHA-256 digest"
        }
    }

    fun requireExactScope(space: String, memoryType: String) {
        require(space in spaces) { "requested space exceeds the authorized scope" }
        require(memoryType in memoryTypes) { "requested memory type exceeds the authorized scope" }
    }

    companion object {
        const val PROTOCOL_VERSION = "ameme.agent-local-node.v1"
        private const val MAX_SCOPE_ITEMS = 8
        private val HEX_64 = Regex("[0-9a-f]{64}")
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val MEMORY_TYPES = setOf("event", "revision")
        private val OPERATIONS = setOf(
            "append_revision",
            "create_event",
            "get_event",
            "set_policy_blocked",
            "undo_capture",
            "visible_events",
        )

        private fun requireIdentifier(value: String, field: String) {
            require(IDENTIFIER.matches(value)) { "$field must be a v1 Identifier" }
        }
    }
}

/**
 * Opaque in-memory application payload. It is excluded from control metadata and redacted from
 * toString so routine diagnostics cannot accidentally emit memory content.
 */
class AgentLocalNodeRequest(
    val control: AgentLocalNodeControl,
    payload: ByteArray,
) : Closeable {
    private val payloadBytes = payload.copyOf()
    private var cleared = false

    fun payloadCopy(): ByteArray {
        check(!cleared) { "request payload has been cleared" }
        return payloadBytes.copyOf()
    }

    override fun close() {
        payloadBytes.fill(0)
        cleared = true
    }

    override fun toString(): String =
        "AgentLocalNodeRequest(control=$control, " +
            "payload=<${if (cleared) "cleared" else "redacted:${payloadBytes.size} bytes"}>)"
}

class AgentLocalNodeResponse private constructor(
    val protocolVersion: String,
    val requestId: String,
    val status: AgentLocalNodeStatus,
    val resultDigest: String?,
    val error: AgentLocalNodeError?,
    payload: ByteArray = byteArrayOf(),
) : Closeable {
    private val payloadBytes = payload.copyOf()
    private var cleared = false

    init {
        require(protocolVersion == AgentLocalNodeControl.PROTOCOL_VERSION) {
            "unsupported Agent Local Node protocol version"
        }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        when (status) {
            AgentLocalNodeStatus.Ok -> {
                require(payloadBytes.isNotEmpty()) { "ok response requires a result" }
                require(resultDigest?.matches(DIGEST) == true) { "ok response requires a result digest" }
                require(error == null) { "ok response cannot contain an error" }
            }

            AgentLocalNodeStatus.Error -> {
                require(payloadBytes.isEmpty()) { "error response cannot contain a result" }
                require(resultDigest == null) { "error response cannot contain a result digest" }
                require(error != null) { "error response requires an error" }
            }
        }
    }

    fun payloadCopy(): ByteArray {
        check(!cleared) { "response payload has been cleared" }
        return payloadBytes.copyOf()
    }

    fun requireMatches(request: AgentLocalNodeRequest): AgentLocalNodeResponse {
        require(protocolVersion == request.control.protocolVersion) {
            "response protocol does not match request"
        }
        require(requestId == request.control.requestId) {
            "response requestId does not match request"
        }
        return this
    }

    override fun close() {
        payloadBytes.fill(0)
        cleared = true
    }

    override fun toString(): String =
        "AgentLocalNodeResponse(requestId=$requestId, status=$status, error=${error?.code}, " +
            "payload=<${if (cleared) "cleared" else "redacted:${payloadBytes.size} bytes"}>)"

    companion object {
        /** Construct only after binding the received envelope to its originating request. */
        fun validatedFor(
            request: AgentLocalNodeRequest,
            protocolVersion: String,
            requestId: String,
            status: AgentLocalNodeStatus,
            resultDigest: String? = null,
            error: AgentLocalNodeError? = null,
            payload: ByteArray = byteArrayOf(),
        ): AgentLocalNodeResponse {
            require(protocolVersion == request.control.protocolVersion) {
                "response protocol does not match request"
            }
            require(requestId == request.control.requestId) {
                "response requestId does not match request"
            }
            return AgentLocalNodeResponse(
                protocolVersion = protocolVersion,
                requestId = requestId,
                status = status,
                resultDigest = resultDigest,
                error = error,
                payload = payload,
            )
        }

        private val DIGEST = Regex("sha256_[0-9a-f]{64}")
    }
}

enum class AgentLocalNodeStatus(val wireValue: String) {
    Ok("ok"),
    Error("error"),
}

data class AgentLocalNodeError(
    val code: AgentLocalNodeErrorCode,
    val retryable: Boolean = code.retryable,
) {
    init {
        require(retryable == code.retryable) { "retryable must match the frozen v1 error mapping" }
    }
}

enum class AgentLocalNodeErrorCode(val retryable: Boolean) {
    AUTH_REQUIRED(false),
    DATA_TYPE_DENIED(false),
    GRANT_EXPIRED(false),
    GRANT_REVOKED(false),
    IDEMPOTENCY_CONFLICT(false),
    INTERNAL_ERROR(true),
    INVALID_REQUEST(false),
    NOT_VISIBLE(false),
    OPERATION_UNSUPPORTED(false),
    PAYLOAD_DIGEST_MISMATCH(false),
    PAYLOAD_TOO_LARGE(false),
    PURPOSE_DENIED(false),
    RESPONSE_REQUEST_MISMATCH(false),
    RESULT_DIGEST_MISMATCH(false),
    REVISION_CONFLICT(false),
    SCHEMA_UNSUPPORTED(false),
    SCOPE_MISMATCH(false),
    SPACE_DENIED(false),
    TEMPORARILY_UNAVAILABLE(true),
}
