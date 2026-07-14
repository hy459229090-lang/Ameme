package com.ameme.android.data.transport

import java.io.Closeable

/**
 * Application-layer port for a future authenticated Agent-to-Android Local Node transport.
 *
 * This deliberately does not define a LAN wire format or implementation. Those remain blocked
 * on the accepted device discovery, authentication, encryption, replay, and lifecycle design.
 */
interface AgentLocalNodeTransport : Closeable {
    suspend fun exchange(request: AgentLocalNodeRequest): AgentLocalNodeResponse

    override fun close() = Unit
}

/** Content-free metadata that may be used for routing, audit, and retry control. */
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
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(callerId.isNotBlank()) { "callerId must not be blank" }
        require(grantId.isNotBlank()) { "grantId must not be blank" }
        require(purpose.isNotBlank()) { "purpose must not be blank" }
        require(spaces.isNotEmpty() && spaces.none(String::isBlank)) {
            "spaces must contain explicit values"
        }
        require(memoryTypes.isNotEmpty() && memoryTypes.none(String::isBlank)) {
            "memoryTypes must contain explicit values"
        }
        require(operation.isNotBlank()) { "operation must not be blank" }
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
        private val HEX_64 = Regex("[0-9a-f]{64}")
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
    payload: ByteArray = byteArrayOf(),
) : Closeable {
    private val payloadBytes = payload.copyOf()
    private var cleared = false

    init {
        require(protocolVersion == AgentLocalNodeControl.PROTOCOL_VERSION) {
            "unsupported Agent Local Node protocol version"
        }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
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
        "AgentLocalNodeResponse(requestId=$requestId, status=$status, " +
            "payload=<${if (cleared) "cleared" else "redacted:${payloadBytes.size} bytes"}>)"

    companion object {
        /** Construct only after binding the received envelope to its originating request. */
        fun validatedFor(
            request: AgentLocalNodeRequest,
            protocolVersion: String,
            requestId: String,
            status: AgentLocalNodeStatus,
            payload: ByteArray = byteArrayOf(),
        ): AgentLocalNodeResponse {
            require(protocolVersion == request.control.protocolVersion) {
                "response protocol does not match request"
            }
            require(requestId == request.control.requestId) {
                "response requestId does not match request"
            }
            return AgentLocalNodeResponse(protocolVersion, requestId, status, payload)
        }
    }
}

enum class AgentLocalNodeStatus {
    Ok,
    NotVisible,
    Conflict,
    IdempotencyConflict,
    Unavailable,
}
