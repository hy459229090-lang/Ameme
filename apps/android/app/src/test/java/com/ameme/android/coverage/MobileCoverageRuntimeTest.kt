package com.ameme.android.coverage

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileCoverageRuntimeTest {
    private val registry = MobileSourceCapabilities.createRegistry()

    @Test fun registryDescribesOnlyCurrentMobileCapabilities() {
        assertEquals(setOf("cap_explicit_capture", "cap_share", "cap_agent", "cap_calendar", "cap_photo", "cap_explicit_voice"), registry.ids().toSet())
        val expectedContexts = mapOf(
            "cap_explicit_capture" to ContextType.entries.toSet(),
            "cap_share" to setOf(ContextType.ContentConsumption, ContextType.ContentCreation, ContextType.ActivityResult),
            "cap_agent" to setOf(ContextType.ActivityResult, ContextType.ContentCreation, ContextType.DecisionCommitment),
            "cap_calendar" to setOf(ContextType.TimeSchedule, ContextType.CommunicationRelationship),
            "cap_photo" to setOf(ContextType.ActivityResult, ContextType.CommunicationRelationship, ContextType.PlaceMobility, ContextType.ConsumptionEntertainment),
            "cap_explicit_voice" to setOf(ContextType.ActivityResult, ContextType.CommunicationRelationship, ContextType.DecisionCommitment, ContextType.ContentCreation, ContextType.IntentFeeling),
        )
        expectedContexts.forEach { (capabilityId, contexts) ->
            assertEquals(contexts, registry.get(capabilityId).contextTypes)
            assertEquals(true, HardGate.RealDevice in registry.get(capabilityId).hardGates)
            assertEquals(true, HardGate.RealUser in registry.get(capabilityId).hardGates)
        }
        val capture = registry.get("cap_explicit_capture")
        assertEquals(5, capture.eligibleSegmentIds.size)
        val calendar = registry.get("cap_calendar")
        assertEquals(AcquisitionMode.ImportOrForward, calendar.acquisitionMode)
        assertEquals(ValidationStatus.ImplementedPartial, calendar.validationStatus)
        val voice = registry.get("cap_explicit_voice")
        assertEquals(BystanderRisk.High, voice.bystanderRisk)
        assertEquals(true, HardGate.BystanderNotice in voice.hardGates)
    }

    @Test fun statusSignalsCompileAsPartialStatusOnlyWithoutCandidates() {
        val factory = MobileSourceStatusSignalFactory(registry)
        val expected = mapOf(
            SourceState.NotAuthorized to GapReason.SourceNotAuthorized,
            SourceState.Failed to GapReason.SourceFailed,
            SourceState.Unavailable to GapReason.CapabilityUnavailable,
        )
        expected.forEach { (state, reason) ->
            val signal = factory.create("signal_${state.wireValue}", "cap_calendar", state, setOf(ContextType.TimeSchedule), Instant.parse("2026-07-26T04:00:00Z"))
            assertEquals(emptySet<Any>(), signal.sourceObjectIds)
            assertEquals(emptySet<Any>(), signal.observedFields)
            assertNull(signal.eventHint)
            val result = CoverageCompiler(registry).compileDay(CoverageCompileRequest("day_001", "owner_001", "space_001", LocalDate.parse("2026-07-26"), "Asia/Shanghai", listOf(signal)))
            assertEquals(CoverageState.Partial, result.coverageState)
            assertEquals(0, result.candidateEvents.size)
            assertEquals(reason, result.contextGaps.single().reason)
            assertEquals(PromptPolicy.SourceStatusOnly, result.contextGaps.single().promptPolicy)
        }
    }
}
