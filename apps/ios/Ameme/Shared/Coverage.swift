import Foundation

/// Pure, conservative context-coverage contracts. These records describe
/// source evidence and known gaps; they are deliberately not Memory events.
public enum CoverageContractError: Error, Equatable {
    case invalid(String)
}

public enum ContextType: String, CaseIterable, Codable, Hashable, Sendable {
    case timeSchedule = "time_schedule"
    case activityResult = "activity_result"
    case contentConsumption = "content_consumption"
    case contentCreation = "content_creation"
    case communicationRelationship = "communication_relationship"
    case decisionCommitment = "decision_commitment"
    case placeMobility = "place_mobility"
    case bodyState = "body_state"
    case consumptionEntertainment = "consumption_entertainment"
    case intentFeeling = "intent_feeling"
}

public enum EvidenceField: String, CaseIterable, Codable, Hashable, Sendable {
    case time, place, people, action, result, intent, emotion, relationship, description
}

public enum SourceState: String, Codable, Hashable, Sendable {
    case available, notAuthorized = "not_authorized", failed, unavailable
}

public enum CoverageFactStatus: String, Codable, Hashable, Sendable {
    case observed, userAsserted = "user_asserted", planned, inferred
}

public enum GapReason: String, Codable, Hashable, Sendable {
    case actualityUnconfirmed = "actuality_unconfirmed"
    case resultMissing = "result_missing"
    case meaningMissing = "meaning_missing"
    case identityAmbiguous = "identity_ambiguous"
    case sourceNotAuthorized = "source_not_authorized"
    case sourceFailed = "source_failed"
    case capabilityUnavailable = "capability_unavailable"
}

public enum PromptPolicy: String, Codable, Hashable, Sendable {
    case doNotPrompt = "do_not_prompt", optional, promptOnce = "prompt_once", sourceStatusOnly = "source_status_only"
}

public enum CoverageValueLevel: String, Codable, Hashable, Sendable { case low, medium, high }
public enum SourceAcquisitionMode: String, Codable, Hashable, Sendable { case automatic, sceneTriggered = "scene_triggered", oneAction = "one_action", importOrForward = "import_or_forward", gapPrompt = "gap_prompt", continuousOptIn = "continuous_opt_in" }
public enum CoveragePriority: String, Codable, Hashable, Sendable { case p0 = "P0", p1 = "P1", p2 = "P2", p3 = "P3" }
public enum SourceType: String, Codable, Hashable, Sendable { case text, audio, photo, share, calendar, agent, browser, file, meeting, email, location, health, lifeService = "life_service", continuousScreen = "continuous_screen", continuousAudio = "continuous_audio" }
public enum FieldAuthority: String, Codable, Hashable, Sendable { case strong, supporting, weak, none }
public enum BystanderRisk: String, Codable, Hashable, Sendable { case none, low, medium, high }
public enum PermissionTier: String, Codable, Hashable, Sendable { case none, low, medium, high, veryHigh = "very_high" }
public enum CapabilityValidationStatus: String, Codable, Hashable, Sendable { case implementedPartial = "implemented_partial", officialPathOnly = "official_path_only", syntheticOnly = "synthetic_only", researchNeeded = "research_needed", deferred }
public enum CapabilityHardGate: String, Codable, Hashable, Sendable { case sourceExplanation = "source_explanation", explicitPermission = "explicit_permission", pauseAndRevoke = "pause_and_revoke", lineage, cascadeDelete = "cascade_delete", retentionLimit = "retention_limit", bystanderNotice = "bystander_notice", platformPolicy = "platform_policy", recovery, realDevice = "real_device", realUser = "real_user" }
public enum CoverageObservationState: String, Codable, Hashable, Sendable { case observed, partial, notAuthorized = "not_authorized", failed, unavailable }
public enum CoverageState: String, Codable, Hashable, Sendable { case partial, evidenceAvailable = "evidence_available", sparse }
public enum ContextGapState: String, Codable, Hashable, Sendable { case open, dismissed, resolved, expired }
public enum CoverageTimePrecision: String, Codable, Hashable, Sendable { case exact, minute, hour, partOfDay = "part_of_day", day, range, unknown }
public enum CoverageEventType: String, Codable, Hashable, Sendable { case activity, communication, decision, result, stateChange = "state_change", milestone, experience }

public struct CoverageTimeRange: Codable, Hashable, Sendable {
    public let start: String
    public let end: String?
    public let timezone: String?
    public let precision: CoverageTimePrecision
    public init(start: String, end: String? = nil, timezone: String? = nil, precision: CoverageTimePrecision) { self.start = start; self.end = end; self.timezone = timezone; self.precision = precision }
}

