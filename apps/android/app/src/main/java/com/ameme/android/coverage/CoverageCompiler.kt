package com.ameme.android.coverage

/** Deterministic, non-persistent implementation of the v1 coverage contract. */
class SourceCapabilityRegistry private constructor(private val capabilities: Map<String, SourceCapability>) {
    fun get(capabilityId: String): SourceCapability = capabilities[capabilityId]
        ?: throw IllegalArgumentException("unknown capability_id $capabilityId")

    fun ids(): List<String> = capabilities.keys.sorted()

    companion object {
        fun fromList(items: Iterable<SourceCapability>): SourceCapabilityRegistry {
            val registry = linkedMapOf<String, SourceCapability>()
            items.forEach { capability ->
                require(capability.capabilityId.isNotBlank()) { "capability_id must be non-empty" }
                require(capability.schemaVersion == 1) { "${capability.capabilityId} has unsupported schema_version" }
                require(registry.put(capability.capabilityId, capability) == null) { "duplicate capability_id ${capability.capabilityId}" }
                require(capability.contextTypes.isNotEmpty()) { "${capability.capabilityId}.context_types must not be empty" }
                require(capability.eligibleSegmentIds.isNotEmpty() && capability.eligibleSegmentIds.none(String::isBlank)) { "${capability.capabilityId}.eligible_segment_ids must not be empty" }
                require(capability.fieldClaims.isNotEmpty()) { "${capability.capabilityId}.field_claims must not be empty" }
                require(capability.fieldClaims.map(FieldClaim::field).distinct().size == capability.fieldClaims.size) { "${capability.capabilityId}.field_claims has duplicate field" }
                capability.fieldClaims.forEach { claim ->
                    require(claim.limitation.isNotBlank()) { "${capability.capabilityId}.${claim.field}.limitation must be non-empty" }
                    if (claim.field in setOf(EvidenceField.Intent, EvidenceField.Emotion) && claim.authority == FieldAuthority.Strong) {
                        require(capability.sourceType in setOf(SourceType.Text, SourceType.Audio)) { "${capability.capabilityId} cannot strongly infer ${claim.field} from behavior" }
                    }
                }
                require(capability.acquisitionMode != AcquisitionMode.ContinuousOptIn || capability.priority == CapabilityPriority.P3) { "continuous capture cannot be P0/P1/P2" }
                require(setOf(HardGate.Lineage, HardGate.CascadeDelete, HardGate.Recovery).all(capability.hardGates::contains)) { "${capability.capabilityId} misses required lifecycle hard gate" }
                require(capability.bystanderRisk != BystanderRisk.High || HardGate.BystanderNotice in capability.hardGates) { "${capability.capabilityId} high bystander risk requires bystander_notice" }
            }
            require(registry.isNotEmpty()) { "capability registry must not be empty" }
            return SourceCapabilityRegistry(registry)
        }
    }
}

