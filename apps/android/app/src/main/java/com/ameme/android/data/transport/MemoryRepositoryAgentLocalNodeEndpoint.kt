package com.ameme.android.data.transport

import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.EventType
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class VerifiedAgentLocalNodeGrantState {
    Active,
    Revoked,
}

/** Authorization established outside this application endpoint. Control fields remain claims. */
internal data class VerifiedAgentLocalNodeSession(
    val callerId: String,
    val grantId: String,
    val purpose: String,
    val allowedSpaces: Set<String>,
    val allowedMemoryTypes: Set<String>,
    val allowedOperations: Set<String>,
    val allowedSensitivities: Set<String>,
    val allowedDataClasses: Set<String>,
    val expiresAt: Instant,
    val grantState: VerifiedAgentLocalNodeGrantState = VerifiedAgentLocalNodeGrantState.Active,
    val accessGrant: AgentAccessGrant? = null,
)

/** Durable, atomic idempotency is an injected infrastructure dependency, not endpoint state. */
internal fun interface AgentLocalNodeIdempotencyRegistry {
    fun resolveOrCapture(
        binding: AgentLocalNodeIdempotencyBinding,
        payloadDigest: String,
        capture: () -> AgentLocalNodeCaptureOutcome,
    ): AgentLocalNodeIdempotencyResult
}

internal data class AgentLocalNodeIdempotencyBinding(
    val callerId: String,
    val grantId: String,
    val purpose: String,
    val spaceId: String,
    val memoryType: String,
    val operation: String,
    val slot: String,
)

internal data class AgentLocalNodeCaptureOutcome(
    val eventId: String,
    val revision: Int,
)

internal sealed interface AgentLocalNodeIdempotencyResult {
    data class Applied(val outcome: AgentLocalNodeCaptureOutcome) : AgentLocalNodeIdempotencyResult

    data object Conflict : AgentLocalNodeIdempotencyResult
}

/**
 * Process-local `create_event` application dispatcher backed by [MemoryRepository].
 *
 * This is not a socket, discovery service, LAN protocol, or authentication implementation. The
 * public factory is closed. An enabled instance requires a separately verified session and an
 * atomic idempotency registry; the production runtime injects both and may additionally attach
 * the local AccessGrant policy gate.
 */