public struct SourceFieldClaim: Codable, Hashable, Sendable {
    public let field: EvidenceField
    public let authority: FieldAuthority
    public let limitation: String
    public init(field: EvidenceField, authority: FieldAuthority, limitation: String) { self.field = field; self.authority = authority; self.limitation = limitation }
}

public struct SourceCapability: Codable, Hashable, Sendable {
    public let schemaVersion: Int
    public let capabilityID: String
    public let sourceType: SourceType
    public let acquisitionMode: SourceAcquisitionMode
    public let contextTypes: [ContextType]
    public let fieldClaims: [SourceFieldClaim]
    public let eligibleSegmentIDs: [String]
    public let priority: CoveragePriority
    public let permissionTier: PermissionTier
    public let bystanderRisk: BystanderRisk
    public let validationStatus: CapabilityValidationStatus
    public let hardGates: [CapabilityHardGate]

    public init(schemaVersion: Int = 1, capabilityID: String, sourceType: SourceType, acquisitionMode: SourceAcquisitionMode, contextTypes: [ContextType], fieldClaims: [SourceFieldClaim], eligibleSegmentIDs: [String], priority: CoveragePriority, permissionTier: PermissionTier = .low, bystanderRisk: BystanderRisk, validationStatus: CapabilityValidationStatus = .implementedPartial, hardGates: [CapabilityHardGate]) {
        self.schemaVersion = schemaVersion; self.capabilityID = capabilityID; self.sourceType = sourceType; self.acquisitionMode = acquisitionMode; self.contextTypes = contextTypes; self.fieldClaims = fieldClaims; self.eligibleSegmentIDs = eligibleSegmentIDs; self.priority = priority; self.permissionTier = permissionTier; self.bystanderRisk = bystanderRisk; self.validationStatus = validationStatus; self.hardGates = hardGates
    }
}

public struct CoverageSignal: Codable, Hashable, Sendable {
    public let signalID, capabilityID: String
    public let sourceState: SourceState
    public let contextTypes: [ContextType]
    public let observedFields: [EvidenceField]
    public let factStatus: CoverageFactStatus
    public let importance: CoverageValueLevel
    public let confidence: Double
    public let sourceObjectIDs: [String]
    public let observedAt: String
    public let timeRange: CoverageTimeRange?
    public let eventHint: CoverageEventHint?
    public let gapHints: [CoverageGapHint]
    public init(signalID: String, capabilityID: String, sourceState: SourceState, contextTypes: [ContextType], observedFields: [EvidenceField], factStatus: CoverageFactStatus, importance: CoverageValueLevel, confidence: Double, sourceObjectIDs: [String], observedAt: String, timeRange: CoverageTimeRange? = nil, eventHint: CoverageEventHint? = nil, gapHints: [CoverageGapHint]) { self.signalID = signalID; self.capabilityID = capabilityID; self.sourceState = sourceState; self.contextTypes = contextTypes; self.observedFields = observedFields; self.factStatus = factStatus; self.importance = importance; self.confidence = confidence; self.sourceObjectIDs = sourceObjectIDs; self.observedAt = observedAt; self.timeRange = timeRange; self.eventHint = eventHint; self.gapHints = gapHints }
}
public struct CoverageEventHint: Codable, Hashable, Sendable { public let eventType: CoverageEventType; public let title: String; public init(eventType: CoverageEventType, title: String) { self.eventType = eventType; self.title = title } }
public struct CoverageGapHint: Codable, Hashable, Sendable { public let contextType: ContextType; public let reason: GapReason; public let valueLevel: CoverageValueLevel; public init(contextType: ContextType, reason: GapReason, valueLevel: CoverageValueLevel) { self.contextType = contextType; self.reason = reason; self.valueLevel = valueLevel } }

