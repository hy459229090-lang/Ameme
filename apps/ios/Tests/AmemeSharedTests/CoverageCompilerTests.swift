import XCTest
@testable import AmemeShared

final class CoverageCompilerTests: XCTestCase {
    private let gates: [CapabilityHardGate] = [.lineage, .cascadeDelete, .recovery]
    private func capability(source: SourceType = .calendar, contexts: [ContextType] = [.timeSchedule, .activityResult], mode: SourceAcquisitionMode = .oneAction, priority: CoveragePriority = .p0, risk: BystanderRisk = .none, claims: [SourceFieldClaim] = [SourceFieldClaim(field: .time, authority: .strong, limitation: "user-selected source")]) -> SourceCapability { SourceCapability(capabilityID: "cap_calendar", sourceType: source, acquisitionMode: mode, contextTypes: contexts, fieldClaims: claims, eligibleSegmentIDs: ["segment_t0"], priority: priority, bystanderRisk: risk, hardGates: gates) }
    private func compiler(_ value: SourceCapability? = nil) throws -> CoverageCompiler { CoverageCompiler(registry: try SourceCapabilityRegistry([value ?? capability()])) }
    private func signal(state: SourceState = .available, fields: [EvidenceField] = [.time, .action], status: CoverageFactStatus = .planned, confidence: Double = 1, gaps: [CoverageGapHint] = []) -> CoverageSignal { CoverageSignal(signalID: "signal_001", capabilityID: "cap_calendar", sourceState: state, contextTypes: [.timeSchedule, .activityResult], observedFields: fields, factStatus: status, importance: .high, confidence: confidence, sourceObjectIDs: state == .available ? ["source_001"] : [], observedAt: "2026-07-26T10:00:00Z", eventHint: CoverageEventHint(eventType: .activity, title: "Calendar plan"), gapHints: gaps) }
    func testCalendarPlanRemainsPlannedAndPromptsOnce() throws { let output = try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "Asia/Shanghai", signals: [signal(gaps: [CoverageGapHint(contextType: .activityResult, reason: .actualityUnconfirmed, valueLevel: .high)])]); XCTAssertEqual(output.coverageState, .evidenceAvailable); XCTAssertEqual(output.candidateEvents.first?.factStatus, .planned); XCTAssertEqual(output.contextGaps.first?.promptPolicy, .promptOnce); XCTAssertEqual(output.unknownContextTypes.count, 8) }
    func testLowConfidenceInferenceDoesNotBecomeCandidate() throws { let output = try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [signal(status: .inferred, confidence: 0.69)]); XCTAssertEqual(output.coverageState, .sparse); XCTAssertTrue(output.candidateEvents.isEmpty); XCTAssertEqual(output.coveredContextTypes, [.activityResult, .timeSchedule]) }
    func testUnavailableSourceCannotClaimEvidenceAndIssuesMakePartial() throws { let inactive = signal(state: .notAuthorized, fields: [], gaps: [CoverageGapHint(contextType: .timeSchedule, reason: .sourceNotAuthorized, valueLevel: .high)]); let output = try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [inactive]); XCTAssertEqual(output.coverageState, .partial); XCTAssertEqual(output.contextGaps.first?.promptPolicy, .sourceStatusOnly); var invalid = inactive; invalid = CoverageSignal(signalID: invalid.signalID, capabilityID: invalid.capabilityID, sourceState: .notAuthorized, contextTypes: invalid.contextTypes, observedFields: [.action], factStatus: invalid.factStatus, importance: invalid.importance, confidence: invalid.confidence, sourceObjectIDs: [], observedAt: invalid.observedAt, gapHints: invalid.gapHints); XCTAssertThrowsError(try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [invalid])) }
    func testRegistryRejectsPolicyViolations() { XCTAssertThrowsError(try SourceCapabilityRegistry([capability(mode: .continuousOptIn)])); XCTAssertThrowsError(try SourceCapabilityRegistry([SourceCapability(capabilityID: "cap_photo", sourceType: .photo, acquisitionMode: .oneAction, contextTypes: [.activityResult], fieldClaims: [SourceFieldClaim(field: .emotion, authority: .strong, limitation: "invalid")], eligibleSegmentIDs: ["segment_t0"], priority: .p0, bystanderRisk: .none, hardGates: gates)])); XCTAssertThrowsError(try SourceCapabilityRegistry([capability(risk: .high)])); XCTAssertThrowsError(try SourceCapabilityRegistry([SourceCapability(capabilityID: "cap_missing", sourceType: .calendar, acquisitionMode: .oneAction, contextTypes: [.timeSchedule], fieldClaims: [SourceFieldClaim(field: .time, authority: .strong, limitation: "x")], eligibleSegmentIDs: ["segment_t0"], priority: .p0, bystanderRisk: .none, hardGates: [.lineage, .recovery])])) }
    func testGapsDeduplicateByContextReasonAndTimeRange() throws { let gap = CoverageGapHint(contextType: .activityResult, reason: .resultMissing, valueLevel: .medium); let first = signal(gaps: [gap]); let second = CoverageSignal(signalID: "signal_002", capabilityID: "cap_calendar", sourceState: .available, contextTypes: [.timeSchedule, .activityResult], observedFields: [.time], factStatus: .observed, importance: .medium, confidence: 1, sourceObjectIDs: ["source_002"], observedAt: "2026-07-26T11:00:00Z", gapHints: [gap]); let output = try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [first, second]); XCTAssertEqual(output.contextGaps.count, 1); XCTAssertEqual(output.contextGaps.first?.promptPolicy, .optional) }
    func testSchemaBoundsRejectBlankIDsOutOfScopeGapAndInvalidTitle() throws {
        XCTAssertThrowsError(try SourceCapabilityRegistry([SourceCapability(capabilityID: "cap_blank", sourceType: .calendar, acquisitionMode: .oneAction, contextTypes: [.timeSchedule], fieldClaims: [SourceFieldClaim(field: .time, authority: .strong, limitation: "x")], eligibleSegmentIDs: ["  "], priority: .p0, bystanderRisk: .none, hardGates: gates)]))
        let outOfScope = signal(gaps: [CoverageGapHint(contextType: .contentCreation, reason: .meaningMissing, valueLevel: .low)])
        let ordinaryGapOutput = try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [outOfScope])
        XCTAssertEqual(ordinaryGapOutput.contextGaps.first?.contextType, .contentCreation)
        let statusOutOfScope = signal(state: .failed, fields: [], gaps: [CoverageGapHint(contextType: .contentCreation, reason: .sourceFailed, valueLevel: .low)])
        XCTAssertThrowsError(try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [statusOutOfScope]))
        let longTitle = CoverageSignal(signalID: "signal_title", capabilityID: "cap_calendar", sourceState: .available, contextTypes: [.timeSchedule], observedFields: [.time, .action], factStatus: .observed, importance: .high, confidence: 1, sourceObjectIDs: ["source_title"], observedAt: "2026-07-26T10:00:00Z", eventHint: CoverageEventHint(eventType: .activity, title: String(repeating: "x", count: 201)), gapHints: [])
        XCTAssertThrowsError(try compiler().compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [longTitle]))
    }

    func testMobileRegistryDeclaresCurrentSourceTruth() throws {
        let registry = try MobileSourceCapabilities.makeRegistry()
        XCTAssertEqual(
            Set(registry.ids),
            Set(["cap_explicit_capture", "cap_share", "cap_agent", "cap_calendar", "cap_photo", "cap_explicit_voice"])
        )
        let expectedContexts: [String: Set<ContextType>] = [
            "cap_explicit_capture": Set(ContextType.allCases),
            "cap_share": [.contentConsumption, .contentCreation, .activityResult],
            "cap_agent": [.activityResult, .contentCreation, .decisionCommitment],
            "cap_calendar": [.timeSchedule, .communicationRelationship],
            "cap_photo": [.activityResult, .communicationRelationship, .placeMobility, .consumptionEntertainment],
            "cap_explicit_voice": [.activityResult, .communicationRelationship, .decisionCommitment, .contentCreation, .intentFeeling],
        ]
        for (capabilityID, contexts) in expectedContexts {
            let capability = try registry.capability(id: capabilityID)
            XCTAssertEqual(Set(capability.contextTypes), contexts)
            XCTAssertTrue(capability.hardGates.contains(.realDevice))
            XCTAssertTrue(capability.hardGates.contains(.realUser))
        }
        XCTAssertEqual(MobileSourceCapabilities.allSegmentIDs.count, 5)
        let explicit = try registry.capability(id: "cap_explicit_capture")
        XCTAssertEqual(Set(explicit.contextTypes), Set(ContextType.allCases))
        XCTAssertEqual(explicit.eligibleSegmentIDs, MobileSourceCapabilities.allSegmentIDs)
        XCTAssertEqual((try registry.capability(id: "cap_calendar")).acquisitionMode, .importOrForward)
        let voice = try registry.capability(id: "cap_explicit_voice")
        XCTAssertEqual(voice.bystanderRisk, .high)
        XCTAssertTrue(voice.hardGates.contains(.bystanderNotice))
    }

    func testMobileStatusSignalsStayStatusOnlyAndCannotCreateCandidates() throws {
        let registry = try MobileSourceCapabilities.makeRegistry()
        for state in [SourceState.notAuthorized, .failed, .unavailable] {
            let signal = try MobileSourceStatusSignalFactory.makeSignal(registry: registry, signalID: "status_\(state.rawValue)", capabilityID: "cap_calendar", state: state, contextTypes: [.timeSchedule], observedAt: "2026-07-26T12:00:00Z")
            XCTAssertTrue(signal.sourceObjectIDs.isEmpty)
            XCTAssertTrue(signal.observedFields.isEmpty)
            XCTAssertNil(signal.eventHint)
            let output = try CoverageCompiler(registry: registry).compileDay(dayID: "day_001", ownerID: "owner_001", spaceID: "space_001", localDate: "2026-07-26", timezone: "UTC", signals: [signal])
            XCTAssertEqual(output.coverageState, .partial)
            XCTAssertTrue(output.candidateEvents.isEmpty)
            XCTAssertEqual(output.contextGaps.first?.promptPolicy, .sourceStatusOnly)
        }
        XCTAssertThrowsError(try MobileSourceStatusSignalFactory.makeSignal(registry: registry, signalID: "bad", capabilityID: "cap_calendar", state: .failed, contextTypes: [.bodyState], observedAt: "2026-07-26T12:00:00Z"))
    }
}