class MemoryRepositoryAgentLocalNodeEndpoint private constructor(
    private val repository: MemoryRepository?,
    private val repositorySpaceId: String?,
    private val verifiedSession: VerifiedAgentLocalNodeSession?,
    private val idempotencyRegistry: AgentLocalNodeIdempotencyRegistry?,
    private val clock: Clock,
) : AgentLocalNodeTransport {
    override suspend fun exchange(request: AgentLocalNodeRequest): AgentLocalNodeResponse {
        var payloadBytes: ByteArray? = null
        try {
            val control = request.control
            authorizationError(control)?.let { return error(request, it) }
            if (control.operation != OPERATION_CREATE_EVENT) {
                return error(request, AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED)
            }

            payloadBytes = request.payloadCopy()
            val command = try {
                AgentLocalNodeApplicationCodec.decodeCreateEvent(payloadBytes)
            } catch (_: AgentLocalNodePayloadTooLargeException) {
                return error(request, AgentLocalNodeErrorCode.PAYLOAD_TOO_LARGE)
            } catch (_: IllegalArgumentException) {
                return error(request, AgentLocalNodeErrorCode.INVALID_REQUEST)
            }
            if (!AgentLocalNodeApplicationCodec.digestMatches(payloadBytes, control.payloadDigest)) {
                return error(request, AgentLocalNodeErrorCode.PAYLOAD_DIGEST_MISMATCH)
            }

            val spaceId = requireNotNull(repositorySpaceId)
            if (
                control.spaces != setOf(command.space) ||
                control.memoryTypes != setOf(command.memoryType)
            ) {
                return error(request, AgentLocalNodeErrorCode.SCOPE_MISMATCH)
            }
            if (command.space != spaceId) {
                return error(request, AgentLocalNodeErrorCode.SPACE_DENIED)
            }
            val session = requireNotNull(verifiedSession)
            if (command.sensitivity !in session.allowedSensitivities) {
                return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
            }
            if (command.dataClass !in session.allowedDataClasses) {
                return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
            }

            val factStatus = command.toFactStatus()
                ?: return error(request, AgentLocalNodeErrorCode.INVALID_REQUEST)
            val eventTimestamp = command.eventTimestamp()
            val content = command.content
            val result = requireNotNull(idempotencyRegistry).resolveOrCapture(
                binding = AgentLocalNodeIdempotencyBinding(
                    callerId = control.callerId,
                    grantId = control.grantId,
                    purpose = control.purpose,
                    spaceId = command.space,
                    memoryType = command.memoryType,
                    operation = control.operation,
                    slot = control.idempotencySlot,
                ),
                payloadDigest = control.payloadDigest,
            ) {
                val event = requireNotNull(repository).captureSource(
                    SourceCaptureRequest(
                        sourceKind = SourceKind.AgentAutonomous,
                        title = content.lineSequence()
                            .map(String::trim)
                            .firstOrNull(String::isNotEmpty)
                            .orEmpty()
                            .take(MAX_TITLE_CHARS),
                        detail = content,
                        factStatus = factStatus,
                        localDate = eventTimestamp.toLocalDate(),
                        time = eventTimestamp.toLocalTime(),
                        eventType = EventType.entries.first { it.wireValue == command.eventType },
                        evidenceState = EvidenceState.entries.first { it.wireValue == command.evidenceState },
                        sensitivity = Sensitivity.entries.first { it.wireValue == command.sensitivity },
                    ),
                )
                AgentLocalNodeCaptureOutcome(eventId = event.id, revision = FIRST_REVISION)
            }
            return when (result) {
                is AgentLocalNodeIdempotencyResult.Applied -> success(request, result.outcome)
                AgentLocalNodeIdempotencyResult.Conflict ->
                    error(request, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return error(request, AgentLocalNodeErrorCode.TEMPORARILY_UNAVAILABLE)
        } finally {
            payloadBytes?.fill(0)
            request.close()
        }
    }

    private fun authorizationError(control: AgentLocalNodeControl): AgentLocalNodeErrorCode? {
        val session = verifiedSession ?: return AgentLocalNodeErrorCode.AUTH_REQUIRED
        if (repository == null || repositorySpaceId == null || idempotencyRegistry == null) {
            return AgentLocalNodeErrorCode.AUTH_REQUIRED
        }
        if (control.callerId != session.callerId || control.grantId != session.grantId) {
            return AgentLocalNodeErrorCode.AUTH_REQUIRED
        }
        session.accessGrant?.let { grant ->
            val grantFailure = runCatching {
                grant.authorizeScope(
                    callerId = control.callerId,
                    grantId = control.grantId,
                    purpose = control.purpose,
                    spaces = control.spaces,
                    dataTypes = control.memoryTypes,
                    at = clock.instant(),
                )
            }.exceptionOrNull()
            if (grantFailure != null) {
                return when (grantFailure) {
                    is AgentAccessGrantException -> when (grantFailure.failure) {
                        AgentAccessGrantFailure.AuthRequired -> AgentLocalNodeErrorCode.AUTH_REQUIRED
                        AgentAccessGrantFailure.GrantRevoked -> AgentLocalNodeErrorCode.GRANT_REVOKED
                        AgentAccessGrantFailure.GrantExpired -> AgentLocalNodeErrorCode.GRANT_EXPIRED
                        AgentAccessGrantFailure.PurposeDenied -> AgentLocalNodeErrorCode.PURPOSE_DENIED
                        AgentAccessGrantFailure.SpaceDenied -> AgentLocalNodeErrorCode.SPACE_DENIED
                        AgentAccessGrantFailure.DataTypeDenied -> AgentLocalNodeErrorCode.DATA_TYPE_DENIED
                    }
                    else -> AgentLocalNodeErrorCode.AUTH_REQUIRED
                }
            }
        }
        if (session.grantState == VerifiedAgentLocalNodeGrantState.Revoked) {
            return AgentLocalNodeErrorCode.GRANT_REVOKED
        }
        if (!session.expiresAt.isAfter(clock.instant())) return AgentLocalNodeErrorCode.GRANT_EXPIRED
        if (control.purpose != session.purpose || control.purpose != PURPOSE_AUTONOMOUS_MEMORY) {
            return AgentLocalNodeErrorCode.PURPOSE_DENIED
        }
        if (!control.spaces.all(session.allowedSpaces::contains)) {
            return AgentLocalNodeErrorCode.SPACE_DENIED
        }
        if (!control.memoryTypes.all(session.allowedMemoryTypes::contains)) {
            return AgentLocalNodeErrorCode.DATA_TYPE_DENIED
        }
        if (control.operation !in session.allowedOperations) {
            return AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED
        }
        return null
    }

    private fun success(
        request: AgentLocalNodeRequest,
        outcome: AgentLocalNodeCaptureOutcome,
    ): AgentLocalNodeResponse {
        val result = AgentLocalNodeApplicationCodec.encodeCreateEventResult(
            AgentLocalNodeCreateEventResult(
                eventId = outcome.eventId,
                revision = outcome.revision,
            ),
        )
        return try {
            AgentLocalNodeResponse.validatedFor(
                request = request,
                protocolVersion = request.control.protocolVersion,
                requestId = request.control.requestId,
                status = AgentLocalNodeStatus.Ok,
                resultDigest = AgentLocalNodeApplicationCodec.canonicalDigest(result),
                payload = result,
            )
        } finally {
            result.fill(0)
        }
    }

    private fun error(
        request: AgentLocalNodeRequest,
        code: AgentLocalNodeErrorCode,
    ): AgentLocalNodeResponse = AgentLocalNodeResponse.validatedFor(
        request = request,
        protocolVersion = request.control.protocolVersion,
        requestId = request.control.requestId,
        status = AgentLocalNodeStatus.Error,
        error = AgentLocalNodeError(code),
    )

    companion object {
        const val PURPOSE_AUTONOMOUS_MEMORY = "autonomous_memory"
        const val OPERATION_CREATE_EVENT = "create_event"
        const val MEMORY_TYPE_EVENT = "event"
        private const val FIRST_REVISION = 1
        private const val MAX_TITLE_CHARS = 240

        /** Production-safe default until authentication and durable idempotency are accepted. */
        fun closed(clock: Clock = Clock.systemUTC()): MemoryRepositoryAgentLocalNodeEndpoint =
            MemoryRepositoryAgentLocalNodeEndpoint(null, null, null, null, clock)

        /** Test/spike seam; intentionally not wired into the Android production application. */
        internal fun enabledForVerifiedSession(
            repository: MemoryRepository,
            repositorySpaceId: String,
            verifiedSession: VerifiedAgentLocalNodeSession,
            idempotencyRegistry: AgentLocalNodeIdempotencyRegistry,
            clock: Clock = Clock.systemUTC(),
        ): MemoryRepositoryAgentLocalNodeEndpoint {
            require(repositorySpaceId.isNotBlank()) { "repositorySpaceId must not be blank" }
            return MemoryRepositoryAgentLocalNodeEndpoint(
                repository,
                repositorySpaceId,
                verifiedSession,
                idempotencyRegistry,
                clock,
            )
        }
    }
}

@Serializable
internal data class AgentLocalNodeCreateEventPayload(
    val space: String,
    @SerialName("memory_type") val memoryType: String,
    val content: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("evidence_state") val evidenceState: String,
    @SerialName("fact_status") val factStatus: String,
    val sensitivity: String,
    @SerialName("data_class") val dataClass: String,
    val now: String,
    @SerialName("event_time") val eventTime: String? = null,
) {
    init {
        requireIdentifier(space, "space")
        require(memoryType == "event") { "unsupported memory_type" }
        require(content.isNotBlank() && content.codePointLength() <= 4_000) { "invalid content" }
        require('\u0000' !in content) { "content contains NUL" }
        require(eventType in EVENT_TYPES) { "invalid event_type" }
        require(evidenceState in EVIDENCE_STATES) { "invalid evidence_state" }
        require(factStatus in FACT_STATUSES) { "invalid fact_status" }
        require(sensitivity in SENSITIVITIES) { "invalid sensitivity" }
        require(dataClass == "structured") { "unsupported data_class" }
        parseTimestamp(now)
        eventTime?.let(::parseTimestamp)
    }

    fun eventTimestamp(): OffsetDateTime = parseTimestamp(eventTime ?: now)

    fun toFactStatus(): FactStatus? = when (evidenceState to factStatus) {
        "observed" to "confirmed" -> FactStatus.Confirmed
        "user_asserted" to "user_asserted" -> FactStatus.UserAsserted
        "inferred" to "low_confidence_candidate" -> FactStatus.NeedsReview
        else -> null
    }

    companion object {
        val EVENT_TYPES = setOf(
            "activity", "communication", "decision", "result", "state_change", "milestone", "experience",
        )
        val EVIDENCE_STATES = setOf("observed", "user_asserted", "inferred")
        val FACT_STATUSES = setOf("confirmed", "user_asserted", "low_confidence_candidate")
        val SENSITIVITIES = setOf("public", "personal", "confidential", "restricted")
    }
}

@Serializable
internal data class AgentLocalNodeCreateEventResult(
    @SerialName("event_id") val eventId: String,
    @SerialName("object_type") val objectType: String = "event",
    val revision: Int,
) {
    init {
        requireIdentifier(eventId, "event_id")
        require(objectType == "event") { "unsupported object_type" }
        require(revision >= 1) { "invalid revision" }
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object AgentLocalNodeApplicationCodec {
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 32_768
    private const val MAX_REQUEST_BYTES = 65_536
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
    }

    fun encodeCreateEvent(payload: AgentLocalNodeCreateEventPayload): ByteArray =
        canonicalBytes(json.encodeToJsonElement(payload)).also {
            if (it.size > MAX_CANONICAL_PAYLOAD_BYTES) {
                it.fill(0)
                throw AgentLocalNodePayloadTooLargeException()
            }
        }

    fun decodeCreateEvent(bytes: ByteArray): AgentLocalNodeCreateEventPayload {
        if (bytes.size > MAX_CANONICAL_PAYLOAD_BYTES) throw AgentLocalNodePayloadTooLargeException()
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "payload must be an object" }
        val payload = json.decodeFromJsonElement<AgentLocalNodeCreateEventPayload>(element)
        val canonical = encodeCreateEvent(payload)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "payload is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return payload
    }

    fun payloadDigest(payload: AgentLocalNodeCreateEventPayload): String {
        val bytes = encodeCreateEvent(payload)
        return try {
            canonicalDigest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun digestMatches(payload: ByteArray, claimedDigest: String): Boolean {
        val actual = canonicalDigest(payload).encodeToByteArray()
        val claimed = claimedDigest.encodeToByteArray()
        return try {
            MessageDigest.isEqual(actual, claimed)
        } finally {
            actual.fill(0)
            claimed.fill(0)
        }
    }

    fun encodeCreateEventResult(result: AgentLocalNodeCreateEventResult): ByteArray =
        canonicalBytes(json.encodeToJsonElement(result))

    /** Canonical envelope encoder used for shared Kotlin conformance tests, not a LAN transport. */
    fun encodeCreateEventRequestEnvelope(
        control: AgentLocalNodeControl,
        payload: AgentLocalNodeCreateEventPayload,
    ): ByteArray {
        require(control.operation == MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT)
        require(control.spaces == setOf(payload.space))
        require(control.memoryTypes == setOf(payload.memoryType))
        require(control.payloadDigest == payloadDigest(payload))
        val payloadElement = json.encodeToJsonElement(payload)
        return canonicalBytes(
            buildJsonObject {
                put("protocol_version", AgentLocalNodeControl.PROTOCOL_VERSION)
                put("request_id", control.requestId)
                put("control", buildJsonObject {
                    put("caller_id", control.callerId)
                    put("grant_id", control.grantId)
                    put("purpose", control.purpose)
                    put("spaces", sortedStringArray(control.spaces))
                    put("memory_types", sortedStringArray(control.memoryTypes))
                    put("operation", control.operation)
                    put("idempotency_slot", control.idempotencySlot)
                    put("payload_digest", control.payloadDigest)
                })
                put("payload", payloadElement)
            },
        )
    }

    fun decodeCreateEventRequestEnvelope(bytes: ByteArray): AgentLocalNodeRequest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_REQUEST_BYTES) { "request envelope is too large" }
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("request envelope must be an object")
        require(root.keys == setOf("protocol_version", "request_id", "control", "payload")) {
            "request envelope fields are invalid"
        }
        val canonical = canonicalBytes(root)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "request envelope is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        val protocolVersion = root.string("protocol_version")
        val requestId = root.string("request_id")
        require(protocolVersion == AgentLocalNodeControl.PROTOCOL_VERSION)
        val controlElement = root["control"] as? JsonObject
            ?: throw IllegalArgumentException("control must be an object")
        require(
            controlElement.keys == setOf(
                "caller_id",
                "grant_id",
                "purpose",
                "spaces",
                "memory_types",
                "operation",
                "idempotency_slot",
                "payload_digest",
            ),
        ) { "control fields are invalid" }
        val spaces = controlElement.sortedStrings("spaces")
        val memoryTypes = controlElement.sortedStrings("memory_types")
        val payloadElement = root["payload"] as? JsonObject
            ?: throw IllegalArgumentException("payload must be an object")
        val payload = json.decodeFromJsonElement<AgentLocalNodeCreateEventPayload>(payloadElement)
        val payloadBytes = encodeCreateEvent(payload)
        return try {
            AgentLocalNodeRequest(
                AgentLocalNodeControl(
                    protocolVersion = protocolVersion,
                    requestId = requestId,
                    callerId = controlElement.string("caller_id"),
                    grantId = controlElement.string("grant_id"),
                    purpose = controlElement.string("purpose"),
                    spaces = spaces.toSet(),
                    memoryTypes = memoryTypes.toSet(),
                    operation = controlElement.string("operation"),
                    idempotencySlot = controlElement.string("idempotency_slot"),
                    payloadDigest = controlElement.string("payload_digest"),
                ),
                payloadBytes,
            )
        } finally {
            payloadBytes.fill(0)
        }
    }

    /** Canonical six-field response envelope encoder for shared Kotlin conformance tests. */
    fun encodeResponseEnvelope(response: AgentLocalNodeResponse): ByteArray {
        val result = if (response.status == AgentLocalNodeStatus.Ok) {
            val resultBytes = response.payloadCopy()
            try {
                json.encodeToJsonElement(decodeCreateEventResult(resultBytes))
            } finally {
                resultBytes.fill(0)
            }
        } else {
            JsonNull
        }
        val error = response.error?.let { value ->
            buildJsonObject {
                put("code", value.code.name)
                put("retryable", value.retryable)
            }
        } ?: JsonNull
        return canonicalBytes(
            buildJsonObject {
                put("protocol_version", response.protocolVersion)
                put("request_id", response.requestId)
                put("status", response.status.wireValue)
                put("result", result)
                put("result_digest", response.resultDigest?.let(::JsonPrimitive) ?: JsonNull)
                put("error", error)
            },
        )
    }

    fun decodeCreateEventResult(bytes: ByteArray): AgentLocalNodeCreateEventResult {
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "result must be an object" }
        val result = json.decodeFromJsonElement<AgentLocalNodeCreateEventResult>(element)
        val canonical = encodeCreateEventResult(result)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "result is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return result
    }

    fun canonicalDigest(bytes: ByteArray): String =
        "sha256_" + MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun canonicalBytes(element: JsonElement): ByteArray = canonicalText(element).encodeToByteArray()

    private fun JsonObject.string(key: String): String {
        val primitive = get(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a string")
        require(primitive.isString) { "$key must be a string" }
        return primitive.content
    }

    private fun JsonObject.sortedStrings(key: String): List<String> {
        val array = get(key) as? JsonArray ?: throw IllegalArgumentException("$key must be an array")
        val values = array.map { element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("$key must contain strings")
            require(primitive.isString) { "$key must contain strings" }
            primitive.content
        }
        require(values.isNotEmpty() && values == values.distinct().sortedWith(::compareUnicodeCodePoints)) {
            "$key must be sorted and unique"
        }
        return values
    }

    private fun sortedStringArray(values: Set<String>): JsonArray = buildJsonArray {
        values.sortedWith(::compareUnicodeCodePoints).forEach { add(JsonPrimitive(it)) }
    }

    private fun canonicalText(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedWith { left, right -> compareUnicodeCodePoints(left.key, right.key) }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                json.encodeToString(String.serializer(), key) + ":" + canonicalText(value)
            }

        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalText)
        JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) {
            json.encodeToString(String.serializer(), element.content)
        } else {
            validateCanonicalPrimitive(element.content)
        }
    }
}

