import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class LongTermMemoryKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key = SymmetricKey(data: Data(repeating: 0x58, count: 32))
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

private struct TestEnvelopeV3: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
}

@MainActor
final class LongTermMemoryTests: XCTestCase {
    func testProposalConfirmationRevisionInvalidationAndNoResurrection() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = LongTermMemoryKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = store.add(
            title: "完成可验证里程碑",
            detail: "生产证据已通过。",
            kind: .text,
            sourceLabel: "测试",
            factStatus: .confirmed,
            eventType: .milestone,
            evidenceState: .observed
        )
        let proposedAt = Date(timeIntervalSince1970: 1_759_000_000)
        let candidate = try store.proposeLongTermMemory(
            proposal(
                event: event,
                type: .fact,
                summary: "该里程碑已完成",
                proposedAt: proposedAt
            )
        )
        XCTAssertEqual(candidate.state, .eligibleForMemoryCompiler)
        XCTAssertTrue(store.visibleLongTermMemories(at: proposedAt).isEmpty)

        let active = try store.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: candidate.memoryID,
                confirmedAt: proposedAt.addingTimeInterval(1)
            )
        )
        XCTAssertEqual(active.state, .active)
        XCTAssertEqual(store.visibleLongTermMemories(at: proposedAt.addingTimeInterval(2)).map(\.memoryID), [active.memoryID])

        XCTAssertTrue(store.addUserWords("修订后必须重新编译", to: event.id))
        XCTAssertEqual(store.longTermMemory(id: active.memoryID)?.state, .invalidated)
        XCTAssertEqual(
            store.longTermMemory(id: active.memoryID)?.invalidationReason,
            .eventRevisionChanged
        )
        XCTAssertTrue(store.visibleLongTermMemories(at: proposedAt.addingTimeInterval(2)).isEmpty)

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(reloaded.longTermMemory(id: active.memoryID)?.state, .invalidated)
        let replay = try reloaded.proposeLongTermMemory(
            proposal(
                event: event,
                type: .fact,
                summary: "不得复活",
                proposedAt: proposedAt.addingTimeInterval(3)
            )
        )
        XCTAssertEqual(replay.memoryID, active.memoryID)
        XCTAssertEqual(replay.state, .invalidated)
    }

    func testInferenceAndSensitiveTypesNeedConfirmationAndDeleteInvalidates() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(
            keyStore: LongTermMemoryKeyStore(),
            rootDirectory: root
        )
        let event = store.add(
            title: "推断偏好",
            detail: "只用于验证边界。",
            kind: .text,
            sourceLabel: "测试 Agent",
            factStatus: .inferred,
            eventType: .experience,
            evidenceState: .inferred
        )
        let proposedAt = Date(timeIntervalSince1970: 1_759_000_100)
        let candidate = try store.proposeLongTermMemory(
            proposal(
                event: event,
                type: .preference,
                summary: "偏好候选",
                proposedAt: proposedAt
            )
        )
        XCTAssertEqual(candidate.state, .candidateUserConfirmationRequired)
        XCTAssertTrue(store.visibleLongTermMemories(at: proposedAt).isEmpty)
        _ = try store.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: candidate.memoryID,
                confirmedAt: proposedAt.addingTimeInterval(1)
            )
        )
        XCTAssertTrue(store.delete(id: event.id))
        XCTAssertEqual(store.longTermMemory(id: candidate.memoryID)?.state, .invalidated)
        XCTAssertEqual(store.longTermMemory(id: candidate.memoryID)?.invalidationReason, .eventDeleted)
    }

    func testExplicitReplacementSupersedesOldMemory() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(
            keyStore: LongTermMemoryKeyStore(),
            rootDirectory: root
        )
        let base = Date(timeIntervalSince1970: 1_759_000_200)
        let firstEvent = store.addText("原决定")!
        let first = try store.proposeLongTermMemory(
            proposal(event: firstEvent, type: .decision, summary: "原决定", proposedAt: base)
        )
        _ = try store.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: first.memoryID,
                confirmedAt: base.addingTimeInterval(1)
            )
        )
        let nextEvent = store.addText("新决定")!
        let next = try store.proposeLongTermMemory(
            proposal(
                event: nextEvent,
                type: .decision,
                summary: "新决定",
                proposedAt: base.addingTimeInterval(2)
            )
        )
        let replacement = try store.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: next.memoryID,
                confirmedAt: base.addingTimeInterval(3),
                supersedesMemoryID: first.memoryID
            )
        )
        XCTAssertEqual(store.longTermMemory(id: first.memoryID)?.state, .superseded)
        XCTAssertEqual(store.longTermMemory(id: first.memoryID)?.supersededByMemoryID, replacement.memoryID)
        XCTAssertEqual(store.visibleLongTermMemories(at: base.addingTimeInterval(4)).map(\.memoryID), [replacement.memoryID])
    }

    func testV2EnvelopeMigratesAndInvalidV3FailsClosed() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let keyStore = LongTermMemoryKeyStore()
        let event = MemoryEvent(
            id: UUID(uuidString: "00000000-0000-4000-8000-000000000081")!,
            title: "v2 event",
            detail: "legacy envelope",
            factStatus: .confirmed,
            sourceLabel: "legacy",
            captureKind: .text,
            evidenceState: .observed
        )
        let eventObjects = try JSONSerialization.jsonObject(with: JSONEncoder().encode([event]))
        let v2 = try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 2,
            "events": eventObjects,
            "coverageDays": [],
            "coverageEventLinks": [],
        ])
        let encrypted = try AES.GCM.seal(v2, using: keyStore.key).combined!
        let file = root.appendingPathComponent("events.enc")
        try encrypted.write(to: file, options: .atomic)

        let migrated = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(migrated.events.map(\.id), [event.id])
        let candidate = try migrated.proposeLongTermMemory(
            proposal(
                event: event,
                type: .fact,
                summary: "v2 migrated",
                proposedAt: Date(timeIntervalSince1970: 1_759_000_300)
            )
        )
        XCTAssertEqual(candidate.state, .eligibleForMemoryCompiler)
        XCTAssertEqual(
            LocalMemoryStore(keyStore: keyStore, rootDirectory: root).longTermMemories.count,
            1
        )

        let invalidRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: invalidRoot) }
        try FileManager.default.createDirectory(at: invalidRoot, withIntermediateDirectories: true)
        var invalid = candidate
        invalid.state = .active
        invalid.confirmedAt = Date(timeIntervalSince1970: 1_759_000_301)
        invalid.revision = 2
        invalid.revisions.append(
            LongTermMemoryRevision(
                revision: 2,
                reason: .userConfirm,
                state: .active,
                changedAt: invalid.confirmedAt!
            )
        )
        let invalidEnvelope = TestEnvelopeV3(
            schemaVersion: 3,
            events: [],
            coverageDays: [],
            coverageEventLinks: [],
            longTermMemories: [invalid]
        )
        let invalidClear = try JSONEncoder().encode(invalidEnvelope)
        let invalidEncrypted = try AES.GCM.seal(invalidClear, using: keyStore.key).combined!
        let invalidFile = invalidRoot.appendingPathComponent("events.enc")
        try invalidEncrypted.write(to: invalidFile, options: .atomic)
        let before = try Data(contentsOf: invalidFile)

        let failed = LocalMemoryStore(keyStore: keyStore, rootDirectory: invalidRoot)
        XCTAssertEqual(failed.storageState, .recoverableError)
        XCTAssertTrue(failed.longTermMemories.isEmpty)
        XCTAssertEqual(try Data(contentsOf: invalidFile), before)
    }

    private func proposal(
        event: MemoryEvent,
        type: LongTermMemoryType,
        summary: String,
        proposedAt: Date
    ) -> LongTermMemoryProposal {
        LongTermMemoryProposal(
            ownerID: "local_owner",
            spaceID: "space_personal",
            sourceEventID: event.id,
            expectedEventRevision: event.revision,
            type: type,
            valueSummary: summary,
            validFrom: proposedAt,
            proposedAt: proposedAt
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeLongTermMemory-\(UUID().uuidString)", isDirectory: true)
    }
}
