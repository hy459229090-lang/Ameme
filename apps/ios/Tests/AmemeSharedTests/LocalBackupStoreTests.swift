import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class LocalBackupTestKeyStore: @unchecked Sendable, KeyMaterialStore {
    let key: SymmetricKey

    init(byte: UInt8 = 0x6A) {
        key = SymmetricKey(data: Data(repeating: byte, count: 32))
    }

    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class LocalBackupStoreTests: XCTestCase {
    func testVerifiedBackupRestoresToNewRootAndPreservesDeletionTombstone() throws {
        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let sourceRoot = sandbox.appendingPathComponent("source", isDirectory: true)
        let keyStore = LocalBackupTestKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: sourceRoot)

        let deleted = store.addText("必须保持删除")!
        let mediaPayload = Data("encrypted-recovery-media".utf8)
        let survivingMedia = store.addMedia(mediaPayload, kind: .voice, fileExtension: "m4a")
        XCTAssertNotNil(survivingMedia.sourceLocator)
        XCTAssertTrue(store.delete(id: deleted.id))
        XCTAssertEqual(store.deletionTombstones.count, 1)

        let backup = sandbox.appendingPathComponent("backup", isDirectory: true)
        let manifest = try store.createLocalRecoveryBackup(
            at: backup,
            createdAt: Date(timeIntervalSince1970: 1_759_100_000)
        )
        XCTAssertFalse(manifest.productionRecoveryClaim)
        XCTAssertEqual(manifest.keyMaterialState, LocalBackupManifest.keyMaterialState)
        XCTAssertEqual(manifest.media.count, 1)
        XCTAssertEqual(try store.verifyLocalRecoveryBackup(at: backup), manifest)

        let candidateRoot = sandbox.appendingPathComponent("candidate", isDirectory: true)
        let candidate = try store.restoreLocalRecoveryCandidate(
            from: backup,
            to: candidateRoot
        )
        XCTAssertFalse(candidate.productionRecoveryClaim)
        let restored = LocalMemoryStore(keyStore: keyStore, rootDirectory: candidateRoot)
        XCTAssertEqual(restored.storageState, .ready)
        XCTAssertNil(restored.event(id: deleted.id))
        XCTAssertEqual(
            restored.deletionTombstones.first?.tombstoneDigest,
            store.deletionTombstones.first?.tombstoneDigest
        )
        let restoredMedia = try XCTUnwrap(restored.event(id: survivingMedia.id))
        let locator = try XCTUnwrap(restoredMedia.sourceLocator)
        XCTAssertTrue(locator.hasPrefix(candidateRoot.path))
        let encrypted = try Data(contentsOf: URL(fileURLWithPath: locator))
        XCTAssertFalse(String(decoding: encrypted, as: UTF8.self).contains("encrypted-recovery-media"))
    }

    func testOldBackupCorruptionWrongKeyAndNonemptyTargetFailClosed() throws {
        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let sourceRoot = sandbox.appendingPathComponent("source", isDirectory: true)
        let keyStore = LocalBackupTestKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: sourceRoot)
        let event = store.addText("删除水位必须阻止旧快照复活")!

        let oldBackup = sandbox.appendingPathComponent("old-backup", isDirectory: true)
        _ = try store.createLocalRecoveryBackup(at: oldBackup)
        XCTAssertTrue(store.delete(id: event.id))
        XCTAssertThrowsError(
            try store.restoreLocalRecoveryCandidate(
                from: oldBackup,
                to: sandbox.appendingPathComponent("old-candidate", isDirectory: true)
            )
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .snapshotPredatesDeletion)
        }

        let corrupted = sandbox.appendingPathComponent("corrupted", isDirectory: true)
        try FileManager.default.copyItem(at: oldBackup, to: corrupted)
        let ciphertext = corrupted.appendingPathComponent("events.enc")
        var bytes = try Data(contentsOf: ciphertext)
        bytes[bytes.startIndex] ^= 0x01
        try bytes.write(to: ciphertext, options: .atomic)
        XCTAssertThrowsError(try store.verifyLocalRecoveryBackup(at: corrupted))

        let wrongStore = LocalMemoryStore(
            keyStore: LocalBackupTestKeyStore(byte: 0x7B),
            rootDirectory: sandbox.appendingPathComponent("wrong-key-source", isDirectory: true)
        )
        XCTAssertThrowsError(try wrongStore.verifyLocalRecoveryBackup(at: oldBackup)) {
            XCTAssertEqual($0 as? LocalBackupError, .authenticationFailed)
        }

        let currentBackup = sandbox.appendingPathComponent("current-backup", isDirectory: true)
        _ = try store.createLocalRecoveryBackup(at: currentBackup)
        let nonempty = sandbox.appendingPathComponent("nonempty", isDirectory: true)
        try FileManager.default.createDirectory(at: nonempty, withIntermediateDirectories: true)
        let sentinel = nonempty.appendingPathComponent("keep.txt")
        try Data("keep".utf8).write(to: sentinel)
        XCTAssertThrowsError(
            try store.restoreLocalRecoveryCandidate(from: currentBackup, to: nonempty)
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .destinationExists)
        }
        XCTAssertEqual(try String(contentsOf: sentinel, encoding: .utf8), "keep")
    }

    func testExactAuthorizationActivatesCandidateAndRewritesOwnedMediaToLiveRoot() throws {
        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let liveRoot = sandbox.appendingPathComponent("live", isDirectory: true)
        let keyStore = LocalBackupTestKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: liveRoot)
        let backedUp = store.addText("来自已验证候选")!
        let media = store.addMedia(
            Data("candidate-owned-media".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let backup = sandbox.appendingPathComponent("backup", isDirectory: true)
        let manifest = try store.createLocalRecoveryBackup(
            at: backup,
            createdAt: Date(timeIntervalSince1970: 1_759_100_000)
        )
        let liveOnly = store.addText("只存在于切换前 live")!
        let candidate = try store.restoreLocalRecoveryCandidate(
            from: backup,
            to: sandbox.appendingPathComponent("candidate", isDirectory: true)
        )
        let confirmationID = LocalRecoveryActivationAuthorization.confirmationPrefix +
            UUID().uuidString.lowercased()
        let activatedAt = Date(timeIntervalSince1970: 1_759_100_120)
        let receipt = try store.activateLocalRecoveryCandidate(
            candidate,
            authorization: LocalRecoveryActivationAuthorization(
                confirmationID: confirmationID,
                candidateBackupID: manifest.backupID,
                confirmedAt: activatedAt.addingTimeInterval(-30),
                expiresAt: activatedAt.addingTimeInterval(300)
            ),
            activatedAt: activatedAt
        )

        XCTAssertEqual(receipt.confirmationID, confirmationID)
        XCTAssertEqual(receipt.candidateBackupID, manifest.backupID)
        XCTAssertFalse(receipt.cleanupPending)
        XCTAssertFalse(receipt.productionRecoveryClaim)
        XCTAssertNotNil(store.event(id: backedUp.id))
        XCTAssertNil(store.event(id: liveOnly.id))
        let restoredMedia = try XCTUnwrap(store.event(id: media.id))
        let locator = try XCTUnwrap(restoredMedia.sourceLocator)
        XCTAssertTrue(locator.hasPrefix(liveRoot.path + "/media/"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: locator))
        XCTAssertTrue(FileManager.default.fileExists(atPath: candidate.directory.path))
    }

    func testActivationFailureRollsBackLiveAndExpiredAuthorizationDoesNotMutate() throws {
        enum InjectedFailure: Error { case afterCandidateMove }

        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let liveRoot = sandbox.appendingPathComponent("live", isDirectory: true)
        let keyStore = LocalBackupTestKeyStore()
        let store = LocalMemoryStore(keyStore: keyStore, rootDirectory: liveRoot)
        _ = store.addText("候选版本")
        let backup = sandbox.appendingPathComponent("backup", isDirectory: true)
        let manifest = try store.createLocalRecoveryBackup(at: backup)
        let liveOnly = store.addText("失败后必须保留")!
        let candidate = try store.restoreLocalRecoveryCandidate(
            from: backup,
            to: sandbox.appendingPathComponent("candidate", isDirectory: true)
        )
        let activatedAt = Date(timeIntervalSince1970: 1_759_100_120)
        let confirmationID = LocalRecoveryActivationAuthorization.confirmationPrefix +
            UUID().uuidString.lowercased()

        XCTAssertThrowsError(
            try store.activateLocalRecoveryCandidate(
                candidate,
                authorization: LocalRecoveryActivationAuthorization(
                    confirmationID: confirmationID,
                    candidateBackupID: manifest.backupID,
                    confirmedAt: activatedAt.addingTimeInterval(-600),
                    expiresAt: activatedAt.addingTimeInterval(-1)
                ),
                activatedAt: activatedAt
            )
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .activationAuthorizationInvalid)
        }
        XCTAssertNotNil(store.event(id: liveOnly.id))

        store.recoveryActivationFailureInjector = { phase in
            if phase == .candidateMovedToLive {
                throw InjectedFailure.afterCandidateMove
            }
        }
        XCTAssertThrowsError(
            try store.activateLocalRecoveryCandidate(
                candidate,
                authorization: LocalRecoveryActivationAuthorization(
                    confirmationID: confirmationID,
                    candidateBackupID: manifest.backupID,
                    confirmedAt: activatedAt.addingTimeInterval(-30),
                    expiresAt: activatedAt.addingTimeInterval(300)
                ),
                activatedAt: activatedAt
            )
        )
        store.recoveryActivationFailureInjector = nil
        XCTAssertNotNil(store.event(id: liveOnly.id))
        let reopened = LocalMemoryStore(keyStore: keyStore, rootDirectory: liveRoot)
        XCTAssertNotNil(reopened.event(id: liveOnly.id))
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: sandbox.appendingPathComponent(".live.recovery-journal").path
            )
        )
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: sandbox.appendingPathComponent(".live.recovery-rollback").path
            )
        )

        let forgedJournal = sandbox.appendingPathComponent(".live.recovery-journal")
        let forgedPayload: [String: Any] = [
            "version": 1,
            "state": "committed",
            "confirmationID": LocalRecoveryActivationAuthorization.confirmationPrefix +
                UUID().uuidString.lowercased(),
            "backupID": manifest.backupID,
            "authenticationMAC": String(repeating: "0", count: 64),
        ]
        try JSONSerialization.data(
            withJSONObject: forgedPayload,
            options: [.sortedKeys]
        ).write(to: forgedJournal, options: [.atomic])
        XCTAssertThrowsError(
            try LocalBackupStore.recoverInterruptedActivation(
                liveDirectory: liveRoot,
                key: keyStore.key,
                fileManager: .default
            )
        ) {
            XCTAssertEqual($0 as? LocalBackupError, .activationJournalInvalid)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: forgedJournal.path))
        let failClosed = LocalMemoryStore(keyStore: keyStore, rootDirectory: liveRoot)
        XCTAssertEqual(failClosed.storageState, .recoverableError)
        XCTAssertNotNil(reopened.event(id: liveOnly.id))
    }

    func testBackupUsesAppOwnedSourceObjectAsAuthoritativeMediaInventory() throws {
        let sandbox = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: sandbox) }
        let sourceRoot = sandbox.appendingPathComponent("source", isDirectory: true)
        let mediaRoot = sourceRoot.appendingPathComponent("media", isDirectory: true)
        try FileManager.default.createDirectory(at: mediaRoot, withIntermediateDirectories: true)
        let media = mediaRoot
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("enc")
        let ciphertext = Data("source-object-authoritative-ciphertext".utf8)
        try ciphertext.write(to: media)
        let digest = SHA256.hash(data: ciphertext)
            .map { String(format: "%02x", $0) }
            .joined()
        let source = SourceObject(
            sourceObjectID: "source_backup_authority",
            kind: .capturedLocator,
            rawOwnership: .appOwnedEncrypted,
            rawState: .available,
            sourceLocator: media.path,
            rawCiphertextSHA256: digest,
            createdAtEpochMilliseconds: 1_759_100_000_000
        )
        let envelope = LocalStoreEnvelope(
            events: [],
            coverageDays: [],
            coverageEventLinks: [],
            longTermMemories: [],
            deletionTombstones: [],
            reuseAttempts: [],
            reuseOutcomes: [],
            sourceObjects: [source],
            eventSourceLinks: [],
            eventFieldEvidence: [],
            sourceDeletionRecords: []
        )
        let keyStore = LocalBackupTestKeyStore()
        let sealed = try AES.GCM.seal(JSONEncoder().encode(envelope), using: keyStore.key)
        let backup = sandbox.appendingPathComponent("backup", isDirectory: true)

        let manifest = try LocalBackupStore.create(
            destinationDirectory: backup,
            encryptedEnvelope: try XCTUnwrap(sealed.combined),
            events: [],
            sourceObjects: [source],
            sourceMediaDirectory: mediaRoot,
            deletionTombstones: [],
            localStoreSchemaVersion: LocalStoreEnvelope.currentSchemaVersion,
            key: keyStore.key,
            createdAt: Date(timeIntervalSince1970: 1_759_100_000),
            fileManager: .default
        )

        XCTAssertEqual(manifest.media.count, 1)
        XCTAssertEqual(manifest.media.first?.sha256, digest)
        XCTAssertEqual(
            try LocalBackupStore.verify(
                backupDirectory: backup,
                key: keyStore.key,
                fileManager: .default
            ).envelope.sourceObjects.first,
            source
        )
    }

    func testAppOwnedAvailableSourceRequiresOwnedMediaLocator() {
        let invalid = SourceObject(
            sourceObjectID: "source_invalid_available",
            kind: .capturedLocator,
            rawOwnership: .appOwnedEncrypted,
            rawState: .available,
            sourceLocator: nil,
            rawCiphertextSHA256: String(repeating: "a", count: 64),
            createdAtEpochMilliseconds: 1
        )
        XCTAssertFalse(invalid.isInternallyValid)
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeLocalBackup-\(UUID().uuidString)", isDirectory: true)
    }
}
