package com.ameme.android.coverage

import com.ameme.android.data.CoverageAcceptanceMode
import com.ameme.android.data.CoverageCandidateAcceptance
import com.ameme.android.data.CoverageCandidateLifecycle
import com.ameme.android.data.CoverageEventLink
import com.ameme.android.data.CoverageEventLinkLifecycle
import com.ameme.android.data.LocalEventUserConfirmation
import com.ameme.android.data.PersistedCoverageDay
import com.ameme.android.data.UserConfirmationKind
import com.ameme.android.data.UserConfirmationState
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoverageRepositoryContractTest {
    @Test fun compiledFactoryCreatesOnlyOpenCandidateStates() {
        val compilation = CoverageCompiler(MobileSourceCapabilities.createRegistry()).compileDay(
            CoverageCompileRequest(
                dayId = "day_repository_001",
                ownerId = "owner_001",
                spaceId = "space_personal",
                localDate = LocalDate.parse("2026-07-26"),
                timezone = "Asia/Shanghai",
                signals = listOf(calendarSignal()),
            ),
        )
        val persisted = PersistedCoverageDay.compiled(
            compilation,
            registryVersion = "mobile-v1",
            compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
        )
        assertEquals(
            mapOf("candidate_signal_repository_001" to CoverageCandidateLifecycle.Open),
            persisted.candidateStates,
        )
        assertThrows(IllegalArgumentException::class.java) {
            persisted.copy(
                candidateStates = persisted.candidateStates +
                    ("removed_candidate" to CoverageCandidateLifecycle.Open),
            )
        }
        persisted.copy(
            candidateStates = persisted.candidateStates +
                ("removed_candidate" to CoverageCandidateLifecycle.Deleted),
        )
    }

    @Test fun acceptanceAndLinkRequireBoundedLineageFields() {
        val acceptance = CoverageCandidateAcceptance(
            dayId = "day_repository_001",
            candidateId = "candidate_signal_repository_001",
            acceptedAt = Instant.parse("2026-07-26T08:01:00Z"),
            localDate = LocalDate.parse("2026-07-26"),
            time = LocalTime.parse("16:00"),
            detail = "用户选择保存该日历计划。",
            sourceLabel = "用户选择的日历",
            captureKind = CaptureKind.Import,
            sensitivity = Sensitivity.Confidential,
            mode = CoverageAcceptanceMode.PreserveEvidence,
        )
        assertEquals(CoverageAcceptanceMode.PreserveEvidence, acceptance.mode)
        val link = CoverageEventLink(
            dayId = acceptance.dayId,
            spaceId = "space_personal",
            candidateId = acceptance.candidateId,
            eventId = "evt_repository_001",
            createdRevision = 1,
            sourceObjectIds = setOf("source_calendar_001"),
            lifecycle = CoverageEventLinkLifecycle.Active,
            acceptedAt = acceptance.acceptedAt,
        )
        assertEquals(setOf("source_calendar_001"), link.sourceObjectIds)
        assertThrows(IllegalArgumentException::class.java) {
            link.copy(sourceObjectIds = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            acceptance.copy(detail = " ")
        }
    }

    @Test fun userConfirmationStoresOnlyExactRevisionFieldNamesAndTerminalState() {
        val confirmation = LocalEventUserConfirmation(
            confirmationId = "confirm_repository_001",
            eventId = "event_repository_001",
            eventRevision = 3,
            kind = UserConfirmationKind.CoverageAcceptance,
            confirmedFields = setOf(EvidenceField.Time, EvidenceField.Action),
            completeFieldSet = true,
            confirmedAt = Instant.parse("2026-07-26T08:01:00Z"),
            state = UserConfirmationState.Active,
        )

        assertEquals(3, confirmation.eventRevision)
        assertEquals(setOf(EvidenceField.Time, EvidenceField.Action), confirmation.confirmedFields)
        assertEquals(true, confirmation.completeFieldSet)
        assertThrows(IllegalArgumentException::class.java) {
            confirmation.copy(confirmedFields = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            confirmation.copy(
                state = UserConfirmationState.Deleted,
                deletedAt = null,
            )
        }
    }

    private fun calendarSignal() = CoverageSignal(
        signalId = "signal_repository_001",
        capabilityId = "cap_calendar",
        sourceState = SourceState.Available,
        contextTypes = setOf(ContextType.TimeSchedule),
        observedFields = setOf(EvidenceField.Time, EvidenceField.Action),
        factStatus = CoverageFactStatus.Planned,
        confidence = 1.0,
        importance = ValueLevel.High,
        sourceObjectIds = setOf("source_calendar_001"),
        observedAt = Instant.parse("2026-07-26T08:00:00Z"),
        timeRange = TimeRange(
            start = Instant.parse("2026-07-26T10:00:00Z"),
            end = Instant.parse("2026-07-26T11:00:00Z"),
            timezone = "Asia/Shanghai",
            precision = TimePrecision.Range,
        ),
        eventHint = EventHint(CandidateEventType.Activity, "用户选择的日历计划"),
        gapHints = listOf(
            GapHint(ContextType.ActivityResult, GapReason.ActualityUnconfirmed, ValueLevel.High),
        ),
    )
}
