package com.ameme.android.coverage

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CoverageCompilerTest {
    private val now = Instant.parse("2026-07-26T04:00:00Z")
    private val compiler = CoverageCompiler(SourceCapabilityRegistry.fromList(listOf(capability())))

    @Test fun calendarPlanStaysCandidateAndCreatesActualityPrompt() {
        val result = compiler.compileDay(request(signal(gapHints = listOf(GapHint(ContextType.TimeSchedule, GapReason.ActualityUnconfirmed, ValueLevel.High)), factStatus = CoverageFactStatus.Planned)))
        assertEquals(CoverageState.EvidenceAvailable, result.coverageState)
        assertEquals(CoverageFactStatus.Planned, result.candidateEvents.single().factStatus)
        assertEquals(PromptPolicy.PromptOnce, result.contextGaps.single().promptPolicy)
    }

    @Test fun unavailableSourceCannotClaimEvidenceAndStatusGapIsNotPrompt() {
        assertThrows(IllegalArgumentException::class.java) { compiler.compileDay(request(signal(sourceState = SourceState.Failed, observedFields = setOf(EvidenceField.Action), sourceObjectIds = emptySet()))) }
        val result = compiler.compileDay(request(signal(sourceState = SourceState.NotAuthorized, observedFields = emptySet(), sourceObjectIds = emptySet(), gapHints = listOf(GapHint(ContextType.TimeSchedule, GapReason.SourceNotAuthorized, ValueLevel.High)))))
        assertEquals(CoverageState.Partial, result.coverageState)
        assertEquals(PromptPolicy.SourceStatusOnly, result.contextGaps.single().promptPolicy)
        assertFalse(result.candidateEvents.isNotEmpty())
    }

    @Test fun candidateRequiresTimeAndEvidenceAndInferenceThreshold() {
        assertFalse(compiler.compileDay(request(signal(observedFields = setOf(EvidenceField.Action)))).candidateEvents.isNotEmpty())
        assertFalse(compiler.compileDay(request(signal(factStatus = CoverageFactStatus.Inferred, confidence = 0.69))).candidateEvents.isNotEmpty())
        assertEquals(1, compiler.compileDay(request(signal(factStatus = CoverageFactStatus.Inferred, confidence = 0.7))).candidateEvents.size)
    }

    @Test fun registryProtectsLifecycleAndBehavioralAuthorityRules() {
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityRegistry.fromList(listOf(capability(acquisitionMode = AcquisitionMode.ContinuousOptIn, priority = CapabilityPriority.P0))) }
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityRegistry.fromList(listOf(capability(hardGates = setOf(HardGate.Lineage)))) }
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityRegistry.fromList(listOf(capability(sourceType = SourceType.Health, fieldClaims = listOf(FieldClaim(EvidenceField.Emotion, FieldAuthority.Strong, "behavior is not emotion"))))) }
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityRegistry.fromList(listOf(capability(bystanderRisk = BystanderRisk.High, hardGates = setOf(HardGate.Lineage, HardGate.CascadeDelete, HardGate.Recovery)))) }
    }

    @Test fun gapsDeduplicateAndOutputOnlyExplicitStates() {
        val duplicated = GapHint(ContextType.TimeSchedule, GapReason.ResultMissing, ValueLevel.Medium)
        val result = compiler.compileDay(request(signal(gapHints = listOf(duplicated, duplicated))))
        assertEquals(1, result.contextGaps.size)
        assertEquals(PromptPolicy.Optional, result.contextGaps.single().promptPolicy)
        assertEquals(setOf(ContextType.TimeSchedule), result.coveredContextTypes)
        assertEquals(ContextType.entries.toSet() - setOf(ContextType.TimeSchedule), result.unknownContextTypes)
    }

    @Test fun allowsOrdinaryOutOfScopeGapButRejectsStatusGapOutsideCapability() {
        val ordinary = compiler.compileDay(request(signal(gapHints = listOf(GapHint(ContextType.ActivityResult, GapReason.ResultMissing, ValueLevel.Low)))))
        assertEquals(ContextType.ActivityResult, ordinary.contextGaps.single().contextType)
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compileDay(request(signal(sourceState = SourceState.NotAuthorized, observedFields = emptySet(), sourceObjectIds = emptySet(), gapHints = listOf(GapHint(ContextType.ActivityResult, GapReason.SourceNotAuthorized, ValueLevel.Low)))))
        }
    }

    @Test fun rejectsBlankIdsAndInvalidEventHintTitle() {
        assertThrows(IllegalArgumentException::class.java) { compiler.compileDay(request(signal(sourceObjectIds = setOf(" ")))) }
        assertThrows(IllegalArgumentException::class.java) { EventHint(CandidateEventType.Activity, " ") }
        assertThrows(IllegalArgumentException::class.java) { EventHint(CandidateEventType.Activity, "x".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) {
            SourceCapabilityRegistry.fromList(listOf(capability().copy(eligibleSegmentIds = setOf(" "))))
        }
    }

    private fun request(signal: CoverageSignal) = CoverageCompileRequest("day_001", "owner_001", "space_001", LocalDate.parse("2026-07-26"), "Asia/Shanghai", listOf(signal))
    private fun signal(sourceState: SourceState = SourceState.Available, observedFields: Set<EvidenceField> = setOf(EvidenceField.Time, EvidenceField.Action), sourceObjectIds: Set<String> = setOf("source_001"), factStatus: CoverageFactStatus = CoverageFactStatus.Observed, confidence: Double = 0.8, gapHints: List<GapHint> = emptyList()) = CoverageSignal("signal_001", "cap_calendar", sourceState, setOf(ContextType.TimeSchedule), observedFields, factStatus, confidence, ValueLevel.Medium, sourceObjectIds, now, eventHint = EventHint(CandidateEventType.Activity, "Synthetic meeting"), gapHints = gapHints)
    private fun capability(sourceType: SourceType = SourceType.Calendar, acquisitionMode: AcquisitionMode = AcquisitionMode.OneAction, priority: CapabilityPriority = CapabilityPriority.P0, hardGates: Set<HardGate> = setOf(HardGate.Lineage, HardGate.CascadeDelete, HardGate.Recovery), bystanderRisk: BystanderRisk = BystanderRisk.Low, fieldClaims: List<FieldClaim> = listOf(FieldClaim(EvidenceField.Time, FieldAuthority.Strong, "scheduled time only"))) = SourceCapability(capabilityId = "cap_calendar", sourceType = sourceType, acquisitionMode = acquisitionMode, contextTypes = setOf(ContextType.TimeSchedule), fieldClaims = fieldClaims, eligibleSegmentIds = setOf("t0"), priority = priority, permissionTier = PermissionTier.Low, bystanderRisk = bystanderRisk, validationStatus = ValidationStatus.ImplementedPartial, hardGates = hardGates)
}
