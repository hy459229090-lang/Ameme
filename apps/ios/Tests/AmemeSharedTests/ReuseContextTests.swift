import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class ReuseTestKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key = SymmetricKey(data: Data(repeating: 0x71, count: 32))
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class ReuseContextTests: XCTestCase {
    func testFourReuseJourneysRevalidateAndPersistOnlyContentFreeTelemetry() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeReuse-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = ReuseTestKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let canary = "CANARY_PROJECT_BODY_NEVER_TELEMETRY"
        let now = Date(timeIntervalSince1970: 1_759_100_000)
        _ = store.add(
            title: "\(canary) 历史进展",
            detail: "项目进入封闭测试",
            kind: .text,
            sourceLabel: "测试",
            localDate: now,
            eventType: .milestone
        )
        let decisionEvent = store.add(
            title: "\(canary) 决定",
            detail: "按恢复门禁推进",
            kind: .text,
            sourceLabel: "测试",
            localDate: now,
            eventType: .decision
        )
        _ = store.add(
            title: "\(canary) 受限",
            detail: "不得参与复用",
            kind: .text,
            sourceLabel: "测试",
            localDate: now,
            eventType: .decision,
            sensitivity: .restricted
        )
        let candidate = try store.proposeLongTermMemory(
            LongTermMemoryProposal(
                ownerID: "owner_local",
                spaceID: "space_personal",
                sourceEventID: decisionEvent.id,
                expectedEventRevision: decisionEvent.revision,
                type: .decision,
                valueSummary: "\(canary) 恢复门禁决定",
                validFrom: now,
                proposedAt: now
            )
        )
        let active = try store.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: candidate.memoryID,
                confirmedAt: now.addingTimeInterval(1)
            )
        )
        let requests = [
            ReuseRequest(
                spaceID: "space_personal",
                intent: .historicalSearch,
                query: canary,
                requestedAt: now.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .projectResume,
                query: canary,
                requestedAt: now.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .preMeetingContext,
                query: canary,
                meetingAnchorDate: now,
                requestedAt: now.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .decisionCommitmentRecall,
                requestedAt: now.addingTimeInterval(2)
            ),
        ]
        let resolvedContexts = try requests.map { request in
            let context = try store.buildReuseContext(request)
            return try store.resolveReuseContext(
                context,
                at: now.addingTimeInterval(3)
            )
        }
        let contexts = resolvedContexts.map(\.context)
        XCTAssertEqual(Set(contexts.map(\.intent)), Set(ReuseIntent.allCases))
        XCTAssertTrue(contexts.allSatisfy { !$0.references.isEmpty })
        XCTAssertTrue(resolvedContexts.allSatisfy {
            !$0.items.isEmpty && $0.items.map(\.reference) == $0.context.references
        })
        XCTAssertTrue(resolvedContexts.flatMap(\.items).allSatisfy {
            $0.sourceEvent.sensitivity != .restricted
        })
        XCTAssertTrue(contexts.allSatisfy { $0.exclusions.contains(.restricted) })
        XCTAssertTrue(contexts.flatMap(\.references).allSatisfy {
            $0.sensitivity != .restricted
        })
        XCTAssertTrue(contexts.last!.references.contains {
            $0.objectType == .longTermMemory
        })

        for (index, context) in contexts.enumerated() {
            XCTAssertTrue(
                try store.recordReuseOutcome(
                    ReuseOutcomeSubmission(
                        attemptID: context.attemptID,
                        outcome: index == 0 ? .useful : .notUseful,
                        userAction: index == 1 ? .hidden : .none,
                        submittedAt: now.addingTimeInterval(Double(10 + index))
                    )
                )
            )
        }
        XCTAssertThrowsError(
            try store.recordReuseOutcome(
                ReuseOutcomeSubmission(
                    attemptID: contexts[0].attemptID,
                    outcome: .wrongMemory,
                    submittedAt: now.addingTimeInterval(30)
                )
            )
        ) {
            XCTAssertEqual($0 as? ReuseError, .outcomeAlreadyRecorded)
        }
        XCTAssertEqual(store.helpfulReuseCount(since: now), 1)
        XCTAssertEqual(
            store.reuseTelemetryAggregates(since: now).reduce(0) { $0 + $1.attemptCount },
            4
        )

        let encodedTelemetry = String(
            decoding: try JSONEncoder().encode(store.reuseAttempts),
            as: UTF8.self
        )
        XCTAssertFalse(encodedTelemetry.contains(canary))
        XCTAssertFalse(encodedTelemetry.contains(decisionEvent.id.uuidString))
        XCTAssertFalse(encodedTelemetry.contains(active.memoryID.uuidString))

        XCTAssertTrue(store.addUserWords("用户已修订", to: decisionEvent.id))
        let stale = store.revalidateReuseContext(
            contexts.last!,
            at: now.addingTimeInterval(60)
        )
        XCTAssertFalse(stale.references.contains {
            $0.objectID == decisionEvent.id || $0.sourceEventID == decisionEvent.id
        })
        XCTAssertTrue(stale.exclusions.contains(.invalidated))
        let expired = store.revalidateReuseContext(
            contexts[0],
            at: now.addingTimeInterval(903)
        )
        XCTAssertTrue(expired.references.isEmpty)
        XCTAssertTrue(expired.exclusions.contains(.expired))

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(reloaded.reuseAttempts.count, 4)
        XCTAssertEqual(reloaded.reuseOutcomes.count, 4)
        XCTAssertEqual(reloaded.helpfulReuseCount(since: now), 1)
    }

    func testSharedJourneyControllerBuildsResolvedContextAndRecordsFeedback() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeReuseController-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: ReuseTestKeyStore(), rootDirectory: root)
        let now = Date(timeIntervalSince1970: 1_759_100_000)
        _ = store.add(
            title: "Beta 恢复项目",
            detail: "继续验证真实恢复门",
            kind: .text,
            sourceLabel: "测试",
            localDate: now,
            eventType: .milestone
        )
        let controller = ReuseJourneyController()

        controller.start(
            store: store,
            intent: .projectResume,
            query: "Beta 恢复",
            requestedAt: now
        )

        XCTAssertEqual(controller.status, .ready)
        XCTAssertEqual(controller.resolved?.context.intent, .projectResume)
        XCTAssertEqual(controller.resolved?.items.first?.sourceEvent.title, "Beta 恢复项目")
        XCTAssertTrue(
            controller.submitFeedback(
                store: store,
                outcome: .useful,
                submittedAt: now.addingTimeInterval(1)
            )
        )
        XCTAssertEqual(controller.status, .feedbackRecorded)
        XCTAssertEqual(store.helpfulReuseCount(since: now), 1)
    }

    func testProjectAndMeetingScopesAreExplicit() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeReuseScope-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: ReuseTestKeyStore(), rootDirectory: root)
        XCTAssertThrowsError(
            try store.buildReuseContext(
                ReuseRequest(
                    spaceID: "space_personal",
                    intent: .projectResume,
                    requestedAt: .now
                )
            )
        )
        XCTAssertThrowsError(
            try store.buildReuseContext(
                ReuseRequest(
                    spaceID: "space_personal",
                    intent: .preMeetingContext,
                    requestedAt: .now
                )
            )
        )
    }
}