public struct SourceCapabilityRegistry: Sendable {
    private let capabilities: [String: SourceCapability]
    public init(_ values: [SourceCapability]) throws {
        var result: [String: SourceCapability] = [:]
        for capability in values {
            try Self.validate(capability)
            guard result[capability.capabilityID] == nil else { throw CoverageContractError.invalid("duplicate capability_id") }
            result[capability.capabilityID] = capability
        }
        guard !result.isEmpty else { throw CoverageContractError.invalid("capability registry must not be empty") }
        capabilities = result
    }
    public func capability(id: String) throws -> SourceCapability { guard let capability = capabilities[id] else { throw CoverageContractError.invalid("unknown capability_id") }; return capability }
    public var ids: [String] { capabilities.keys.sorted() }
    private static func validate(_ value: SourceCapability) throws {
        guard value.schemaVersion == 1, !value.capabilityID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !value.contextTypes.isEmpty, !value.eligibleSegmentIDs.isEmpty, !value.fieldClaims.isEmpty, value.eligibleSegmentIDs.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw CoverageContractError.invalid("invalid capability") }
        guard unique(value.contextTypes), unique(value.eligibleSegmentIDs), unique(value.fieldClaims.map(\.field)), unique(value.hardGates) else { throw CoverageContractError.invalid("duplicate capability values") }
        guard value.acquisitionMode != .continuousOptIn || value.priority == .p3 else { throw CoverageContractError.invalid("continuous capture cannot be P0/P1/P2") }
        let required: Set<CapabilityHardGate> = [.lineage, .cascadeDelete, .recovery]
        guard required.isSubset(of: Set(value.hardGates)) else { throw CoverageContractError.invalid("capability missing deletion or recovery gate") }
        guard value.bystanderRisk != .high || value.hardGates.contains(.bystanderNotice) else { throw CoverageContractError.invalid("high bystander risk requires notice") }
        guard value.fieldClaims.allSatisfy({ !$0.limitation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw CoverageContractError.invalid("field limitation required") }
        if value.sourceType != .text && value.sourceType != .audio && value.fieldClaims.contains(where: { ($0.field == .intent || $0.field == .emotion) && $0.authority == .strong }) { throw CoverageContractError.invalid("behavioral source cannot strongly infer intent or emotion") }
    }
}

public struct CoverageObservation: Codable, Hashable, Sendable { public let coverageObservationID, ownerID, spaceID, localDate, capabilityID: String; public let sourceObjectIDs: [String]; public let contextTypes: [ContextType]; public let observedFields: [EvidenceField]; public let factStatus: CoverageFactStatus; public let state: CoverageObservationState; public let observedAt: String; public let timeRange: CoverageTimeRange? }
public struct CandidateEvent: Codable, Hashable, Sendable { public let candidateID: String; public let sourceObjectIDs: [String]; public let capabilityID: String; public let eventType: CoverageEventType; public let title: String; public let timeRange: CoverageTimeRange?; public let factStatus: CoverageFactStatus; public let confidence: Double; public let observedFields: [EvidenceField] }
public struct ContextGap: Codable, Hashable, Sendable { public let contextGapID, ownerID, spaceID, localDate: String; public let contextType: ContextType; public let reason: GapReason; public let valueLevel: CoverageValueLevel; public let promptPolicy: PromptPolicy; public let capabilityIDs: [String]; public let state: ContextGapState; public let createdAt: String; public let timeRange: CoverageTimeRange? }
public struct CoverageCompilation: Codable, Hashable, Sendable { public let dayID, ownerID, spaceID, localDate, timezone: String; public let coverageState: CoverageState; public let coveredContextTypes, unknownContextTypes: [ContextType]; public let observations: [CoverageObservation]; public let candidateEvents: [CandidateEvent]; public let contextGaps: [ContextGap] }

