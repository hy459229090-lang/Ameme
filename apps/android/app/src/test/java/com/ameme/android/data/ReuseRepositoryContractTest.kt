package com.ameme.android.data

import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReuseRepositoryContractTest {
    private val now = Instant.parse("2026-07-26T08:00:00Z")

    @Test fun fourIntentsHaveStableContentFreeWireNames() {
        assertEquals(
            setOf(
                "historical_search",
                "project_resume",
                "pre_meeting_context",
                "decision_commitment_recall",
            ),
            ReuseIntent.entries.map(ReuseIntent::wireValue).toSet(),
        )
        assertEquals("wrong_memory", ReuseOutcome.WrongMemory.wireValue)
        assertEquals("important_miss", ReuseOutcome.ImportantMiss.wireValue)
        assertEquals("restricted_egress_blocked", ReuseOutcome.RestrictedEgressBlocked.wireValue)
    }

    @Test fun transitionalProjectAndMeetingReuseRequireExplicitUserScope() {
        assertThrows(IllegalArgumentException::class.java) {
            request(ReuseIntent.ProjectResume)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(ReuseIntent.PreMeetingContext)
        }
        request(ReuseIntent.ProjectResume, query = "ameme beta")
        request(
            ReuseIntent.PreMeetingContext,
            meetingAnchorDate = LocalDate.parse("2026-07-26"),
        )
    }

    @Test fun ephemeralReferencesRejectRestrictedContentAndExpireDeterministically() {
        assertThrows(IllegalArgumentException::class.java) {
            ReuseReference(
                objectType = ReuseObjectType.Event,
                objectId = "evt_restricted",
                revision = 1,
                localDate = LocalDate.parse("2026-07-26"),
                sensitivity = Sensitivity.Restricted,
                selectionReason = ReuseSelectionReason.KeywordMatch,
            )
        }
        val reference = ReuseReference(
            objectType = ReuseObjectType.LongTermMemory,
            objectId = "mem_001",
            revision = 2,
            localDate = LocalDate.parse("2026-07-26"),
            sensitivity = Sensitivity.Confidential,
            selectionReason = ReuseSelectionReason.ActiveDecision,
            sourceEventId = "evt_001",
            sourceEventRevision = 1,
        )
        val context = ReuseContext(
            attemptId = "reuse_00000000-0000-4000-8000-000000000001",
            intent = ReuseIntent.DecisionCommitmentRecall,
            rangeState = ReuseRangeState.CompleteForLocalScope,
            references = listOf(reference),
            exclusions = emptySet(),
            createdAt = now,
            expiresAt = now.plusSeconds(900),
        )
        assertEquals(900, context.expiresAt.epochSecond - context.createdAt.epochSecond)
    }

    private fun request(
        intent: ReuseIntent,
        query: String = "",
        meetingAnchorDate: LocalDate? = null,
    ) = ReuseRequest(
        spaceId = "space_personal",
        intent = intent,
        query = query,
        meetingAnchorDate = meetingAnchorDate,
        requestedAt = now,
    )
}
