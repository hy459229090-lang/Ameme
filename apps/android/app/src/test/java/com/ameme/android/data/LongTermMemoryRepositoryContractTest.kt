package com.ameme.android.data

import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTermMemoryRepositoryContractTest {
    @Test fun wireTypesMatchAgentPolicyAndSensitiveTypesRequireConfirmation() {
        assertEquals(
            setOf(
                "fact",
                "decision",
                "commitment",
                "insight",
                "preference",
                "relationship",
                "health",
                "financial",
                "major_decision",
            ),
            LongTermMemoryType.entries.map(LongTermMemoryType::wireValue).toSet(),
        )
        assertEquals(
            "candidate_user_confirmation_required",
            LongTermMemoryState.CandidateUserConfirmationRequired.wireValue,
        )
        assertEquals(
            "eligible_for_memory_compiler",
            LongTermMemoryState.EligibleForMemoryCompiler.wireValue,
        )
    }

    @Test fun onlyActiveAndUnexpiredRecordsAreVisible() {
        val now = Instant.parse("2026-07-26T08:00:00Z")
        val candidate = record(
            state = LongTermMemoryState.EligibleForMemoryCompiler,
            confirmedAt = null,
        )
        assertFalse(candidate.isVisible(now))
        val active = record(
            state = LongTermMemoryState.Active,
            confirmedAt = now.minusSeconds(1),
        )
        assertTrue(active.isVisible(now))
        assertFalse(active.copy(validUntil = now).isVisible(now))
    }

    @Test fun terminalStatesRequireAuditableReasonOrReplacement() {
        val now = Instant.parse("2026-07-26T08:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            record(
                state = LongTermMemoryState.Invalidated,
                confirmedAt = now.minusSeconds(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(
                state = LongTermMemoryState.Superseded,
                confirmedAt = now.minusSeconds(1),
            )
        }
        val invalidated = record(
            state = LongTermMemoryState.Invalidated,
            confirmedAt = now.minusSeconds(2),
            invalidatedAt = now,
            invalidationReason = LongTermMemoryInvalidationReason.EventRevisionChanged,
        )
        assertFalse(invalidated.isVisible(now))
    }

    private fun record(
        state: LongTermMemoryState,
        confirmedAt: Instant?,
        invalidatedAt: Instant? = null,
        invalidationReason: LongTermMemoryInvalidationReason? = null,
    ) = LongTermMemoryRecord(
        memoryId = "mem_contract_001",
        spaceId = "space_personal",
        type = LongTermMemoryType.Fact,
        valueSummary = "验证长期 Memory 契约",
        sourceEventId = "evt_contract_001",
        sourceEventRevision = 1,
        evidenceState = EvidenceState.Observed,
        sensitivity = Sensitivity.Personal,
        state = state,
        validFrom = Instant.parse("2026-07-26T00:00:00Z"),
        validUntil = Instant.parse("2026-07-27T00:00:00Z"),
        confirmedAt = confirmedAt,
        supersedesMemoryId = null,
        supersededByMemoryId = null,
        invalidatedAt = invalidatedAt,
        invalidationReason = invalidationReason,
        revision = if (state == LongTermMemoryState.Invalidated) 3 else 2,
        createdAt = Instant.parse("2026-07-26T00:00:00Z"),
        updatedAt = invalidatedAt ?: confirmedAt ?: Instant.parse("2026-07-26T00:00:00Z"),
    )
}
