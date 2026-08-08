package com.ameme.android.coverage

import java.time.Instant

/** Current Android source declarations, intentionally limited to implemented partial paths. */
object MobileSourceCapabilities {
    private val allSegments = setOf(
        "segment_t0_ai_worker",
        "segment_t1_communication",
        "segment_t1_learner_creator",
        "segment_t2_life_recorder",
        "segment_t2_quantified_vertical",
    )
    private val lifecycle = setOf(
        HardGate.SourceExplanation,
        HardGate.ExplicitPermission,
        HardGate.Lineage,
        HardGate.CascadeDelete,
        HardGate.Recovery,
        HardGate.RealDevice,
        HardGate.RealUser,
    )

    fun createRegistry(): SourceCapabilityRegistry = SourceCapabilityRegistry.fromList(
        listOf(
            capability(
                id = "cap_explicit_capture", sourceType = SourceType.Text, mode = AcquisitionMode.OneAction,
                contexts = ContextType.entries.toSet(), permission = PermissionTier.Low,
                claims = textClaims("User-entered words can express intent and emotion; they do not prove external results."),
                gates = lifecycle + HardGate.PauseAndRevoke,
            ),
            capability(
                id = "cap_share", sourceType = SourceType.Share, mode = AcquisitionMode.OneAction,
                contexts = setOf(ContextType.ContentConsumption, ContextType.ContentCreation, ContextType.ActivityResult),
                permission = PermissionTier.Low,
                claims = listOf(
                    FieldClaim(EvidenceField.Time, FieldAuthority.Supporting, "Share time is capture time, not necessarily source creation time."),
                    FieldClaim(EvidenceField.Action, FieldAuthority.Supporting, "A share action does not prove the user read or accepted the content."),
                    FieldClaim(EvidenceField.Description, FieldAuthority.Supporting, "A shared item needs user context before it establishes meaning."),
                ),
                gates = lifecycle + HardGate.RetentionLimit,
            ),
            capability(
                id = "cap_agent", sourceType = SourceType.Agent, mode = AcquisitionMode.SceneTriggered,
                contexts = setOf(ContextType.ActivityResult, ContextType.ContentCreation, ContextType.DecisionCommitment),
                permission = PermissionTier.Medium,
                claims = listOf(
                    FieldClaim(EvidenceField.Time, FieldAuthority.Strong, "Tool execution time does not prove the wider task timeline."),
                    FieldClaim(EvidenceField.Action, FieldAuthority.Strong, "Agent records require an exact Grant and do not prove completion."),
                    FieldClaim(EvidenceField.Result, FieldAuthority.Supporting, "Reported outputs can require user review."),
                    FieldClaim(EvidenceField.Description, FieldAuthority.Supporting, "Structured agent payload is bounded by the Grant."),
                ),
                gates = lifecycle + setOf(HardGate.PauseAndRevoke, HardGate.PlatformPolicy),
            ),
            capability(
                id = "cap_calendar", sourceType = SourceType.Calendar, mode = AcquisitionMode.ImportOrForward,
                contexts = setOf(ContextType.TimeSchedule, ContextType.CommunicationRelationship),
                permission = PermissionTier.Medium,
                claims = listOf(
                    FieldClaim(EvidenceField.Time, FieldAuthority.Strong, "Calendar time describes a plan, not whether it happened."),
                    FieldClaim(EvidenceField.People, FieldAuthority.Supporting, "Calendar participants do not prove attendance or relationship."),
                    FieldClaim(EvidenceField.Action, FieldAuthority.Weak, "Calendar title may describe an intended activity."),
                    FieldClaim(EvidenceField.Description, FieldAuthority.Supporting, "Provider details are user-selected import content."),
                ),
                gates = lifecycle + setOf(HardGate.PauseAndRevoke, HardGate.RetentionLimit),
            ),
            capability(
                id = "cap_photo", sourceType = SourceType.Photo, mode = AcquisitionMode.OneAction, priority = CapabilityPriority.P1,
                contexts = setOf(ContextType.ActivityResult, ContextType.CommunicationRelationship, ContextType.PlaceMobility, ContextType.ConsumptionEntertainment),
                permission = PermissionTier.Medium,
                claims = listOf(
                    FieldClaim(EvidenceField.Time, FieldAuthority.Supporting, "Photo timestamp does not establish the activity or its meaning."),
                    FieldClaim(EvidenceField.Place, FieldAuthority.Supporting, "Photo metadata can be absent, stale or imprecise."),
                    FieldClaim(EvidenceField.People, FieldAuthority.Weak, "A photo cannot silently identify people or relationships."),
                    FieldClaim(EvidenceField.Description, FieldAuthority.Weak, "Image interpretation requires review and does not establish behavior."),
                ),
                gates = lifecycle + setOf(HardGate.PauseAndRevoke, HardGate.RetentionLimit, HardGate.BystanderNotice),
                bystanderRisk = BystanderRisk.Medium,
            ),
            capability(
                id = "cap_explicit_voice", sourceType = SourceType.Audio, mode = AcquisitionMode.OneAction,
                contexts = setOf(
                    ContextType.ActivityResult,
                    ContextType.CommunicationRelationship,
                    ContextType.DecisionCommitment,
                    ContextType.ContentCreation,
                    ContextType.IntentFeeling,
                ),
                permission = PermissionTier.High,
                claims = textClaims("Explicit voice text can express intent and emotion; transcription can be incomplete or wrong.") +
                    FieldClaim(EvidenceField.Result, FieldAuthority.Supporting, "Spoken results remain user assertions until corroborated."),
                gates = lifecycle + setOf(HardGate.PauseAndRevoke, HardGate.RetentionLimit, HardGate.BystanderNotice),
                bystanderRisk = BystanderRisk.High,
            ),
        ),
    )

