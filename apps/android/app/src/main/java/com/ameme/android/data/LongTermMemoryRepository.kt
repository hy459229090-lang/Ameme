package com.ameme.android.data

import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.Sensitivity
import java.time.Instant

enum class LongTermMemoryType(
    val wireValue: String,
    val requiresUserConfirmation: Boolean = false,
) {
    Fact("fact"),
    Decision("decision"),
    Commitment("commitment"),
    Insight("insight"),
    Preference("preference", requiresUserConfirmation = true),
    Relationship("relationship", requiresUserConfirmation = true),
    Health("health", requiresUserConfirmation = true),
    Financial("financial", requiresUserConfirmation = true),
    MajorDecision("major_decision", requiresUserConfirmation = true),
}

/**
 * Both compiler outcomes are candidates. Neither is a confirmed long-term Memory until an
 * explicit user confirmation is persisted.
 */
enum class LongTermMemoryState(val wireValue: String) {
    EligibleForMemoryCompiler("eligible_for_memory_compiler"),
    CandidateUserConfirmationRequired("candidate_user_confirmation_required"),
    Active("active"),
    Invalidated("invalidated"),
    Superseded("superseded"),
}

enum class LongTermMemoryInvalidationReason(val wireValue: String) {
    EventRevisionChanged("event_revision_changed"),
    EventDeleted("event_deleted"),
}

data class LongTermMemoryRecord(
    val memoryId: String,
    val spaceId: String,
    val type: LongTermMemoryType,
    val valueSummary: String,
    val sourceEventId: String,
    val sourceEventRevision: Int,
    val evidenceState: EvidenceState,
    val sensitivity: Sensitivity,
    val state: LongTermMemoryState,
    val validFrom: Instant,
    val validUntil: Instant?,
    val confirmedAt: Instant?,
    val supersedesMemoryId: String?,
    val supersededByMemoryId: String?,
    val invalidatedAt: Instant?,
    val invalidationReason: LongTermMemoryInvalidationReason?,
    val revision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(memoryId.isNotBlank() && spaceId.isNotBlank() && sourceEventId.isNotBlank())
        require(valueSummary.isNotBlank() && valueSummary.length <= 4_096)
        require(sourceEventRevision >= 1 && revision >= 1)
        require(validUntil == null || validUntil.isAfter(validFrom))
        when (state) {
            LongTermMemoryState.Active -> {
                require(confirmedAt != null)
                require(invalidatedAt == null && invalidationReason == null && supersededByMemoryId == null)
            }
            LongTermMemoryState.Invalidated -> {
                require(invalidatedAt != null && invalidationReason != null)
                require(supersededByMemoryId == null)
            }
            LongTermMemoryState.Superseded -> {
                require(supersededByMemoryId != null)
                require(invalidatedAt == null && invalidationReason == null)
            }
            LongTermMemoryState.EligibleForMemoryCompiler,
            LongTermMemoryState.CandidateUserConfirmationRequired,
            -> {
                require(confirmedAt == null)
                require(invalidatedAt == null && invalidationReason == null && supersededByMemoryId == null)
            }
        }
    }

    fun isVisible(at: Instant): Boolean =
        state == LongTermMemoryState.Active &&
            !validFrom.isAfter(at) &&
            (validUntil == null || validUntil.isAfter(at))
}

/**
 * The repository derives evidence, sensitivity, and current Event state from the encrypted store.
 * A caller cannot use this request to manufacture evidence or confirm a Memory.
 */
data class LongTermMemoryProposal(
    val sourceEventId: String,
    val expectedEventRevision: Int,
    val type: LongTermMemoryType,
    val valueSummary: String,
    val validFrom: Instant,
    val validUntil: Instant? = null,
    val proposedAt: Instant,
) {
    init {
        require(sourceEventId.isNotBlank())
        require(expectedEventRevision >= 1)
        require(valueSummary.isNotBlank() && valueSummary.length <= 4_096)
        require(validUntil == null || validUntil.isAfter(validFrom))
    }
}

data class LongTermMemoryConfirmation(
    val memoryId: String,
    val confirmedAt: Instant,
    val supersedesMemoryId: String? = null,
) {
    init {
        require(memoryId.isNotBlank())
        require(supersedesMemoryId == null || supersedesMemoryId.isNotBlank())
        require(supersedesMemoryId != memoryId)
    }
}

/**
 * Long-term Memory remains a separate local surface. The Agent Local Node is Event-only and does
 * not receive this repository until its frozen protocol and exact Grant are intentionally revised.
 */
interface LongTermMemoryRepository {
    fun proposeLongTermMemory(proposal: LongTermMemoryProposal): LongTermMemoryRecord

    fun confirmLongTermMemory(confirmation: LongTermMemoryConfirmation): LongTermMemoryRecord

    fun longTermMemory(memoryId: String): LongTermMemoryRecord?

    fun visibleLongTermMemories(at: Instant): List<LongTermMemoryRecord>
}
