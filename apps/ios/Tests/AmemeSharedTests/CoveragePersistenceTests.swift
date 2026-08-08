import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class CoveragePersistenceKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key = SymmetricKey(data: Data(repeating: 0x47, count: 32))
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class CoveragePersistenceTests: XCTestCase {
    func testCoveragePersistsWithoutEventAndAcceptanceDeleteCannotResurrect() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = CoveragePersistenceKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let compilation = try calendarCompilation()

        XCTAssertTrue(
            store.persistCoverageCompilation(
                compilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:00:00Z"
            )
        )
        XCTAssertTrue(store.events.isEmpty)
        XCTAssertEqual(store.coverageDay(id: "day_persist_001")?.candidates.first?.state, .open)

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertTrue(reloaded.events.isEmpty)
        XCTAssertEqual(reloaded.coverageDay(id: "day_persist_001")?.observations.count, 1)
        let event = try reloaded.acceptCoverageCandidate(
            CoverageCandidateAcceptance(
                dayID: "day_persist_001",
                candidateID: "candidate_signal_persist_001",
                detail: "用户选择保存该日历计划。",
                sourceLabel: "用户选择的日历",
                captureKind: .importFile,
                localDate: Date(timeIntervalSince1970: 1_758_441_600),
                time: Date(timeIntervalSince1970: 1_758_477_600),
                sensitivity: .confidential,
                mode: .preserveEvidence,
                linkedAt: "2026-07-26T08:01:00Z"
            )
        )
        XCTAssertEqual(event.factStatus, .planned)
        XCTAssertEqual(reloaded.coverageDay(id: "day_persist_001")?.candidates.first?.state, .consumed)
        XCTAssertEqual(reloaded.coverageEventLinks.first?.state, .active)

        XCTAssertTrue(reloaded.persistCoverageCompilation(
            compilation,
            registryVersion: "mobile-v1",
            compiledAt: "2026-07-26T08:02:00Z"
        ))
        XCTAssertEqual(reloaded.coverageDay(id: "day_persist_001")?.candidates.first?.state, .consumed)
        XCTAssertTrue(reloaded.delete(id: event.id))
        XCTAssertEqual(reloaded.coverageEventLinks.first?.state, .detached)

        let afterDelete = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertNil(afterDelete.event(id: event.id))
        XCTAssertEqual(afterDelete.coverageDay(id: "day_persist_001")?.candidates.first?.state, .consumed)
        XCTAssertEqual(afterDelete.coverageEventLinks.first?.state, .detached)
    }

    func testStatusOnlyCompilationPersistsWithoutCandidateOrEvidenceObjects() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(
            keyStore: CoveragePersistenceKeyStore(),
            rootDirectory: root
        )
        let registry = try MobileSourceCapabilities.makeRegistry()
        let signal = try MobileSourceStatusSignalFactory.makeSignal(
            registry: registry,
            signalID: "signal_denied_001",
            capabilityID: "cap_calendar",
            state: .notAuthorized,
            contextTypes: [.timeSchedule],
            observedAt: "2026-07-26T08:00:00Z"
        )
        let compilation = try CoverageCompiler(registry: registry).compileDay(
            dayID: "day_denied_001",
            ownerID: "owner_001",
            spaceID: "space_personal",
            localDate: "2026-07-26",
            timezone: "Asia/Shanghai",
            signals: [signal]
        )
        XCTAssertTrue(store.persistCoverageCompilation(
            compilation,
            registryVersion: "mobile-v1",
            compiledAt: "2026-07-26T08:00:01Z"
        ))
        let persisted = try XCTUnwrap(store.coverageDay(id: "day_denied_001"))
        XCTAssertEqual(persisted.coverageState, .partial)
        XCTAssertTrue(persisted.candidates.isEmpty)
        XCTAssertTrue(persisted.observations.first?.sourceObjectIDs.isEmpty == true)
        XCTAssertTrue(store.events.isEmpty)
    }

    func testCoverageMutationRollsBackWhenEnvelopeWriteFails() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(
            keyStore: CoveragePersistenceKeyStore(),
            rootDirectory: root
        )
        XCTAssertTrue(store.persistCoverageCompilation(
            try calendarCompilation(),
            registryVersion: "mobile-v1",
            compiledAt: "2026-07-26T08:00:00Z"
        ))
        let file = root.appendingPathComponent("events.enc")
        try FileManager.default.removeItem(at: file)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: true)

        XCTAssertFalse(store.dismissCoverageCandidate(
            dayID: "day_persist_001",
            candidateID: "candidate_signal_persist_001",
            updatedAt: "2026-07-26T08:05:00Z"
        ))
        XCTAssertEqual(store.coverageDay(id: "day_persist_001")?.candidates.first?.state, .open)
        XCTAssertEqual(store.storageState, .recoverableError)
    }

    func testLegacyEncryptedEventArrayLoadsAndMigratesOnNextWrite() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let keyStore = CoveragePersistenceKeyStore()
        let legacy = MemoryEvent(
            id: UUID(uuidString: "00000000-0000-4000-8000-000000000071")!,
            title: "legacy event",
            detail: "legacy encrypted array",
            sourceLabel: "legacy",
            captureKind: .text
        )
        let clear = try JSONEncoder().encode([legacy])
        let encrypted = try AES.GCM.seal(clear, using: keyStore.key).combined!
        try encrypted.write(to: root.appendingPathComponent("events.enc"), options: .atomic)

        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(store.events.map(\.id), [legacy.id])
        XCTAssertTrue(store.coverageDays.isEmpty)
        XCTAssertNotNil(store.addText("触发 v2 envelope 迁移"))

        let reopened = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(reopened.events.count, 2)
        XCTAssertEqual(reopened.storageState, .ready)
    }

    func testUnknownEnvelopeSchemaFailsClosedWithoutOverwrite() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let keyStore = CoveragePersistenceKeyStore()
        let clear = try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 999,
            "events": [],
            "coverageDays": [],
            "coverageEventLinks": [],
        ])
        let encrypted = try AES.GCM.seal(clear, using: keyStore.key).combined!
        let file = root.appendingPathComponent("events.enc")
        try encrypted.write(to: file, options: .atomic)
        let before = try Data(contentsOf: file)

        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(store.storageState, .recoverableError)
        XCTAssertTrue(store.events.isEmpty)
        XCTAssertEqual(try Data(contentsOf: file), before)
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeCoveragePersistence-\(UUID().uuidString)", isDirectory: true)
    }

    private func calendarCompilation() throws -> CoverageCompilation {
        let registry = try MobileSourceCapabilities.makeRegistry()
        let signal = CoverageSignal(
            signalID: "signal_persist_001",
            capabilityID: "cap_calendar",
            sourceState: .available,
            contextTypes: [.timeSchedule],
            observedFields: [.time, .action],
            factStatus: .planned,
            importance: .medium,
            confidence: 1,
            sourceObjectIDs: ["source_calendar_001"],
            observedAt: "2026-07-26T08:00:00Z",
            timeRange: CoverageTimeRange(
                start: "2026-07-26T10:00:00Z",
                end: "2026-07-26T11:00:00Z",
                timezone: "Asia/Shanghai",
                precision: .range
            ),
            eventHint: CoverageEventHint(eventType: .activity, title: "用户选择的日历计划"),
            gapHints: [
                CoverageGapHint(
                    contextType: .activityResult,
                    reason: .actualityUnconfirmed,
                    valueLevel: .high
                ),
            ]
        )
        return try CoverageCompiler(registry: registry).compileDay(
            dayID: "day_persist_001",
            ownerID: "owner_001",
            spaceID: "space_personal",
            localDate: "2026-07-26",
            timezone: "Asia/Shanghai",
            signals: [signal]
        )
    }
}
