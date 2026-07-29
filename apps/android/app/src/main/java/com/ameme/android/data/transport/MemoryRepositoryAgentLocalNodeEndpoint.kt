package com.ameme.android.data.transport

import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.AgentCaptureUndoConflictException
import com.ameme.android.data.AgentCaptureUndoResult
import com.ameme.android.data.AgentCaptureUndoTarget
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.EventType
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.MemoryEvent
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

    fun resolveOrUndo(
        binding: AgentLocalNodeIdempotencyBinding,
        payloadDigest: String,
        undoToken: String,
        at: Instant,
        capture: (AgentCaptureUndoTarget) -> AgentCaptureUndoResult?,
    ): AgentLocalNodeUndoIdempotencyResult = AgentLocalNodeUndoIdempotencyResult.NotVisible
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
    val revisionId: String? = null,
)

internal sealed interface AgentLocalNodeIdempotencyResult {
    data class Applied(val outcome: AgentLocalNodeCaptureOutcome) : AgentLocalNodeIdempotencyResult

    data object Conflict : AgentLocalNodeIdempotencyResult
}

internal sealed interface AgentLocalNodeUndoIdempotencyResult {
    data class Applied(val outcome: AgentCaptureUndoResult) : AgentLocalNodeUndoIdempotencyResult

    data object Conflict : AgentLocalNodeUndoIdempotencyResult

    data object NotVisible : AgentLocalNodeUndoIdempotencyResult
}