internal class AgentLocalNodePayloadTooLargeException : IllegalArgumentException()

private fun requireIdentifier(value: String, field: String) {
    require(value.length in 1..128 && IDENTIFIER.matches(value)) { "invalid $field" }
}

private fun parseTimestamp(value: String): OffsetDateTime {
    require(value.length <= 64 && '\u0000' !in value) { "invalid timestamp" }
    return try {
        OffsetDateTime.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("invalid timestamp")
    }
}

private fun String.codePointLength(): Int = codePointCount(0, length)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun decodeUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw IllegalArgumentException("payload is not valid UTF-8")
    }
}

private fun compareUnicodeCodePoints(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftPoint = left.codePointAt(leftIndex)
        val rightPoint = right.codePointAt(rightIndex)
        if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
        leftIndex += Character.charCount(leftPoint)
        rightIndex += Character.charCount(rightPoint)
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private fun validateCanonicalPrimitive(value: String): String {
    if (value == "true" || value == "false" || value == "null") return value
    require(INTEGER.matches(value)) { "only base-10 integers are supported" }
    val integer = BigInteger(value)
    require(integer in MIN_SAFE_INTEGER..MAX_SAFE_INTEGER) { "integer exceeds IEEE-754 safe range" }
    return value
}

/** Syntax/duplicate-key/NUL/surrogate validator run before kotlinx serialization materializes DTOs. */
private class StrictJsonScanner(private val text: String) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        readValue()
        skipWhitespace()
        require(index == text.length) { "trailing JSON content" }
    }

    private fun readValue() {
        skipWhitespace()
        require(index < text.length) { "missing JSON value" }
        when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            else -> readPrimitive()
        }
    }

    private fun readObject() {
        index++
        val keys = mutableSetOf<String>()
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            skipWhitespace()
            val key = readString()
            require(keys.add(key)) { "duplicate JSON key" }
            skipWhitespace()
            require(consume(':')) { "missing object colon" }
            readValue()
            skipWhitespace()
            if (consume('}')) return
            require(consume(',')) { "missing object comma" }
        }
    }

    private fun readArray() {
        index++
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            readValue()
            skipWhitespace()
            if (consume(']')) return
            require(consume(',')) { "missing array comma" }
        }
    }

    private fun readString(): String {
        require(consume('"')) { "expected JSON string" }
        val value = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' -> {
                    val decoded = value.toString()
                    require('\u0000' !in decoded && !decoded.hasLoneSurrogate()) { "invalid JSON string" }
                    return decoded
                }

                char == '\\' -> {
                    require(index < text.length) { "truncated JSON escape" }
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "truncated unicode escape" }
                            val hex = text.substring(index, index + 4)
                            require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                "invalid unicode escape"
                            }
                            value.append(hex.toInt(16).toChar())
                            index += 4
                        }

                        else -> throw IllegalArgumentException("invalid JSON escape")
                    }
                }

                char < ' ' -> throw IllegalArgumentException("unescaped control character")
                else -> value.append(char)
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun readPrimitive() {
        val start = index
        while (index < text.length && text[index] !in charArrayOf(',', '}', ']') && !text[index].isWhitespace()) {
            index++
        }
        require(index > start) { "missing JSON primitive" }
        validateCanonicalPrimitive(text.substring(start, index))
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun consume(expected: Char): Boolean =
        if (index < text.length && text[index] == expected) {
            index++
            true
        } else {
            false
        }
}

private fun String.hasLoneSurrogate(): Boolean {
    var position = 0
    while (position < length) {
        val char = this[position]
        when {
            Character.isHighSurrogate(char) -> {
                if (position + 1 >= length || !Character.isLowSurrogate(this[position + 1])) return true
                position += 2
            }

            Character.isLowSurrogate(char) -> return true
            else -> position++
        }
    }
    return false
}

private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
private val MIN_SAFE_INTEGER = BigInteger("-9007199254740991")
private val MAX_SAFE_INTEGER = BigInteger("9007199254740991")