class CoverageCompiler(private val registry: SourceCapabilityRegistry) {
    fun compileDay(request: CoverageCompileRequest): CoverageCompileResult {
        require(request.dayId.isNotBlank() && request.ownerId.isNotBlank() && request.spaceId.isNotBlank() && request.timezone.isNotBlank()) { "day identity and timezone must be non-empty" }
        val observations = mutableListOf<CoverageObservation>()
        val candidates = mutableListOf<CandidateEvent>()
        val gaps = mutableListOf<ContextGap>()
        val signalIds = mutableSetOf<String>()
        val gapKeys = mutableSetOf<Triple<ContextType, GapReason, TimeRange?>>()

        request.signals.forEach { signal ->
            require(signal.signalId.isNotBlank() && signalIds.add(signal.signalId)) { "duplicate or empty signal_id" }
            val capability = registry.get(signal.capabilityId)
            require(signal.contextTypes.isNotEmpty() && signal.contextTypes.all(capability.contextTypes::contains)) { "${signal.signalId} claims context outside ${signal.capabilityId}" }
            require(signal.confidence in 0.0..1.0 && !signal.confidence.isNaN()) { "${signal.signalId}.confidence must be in [0,1]" }
            require(signal.sourceObjectIds.none(String::isBlank)) { "${signal.signalId}.source_object_ids contains blank id" }
            if (signal.sourceState == SourceState.Available) require(signal.sourceObjectIds.isNotEmpty()) { "${signal.signalId} available evidence requires a source object" }
            else require(signal.sourceObjectIds.isEmpty() && signal.observedFields.isEmpty()) { "${signal.signalId} unavailable evidence cannot claim fields or sources" }

            val state = when (signal.sourceState) {
                SourceState.Available -> if (signal.observedFields.isEmpty()) ObservationState.Partial else ObservationState.Observed
                SourceState.NotAuthorized -> ObservationState.NotAuthorized
                SourceState.Failed -> ObservationState.Failed
                SourceState.Unavailable -> ObservationState.Unavailable
            }
            observations += CoverageObservation(
                coverageObservationId = "coverage_${signal.signalId}", ownerId = request.ownerId, spaceId = request.spaceId,
                localDate = request.localDate, capabilityId = signal.capabilityId, sourceObjectIds = signal.sourceObjectIds,
                contextTypes = signal.contextTypes, observedFields = signal.observedFields, factStatus = signal.factStatus,
                state = state, observedAt = signal.observedAt, timeRange = signal.timeRange,
            )
            val hint = signal.eventHint
            if (signal.sourceState == SourceState.Available && hint != null && hint.title.isNotBlank() && EvidenceField.Time in signal.observedFields && signal.observedFields.any { it in setOf(EvidenceField.Action, EvidenceField.Result, EvidenceField.Description) } && (signal.factStatus != CoverageFactStatus.Inferred || signal.confidence >= 0.7)) {
                candidates += CandidateEvent("candidate_${signal.signalId}", signal.sourceObjectIds, signal.capabilityId, hint.eventType, hint.title, signal.timeRange, signal.factStatus, signal.confidence, signal.observedFields)
            }
            signal.gapHints.forEach { hintGap ->
                if (hintGap.reason in setOf(GapReason.SourceNotAuthorized, GapReason.SourceFailed, GapReason.CapabilityUnavailable)) {
                    require(hintGap.contextType in capability.contextTypes) { "${signal.signalId} status gap context outside ${signal.capabilityId}" }
                }
                validateGapState(signal, hintGap)
                val key = Triple(hintGap.contextType, hintGap.reason, signal.timeRange)
                if (gapKeys.add(key)) gaps += ContextGap(
                    contextGapId = "gap_${request.dayId}_${"%02d".format(gaps.size + 1)}", ownerId = request.ownerId,
                    spaceId = request.spaceId, localDate = request.localDate, contextType = hintGap.contextType,
                    reason = hintGap.reason, valueLevel = hintGap.valueLevel, promptPolicy = promptPolicy(hintGap.reason, hintGap.valueLevel),
                    capabilityIds = setOf(signal.capabilityId), state = ContextGapState.Open, createdAt = signal.observedAt, timeRange = signal.timeRange,
                )
            }
        }
        val covered = observations.filter { it.state in setOf(ObservationState.Observed, ObservationState.Partial) && it.observedFields.isNotEmpty() }.flatMap { it.contextTypes }.toSet()
        val hasIssue = observations.any { it.state in setOf(ObservationState.NotAuthorized, ObservationState.Failed, ObservationState.Unavailable) }
        return CoverageCompileResult(dayId = request.dayId, ownerId = request.ownerId, spaceId = request.spaceId, localDate = request.localDate, timezone = request.timezone, coverageState = when { hasIssue -> CoverageState.Partial; candidates.isNotEmpty() -> CoverageState.EvidenceAvailable; else -> CoverageState.Sparse }, coveredContextTypes = covered, unknownContextTypes = ContextType.entries.toSet() - covered, observations = observations, candidateEvents = candidates, contextGaps = gaps)
    }

    private fun validateGapState(signal: CoverageSignal, gap: GapHint) {
        when (gap.reason) {
            GapReason.SourceNotAuthorized -> require(signal.sourceState == SourceState.NotAuthorized) { "${signal.signalId} source_not_authorized gap lacks matching state" }
            GapReason.SourceFailed -> require(signal.sourceState == SourceState.Failed) { "${signal.signalId} source_failed gap lacks matching state" }
            GapReason.CapabilityUnavailable -> require(signal.sourceState == SourceState.Unavailable) { "${signal.signalId} capability_unavailable gap lacks matching state" }
            else -> Unit
        }
    }

    private fun promptPolicy(reason: GapReason, level: ValueLevel) = when (reason) {
        GapReason.SourceNotAuthorized, GapReason.SourceFailed, GapReason.CapabilityUnavailable -> PromptPolicy.SourceStatusOnly
        else -> when (level) { ValueLevel.High -> PromptPolicy.PromptOnce; ValueLevel.Medium -> PromptPolicy.Optional; ValueLevel.Low -> PromptPolicy.DoNotPrompt }
    }
}
