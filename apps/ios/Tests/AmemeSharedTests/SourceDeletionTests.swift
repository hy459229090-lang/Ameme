import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class SourceDeletionKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key = SymmetricKey(data: Data(repeating: 0x5D, count: 32))
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class SourceDeletionTests: XCTestCase {
    func testRawOnlyDeletesOwnedCiphertextAndPreservesRevisedEvent() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = store.addMedia(Data("owned-raw".utf8), kind: .voice, fileExtension: "m4a")
        let locator = try XCTUnwrap(event.sourceLocator)
        let source = try XCTUnwrap(store.sourceObjects(for: event.id).first)

        let result = store.deleteRawOnly(sourceObjectID: source.sourceObjectID)

        XCTAssertEqual(result.status, .completed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: locator))
        XCTAssertNil(store.event(id: event.id)?.sourceLocator)
        XCTAssertEqual(store.event(id: event.id)?.revision, 2)
        XCTAssertEqual(store.sourceObjects(for: event.id).first?.rawState, .deleted)

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertNotNil(reloaded.event(id: event.id))
        XCTAssertEqual(reloaded.sourceObjects(for: event.id).first?.rawState, .deleted)
    }

    func testExternalOriginalIsNotClaimedOrMutated() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: SourceDeletionKeyStore(), rootDirectory: root)
        let event = try XCTUnwrap(store.addPhotoReference("external-photo"))
        let source = try XCTUnwrap(store.sourceObjects(for: event.id).first)

        let result = store.deleteRawOnly(sourceObjectID: source.sourceObjectID)

        XCTAssertEqual(result.status, .externalNotOwned)
        XCTAssertEqual(store.event(id: event.id)?.sourceLocator, "photos://external-photo")
        XCTAssertEqual(store.event(id: event.id)?.revision, 1)

        let cascade = store.deleteSourceCascade(sourceObjectID: source.sourceObjectID)
        XCTAssertEqual(cascade.status, .completedLocalOnly)
        XCTAssertNil(store.event(id: event.id))
        XCTAssertEqual(
            store.sourceObjects.first { $0.sourceObjectID == source.sourceObjectID }?.sourceLocator,
            "photos://external-photo"
        )
    }

    func testSingleSourceCascadePersistsSourceWatermarkAndNoResurrection() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = try XCTUnwrap(store.addPhotoReference("cascade-photo"))
        let source = try XCTUnwrap(store.sourceObjects(for: event.id).first)

        let result = store.deleteSourceCascade(sourceObjectID: source.sourceObjectID)

        XCTAssertEqual(result.status, .completed)
        XCTAssertEqual(result.affectedEventCount, 1)
        XCTAssertNil(store.event(id: event.id))
        XCTAssertTrue(store.deletionTombstones.contains {
            $0.objectType == DeletionTombstone.sourceObjectType &&
                $0.objectID == source.sourceObjectID
        })

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertNil(reloaded.event(id: event.id))
        XCTAssertEqual(
            reloaded.sourceObjects.first {
                $0.sourceObjectID == source.sourceObjectID
            }?.state,
            .deleted
        )
    }

    func testMultiSourceCascadeFailsClosedWithoutMutatingEvent() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: SourceDeletionKeyStore(), rootDirectory: root)
        let event = try makeMultiSourceEvent(store: store)

        let result = store.deleteSourceCascade(sourceObjectID: "source_multi_a")

        XCTAssertEqual(result.status, .lineageUnavailable)
        XCTAssertNotNil(store.event(id: event.id))
        XCTAssertTrue(store.sourceObjects(for: event.id).allSatisfy { $0.state == .active })
    }

    func testCompleteFieldEvidenceRecomputesOnlyWhenEveryFieldStillHasSupport() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = try makeMultiSourceEvent(
            store: store,
            fieldSourceObjectIDs: [
                .time: ["source_multi_a", "source_multi_b"],
                .action: ["source_multi_a", "source_multi_b"],
            ]
        )

        let result = store.deleteSourceCascade(sourceObjectID: "source_multi_a")

        XCTAssertEqual(result.status, .completed)
        XCTAssertEqual(result.affectedEventCount, 1)
        XCTAssertEqual(result.recomputedEventCount, 1)
        XCTAssertEqual(result.deletedEventCount, 0)
        XCTAssertEqual(store.event(id: event.id)?.revision, 2)
        XCTAssertEqual(store.event(id: event.id)?.sourceLabel, "多来源（已重算）")
        XCTAssertNil(store.event(id: event.id)?.sourceLocator)
        XCTAssertEqual(
            Set(store.eventFieldEvidence.filter {
                $0.eventID == event.id && $0.state == .active
            }.map(\.sourceObjectID)),
            ["source_multi_b"]
        )

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(reloaded.event(id: event.id)?.revision, 2)
        XCTAssertEqual(reloaded.event(id: event.id)?.sourceLabel, "多来源（已重算）")
        XCTAssertEqual(
            reloaded.sourceObjects.first {
                $0.sourceObjectID == "source_multi_a"
            }?.state,
            .deleted
        )
        XCTAssertEqual(
            Set(reloaded.eventFieldEvidence.filter {
                $0.eventID == event.id && $0.state == .active
            }.map(\.sourceObjectID)),
            ["source_multi_b"]
        )
    }

    func testCompleteUserConfirmationPreservesSingleSourceEventWithoutSourceClaim() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let fields: [EvidenceField: Set<String>] = [
            .time: ["source_user_confirmed"],
            .action: ["source_user_confirmed"],
        ]
        let event = try makeMultiSourceEvent(
            store: store,
            sourceObjectIDs: ["source_user_confirmed"],
            mode: .userConfirmed,
            fieldSourceObjectIDs: fields
        )
        let original = try XCTUnwrap(store.eventUserConfirmations.first)

        XCTAssertEqual(original.kind, .coverageAcceptance)
        XCTAssertEqual(Set(original.confirmedFields), [.time, .action])
        XCTAssertTrue(original.completeFieldSet)
        XCTAssertEqual(original.state, .active)

        let result = store.deleteSourceCascade(sourceObjectID: "source_user_confirmed")

        XCTAssertEqual(result.status, .completed)
        XCTAssertEqual(result.recomputedEventCount, 1)
        XCTAssertEqual(result.deletedEventCount, 0)
        XCTAssertEqual(store.event(id: event.id)?.revision, 2)
        XCTAssertEqual(store.event(id: event.id)?.sourceLabel, "用户确认（来源已删除）")
        XCTAssertTrue(store.eventUserConfirmations.contains {
            $0.confirmationID == original.confirmationID &&
                $0.eventRevision == 1 &&
                $0.state == .deleted
        })
        XCTAssertTrue(store.eventUserConfirmations.contains {
            $0.confirmationID == original.confirmationID &&
                $0.eventRevision == 2 &&
                $0.state == .active &&
                $0.completeFieldSet
        })

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertEqual(reloaded.event(id: event.id)?.revision, 2)
        XCTAssertEqual(
            reloaded.sourceObjects.first {
                $0.sourceObjectID == "source_user_confirmed"
            }?.state,
            .deleted
        )
        XCTAssertTrue(reloaded.eventUserConfirmations.contains {
            $0.confirmationID == original.confirmationID &&
                $0.eventRevision == 2 &&
                $0.state == .active
        })
    }

    func testPartialConfirmationIsAuditedButCannotPreserveCapturedEvent() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: SourceDeletionKeyStore(), rootDirectory: root)
        let event = try XCTUnwrap(store.addPhotoReference("partial-confirmation"))

        XCTAssertTrue(store.updateFactStatus(.processing, to: event.id))
        XCTAssertTrue(store.updateFactStatus(.confirmed, to: event.id))
        let confirmation = try XCTUnwrap(store.eventUserConfirmations.first)
        XCTAssertEqual(confirmation.kind, .factStatusConfirmation)
        XCTAssertFalse(confirmation.completeFieldSet)

        let source = try XCTUnwrap(store.sourceObjects(for: event.id).first)
        let result = store.deleteSourceCascade(sourceObjectID: source.sourceObjectID)

        XCTAssertEqual(result.status, .completedLocalOnly)
        XCTAssertEqual(result.recomputedEventCount, 0)
        XCTAssertEqual(result.deletedEventCount, 1)
        XCTAssertNil(store.event(id: event.id))
        XCTAssertTrue(store.eventUserConfirmations.allSatisfy { $0.state == .deleted })
    }

    func testFieldLosingItsFinalSourceDeletesEventInsteadOfRetainingContent() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: SourceDeletionKeyStore(), rootDirectory: root)
        let event = try makeMultiSourceEvent(
            store: store,
            fieldSourceObjectIDs: [
                .time: ["source_multi_b"],
                .action: ["source_multi_a"],
            ]
        )

        let result = store.deleteSourceCascade(sourceObjectID: "source_multi_a")

        XCTAssertEqual(result.status, .completed)
        XCTAssertEqual(result.recomputedEventCount, 0)
        XCTAssertEqual(result.deletedEventCount, 1)
        XCTAssertNil(store.event(id: event.id))
        XCTAssertTrue(store.eventFieldEvidence.filter {
            $0.eventID == event.id
        }.allSatisfy { $0.state == .deleted })
    }

    func testUserRevisionMakesExactFieldEvidenceStaleAndCascadeFailsClosed() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalMemoryStore(keyStore: SourceDeletionKeyStore(), rootDirectory: root)
        let event = try makeMultiSourceEvent(
            store: store,
            fieldSourceObjectIDs: [
                .time: ["source_multi_a", "source_multi_b"],
                .action: ["source_multi_a", "source_multi_b"],
            ]
        )
        XCTAssertTrue(store.addUserWords("用户补充后的内容不再由旧证据逐字段证明。", to: event.id))

        let result = store.deleteSourceCascade(sourceObjectID: "source_multi_a")

        XCTAssertEqual(result.status, .lineageUnavailable)
        XCTAssertEqual(store.event(id: event.id)?.revision, 2)
        XCTAssertTrue(store.sourceObjects(for: event.id).allSatisfy { $0.state == .active })
    }

    func testV7EnvelopePreservesFieldEvidenceAndMigratesWithoutGuessingConfirmation() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = try makeMultiSourceEvent(
            store: store,
            sourceObjectIDs: ["source_v7_only"]
        )
        XCTAssertEqual(store.eventFieldEvidence.filter {
            $0.eventID == event.id && $0.state == .active
        }.count, 2)
        XCTAssertTrue(store.eventUserConfirmations.isEmpty)

        let file = root.appendingPathComponent("events.enc")
        let encrypted = try Data(contentsOf: file)
        let clear = try AES.GCM.open(
            AES.GCM.SealedBox(combined: encrypted),
            using: keyStore.key
        )
        var legacy = try XCTUnwrap(
            JSONSerialization.jsonObject(with: clear) as? [String: Any]
        )
        legacy["schemaVersion"] = 7
        legacy.removeValue(forKey: "eventUserConfirmations")
        let legacyClear = try JSONSerialization.data(
            withJSONObject: legacy,
            options: [.sortedKeys]
        )
        let legacyEncrypted = try XCTUnwrap(
            AES.GCM.seal(legacyClear, using: keyStore.key).combined
        )
        try legacyEncrypted.write(to: file, options: .atomic)

        let migrated = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)

        XCTAssertEqual(migrated.storageState, .ready)
        XCTAssertEqual(migrated.event(id: event.id)?.revision, 1)
        XCTAssertEqual(migrated.eventFieldEvidence.filter {
            $0.eventID == event.id && $0.state == .active
        }.count, 2)
        XCTAssertTrue(migrated.eventUserConfirmations.isEmpty)
        let migratedClear = try AES.GCM.open(
            AES.GCM.SealedBox(combined: Data(contentsOf: file)),
            using: keyStore.key
        )
        let migratedJSON = try XCTUnwrap(
            JSONSerialization.jsonObject(with: migratedClear) as? [String: Any]
        )
        XCTAssertEqual(migratedJSON["schemaVersion"] as? Int, 8)
        XCTAssertEqual(
            (migratedJSON["eventUserConfirmations"] as? [Any])?.count,
            0
        )
    }

    private func makeMultiSourceEvent(
        store: LocalMemoryStore,
        sourceObjectIDs: Set<String> = ["source_multi_a", "source_multi_b"],
        mode: CoverageAcceptanceMode = .preserveEvidence,
        fieldSourceObjectIDs: [EvidenceField: Set<String>] = [:]
    ) throws -> MemoryEvent {
        let registry = try MobileSourceCapabilities.makeRegistry()
        let signal = CoverageSignal(
            signalID: "signal_multi_source",
            capabilityID: "cap_calendar",
            sourceState: .available,
            contextTypes: [.timeSchedule],
            observedFields: [.time, .action],
            factStatus: .planned,
            importance: .high,
            confidence: 1,
            sourceObjectIDs: sourceObjectIDs,
            observedAt: "2026-07-26T08:00:00Z",
            timeRange: CoverageTimeRange(
                start: "2026-07-26T10:00:00Z",
                end: "2026-07-26T11:00:00Z",
                timezone: "Asia/Shanghai",
                precision: .range
            ),
            eventHint: CoverageEventHint(eventType: .activity, title: "多来源计划"),
            gapHints: []
        )
        let compilation = try CoverageCompiler(registry: registry).compileDay(
            dayID: "day_multi_source",
            ownerID: "owner_multi_source",
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
        let event = try store.acceptCoverageCandidate(
            CoverageCandidateAcceptance(
                dayID: "day_multi_source",
                candidateID: "candidate_signal_multi_source",
                detail: "多来源候选需要字段级 provenance。",
                sourceLabel: "测试来源",
                captureKind: .importFile,
                localDate: Date(timeIntervalSince1970: 1_758_441_600),
                time: nil,
                mode: mode,
                fieldSourceObjectIDs: fieldSourceObjectIDs,
                linkedAt: "2026-07-26T08:01:00Z"
            )
        )
        return event
    }

    func testCascadeConvergesAfterFinalPersistenceFailureAndReload() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = SourceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = store.addMedia(
            Data("retry-owned-raw".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let locator = try XCTUnwrap(event.sourceLocator)
        let source = try XCTUnwrap(store.sourceObjects(for: event.id).first)
        var persistenceAttempt = 0
        store.persistenceFailureInjector = {
            persistenceAttempt += 1
            return persistenceAttempt == 2
        }

        let result = store.deleteSourceCascade(sourceObjectID: source.sourceObjectID)

        XCTAssertEqual(result.status, .pendingCleanup)
        XCTAssertNil(store.event(id: event.id))
        XCTAssertFalse(FileManager.default.fileExists(atPath: locator))
        XCTAssertEqual(
            store.sourceObjects.first { $0.sourceObjectID == source.sourceObjectID }?.state,
            .deleted
        )
        XCTAssertEqual(
            store.sourceObjects.first { $0.sourceObjectID == source.sourceObjectID }?.rawState,
            .pendingCleanup
        )

        store.persistenceFailureInjector = nil
        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertNil(reloaded.event(id: event.id))
        let converged = try XCTUnwrap(
            reloaded.sourceObjects.first { $0.sourceObjectID == source.sourceObjectID }
        )
        XCTAssertEqual(converged.state, .deleted)
        XCTAssertEqual(converged.rawState, .deleted)
        XCTAssertNil(converged.sourceLocator)
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeSourceDeletionTests-\(UUID().uuidString)", isDirectory: true)
    }
}