/**
 * Process-local `create_event` / `append_revision` / exact `undo_capture` / bounded
 * `visible_events` dispatcher backed by [MemoryRepository].
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
            if (control.operation !in IMPLEMENTED_OPERATIONS) {
                return error(request, AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED)
            }

            payloadBytes = request.payloadCopy()
            val decoded = try {
                when (control.operation) {
                    OPERATION_CREATE_EVENT ->
                        AgentLocalNodeDecodedCommand.Create(
                            AgentLocalNodeApplicationCodec.decodeCreateEvent(payloadBytes),
                        )
                    OPERATION_APPEND_REVISION ->
                        AgentLocalNodeDecodedCommand.Append(
                            AgentLocalNodeApplicationCodec.decodeAppendRevision(payloadBytes),
                        )
                    OPERATION_UNDO_CAPTURE ->
                        AgentLocalNodeDecodedCommand.Undo(
                            AgentLocalNodeApplicationCodec.decodeUndoCapture(payloadBytes),
                        )
                    OPERATION_VISIBLE_EVENTS ->
                        AgentLocalNodeDecodedCommand.Visible(
                            AgentLocalNodeApplicationCodec.decodeVisibleEvents(payloadBytes),
                        )
                    else -> return error(request, AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED)
                }
            } catch (_: AgentLocalNodePayloadTooLargeException) {
                return error(request, AgentLocalNodeErrorCode.PAYLOAD_TOO_LARGE)
            } catch (_: IllegalArgumentException) {
                return error(request, AgentLocalNodeErrorCode.INVALID_REQUEST)
            }
            if (!AgentLocalNodeApplicationCodec.digestMatches(payloadBytes, control.payloadDigest)) {
                return error(request, AgentLocalNodeErrorCode.PAYLOAD_DIGEST_MISMATCH)
            }

            return when (decoded) {
                is AgentLocalNodeDecodedCommand.Create ->
                    createEvent(request, decoded.command)
                is AgentLocalNodeDecodedCommand.Append ->
                    appendRevision(request, decoded.command)
                is AgentLocalNodeDecodedCommand.Undo ->
                    undoCapture(request, decoded.command)
                is AgentLocalNodeDecodedCommand.Visible ->
                    visibleEvents(request, decoded.command)
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

    private fun createEvent(
        request: AgentLocalNodeRequest,
        command: AgentLocalNodeCreateEventPayload,
    ): AgentLocalNodeResponse {
        scopeError(request.control, command.space, command.memoryType)?.let {
            return error(request, it)
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
            binding = request.control.idempotencyBinding(command.space, command.memoryType),
            payloadDigest = request.control.payloadDigest,
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
        return idempotencyResponse(request, result)
    }

    private fun appendRevision(
        request: AgentLocalNodeRequest,
        command: AgentLocalNodeAppendRevisionPayload,
    ): AgentLocalNodeResponse {
        scopeError(request.control, command.space, command.memoryType)?.let {
            return error(request, it)
        }
        val factStatus = command.toFactStatus()
            ?: return error(request, AgentLocalNodeErrorCode.INVALID_REQUEST)
        val evidenceState = EvidenceState.entries.first { it.wireValue == command.evidenceState }
        val session = requireNotNull(verifiedSession)
        if ("structured" !in session.allowedDataClasses) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val allowedSensitivities = Sensitivity.entries
            .filterTo(mutableSetOf()) { it.wireValue in session.allowedSensitivities }
        if (allowedSensitivities.isEmpty()) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val result = try {
            requireNotNull(idempotencyRegistry).resolveOrCapture(
                binding = request.control.idempotencyBinding(command.space, command.memoryType),
                payloadDigest = request.control.payloadDigest,
            ) {
                val appended = requireNotNull(repository).appendAgentRevision(
                    eventId = command.eventId,
                    content = command.content,
                    evidenceState = evidenceState,
                    factStatus = factStatus,
                    allowedSensitivities = allowedSensitivities,
                ) ?: throw AgentLocalNodeTargetNotVisibleException()
                AgentLocalNodeCaptureOutcome(
                    eventId = appended.event.id,
                    revision = appended.event.revision,
                    revisionId = appended.revisionId,
                )
            }
        } catch (_: AgentLocalNodeTargetNotVisibleException) {
            return error(request, AgentLocalNodeErrorCode.NOT_VISIBLE)
        }
        return idempotencyResponse(request, result)
    }

    private fun undoCapture(
        request: AgentLocalNodeRequest,
        command: AgentLocalNodeUndoCapturePayload,
    ): AgentLocalNodeResponse {
        scopeError(request.control, command.space, command.memoryType)?.let {
            return error(request, it)
        }
        val session = requireNotNull(verifiedSession)
        if ("structured" !in session.allowedDataClasses) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val allowedSensitivities = Sensitivity.entries
            .filterTo(mutableSetOf()) { it.wireValue in session.allowedSensitivities }
        if (allowedSensitivities.isEmpty()) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val result = try {
            requireNotNull(idempotencyRegistry).resolveOrUndo(
                binding = request.control.idempotencyBinding(command.space, command.memoryType),
                payloadDigest = request.control.payloadDigest,
                undoToken = command.undoToken,
                at = clock.instant(),
            ) { target ->
                requireNotNull(repository).undoAgentCapture(
                    target = target,
                    allowedSensitivities = allowedSensitivities,
                    undoneAt = clock.instant(),
                )
            }
        } catch (_: AgentCaptureUndoConflictException) {
            return error(request, AgentLocalNodeErrorCode.REVISION_CONFLICT)
        }
        return when (result) {
            is AgentLocalNodeUndoIdempotencyResult.Applied ->
                undoSuccess(request, result.outcome)
            AgentLocalNodeUndoIdempotencyResult.Conflict ->
                error(request, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
            AgentLocalNodeUndoIdempotencyResult.NotVisible ->
                error(request, AgentLocalNodeErrorCode.NOT_VISIBLE)
        }
    }

    private fun visibleEvents(
        request: AgentLocalNodeRequest,
        command: AgentLocalNodeVisibleEventsPayload,
    ): AgentLocalNodeResponse {
        val spaces = command.spaces.toSet()
        val memoryTypes = command.memoryTypes.toSet()
        if (request.control.spaces != spaces || request.control.memoryTypes != memoryTypes) {
            return error(request, AgentLocalNodeErrorCode.SCOPE_MISMATCH)
        }
        if (spaces != setOf(requireNotNull(repositorySpaceId))) {
            return error(request, AgentLocalNodeErrorCode.SPACE_DENIED)
        }
        if (memoryTypes != setOf(MEMORY_TYPE_EVENT)) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val session = requireNotNull(verifiedSession)
        if ("structured" !in session.allowedDataClasses) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val allowedSensitivities = Sensitivity.entries
            .filterTo(mutableSetOf()) { it.wireValue in session.allowedSensitivities }
        if (allowedSensitivities.isEmpty()) {
            return error(request, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        }
        val result = requireNotNull(repository).readAgentVisibleEvents(
            query = command.query.orEmpty(),
            startAt = command.startInstant(),
            endAt = command.endInstant(),
            timeZone = clock.zone,
            allowedSensitivities = allowedSensitivities,
            allowHighRisk = command.allowHighRisk,
            limit = command.limit,
        )
        return visibleSuccess(
            request,
            AgentLocalNodeVisibleEventsResult(
                events = result.events.map { it.toAgentLocalNodeEventView(requireNotNull(repositorySpaceId)) },
                riskFiltered = result.riskFiltered,
            ),
        )
    }

    private fun scopeError(
        control: AgentLocalNodeControl,
        space: String,
        memoryType: String,
    ): AgentLocalNodeErrorCode? {
        if (control.spaces != setOf(space) || control.memoryTypes != setOf(memoryType)) {
            return AgentLocalNodeErrorCode.SCOPE_MISMATCH
        }
        if (space != requireNotNull(repositorySpaceId)) {
            return AgentLocalNodeErrorCode.SPACE_DENIED
        }
        return null
    }

    private fun AgentLocalNodeControl.idempotencyBinding(
        space: String,
        memoryType: String,
    ) = AgentLocalNodeIdempotencyBinding(
        callerId = callerId,
        grantId = grantId,
        purpose = purpose,
        spaceId = space,
        memoryType = memoryType,
        operation = operation,
        slot = idempotencySlot,
    )

    private fun idempotencyResponse(
        request: AgentLocalNodeRequest,
        result: AgentLocalNodeIdempotencyResult,
    ): AgentLocalNodeResponse = when (result) {
        is AgentLocalNodeIdempotencyResult.Applied -> success(request, result.outcome)
        AgentLocalNodeIdempotencyResult.Conflict ->
            error(request, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
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
        val result = when (request.control.operation) {
            OPERATION_CREATE_EVENT -> AgentLocalNodeApplicationCodec.encodeCreateEventResult(
                AgentLocalNodeCreateEventResult(
                    eventId = outcome.eventId,
                    revision = outcome.revision,
                ),
            )
            OPERATION_APPEND_REVISION -> AgentLocalNodeApplicationCodec.encodeAppendRevisionResult(
                AgentLocalNodeAppendRevisionResult(
                    eventRevisionId = requireNotNull(outcome.revisionId),
                    targetEventId = outcome.eventId,
                    revision = outcome.revision,
                ),
            )
            else -> error("unsupported success operation")
        }
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

    private fun undoSuccess(
        request: AgentLocalNodeRequest,
        outcome: AgentCaptureUndoResult,
    ): AgentLocalNodeResponse {
        val result = AgentLocalNodeApplicationCodec.encodeUndoCaptureResult(
            AgentLocalNodeUndoCaptureResult(
                targetEventId = outcome.eventId,
                undoneObjectType = outcome.objectType,
                undoneObjectId = outcome.objectId,
                compensationRevisionId = outcome.compensationRevisionId,
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

    private fun visibleSuccess(
        request: AgentLocalNodeRequest,
        visible: AgentLocalNodeVisibleEventsResult,
    ): AgentLocalNodeResponse {
        val result = AgentLocalNodeApplicationCodec.encodeVisibleEventsResult(visible)
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
        const val OPERATION_APPEND_REVISION = "append_revision"
        const val OPERATION_UNDO_CAPTURE = "undo_capture"
        const val OPERATION_VISIBLE_EVENTS = "visible_events"
        const val MEMORY_TYPE_EVENT = "event"
        const val MEMORY_TYPE_REVISION = "revision"
        val IMPLEMENTED_OPERATIONS = setOf(
            OPERATION_CREATE_EVENT,
            OPERATION_APPEND_REVISION,
            OPERATION_UNDO_CAPTURE,
            OPERATION_VISIBLE_EVENTS,
        )
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
internal data class AgentLocalNodeAppendRevisionPayload(
    @SerialName("event_id") val eventId: String,
    val space: String,
    @SerialName("memory_type") val memoryType: String,
    val content: String,
    @SerialName("evidence_state") val evidenceState: String,
    @SerialName("fact_status") val factStatus: String,
    val now: String,
) {
    init {
        requireIdentifier(eventId, "event_id")
        requireIdentifier(space, "space")
        require(memoryType == "revision") { "unsupported memory_type" }
        require(content.isNotBlank() && content.codePointLength() <= 4_000) { "invalid content" }
        require('\u0000' !in content) { "content contains NUL" }
        require(evidenceState in AgentLocalNodeCreateEventPayload.EVIDENCE_STATES) {
            "invalid evidence_state"
        }
        require(factStatus in AgentLocalNodeCreateEventPayload.FACT_STATUSES) {
            "invalid fact_status"
        }
        parseTimestamp(now)
    }

    fun toFactStatus(): FactStatus? = when (evidenceState to factStatus) {
        "observed" to "confirmed" -> FactStatus.Confirmed
        "user_asserted" to "user_asserted" -> FactStatus.UserAsserted
        "inferred" to "low_confidence_candidate" -> FactStatus.NeedsReview
        else -> null
    }
}

@Serializable
internal data class AgentLocalNodeUndoCapturePayload(
    @SerialName("undo_token") val undoToken: String,
    val space: String,
    @SerialName("memory_type") val memoryType: String,
    val now: String,
) {
    init {
        requireIdentifier(undoToken, "undo_token")
        requireIdentifier(space, "space")
        require(memoryType in setOf("event", "revision")) { "unsupported memory_type" }
        parseTimestamp(now)
    }
}

@Serializable
internal data class AgentLocalNodeVisibleEventsPayload(
    val spaces: List<String>,
    @SerialName("memory_types") val memoryTypes: List<String>,
    val query: String? = null,
    @SerialName("allow_high_risk") val allowHighRisk: Boolean,
    val limit: Int,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
) {
    init {
        require(
            spaces.isNotEmpty() &&
                spaces.size <= 8 &&
                spaces == spaces.distinct().sortedWith(::compareUnicodeCodePoints),
        ) { "spaces must be sorted and unique" }
        spaces.forEach { requireIdentifier(it, "space") }
        require(
            memoryTypes.isNotEmpty() &&
                memoryTypes.size <= 8 &&
                memoryTypes == memoryTypes.distinct().sortedWith(::compareUnicodeCodePoints),
        ) { "memory_types must be sorted and unique" }
        require(memoryTypes.all { it in setOf("event", "revision") }) {
            "unsupported memory_type"
        }
        query?.let {
            require(it.codePointLength() <= 1_000 && '\u0000' !in it) { "invalid query" }
        }
        require(limit in 1..100) { "invalid limit" }
        val start = startAt?.let(::parseTimestamp)
        val end = endAt?.let(::parseTimestamp)
        require(start == null || end == null || !end.isBefore(start)) { "invalid time range" }
    }

    fun startInstant(): Instant? = startAt?.let(::parseTimestamp)?.toInstant()

    fun endInstant(): Instant? = endAt?.let(::parseTimestamp)?.toInstant()
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

@Serializable
internal data class AgentLocalNodeAppendRevisionResult(
    @SerialName("event_revision_id") val eventRevisionId: String,
    @SerialName("object_type") val objectType: String = "revision",
    val revision: Int,
    @SerialName("target_event_id") val targetEventId: String,
) {
    init {
        requireIdentifier(eventRevisionId, "event_revision_id")
        require(objectType == "revision") { "unsupported object_type" }
        require(revision >= 2) { "invalid revision" }
        requireIdentifier(targetEventId, "target_event_id")
    }
}

@Serializable
internal data class AgentLocalNodeUndoCaptureResult(
    val state: String = "undone",
    @SerialName("target_event_id") val targetEventId: String,
    @SerialName("undone_object_type") val undoneObjectType: String,
    @SerialName("undone_object_id") val undoneObjectId: String,
    @SerialName("activity_visible") val activityVisible: Boolean = true,
    @SerialName("compensation_revision_id") val compensationRevisionId: String? = null,
) {
    init {
        require(state == "undone") { "invalid undo state" }
        requireIdentifier(targetEventId, "target_event_id")
        require(undoneObjectType in setOf("event", "revision")) { "invalid undone object type" }
        requireIdentifier(undoneObjectId, "undone_object_id")
        require(activityVisible) { "undo activity must remain visible" }
        if (undoneObjectType == "event") {
            require(undoneObjectId == targetEventId) { "event undo identity is invalid" }
            require(compensationRevisionId == null) { "event undo cannot have compensation revision" }
        } else {
            requireNotNull(compensationRevisionId) { "revision undo requires compensation revision" }
            requireIdentifier(compensationRevisionId, "compensation_revision_id")
        }
    }
}

@Serializable
internal data class AgentLocalNodeEventView(
    @SerialName("event_id") val eventId: String,
    @SerialName("space_id") val spaceId: String,
    @SerialName("memory_type") val memoryType: String = "event",
    val revision: Int,
    @SerialName("event_type") val eventType: String,
    val title: String,
    val description: String,
    @SerialName("fact_status") val factStatus: String,
    @SerialName("evidence_state") val evidenceState: String,
    val sensitivity: String,
    @SerialName("data_class") val dataClass: String = "structured",
    @SerialName("content_truncated") val contentTruncated: Boolean,
) {
    init {
        requireIdentifier(eventId, "event_id")
        requireIdentifier(spaceId, "space_id")
        require(memoryType == "event") { "unsupported memory_type" }
        require(revision >= 1) { "invalid revision" }
        require(eventType in AgentLocalNodeCreateEventPayload.EVENT_TYPES) { "invalid event_type" }
        require(title.isNotBlank() && title.codePointLength() <= MAX_READ_TITLE_CODE_POINTS) {
            "invalid title"
        }
        require(description.codePointLength() <= MAX_READ_DESCRIPTION_CODE_POINTS) {
            "invalid description"
        }
        require('\u0000' !in title && '\u0000' !in description) { "read content contains NUL" }
        require(factStatus.isNotBlank() && factStatus.length <= 64) { "invalid fact_status" }
        require(evidenceState in AgentLocalNodeCreateEventPayload.EVIDENCE_STATES) {
            "invalid evidence_state"
        }
        require(sensitivity in AgentLocalNodeCreateEventPayload.SENSITIVITIES) {
            "invalid sensitivity"
        }
        require(dataClass == "structured") { "unsupported data_class" }
    }
}

@Serializable
internal data class AgentLocalNodeVisibleEventsResult(
    val events: List<AgentLocalNodeEventView>,
    @SerialName("risk_filtered") val riskFiltered: Boolean,
) {
    init {
        require(events.size <= 100) { "too many visible events" }
        require(events.map(AgentLocalNodeEventView::eventId).distinct().size == events.size) {
            "duplicate visible event"
        }
    }
}

private sealed interface AgentLocalNodeDecodedCommand {
    data class Create(val command: AgentLocalNodeCreateEventPayload) : AgentLocalNodeDecodedCommand

    data class Append(val command: AgentLocalNodeAppendRevisionPayload) : AgentLocalNodeDecodedCommand

    data class Undo(val command: AgentLocalNodeUndoCapturePayload) : AgentLocalNodeDecodedCommand

    data class Visible(val command: AgentLocalNodeVisibleEventsPayload) : AgentLocalNodeDecodedCommand
}

private class AgentLocalNodeTargetNotVisibleException : IllegalStateException()

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

    fun encodeAppendRevision(payload: AgentLocalNodeAppendRevisionPayload): ByteArray =
        canonicalBytes(json.encodeToJsonElement(payload)).also {
            if (it.size > MAX_CANONICAL_PAYLOAD_BYTES) {
                it.fill(0)
                throw AgentLocalNodePayloadTooLargeException()
            }
        }

    fun decodeAppendRevision(bytes: ByteArray): AgentLocalNodeAppendRevisionPayload {
        if (bytes.size > MAX_CANONICAL_PAYLOAD_BYTES) throw AgentLocalNodePayloadTooLargeException()
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "payload must be an object" }
        val payload = json.decodeFromJsonElement<AgentLocalNodeAppendRevisionPayload>(element)
        val canonical = encodeAppendRevision(payload)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "payload is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return payload
    }

    fun encodeUndoCapture(payload: AgentLocalNodeUndoCapturePayload): ByteArray =
        canonicalBytes(json.encodeToJsonElement(payload)).also {
            if (it.size > MAX_CANONICAL_PAYLOAD_BYTES) {
                it.fill(0)
                throw AgentLocalNodePayloadTooLargeException()
            }
        }

    fun decodeUndoCapture(bytes: ByteArray): AgentLocalNodeUndoCapturePayload {
        if (bytes.size > MAX_CANONICAL_PAYLOAD_BYTES) throw AgentLocalNodePayloadTooLargeException()
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "payload must be an object" }
        val payload = json.decodeFromJsonElement<AgentLocalNodeUndoCapturePayload>(element)
        val canonical = encodeUndoCapture(payload)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "payload is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return payload
    }

    fun encodeVisibleEvents(payload: AgentLocalNodeVisibleEventsPayload): ByteArray =
        canonicalBytes(json.encodeToJsonElement(payload)).also {
            if (it.size > MAX_CANONICAL_PAYLOAD_BYTES) {
                it.fill(0)
                throw AgentLocalNodePayloadTooLargeException()
            }
        }

    fun decodeVisibleEvents(bytes: ByteArray): AgentLocalNodeVisibleEventsPayload {
        if (bytes.size > MAX_CANONICAL_PAYLOAD_BYTES) throw AgentLocalNodePayloadTooLargeException()
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "payload must be an object" }
        val payload = json.decodeFromJsonElement<AgentLocalNodeVisibleEventsPayload>(element)
        val canonical = encodeVisibleEvents(payload)
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

    fun payloadDigest(payload: AgentLocalNodeAppendRevisionPayload): String {
        val bytes = encodeAppendRevision(payload)
        return try {
            canonicalDigest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun payloadDigest(payload: AgentLocalNodeUndoCapturePayload): String {
        val bytes = encodeUndoCapture(payload)
        return try {
            canonicalDigest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun payloadDigest(payload: AgentLocalNodeVisibleEventsPayload): String {
        val bytes = encodeVisibleEvents(payload)
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

    fun encodeAppendRevisionResult(result: AgentLocalNodeAppendRevisionResult): ByteArray =
        canonicalBytes(json.encodeToJsonElement(result))

    fun encodeUndoCaptureResult(result: AgentLocalNodeUndoCaptureResult): ByteArray =
        canonicalBytes(json.encodeToJsonElement(result))

    fun encodeVisibleEventsResult(result: AgentLocalNodeVisibleEventsResult): ByteArray =
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
        return encodeRequestEnvelope(control, json.encodeToJsonElement(payload))
    }

    fun encodeAppendRevisionRequestEnvelope(
        control: AgentLocalNodeControl,
        payload: AgentLocalNodeAppendRevisionPayload,
    ): ByteArray {
        require(control.operation == MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION)
        require(control.spaces == setOf(payload.space))
        require(control.memoryTypes == setOf(payload.memoryType))
        require(control.payloadDigest == payloadDigest(payload))
        return encodeRequestEnvelope(control, json.encodeToJsonElement(payload))
    }

    fun encodeUndoCaptureRequestEnvelope(
        control: AgentLocalNodeControl,
        payload: AgentLocalNodeUndoCapturePayload,
    ): ByteArray {
        require(control.operation == MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE)
        require(control.spaces == setOf(payload.space))
        require(control.memoryTypes == setOf(payload.memoryType))
        require(control.payloadDigest == payloadDigest(payload))
        return encodeRequestEnvelope(control, json.encodeToJsonElement(payload))
    }

    fun encodeVisibleEventsRequestEnvelope(
        control: AgentLocalNodeControl,
        payload: AgentLocalNodeVisibleEventsPayload,
    ): ByteArray {
        require(control.operation == MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_VISIBLE_EVENTS)
        require(control.spaces == payload.spaces.toSet())
        require(control.memoryTypes == payload.memoryTypes.toSet())
        require(control.payloadDigest == payloadDigest(payload))
        return encodeRequestEnvelope(control, json.encodeToJsonElement(payload))
    }

    private fun encodeRequestEnvelope(
        control: AgentLocalNodeControl,
        payload: JsonElement,
    ): ByteArray = canonicalBytes(
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
            put("payload", payload)
        },
    )

    fun decodeRequestEnvelope(bytes: ByteArray): AgentLocalNodeRequest {
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
        val operation = controlElement.string("operation")
        val payloadElement = root["payload"] as? JsonObject
            ?: throw IllegalArgumentException("payload must be an object")
        val payloadBytes = when (operation) {
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT -> {
                val payload = json.decodeFromJsonElement<AgentLocalNodeCreateEventPayload>(payloadElement)
                require(spaces == listOf(payload.space) && memoryTypes == listOf(payload.memoryType))
                encodeCreateEvent(payload)
            }
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION -> {
                val payload = json.decodeFromJsonElement<AgentLocalNodeAppendRevisionPayload>(payloadElement)
                require(spaces == listOf(payload.space) && memoryTypes == listOf(payload.memoryType))
                encodeAppendRevision(payload)
            }
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE -> {
                val payload = json.decodeFromJsonElement<AgentLocalNodeUndoCapturePayload>(payloadElement)
                require(spaces == listOf(payload.space) && memoryTypes == listOf(payload.memoryType))
                encodeUndoCapture(payload)
            }
            MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_VISIBLE_EVENTS -> {
                val payload = json.decodeFromJsonElement<AgentLocalNodeVisibleEventsPayload>(payloadElement)
                require(spaces == payload.spaces && memoryTypes == payload.memoryTypes)
                encodeVisibleEvents(payload)
            }
            else -> throw IllegalArgumentException("operation is unsupported")
        }
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
                    operation = operation,
                    idempotencySlot = controlElement.string("idempotency_slot"),
                    payloadDigest = controlElement.string("payload_digest"),
                ),
                payloadBytes,
            )
        } finally {
            payloadBytes.fill(0)
        }
    }

    fun decodeCreateEventRequestEnvelope(bytes: ByteArray): AgentLocalNodeRequest {
        val request = decodeRequestEnvelope(bytes)
        if (request.control.operation != MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT) {
            request.close()
            throw IllegalArgumentException("operation is not create_event")
        }
        return request
    }

    /** Canonical six-field response envelope encoder for shared Kotlin conformance tests. */
    fun encodeResponseEnvelope(response: AgentLocalNodeResponse): ByteArray {
        val result = if (response.status == AgentLocalNodeStatus.Ok) {
            val resultBytes = response.payloadCopy()
            try {
                decodeResultElement(resultBytes)
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

    fun decodeAppendRevisionResult(bytes: ByteArray): AgentLocalNodeAppendRevisionResult {
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "result must be an object" }
        val result = json.decodeFromJsonElement<AgentLocalNodeAppendRevisionResult>(element)
        val canonical = encodeAppendRevisionResult(result)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "result is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return result
    }

    fun decodeUndoCaptureResult(bytes: ByteArray): AgentLocalNodeUndoCaptureResult {
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "result must be an object" }
        val result = json.decodeFromJsonElement<AgentLocalNodeUndoCaptureResult>(element)
        val canonical = encodeUndoCaptureResult(result)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "result is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return result
    }

    fun decodeVisibleEventsResult(bytes: ByteArray): AgentLocalNodeVisibleEventsResult {
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text)
        require(element is JsonObject) { "result must be an object" }
        val result = json.decodeFromJsonElement<AgentLocalNodeVisibleEventsResult>(element)
        val canonical = encodeVisibleEventsResult(result)
        try {
            require(MessageDigest.isEqual(bytes, canonical)) { "result is not canonical JSON" }
        } finally {
            canonical.fill(0)
        }
        return result
    }

    private fun decodeResultElement(bytes: ByteArray): JsonElement {
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text).validate()
        val element = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("result must be an object")
        return when {
            element["state"]?.let { it is JsonPrimitive && it.isString && it.content == "undone" } == true ->
                json.encodeToJsonElement(decodeUndoCaptureResult(bytes))
            element["object_type"]?.let { it is JsonPrimitive && it.isString && it.content == "event" } == true ->
                json.encodeToJsonElement(decodeCreateEventResult(bytes))
            element["object_type"]?.let { it is JsonPrimitive && it.isString && it.content == "revision" } == true ->
                json.encodeToJsonElement(decodeAppendRevisionResult(bytes))
            element.keys == setOf("events", "risk_filtered") ->
                json.encodeToJsonElement(decodeVisibleEventsResult(bytes))
            else -> throw IllegalArgumentException("result object type is unsupported")
        }
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

private fun String.takeCodePoints(maximum: Int): String =
    if (codePointLength() <= maximum) this else substring(0, offsetByCodePoints(0, maximum))

private fun MemoryEvent.toAgentLocalNodeEventView(spaceId: String): AgentLocalNodeEventView {
    val safeTitle = title.takeCodePoints(MAX_READ_TITLE_CODE_POINTS)
    val safeDescription = detail.takeCodePoints(MAX_READ_DESCRIPTION_CODE_POINTS)
    return AgentLocalNodeEventView(
        eventId = id,
        spaceId = spaceId,
        revision = revision,
        eventType = eventType.wireValue,
        title = safeTitle,
        description = safeDescription,
        factStatus = factStatus.wireValue,
        evidenceState = evidenceState.wireValue,
        sensitivity = sensitivity.wireValue,
        contentTruncated = safeTitle != title || safeDescription != detail,
    )
}

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
private const val MAX_READ_TITLE_CODE_POINTS = 240
private const val MAX_READ_DESCRIPTION_CODE_POINTS = 1_000
private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
private val MIN_SAFE_INTEGER = BigInteger("-9007199254740991")
private val MAX_SAFE_INTEGER = BigInteger("9007199254740991")
