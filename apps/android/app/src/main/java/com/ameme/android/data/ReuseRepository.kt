package com.ameme.android.data

import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate

enum class ReuseIntent(val wireValue: String) {
    HistoricalSearch("historical_search"),
    ProjectResume("project_resume"),
    PreMeetingContext("pre_meeting_context"),
    DecisionCommitmentRecall("decision_commitment_recall"),
}

enum class ReuseRangeState(val wireValue: String) {
    CompleteForLocalScope("complete_for_local_scope"),
    PartialForLocalScope("partial_for_local_scope"),
    Empty("empty"),
}

enum class ReuseObjectType(val wireValue: String) {
    Event("event"),
    LongTermMemory("long_term_memory"),
}

enum class ReuseSelectionReason(val wireValue: String) {
    KeywordMatch("keyword_match"),
    DateMatch("date_match"),
    MeetingAnchor("meeting_anchor"),
    ActiveDecision("active_decision"),
    ActiveCommitment("active_commitment"),
}

enum class ReuseExclusion(val wireValue: String) {
    Restricted("restricted"),
    Invalidated("invalidated"),
    Deleted("deleted"),
    Expired("expired"),
    InsufficientQuery("insufficient_query"),
    PolicyFiltered("policy_filtered"),
}

enum class ReuseOutcome(val wireValue: String) {
    Useful("useful"),
    NotUseful("not_useful"),
    WrongMemory("wrong_memory"),
    ImportantMiss("important_miss"),
    Outdated("outdated"),
    PermissionDenied("permission_denied"),
    DeletionFailure("deletion_failure"),
    RecoveryFailure("recovery_failure"),
    RestrictedEgressBlocked("restricted_egress_blocked"),
}

enum class ReuseUserAction(val wireValue: String) {
    None("none"),
    Revised("revised"),
    Hidden("hidden"),
    Deleted("deleted"),
}

enum class ReuseResultCountBucket(val wireValue: String) {
    Zero("0"),
    One("1"),
    TwoToFive("2_5"),
    SixToTen("6_10"),
    ElevenToFifty("11_50");

    companion object {
        fun from(count: Int): ReuseResultCountBucket = when (count) {
            0 -> Zero
            1 -> One
            in 2..5 -> TwoToFive
            in 6..10 -> SixToTen
            else -> ElevenToFifty
        }
    }
}

/**
 * A request is local-only and transient. Query text is used for deterministic selection but is
 * never copied into the persistent reuse-attempt record.
 *
 * Ameme does not yet have a Project entity. Project resume therefore requires explicit user
 * keywords and must not be presented as inferred project membership.
 */
data class ReuseRequest(
    val spaceId: String,
    val intent: ReuseIntent,
    val query: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val meetingAnchorDate: LocalDate? = null,
    val limit: Int = 20,
    val requestedAt: Instant,
) {
    init {
        require(spaceId.isNotBlank())
        require(query.length <= 1_024)
        require(limit in 1..50)
        require(startDate == null || endDate == null || !endDate.isBefore(startDate))
        val terms = query.split(Regex("\\s+")).filter(String::isNotBlank)
        require(terms.size <= 16)
        when (intent) {
            ReuseIntent.HistoricalSearch ->
                require(terms.isNotEmpty() || startDate != null || endDate != null)
            ReuseIntent.ProjectResume ->
                require(terms.isNotEmpty()) { "project resume requires explicit user keywords" }
            ReuseIntent.PreMeetingContext ->
                require(terms.isNotEmpty() || meetingAnchorDate != null) {
                    "pre-meeting context requires explicit keywords or a user-selected date"
                }
            ReuseIntent.DecisionCommitmentRecall -> Unit
        }
    }
}

/**
 * Exact references are intentionally ephemeral. Consumers must call [revalidateReuseContext]
 * immediately before resolving content; these values are never persisted in telemetry.
 */
data class ReuseReference(
    val objectType: ReuseObjectType,
    val objectId: String,
    val revision: Int,
    val localDate: LocalDate,
    val sensitivity: Sensitivity,
    val selectionReason: ReuseSelectionReason,
    val sourceEventId: String? = null,
    val sourceEventRevision: Int? = null,
) {
    init {
        require(objectId.isNotBlank() && revision >= 1)
        require(sensitivity != Sensitivity.Restricted)
        require(
            objectType == ReuseObjectType.Event ||
                (!sourceEventId.isNullOrBlank() && (sourceEventRevision ?: 0) >= 1),
        )
    }
}

data class ReuseContext(
    val attemptId: String,
    val intent: ReuseIntent,
    val rangeState: ReuseRangeState,
    val references: List<ReuseReference>,
    val exclusions: Set<ReuseExclusion>,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(attemptId.isNotBlank())
        require(expiresAt.isAfter(createdAt))
        require(references.size <= 50)
        require(
            (rangeState == ReuseRangeState.Empty) == references.isEmpty(),
        ) { "reuse range state must match its references" }
    }
}

/**
 * Transient content resolved from a revalidated exact reference. This object is returned only to
 * the local UI and is never written to reuse telemetry.
 */
data class ResolvedReuseItem(
    val reference: ReuseReference,
    val sourceEvent: com.ameme.android.domain.MemoryEvent,
    val memorySummary: String? = null,
    val memoryType: LongTermMemoryType? = null,
) {
    init {
        require(sourceEvent.sensitivity != Sensitivity.Restricted)
        when (reference.objectType) {
            ReuseObjectType.Event -> {
                require(reference.objectId == sourceEvent.id)
                require(reference.revision == sourceEvent.revision)
                require(memorySummary == null && memoryType == null)
            }
            ReuseObjectType.LongTermMemory -> {
                require(reference.sourceEventId == sourceEvent.id)
                require(reference.sourceEventRevision == sourceEvent.revision)
                require(!memorySummary.isNullOrBlank() && memoryType != null)
            }
        }
    }
}

data class ResolvedReuseContext(
    val context: ReuseContext,
    val items: List<ResolvedReuseItem>,
) {
    init {
        require(items.map(ResolvedReuseItem::reference) == context.references)
    }
}

data class ReuseOutcomeSubmission(
    val attemptId: String,
    val outcome: ReuseOutcome,
    val userAction: ReuseUserAction = ReuseUserAction.None,
    val submittedAt: Instant,
) {
    init {
        require(attemptId.isNotBlank())
    }
}

data class ReuseTelemetryAggregate(
    val intent: ReuseIntent,
    val outcome: ReuseOutcome?,
    val userAction: ReuseUserAction?,
    val resultCountBucket: ReuseResultCountBucket,
    val attemptCount: Int,
) {
    init {
        require(attemptCount >= 1)
    }
}

interface ReuseRepository {
    fun buildReuseContext(request: ReuseRequest): ReuseContext

    fun revalidateReuseContext(context: ReuseContext, at: Instant): ReuseContext

    /**
     * Revalidates and resolves content inside one local-store read transaction so a caller cannot
     * display content from a stale exact revision between separate reads.
     */
    fun resolveReuseContext(context: ReuseContext, at: Instant): ResolvedReuseContext

    fun recordReuseOutcome(submission: ReuseOutcomeSubmission): Boolean

    fun reuseTelemetryAggregates(since: Instant): List<ReuseTelemetryAggregate>

    fun helpfulReuseCount(since: Instant): Int
}
