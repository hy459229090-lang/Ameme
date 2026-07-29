import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class LocalSpaceDeletionKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key = SymmetricKey(data: Data(repeating: 0x71, count: 32))
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class LocalSpaceDeletionTests: XCTestCase {
    func testLocalSpaceDeleteRemovesOwnedRawFreezesWritesAndRejectsOldBackup() throws {
        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let root = sandbox.appendingPathComponent("source", isDirectory: true)
        let keyStore = LocalSpaceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let owned = store.addMedia(
            Data("space-delete-owned-raw".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let ownedLocator = try XCTUnwrap(owned.sourceLocator)
        let external = try XCTUnwrap(store.addPhotoReference("space-delete-external"))
        let oldBackup = sandbox.appendingPathComponent("old-backup", isDirectory: true)
        _ = try store.createLocalRecoveryBackup(at: oldBackup)

        let result = store.deleteLocalSpace(
            requestedAt: Date(timeIntervalSince1970: 1_759_100_500)
        )

        XCTAssertEqual(result.status, .completedLocalOnly)
        XCTAssertEqual(result.affectedEventCount, 2)
        XCTAssertEqual(result.affectedSourceCount, 2)
        XCTAssertEqual(result.pendingRawCleanupCount, 0)
        XCTAssertTrue(result.externalOriginalsRetained)
        XCTAssertFalse(result.accountDeletionClaim)
        XCTAssertFalse(result.peerDeletionProofClaim)
        XCTAssertTrue(store.isLocalSpaceDeleted)
        XCTAssertEqual(store.storageState, .deleted)
        XCTAssertTrue(store.events.isEmpty)
        XCTAssertTrue(store.coverageDays.isEmpty)
        XCTAssertTrue(store.longTermMemories.isEmpty)
        XCTAssertTrue(store.reuseAttempts.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ownedLocator))
        XCTAssertTrue(store.sourceObjects.allSatisfy {
            $0.state == .deleted && $0.sourceLocator == nil
        })
        XCTAssertNil(store.event(id: owned.id))
        XCTAssertNil(store.event(id: external.id))
        XCTAssertNil(store.addText("删除后不得重新写入"))
        XCTAssertTrue(store.events.isEmpty)
        XCTAssertThrowsError(
            try store.createLocalRecoveryBackup(
                at: sandbox.appendingPathComponent("forbidden-backup", isDirectory: true)
            )
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .sourceStoreUnavailable)
        }
        XCTAssertThrowsError(
            try store.restoreLocalRecoveryCandidate(
                from: oldBackup,
                to: sandbox.appendingPathComponent("old-candidate", isDirectory: true)
            )
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .snapshotPredatesDeletion)
        }

        let reloaded = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        XCTAssertTrue(reloaded.isLocalSpaceDeleted)
        XCTAssertEqual(reloaded.storageState, .deleted)
        XCTAssertTrue(reloaded.events.isEmpty)
        XCTAssertNil(reloaded.addText("重启后不得复活"))
        XCTAssertEqual(
            reloaded.deleteLocalSpace().status,
            .alreadyDeleted
        )
    }

    func testRootPersistenceFailureRollsBackEveryInMemoryProjection() throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let keyStore = LocalSpaceDeletionKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: root)
        let event = try XCTUnwrap(store.addText("根水位失败时必须保留"))
        store.persistenceFailureInjector = { true }

        let result = store.deleteLocalSpace()

        XCTAssertEqual(result.status, .persistenceFailed)
        XCTAssertFalse(store.isLocalSpaceDeleted)
        XCTAssertEqual(store.event(id: event.id)?.id, event.id)
        XCTAssertEqual(store.storageState, .recoverableError)

        store.persistenceFailureInjector = nil
        store.retryLoad()
        XCTAssertEqual(store.storageState, .ready)
        XCTAssertEqual(store.event(id: event.id)?.id, event.id)
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeLocalSpaceDeletion-\(UUID().uuidString)", isDirectory: true)
    }
}
