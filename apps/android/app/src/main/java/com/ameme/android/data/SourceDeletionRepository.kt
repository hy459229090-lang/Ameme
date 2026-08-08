package com.ameme.android.data

import com.ameme.android.coverage.EvidenceField
import java.time.Instant

/** Local source lineage only. It is not an account, peer, provider, or distributed deletion proof. */
enum class LocalSourceKind(val wireValue: String) {
    CapturedLocator("captured_locator"),
    CoverageEvidence("coverage_evidence"),
}

enum class LocalRawOwnership(val wireValue: String) {
    ExternalNotOwned("external_not_owned"),
    NoRaw("no_raw"),
}

enum class LocalSourceState(val wireValue: String) {
    Active("active"),
    Deleted("deleted"),
}

enum class EventFieldEvidenceState(val wireValue: String) {
    Active("active"),
    Deleted("deleted"),
}

enum class UserConfirmationKind(val wireValue: String) {
    CoverageAcceptance("coverage_acceptance"),
    FactStatusConfirmation("fact_status_confirmation"),
    UserRevision("user_revision"),
}

enum class UserConfirmationState(val wireValue: String) {
    Active("active"),
    Deleted("deleted"),
}

/**
 * Content-free, exact-revision proof of one explicit user action.
 *
 * [confirmedFields] identifies only field names, never values. [completeFieldSet] is true only
 * when the confirmation surface was bound to the complete accepted Candidate field set. Partial
 * confirmations remain auditable but can never preserve an Event during source cascade.
 */
data class LocalEventUserConfirmation(
    val confirmationId: String,
    val eventId: String,
    val eventRevision: Int,
    val kind: UserConfirmationKind,
    val confirmedFields: Set<EvidenceField>,
    val completeFieldSet: Boolean,
    val confirmedAt: Instant,
    val state: UserConfirmationState,
    val deletedAt: Instant? = null,
) {
    init {
        require(confirmationId.isNotBlank() && eventId.isNotBlank() && eventRevision >= 1)
        require(confirmedFields.isNotEmpty())
        require(
            (state == UserConfirmationState.Active && deletedAt == null) ||
                (state == UserConfirmationState.Deleted && deletedAt != null),
        )
    }
}

/**
 * Content-free exact-revision evidence. It states only that one source supports one accepted
 * Coverage field; it never stores the field value or permits a stale revision to be reused.
 */
data class LocalEventFieldEvidence(
    val eventId: String,
    val eventRevision: Int,
    val field: EvidenceField,
    val sourceObjectId: String,
    val state: EventFieldEvidenceState,
    val createdAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(eventId.isNotBlank() && eventRevision >= 1 && sourceObjectId.isNotBlank())
        require(
            (state == EventFieldEvidenceState.Active && deletedAt == null) ||
                (state == EventFieldEvidenceState.Deleted && deletedAt != null),
        )
    }
}

data class LocalSourceObject(
    val sourceObjectId: String,
    val spaceId: String,
    val kind: LocalSourceKind,
    val rawOwnership: LocalRawOwnership,
    val state: LocalSourceState,
    val createdAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(sourceObjectId.isNotBlank() && spaceId.isNotBlank())
        require(state != LocalSourceState.Deleted || deletedAt != null)
    }
}

enum class SourceDeletionOperation(val wireValue: String) {
    RawOnly("raw_only"),
    SourceCascade("source_cascade"),
}

enum class SourceDeletionStatus(val wireValue: String) {
    Completed("completed"),
    CompletedLocalOnly("completed_local_only"),
    ExternalNotOwned("external_not_owned"),
    NoRaw("no_raw"),
    NotFound("not_found"),
    LineageUnavailable("lineage_unavailable"),
}

data class SourceDeletionResult(
    val jobId: String,
    val sourceObjectId: String,
    val operation: SourceDeletionOperation,
    val status: SourceDeletionStatus,
    val affectedEventCount: Int,
    val recomputedEventCount: Int = 0,
    val deletedEventCount: Int = 0,
    val createdAt: Instant,
    val completedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && sourceObjectId.isNotBlank())
        require(affectedEventCount >= 0)
        require(recomputedEventCount >= 0 && deletedEventCount >= 0)
        require(recomputedEventCount + deletedEventCount <= affectedEventCount)
        require(!completedAt.isBefore(createdAt))
    }
}

interface SourceDeletionRepository {
    fun sourceObjectsForEvent(eventId: String): List<LocalSourceObject>

    fun userConfirmationsForEvent(eventId: String): List<LocalEventUserConfirmation>

    fun deleteRawOnly(sourceObjectId: String, requestedAt: Instant = Instant.now()): SourceDeletionResult

    /**
     * A single-source Event is deleted unless its exact current revision has a complete explicit
     * user confirmation. A multi-source Event can append a conservative recompute revision only
     * when complete exact-revision provenance is present and every accepted field remains supported
     * by either a remaining source or a complete user confirmation. Missing/stale/partial mappings
     * fail closed; a mapped field that loses its final support deletes the Event instead of
     * retaining unsupported content.
     */
    fun deleteSourceCascade(
        sourceObjectId: String,
        requestedAt: Instant = Instant.now(),
    ): SourceDeletionResult
}