    private fun capability(
        id: String,
        sourceType: SourceType,
        mode: AcquisitionMode,
        contexts: Set<ContextType>,
        permission: PermissionTier,
        claims: List<FieldClaim>,
        gates: Set<HardGate>,
        priority: CapabilityPriority = CapabilityPriority.P0,
        bystanderRisk: BystanderRisk = BystanderRisk.Low,
    ) = SourceCapability(
        capabilityId = id, sourceType = sourceType, acquisitionMode = mode, contextTypes = contexts,
        fieldClaims = claims, eligibleSegmentIds = allSegments, priority = priority, permissionTier = permission,
        bystanderRisk = bystanderRisk, validationStatus = ValidationStatus.ImplementedPartial, hardGates = gates,
    )

    private fun textClaims(limitation: String) = listOf(
        FieldClaim(EvidenceField.Time, FieldAuthority.Supporting, "Capture time is not a complete activity timeline."),
        FieldClaim(EvidenceField.Action, FieldAuthority.Supporting, limitation),
        FieldClaim(EvidenceField.Description, FieldAuthority.Strong, limitation),
        FieldClaim(EvidenceField.Intent, FieldAuthority.Strong, limitation),
        FieldClaim(EvidenceField.Emotion, FieldAuthority.Strong, limitation),
    )
}

/** Emits only non-evidence source-status observations; it never constructs an EventHint. */
class MobileSourceStatusSignalFactory(private val registry: SourceCapabilityRegistry) {
    fun create(
        signalId: String,
        capabilityId: String,
        sourceState: SourceState,
        contextTypes: Set<ContextType>,
        observedAt: Instant,
        importance: ValueLevel = ValueLevel.Medium,
    ): CoverageSignal {
        require(signalId.isNotBlank()) { "signal_id must be non-empty" }
        require(sourceState != SourceState.Available) { "status factory only accepts unavailable source states" }
        val capability = registry.get(capabilityId)
        require(contextTypes.isNotEmpty() && contextTypes.all(capability.contextTypes::contains)) { "status contexts must be covered by $capabilityId" }
        val reason = when (sourceState) {
            SourceState.NotAuthorized -> GapReason.SourceNotAuthorized
            SourceState.Failed -> GapReason.SourceFailed
            SourceState.Unavailable -> GapReason.CapabilityUnavailable
            SourceState.Available -> error("validated above")
        }
        return CoverageSignal(
            signalId = signalId, capabilityId = capabilityId, sourceState = sourceState, contextTypes = contextTypes,
            observedFields = emptySet(), factStatus = CoverageFactStatus.Observed, confidence = 0.0,
            importance = importance, sourceObjectIds = emptySet(), observedAt = observedAt, eventHint = null,
            gapHints = contextTypes.map { GapHint(it, reason, importance) },
        )
    }
}
