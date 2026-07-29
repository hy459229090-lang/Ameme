import Foundation

/// Declares the currently implemented mobile entry points. Hard gates here are
/// requirements, not claims that device, user, or external release evidence passed.
public enum MobileSourceCapabilities {
    public static let allSegmentIDs = [
        "segment_t0_ai_worker", "segment_t1_communication", "segment_t1_learner_creator",
        "segment_t2_life_recorder", "segment_t2_quantified_vertical",
    ]

    public static func makeRegistry() throws -> SourceCapabilityRegistry {
        try SourceCapabilityRegistry([
            capability(id: "cap_explicit_capture", type: .text, mode: .oneAction, contexts: ContextType.allCases, claims: [claim(.time), claim(.action), claim(.description, .strong), claim(.intent, .strong), claim(.emotion, .strong)], permission: .low, risk: .low, gates: standardGates.union([.pauseAndRevoke])),
            capability(id: "cap_share", type: .share, mode: .oneAction, contexts: [.contentConsumption, .contentCreation, .activityResult], claims: [claim(.time), claim(.action), claim(.description)], permission: .low, risk: .low, gates: standardGates.union([.retentionLimit])),
            capability(id: "cap_agent", type: .agent, mode: .sceneTriggered, contexts: [.activityResult, .decisionCommitment, .contentCreation], claims: [claim(.time, .strong), claim(.action, .strong), claim(.result), claim(.description)], permission: .medium, risk: .low, gates: standardGates.union([.pauseAndRevoke, .platformPolicy])),
            // Calendar remains an explicit user-selected import; it is not background automatic capture.
            capability(id: "cap_calendar", type: .calendar, mode: .importOrForward, contexts: [.timeSchedule, .communicationRelationship], claims: [claim(.time, .strong), claim(.people), claim(.action, .weak), claim(.description)], permission: .medium, risk: .medium, gates: standardGates.union([.pauseAndRevoke, .retentionLimit])),
            capability(id: "cap_photo", type: .photo, mode: .oneAction, contexts: [.activityResult, .communicationRelationship, .placeMobility, .consumptionEntertainment], claims: [claim(.time), claim(.place), claim(.people, .weak), claim(.description, .weak)], permission: .medium, risk: .medium, gates: standardGates.union([.pauseAndRevoke, .retentionLimit, .bystanderNotice])),
            capability(id: "cap_explicit_voice", type: .audio, mode: .oneAction, contexts: [.activityResult, .communicationRelationship, .decisionCommitment, .contentCreation, .intentFeeling], claims: [claim(.time), claim(.action), claim(.result), claim(.intent, .strong), claim(.emotion, .strong), claim(.description, .strong)], permission: .high, risk: .high, gates: standardGates.union([.pauseAndRevoke, .retentionLimit, .bystanderNotice])),
        ])
    }

    private static let standardGates: Set<CapabilityHardGate> = [.sourceExplanation, .explicitPermission, .lineage, .cascadeDelete, .recovery, .realDevice, .realUser]
    private static func claim(_ field: EvidenceField, _ authority: FieldAuthority = .supporting) -> SourceFieldClaim { SourceFieldClaim(field: field, authority: authority, limitation: "Only explicitly user-authorized source evidence is represented.") }
    private static func capability(id: String, type: SourceType, mode: SourceAcquisitionMode, contexts: [ContextType], claims: [SourceFieldClaim], permission: PermissionTier, risk: BystanderRisk, gates: Set<CapabilityHardGate>) -> SourceCapability {
        SourceCapability(capabilityID: id, sourceType: type, acquisitionMode: mode, contextTypes: contexts, fieldClaims: claims, eligibleSegmentIDs: allSegmentIDs, priority: id == "cap_photo" ? .p1 : .p0, permissionTier: permission, bystanderRisk: risk, validationStatus: .implementedPartial, hardGates: gates.sorted(by: { $0.rawValue < $1.rawValue }))
    }
}

/// Creates source-status evidence only. It intentionally has no source objects,
/// observed fields, or event hint, so a missing integration cannot create an Event.
public enum MobileSourceStatusSignalFactory {
    public static func makeSignal(registry: SourceCapabilityRegistry, signalID: String, capabilityID: String, state: SourceState, contextTypes: [ContextType], observedAt: String, valueLevel: CoverageValueLevel = .medium) throws -> CoverageSignal {
        guard state != .available else { throw CoverageContractError.invalid("status factory requires unavailable source state") }
        let capability = try registry.capability(id: capabilityID)
        guard !contextTypes.isEmpty, Set(contextTypes).isSubset(of: Set(capability.contextTypes)) else { throw CoverageContractError.invalid("status context outside capability") }
        let reason: GapReason = switch state { case .notAuthorized: .sourceNotAuthorized; case .failed: .sourceFailed; case .unavailable: .capabilityUnavailable; case .available: preconditionFailure("guarded") }
        return CoverageSignal(signalID: signalID, capabilityID: capabilityID, sourceState: state, contextTypes: contextTypes, observedFields: [], factStatus: .observed, importance: valueLevel, confidence: 0, sourceObjectIDs: [], observedAt: observedAt, eventHint: nil, gapHints: contextTypes.map { CoverageGapHint(contextType: $0, reason: reason, valueLevel: valueLevel) })
    }
}