public struct CoverageCompiler: Sendable {
    private let registry: SourceCapabilityRegistry
    public init(registry: SourceCapabilityRegistry) { self.registry = registry }
    public func compileDay(dayID: String, ownerID: String, spaceID: String, localDate: String, timezone: String, signals: [CoverageSignal]) throws -> CoverageCompilation {
        guard [dayID, ownerID, spaceID, localDate, timezone].allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw CoverageContractError.invalid("day identity required") }
        var seenSignals = Set<String>(), seenGaps = Set<GapKey>(), observations: [CoverageObservation] = [], candidates: [CandidateEvent] = [], gaps: [ContextGap] = []
        for signal in signals {
            guard seenSignals.insert(signal.signalID).inserted, !signal.signalID.isEmpty, !signal.observedAt.isEmpty, (0...1).contains(signal.confidence), unique(signal.contextTypes), unique(signal.observedFields), unique(signal.sourceObjectIDs), signal.sourceObjectIDs.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw CoverageContractError.invalid("invalid or duplicate signal") }
            let capability = try registry.capability(id: signal.capabilityID)
            guard !signal.contextTypes.isEmpty, Set(signal.contextTypes).isSubset(of: Set(capability.contextTypes)) else { throw CoverageContractError.invalid("signal claims context outside capability") }
            if signal.sourceState == .available { guard !signal.sourceObjectIDs.isEmpty else { throw CoverageContractError.invalid("available evidence requires source object") } } else if !signal.sourceObjectIDs.isEmpty || !signal.observedFields.isEmpty { throw CoverageContractError.invalid("unavailable evidence cannot claim fields or sources") }
            let state: CoverageObservationState = switch signal.sourceState { case .available: signal.observedFields.isEmpty ? .partial : .observed; case .notAuthorized: .notAuthorized; case .failed: .failed; case .unavailable: .unavailable }
            observations.append(CoverageObservation(coverageObservationID: "coverage_\(signal.signalID)", ownerID: ownerID, spaceID: spaceID, localDate: localDate, capabilityID: signal.capabilityID, sourceObjectIDs: signal.sourceObjectIDs, contextTypes: signal.contextTypes, observedFields: signal.observedFields, factStatus: signal.factStatus, state: state, observedAt: signal.observedAt, timeRange: signal.timeRange))
            if let hint = signal.eventHint {
                guard !hint.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, hint.title.count <= 200 else { throw CoverageContractError.invalid("event title must be 1...200 characters") }
                if signal.sourceState == .available, signal.observedFields.contains(.time), !Set(signal.observedFields).intersection([.action, .result, .description]).isEmpty, signal.factStatus != .inferred || signal.confidence >= 0.7 { candidates.append(CandidateEvent(candidateID: "candidate_\(signal.signalID)", sourceObjectIDs: signal.sourceObjectIDs, capabilityID: signal.capabilityID, eventType: hint.eventType, title: hint.title, timeRange: signal.timeRange, factStatus: signal.factStatus, confidence: signal.confidence, observedFields: signal.observedFields)) }
            }
            for hint in signal.gapHints {
                if [.sourceNotAuthorized, .sourceFailed, .capabilityUnavailable].contains(hint.reason) {
                    guard capability.contextTypes.contains(hint.contextType) else { throw CoverageContractError.invalid("status gap context outside capability") }
                }
                try validate(hint: hint, sourceState: signal.sourceState)
                let key = GapKey(contextType: hint.contextType, reason: hint.reason, timeRange: signal.timeRange)
                guard seenGaps.insert(key).inserted else { continue }
                gaps.append(ContextGap(contextGapID: "gap_\(dayID)_\(String(format: "%02d", gaps.count + 1))", ownerID: ownerID, spaceID: spaceID, localDate: localDate, contextType: hint.contextType, reason: hint.reason, valueLevel: hint.valueLevel, promptPolicy: promptPolicy(for: hint), capabilityIDs: [signal.capabilityID], state: .open, createdAt: signal.observedAt, timeRange: signal.timeRange))
            }
        }
        let covered = Set(observations.filter { ($0.state == .observed || $0.state == .partial) && !$0.observedFields.isEmpty }.flatMap(\.contextTypes))
        let issues = observations.contains { [.notAuthorized, .failed, .unavailable].contains($0.state) }
        return CoverageCompilation(dayID: dayID, ownerID: ownerID, spaceID: spaceID, localDate: localDate, timezone: timezone, coverageState: issues ? .partial : candidates.isEmpty ? .sparse : .evidenceAvailable, coveredContextTypes: covered.sorted(by: { $0.rawValue < $1.rawValue }), unknownContextTypes: Set(ContextType.allCases).subtracting(covered).sorted(by: { $0.rawValue < $1.rawValue }), observations: observations, candidateEvents: candidates, contextGaps: gaps)
    }
    private func validate(hint: CoverageGapHint, sourceState: SourceState) throws { switch hint.reason { case .sourceNotAuthorized where sourceState != .notAuthorized, .sourceFailed where sourceState != .failed, .capabilityUnavailable where sourceState != .unavailable: throw CoverageContractError.invalid("gap lacks matching source state"); default: break } }
    private func promptPolicy(for hint: CoverageGapHint) -> PromptPolicy {
        if [.sourceNotAuthorized, .sourceFailed, .capabilityUnavailable].contains(hint.reason) {
            return .sourceStatusOnly
        }
        switch hint.valueLevel {
        case .high: return .promptOnce
        case .medium: return .optional
        case .low: return .doNotPrompt
        }
    }
}

private struct GapKey: Hashable { let contextType: ContextType; let reason: GapReason; let timeRange: CoverageTimeRange? }
private func unique<T: Hashable>(_ values: [T]) -> Bool { values.count == Set(values).count }
