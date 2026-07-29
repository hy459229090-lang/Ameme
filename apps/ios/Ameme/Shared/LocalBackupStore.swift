import CryptoKit
import Foundation

public struct DeletionTombstone: Codable, Hashable, Sendable {
    public static let eventObjectType = "EVENT"
    public static let sourceObjectType = "SOURCE_OBJECT"
    public static let spaceObjectType = "SPACE"
    public static let personalSpaceID = "space_personal"

    public let spaceID: String
    public let objectType: String
    public let objectID: String
    public let terminalRevision: Int
    public let deletedAtEpochMilliseconds: Int64
    public let reason: String
    public let tombstoneDigest: String

    public static func event(
        eventID: UUID,
        terminalRevision: Int,
        deletedAt: Date,
        reason: String
    ) -> DeletionTombstone {
        create(
            spaceID: personalSpaceID,
            objectType: eventObjectType,
            objectID: eventID.uuidString.lowercased(),
            terminalRevision: terminalRevision,
            deletedAtEpochMilliseconds: Int64((deletedAt.timeIntervalSince1970 * 1_000).rounded()),
            reason: reason
        )
    }

    public static func localSpace(
        deletedAt: Date,
        reason: String = "local_space_delete"
    ) -> DeletionTombstone {
        create(
            spaceID: personalSpaceID,
            objectType: spaceObjectType,
            objectID: personalSpaceID,
            terminalRevision: 1,
            deletedAtEpochMilliseconds: Int64((deletedAt.timeIntervalSince1970 * 1_000).rounded()),
            reason: reason
        )
    }

    public static func create(
        spaceID: String,
        objectType: String,
        objectID: String,
        terminalRevision: Int,
        deletedAtEpochMilliseconds: Int64,
        reason: String
    ) -> DeletionTombstone {
        let payload = [
            spaceID,
            objectType,
            objectID,
            String(terminalRevision),
            String(deletedAtEpochMilliseconds),
            reason,
        ].joined(separator: "\u{1f}")
        return DeletionTombstone(
            spaceID: spaceID,
            objectType: objectType,
            objectID: objectID,
            terminalRevision: terminalRevision,
            deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
            reason: reason,
            tombstoneDigest: Self.sha256Hex(Data(payload.utf8))
        )
    }

    public var canonicalKey: String {
        [spaceID, objectType, objectID].joined(separator: "\u{1f}")
    }

    public var isInternallyValid: Bool {
        guard !spaceID.isEmpty, !objectType.isEmpty, !objectID.isEmpty,
              terminalRevision >= 1, deletedAtEpochMilliseconds >= 0,
              !reason.isEmpty, tombstoneDigest.count == 64,
              objectType != Self.eventObjectType || UUID(uuidString: objectID) != nil,
              objectType != Self.spaceObjectType ||
                (spaceID == Self.personalSpaceID && objectID == spaceID) else {
            return false
        }
        let expected = Self.create(
            spaceID: spaceID,
            objectType: objectType,
            objectID: objectID,
            terminalRevision: terminalRevision,
            deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
            reason: reason
        )
        return expected.tombstoneDigest == tombstoneDigest
    }

    public static func canonicalOrder(_ lhs: DeletionTombstone, _ rhs: DeletionTombstone) -> Bool {
        lhs.canonicalKey < rhs.canonicalKey
    }

    fileprivate static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

public struct LocalBackupMediaEntry: Codable, Equatable, Sendable {
    public let relativePath: String
    public let sha256: String
    public let size: Int64
}

public struct LocalBackupManifest: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1
    public static let keyMaterialState = "external_same_install_required"

    public let schemaVersion: Int
    public let backupID: String
    public let createdAt: String
    public let localStoreSchemaVersion: Int
    public let eventsCiphertextSHA256: String
    public let eventsCiphertextSize: Int64
    public let media: [LocalBackupMediaEntry]
    public let deletionWatermarkDigest: String
    public let keyMaterialState: String
    public let productionRecoveryClaim: Bool
    public let manifestMAC: String

    fileprivate var canonicalAuthenticationPayload: Data {
        var fields = [
            String(schemaVersion),
            backupID,
            createdAt,
            String(localStoreSchemaVersion),
            eventsCiphertextSHA256,
            String(eventsCiphertextSize),
            deletionWatermarkDigest,
            keyMaterialState,
            String(productionRecoveryClaim),
        ]
        for entry in media.sorted(by: { $0.relativePath < $1.relativePath }) {
            fields.append(entry.relativePath)
            fields.append(entry.sha256)
            fields.append(String(entry.size))
        }
        return Data(fields.joined(separator: "\u{1f}").utf8)
    }
}

public struct LocalRecoveryCandidate: Equatable, Sendable {
    public let directory: URL
    public let eventsFile: URL
    public let sourceManifest: LocalBackupManifest
    public let productionRecoveryClaim: Bool
}

public struct LocalRecoveryActivationAuthorization: Equatable, Sendable {
    public static let confirmationPrefix = "recovery_confirmation_"
    public static let maximumValidity: TimeInterval = 15 * 60

    public let confirmationID: String
    public let candidateBackupID: String
    public let confirmedAt: Date
    public let expiresAt: Date
    public let productionRecoveryClaim: Bool

    public init(
        confirmationID: String,
        candidateBackupID: String,
        confirmedAt: Date,
        expiresAt: Date,
        productionRecoveryClaim: Bool = false
    ) {
        self.confirmationID = confirmationID
        self.candidateBackupID = candidateBackupID
        self.confirmedAt = confirmedAt
        self.expiresAt = expiresAt
        self.productionRecoveryClaim = productionRecoveryClaim
    }

    fileprivate func validate(expectedBackupID: String, activatedAt: Date) throws {
        guard !productionRecoveryClaim,
              candidateBackupID == expectedBackupID,
              confirmationID.hasPrefix(Self.confirmationPrefix),
              UUID(
                uuidString: String(confirmationID.dropFirst(Self.confirmationPrefix.count))
              ) != nil,
              expiresAt > confirmedAt,
              expiresAt.timeIntervalSince(confirmedAt) <= Self.maximumValidity,
              activatedAt >= confirmedAt,
              activatedAt < expiresAt else {
            throw LocalBackupError.activationAuthorizationInvalid
        }
    }
}

public struct LocalRecoveryActivationReceipt: Equatable, Sendable {
    public let confirmationID: String
    public let candidateBackupID: String
    public let activatedAt: Date
    public let cleanupPending: Bool
    public let productionRecoveryClaim: Bool
}

public enum LocalBackupError: Error, Equatable {
    case sourceStoreUnavailable
    case destinationExists
    case sourceMediaUnavailable
    case invalidArtifact
    case authenticationFailed
    case unsupportedSchema
    case snapshotPredatesDeletion
    case restoredEnvelopeInvalid
    case activationAuthorizationInvalid
    case activationStoreBusy
    case activationJournalInvalid
}

struct VerifiedLocalBackup {
    let manifest: LocalBackupManifest
    let envelope: LocalStoreEnvelope
}

enum LocalRecoveryActivationPhase {
    case candidateStaged
    case journalPrepared
    case liveMovedToRollback
    case candidateMovedToLive
    case liveVerified
}

enum LocalBackupStore {
    private static let manifestName = "backup-manifest.json"
    private static let eventsName = "events.enc"
    private static let maximumManifestBytes = 128 * 1024
    private static let maximumEventsBytes: Int64 = 16 * 1024 * 1024 * 1024

    static func create(
        destinationDirectory: URL,
        encryptedEnvelope: Data,
        events: [MemoryEvent],
        sourceObjects: [SourceObject],
        sourceMediaDirectory: URL,
        deletionTombstones: [DeletionTombstone],
        localStoreSchemaVersion: Int,
        key: SymmetricKey,
        createdAt: Date,
        fileManager: FileManager
    ) throws -> LocalBackupManifest {
        guard !fileManager.fileExists(atPath: destinationDirectory.path) else {
            throw LocalBackupError.destinationExists
        }
        let parent = destinationDirectory.deletingLastPathComponent()
        try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        let staging = parent.appendingPathComponent(
            ".\(destinationDirectory.lastPathComponent).staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false)
        do {
            let mediaEntries = try collectMedia(
                events: events,
                sourceObjects: sourceObjects,
                sourceMediaDirectory: sourceMediaDirectory,
                fileManager: fileManager
            )
            let stagedEvents = staging.appendingPathComponent(eventsName)
            try encryptedEnvelope.write(to: stagedEvents, options: [.atomic])
            for (entry, sourceURL) in mediaEntries {
                let target = staging.appendingPathComponent(entry.relativePath)
                try fileManager.createDirectory(
                    at: target.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try fileManager.copyItem(at: sourceURL, to: target)
            }
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let unsigned = LocalBackupManifest(
                schemaVersion: LocalBackupManifest.currentSchemaVersion,
                backupID: "backup_\(UUID().uuidString.lowercased())",
                createdAt: formatter.string(from: createdAt),
                localStoreSchemaVersion: localStoreSchemaVersion,
                eventsCiphertextSHA256: sha256Hex(encryptedEnvelope),
                eventsCiphertextSize: Int64(encryptedEnvelope.count),
                media: mediaEntries.map(\.0),
                deletionWatermarkDigest: deletionWatermarkDigest(deletionTombstones),
                keyMaterialState: LocalBackupManifest.keyMaterialState,
                productionRecoveryClaim: false,
                manifestMAC: ""
            )
            let manifest = replacingMAC(
                unsigned,
                with: hmacHex(key: key, payload: unsigned.canonicalAuthenticationPayload)
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(manifest).write(
                to: staging.appendingPathComponent(manifestName),
                options: [.atomic]
            )
            _ = try verify(backupDirectory: staging, key: key, fileManager: fileManager)
            try fileManager.moveItem(at: staging, to: destinationDirectory)
            return manifest
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    static func verify(
        backupDirectory: URL,
        key: SymmetricKey,
        fileManager: FileManager
    ) throws -> VerifiedLocalBackup {
        let manifestURL = backupDirectory.appendingPathComponent(manifestName)
        let eventsURL = backupDirectory.appendingPathComponent(eventsName)
        guard isRegularNonSymbolicFile(manifestURL, fileManager: fileManager),
              isRegularNonSymbolicFile(eventsURL, fileManager: fileManager),
              let manifestSize = fileSize(manifestURL, fileManager: fileManager),
              manifestSize > 0, manifestSize <= maximumManifestBytes else {
            throw LocalBackupError.invalidArtifact
        }
        let manifest: LocalBackupManifest
        do {
            manifest = try JSONDecoder().decode(
                LocalBackupManifest.self,
                from: Data(contentsOf: manifestURL)
            )
        } catch {
            throw LocalBackupError.invalidArtifact
        }
        guard manifest.schemaVersion == LocalBackupManifest.currentSchemaVersion,
              manifest.localStoreSchemaVersion == LocalStoreEnvelope.currentSchemaVersion else {
            throw LocalBackupError.unsupportedSchema
        }
        guard manifest.keyMaterialState == LocalBackupManifest.keyMaterialState,
              !manifest.productionRecoveryClaim,
              manifest.backupID.hasPrefix("backup_"),
              UUID(uuidString: String(manifest.backupID.dropFirst("backup_".count))) != nil,
              parseISO8601(manifest.createdAt) != nil,
              isDigest(manifest.eventsCiphertextSHA256),
              isDigest(manifest.deletionWatermarkDigest),
              isDigest(manifest.manifestMAC),
              manifest.media.map(\.relativePath).count ==
                Set(manifest.media.map(\.relativePath)).count,
              manifest.media.allSatisfy({
                  isSafeMediaPath($0.relativePath) &&
                  isDigest($0.sha256) &&
                  $0.size >= 0
              }) else {
            throw LocalBackupError.invalidArtifact
        }
        let unsigned = replacingMAC(manifest, with: "")
        let expectedMAC = hmacHex(key: key, payload: unsigned.canonicalAuthenticationPayload)
        guard constantTimeEqual(expectedMAC, manifest.manifestMAC) else {
            throw LocalBackupError.authenticationFailed
        }
        let expectedFiles = Set(
            [manifestName, eventsName] + manifest.media.map(\.relativePath)
        )
        guard try regularFileSet(
            root: backupDirectory,
            fileManager: fileManager
        ) == expectedFiles else {
            throw LocalBackupError.invalidArtifact
        }

        let encrypted = try Data(contentsOf: eventsURL)
        guard Int64(encrypted.count) == manifest.eventsCiphertextSize,
              manifest.eventsCiphertextSize > 0,
              manifest.eventsCiphertextSize <= maximumEventsBytes,
              sha256Hex(encrypted) == manifest.eventsCiphertextSHA256 else {
            throw LocalBackupError.invalidArtifact
        }
        for entry in manifest.media {
            let file = backupDirectory.appendingPathComponent(entry.relativePath)
            guard isRegularNonSymbolicFile(file, fileManager: fileManager),
                  fileSize(file, fileManager: fileManager) == entry.size,
                  sha256Hex(try Data(contentsOf: file)) == entry.sha256 else {
                throw LocalBackupError.invalidArtifact
            }
        }
        let envelope: LocalStoreEnvelope
        do {
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let clear = try AES.GCM.open(box, using: key)
            envelope = try JSONDecoder().decode(LocalStoreEnvelope.self, from: clear)
        } catch {
            throw LocalBackupError.authenticationFailed
        }
        guard envelope.schemaVersion == LocalStoreEnvelope.currentSchemaVersion,
              envelope.isInternallyConsistent,
              deletionWatermarkDigest(envelope.deletionTombstones) ==
                manifest.deletionWatermarkDigest,
              backupMediaMatchesEnvelope(manifest: manifest, envelope: envelope) else {
            throw LocalBackupError.invalidArtifact
        }
        return VerifiedLocalBackup(manifest: manifest, envelope: envelope)
    }

    static func restoreCandidate(
        backupDirectory: URL,
        destinationDirectory: URL,
        authoritativeTombstones: [DeletionTombstone],
        key: SymmetricKey,
        fileManager: FileManager
    ) throws -> LocalRecoveryCandidate {
        guard !fileManager.fileExists(atPath: destinationDirectory.path) else {
            throw LocalBackupError.destinationExists
        }
        let verified = try verify(
            backupDirectory: backupDirectory,
            key: key,
            fileManager: fileManager
        )
        let storedTombstones = Dictionary(
            uniqueKeysWithValues: verified.envelope.deletionTombstones.map {
                ($0.canonicalKey, $0)
            }
        )
        for required in authoritativeTombstones {
            guard required.isInternallyValid,
                  let stored = storedTombstones[required.canonicalKey],
                  stored.terminalRevision >= required.terminalRevision,
                  stored.terminalRevision != required.terminalRevision ||
                    constantTimeEqual(stored.tombstoneDigest, required.tombstoneDigest) else {
                throw LocalBackupError.snapshotPredatesDeletion
            }
        }

        let parent = destinationDirectory.deletingLastPathComponent()
        try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        let staging = parent.appendingPathComponent(
            ".\(destinationDirectory.lastPathComponent).staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false)
        do {
            let restoredMediaDirectory = staging.appendingPathComponent("media", isDirectory: true)
            try fileManager.createDirectory(
                at: restoredMediaDirectory,
                withIntermediateDirectories: true
            )
            let backedUpMediaNames = Set(verified.manifest.media.map {
                URL(fileURLWithPath: $0.relativePath).lastPathComponent
            })
            for entry in verified.manifest.media {
                let source = backupDirectory.appendingPathComponent(entry.relativePath)
                let target = restoredMediaDirectory.appendingPathComponent(
                    URL(fileURLWithPath: entry.relativePath).lastPathComponent
                )
                try fileManager.copyItem(at: source, to: target)
            }
            let restoredEvents = verified.envelope.events.map { event in
                restoredEvent(
                    event,
                    backedUpMediaNames: backedUpMediaNames,
                    destinationMediaDirectory: restoredMediaDirectory
                )
            }
            let restoredSources = try verified.envelope.sourceObjects.map { source in
                try restoredSourceObject(
                    source,
                    backedUpMediaNames: backedUpMediaNames,
                    destinationMediaDirectory: restoredMediaDirectory
                )
            }
            let restoredEnvelope = LocalStoreEnvelope(
                events: restoredEvents,
                coverageDays: verified.envelope.coverageDays,
                coverageEventLinks: verified.envelope.coverageEventLinks,
                longTermMemories: verified.envelope.longTermMemories,
                deletionTombstones: verified.envelope.deletionTombstones,
                reuseAttempts: verified.envelope.reuseAttempts,
                reuseOutcomes: verified.envelope.reuseOutcomes,
                sourceObjects: restoredSources,
                eventSourceLinks: verified.envelope.eventSourceLinks,
                eventFieldEvidence: verified.envelope.eventFieldEvidence,
                eventUserConfirmations: verified.envelope.eventUserConfirmations,
                sourceDeletionRecords: verified.envelope.sourceDeletionRecords
            )
            guard restoredEnvelope.isInternallyConsistent else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            let clear = try JSONEncoder().encode(restoredEnvelope)
            let sealed = try AES.GCM.seal(clear, using: key)
            guard let combined = sealed.combined else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            try combined.write(
                to: staging.appendingPathComponent(eventsName),
                options: [.atomic]
            )
            let checkBox = try AES.GCM.SealedBox(combined: Data(
                contentsOf: staging.appendingPathComponent(eventsName)
            ))
            let checkClear = try AES.GCM.open(checkBox, using: key)
            let checkEnvelope = try JSONDecoder().decode(LocalStoreEnvelope.self, from: checkClear)
            guard checkEnvelope.isInternallyConsistent else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            try fileManager.moveItem(at: staging, to: destinationDirectory)
            return LocalRecoveryCandidate(
                directory: destinationDirectory,
                eventsFile: destinationDirectory.appendingPathComponent(eventsName),
                sourceManifest: verified.manifest,
                productionRecoveryClaim: false
            )
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    static func activateCandidate(
        _ candidate: LocalRecoveryCandidate,
        liveDirectory: URL,
        authorization: LocalRecoveryActivationAuthorization,
        activatedAt: Date,
        authoritativeTombstones: [DeletionTombstone],
        key: SymmetricKey,
        fileManager: FileManager,
        failureInjector: ((LocalRecoveryActivationPhase) throws -> Void)?
    ) throws -> LocalRecoveryActivationReceipt {
        guard !candidate.productionRecoveryClaim,
              !candidate.sourceManifest.productionRecoveryClaim else {
            throw LocalBackupError.activationAuthorizationInvalid
        }
        try authorization.validate(
            expectedBackupID: candidate.sourceManifest.backupID,
            activatedAt: activatedAt
        )
        let live = liveDirectory.standardizedFileURL
        let candidateRoot = candidate.directory.standardizedFileURL
        let candidateRootValues = try? candidateRoot.resourceValues(
            forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
        )
        guard candidateRoot != live,
              candidateRootValues?.isDirectory == true,
              candidateRootValues?.isSymbolicLink != true,
              candidate.eventsFile.standardizedFileURL ==
                candidateRoot.appendingPathComponent(eventsName).standardizedFileURL else {
            throw LocalBackupError.invalidArtifact
        }

        _ = try recoverInterruptedActivation(
            liveDirectory: live,
            key: key,
            fileManager: fileManager
        )
        let files = ActivationFiles(liveDirectory: live)
        try validateLiveRoot(files.live, fileManager: fileManager)
        guard !fileManager.fileExists(atPath: files.staged.path),
              !fileManager.fileExists(atPath: files.rollback.path),
              !fileManager.fileExists(atPath: files.journal.path) else {
            throw LocalBackupError.activationStoreBusy
        }

        let candidateEnvelope = try verifyRecoveryCandidate(
            candidate,
            authoritativeTombstones: authoritativeTombstones,
            key: key,
            fileManager: fileManager
        )
        do {
            try stageCandidateForActivation(
                candidate: candidate,
                envelope: candidateEnvelope,
                stagedDirectory: files.staged,
                liveDirectory: files.live,
                key: key,
                fileManager: fileManager
            )
            try verifyActivationDirectory(
                files.staged,
                expectedLocatorRoot: files.live,
                manifest: candidate.sourceManifest,
                authoritativeTombstones: authoritativeTombstones,
                key: key,
                fileManager: fileManager
            )
            try failureInjector?(.candidateStaged)

            try writeActivationJournal(
                files.journal,
                state: .prepared,
                authorization: authorization,
                key: key
            )
            try failureInjector?(.journalPrepared)

            try fileManager.moveItem(at: files.live, to: files.rollback)
            try failureInjector?(.liveMovedToRollback)

            try fileManager.moveItem(at: files.staged, to: files.live)
            try failureInjector?(.candidateMovedToLive)

            try verifyActivationDirectory(
                files.live,
                expectedLocatorRoot: files.live,
                manifest: candidate.sourceManifest,
                authoritativeTombstones: authoritativeTombstones,
                key: key,
                fileManager: fileManager
            )
            try failureInjector?(.liveVerified)

            try writeActivationJournal(
                files.journal,
                state: .committed,
                authorization: authorization,
                key: key
            )
            let cleanupPending: Bool
            do {
                try removeActivationRoot(files.rollback, fileManager: fileManager)
                try removeActivationFile(files.journal, fileManager: fileManager)
                cleanupPending = false
            } catch {
                cleanupPending = true
            }
            return LocalRecoveryActivationReceipt(
                confirmationID: authorization.confirmationID,
                candidateBackupID: candidate.sourceManifest.backupID,
                activatedAt: activatedAt,
                cleanupPending: cleanupPending,
                productionRecoveryClaim: false
            )
        } catch {
            if fileManager.fileExists(atPath: files.journal.path) {
                _ = try recoverInterruptedActivation(
                    liveDirectory: live,
                    key: key,
                    fileManager: fileManager
                )
            } else if fileManager.fileExists(atPath: files.staged.path) {
                try removeActivationRoot(files.staged, fileManager: fileManager)
            }
            throw error
        }
    }

    /// Resolves a crash journal before LocalMemoryStore reads the live encrypted envelope.
    ///
    /// A PREPARED swap is fail-closed and restores the old root whenever rollback exists.
    /// COMMITTED means the new root was already decrypted and relationship-checked, so startup
    /// keeps it and only finishes deletion of the transient rollback root.
    @discardableResult
    static func recoverInterruptedActivation(
        liveDirectory: URL,
        key: SymmetricKey,
        fileManager: FileManager
    ) throws -> Bool {
        let files = ActivationFiles(liveDirectory: liveDirectory.standardizedFileURL)
        guard fileManager.fileExists(atPath: files.journal.path) else { return false }
        let journal = try readActivationJournal(
            files.journal,
            key: key,
            fileManager: fileManager
        )
        switch journal.state {
        case .prepared:
            if fileManager.fileExists(atPath: files.rollback.path) {
                try validateLiveRoot(files.rollback, fileManager: fileManager)
                if fileManager.fileExists(atPath: files.live.path) {
                    try removeActivationRoot(files.live, fileManager: fileManager)
                }
                try fileManager.moveItem(at: files.rollback, to: files.live)
            } else {
                try validateLiveRoot(files.live, fileManager: fileManager)
            }
        case .committed:
            try validateLiveRoot(files.live, fileManager: fileManager)
            if fileManager.fileExists(atPath: files.rollback.path) {
                try removeActivationRoot(files.rollback, fileManager: fileManager)
            }
        }
        if fileManager.fileExists(atPath: files.staged.path) {
            try removeActivationRoot(files.staged, fileManager: fileManager)
        }
        try removeActivationFile(files.journal, fileManager: fileManager)
        return true
    }

    static func deletionWatermarkDigest(_ tombstones: [DeletionTombstone]) -> String {
        let canonical = tombstones
            .sorted(by: DeletionTombstone.canonicalOrder)
            .map {
                [
                    $0.spaceID,
                    $0.objectType,
                    $0.objectID,
                    String($0.terminalRevision),
                    String($0.deletedAtEpochMilliseconds),
                    $0.reason,
                    $0.tombstoneDigest,
                ].joined(separator: "\u{1f}")
            }
            .joined(separator: "\n")
        return sha256Hex(Data(canonical.utf8))
    }

    private enum ActivationJournalState: String, Codable {
        case prepared
        case committed
    }

    private struct ActivationJournal: Codable {
        static let currentVersion = 1

        let version: Int
        let state: ActivationJournalState
        let confirmationID: String
        let backupID: String
        let authenticationMAC: String

        var canonicalAuthenticationPayload: Data {
            Data(
                [
                    String(version),
                    state.rawValue,
                    confirmationID,
                    backupID,
                ].joined(separator: "\u{1f}").utf8
            )
        }
    }

    private struct ActivationFiles {
        let live: URL
        let staged: URL
        let rollback: URL
        let journal: URL

        init(liveDirectory: URL) {
            live = liveDirectory.standardizedFileURL
            let parent = live.deletingLastPathComponent()
            let name = live.lastPathComponent
            staged = parent.appendingPathComponent(
                ".\(name).recovery-stage",
                isDirectory: true
            )
            rollback = parent.appendingPathComponent(
                ".\(name).recovery-rollback",
                isDirectory: true
            )
            journal = parent.appendingPathComponent(".\(name).recovery-journal")
        }
    }

    private static func verifyRecoveryCandidate(
        _ candidate: LocalRecoveryCandidate,
        authoritativeTombstones: [DeletionTombstone],
        key: SymmetricKey,
        fileManager: FileManager
    ) throws -> LocalStoreEnvelope {
        let manifest = candidate.sourceManifest
        guard manifest.schemaVersion == LocalBackupManifest.currentSchemaVersion,
              manifest.localStoreSchemaVersion == LocalStoreEnvelope.currentSchemaVersion,
              manifest.keyMaterialState == LocalBackupManifest.keyMaterialState,
              !manifest.productionRecoveryClaim,
              manifest.backupID.hasPrefix("backup_"),
              UUID(uuidString: String(manifest.backupID.dropFirst("backup_".count))) != nil,
              parseISO8601(manifest.createdAt) != nil,
              isDigest(manifest.manifestMAC),
              constantTimeEqual(
                hmacHex(
                    key: key,
                    payload: replacingMAC(manifest, with: "").canonicalAuthenticationPayload
                ),
                manifest.manifestMAC
              ) else {
            throw LocalBackupError.authenticationFailed
        }
        let expectedFiles = Set(
            [eventsName] + manifest.media.map(\.relativePath)
        )
        guard try regularFileSet(
            root: candidate.directory,
            fileManager: fileManager
        ) == expectedFiles else {
            throw LocalBackupError.invalidArtifact
        }
        for entry in manifest.media {
            let media = candidate.directory.appendingPathComponent(entry.relativePath)
            guard isRegularNonSymbolicFile(media, fileManager: fileManager),
                  fileSize(media, fileManager: fileManager) == entry.size,
                  sha256Hex(try Data(contentsOf: media)) == entry.sha256 else {
                throw LocalBackupError.invalidArtifact
            }
        }
        let encrypted = try Data(contentsOf: candidate.eventsFile)
        guard Int64(encrypted.count) > 0,
              Int64(encrypted.count) <= maximumEventsBytes else {
            throw LocalBackupError.invalidArtifact
        }
        let envelope: LocalStoreEnvelope
        do {
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let clear = try AES.GCM.open(box, using: key)
            envelope = try JSONDecoder().decode(LocalStoreEnvelope.self, from: clear)
        } catch {
            throw LocalBackupError.authenticationFailed
        }
        try validateActivationEnvelope(
            envelope,
            expectedLocatorRoot: candidate.directory,
            manifest: manifest,
            authoritativeTombstones: authoritativeTombstones
        )
        return envelope
    }

    private static func stageCandidateForActivation(
        candidate: LocalRecoveryCandidate,
        envelope: LocalStoreEnvelope,
        stagedDirectory: URL,
        liveDirectory: URL,
        key: SymmetricKey,
        fileManager: FileManager
    ) throws {
        try fileManager.createDirectory(
            at: stagedDirectory,
            withIntermediateDirectories: false
        )
        let stagedMedia = stagedDirectory.appendingPathComponent("media", isDirectory: true)
        try fileManager.createDirectory(at: stagedMedia, withIntermediateDirectories: false)
        let backedUpMediaNames = Set(candidate.sourceManifest.media.map {
            URL(fileURLWithPath: $0.relativePath).lastPathComponent
        })
        for entry in candidate.sourceManifest.media {
            let source = candidate.directory.appendingPathComponent(entry.relativePath)
            let destination = stagedMedia.appendingPathComponent(source.lastPathComponent)
            try fileManager.copyItem(at: source, to: destination)
        }
        let liveMedia = liveDirectory.appendingPathComponent("media", isDirectory: true)
        let activatedEnvelope = LocalStoreEnvelope(
            events: envelope.events.map {
                restoredEvent(
                    $0,
                    backedUpMediaNames: backedUpMediaNames,
                    destinationMediaDirectory: liveMedia
                )
            },
            coverageDays: envelope.coverageDays,
            coverageEventLinks: envelope.coverageEventLinks,
            longTermMemories: envelope.longTermMemories,
            deletionTombstones: envelope.deletionTombstones,
            reuseAttempts: envelope.reuseAttempts,
            reuseOutcomes: envelope.reuseOutcomes,
            sourceObjects: try envelope.sourceObjects.map {
                try restoredSourceObject(
                    $0,
                    backedUpMediaNames: backedUpMediaNames,
                    destinationMediaDirectory: liveMedia
                )
            },
            eventSourceLinks: envelope.eventSourceLinks,
            eventFieldEvidence: envelope.eventFieldEvidence,
            eventUserConfirmations: envelope.eventUserConfirmations,
            sourceDeletionRecords: envelope.sourceDeletionRecords
        )
        guard activatedEnvelope.isInternallyConsistent else {
            throw LocalBackupError.restoredEnvelopeInvalid
        }
        let sealed = try AES.GCM.seal(
            JSONEncoder().encode(activatedEnvelope),
            using: key
        )
        guard let combined = sealed.combined else {
            throw LocalBackupError.restoredEnvelopeInvalid
        }
        try combined.write(
            to: stagedDirectory.appendingPathComponent(eventsName),
            options: [.atomic]
        )
    }

    private static func verifyActivationDirectory(
        _ directory: URL,
        expectedLocatorRoot: URL,
        manifest: LocalBackupManifest,
        authoritativeTombstones: [DeletionTombstone],
        key: SymmetricKey,
        fileManager: FileManager
    ) throws {
        let expectedFiles = Set([eventsName] + manifest.media.map(\.relativePath))
        guard try regularFileSet(root: directory, fileManager: fileManager) == expectedFiles else {
            throw LocalBackupError.invalidArtifact
        }
        for entry in manifest.media {
            let media = directory.appendingPathComponent(entry.relativePath)
            guard fileSize(media, fileManager: fileManager) == entry.size,
                  sha256Hex(try Data(contentsOf: media)) == entry.sha256 else {
                throw LocalBackupError.invalidArtifact
            }
        }
        let encrypted = try Data(contentsOf: directory.appendingPathComponent(eventsName))
        let envelope: LocalStoreEnvelope
        do {
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let clear = try AES.GCM.open(box, using: key)
            envelope = try JSONDecoder().decode(LocalStoreEnvelope.self, from: clear)
        } catch {
            throw LocalBackupError.authenticationFailed
        }
        try validateActivationEnvelope(
            envelope,
            expectedLocatorRoot: expectedLocatorRoot,
            manifest: manifest,
            authoritativeTombstones: authoritativeTombstones
        )
    }

    private static func validateActivationEnvelope(
        _ envelope: LocalStoreEnvelope,
        expectedLocatorRoot: URL,
        manifest: LocalBackupManifest,
        authoritativeTombstones: [DeletionTombstone]
    ) throws {
        guard envelope.schemaVersion == LocalStoreEnvelope.currentSchemaVersion,
              envelope.isInternallyConsistent,
              deletionWatermarkDigest(envelope.deletionTombstones) ==
                manifest.deletionWatermarkDigest,
              backupMediaMatchesEnvelope(manifest: manifest, envelope: envelope),
              ownedMediaLocatorsMatch(
                envelope: envelope,
                manifest: manifest,
                expectedRoot: expectedLocatorRoot
              ) else {
            throw LocalBackupError.restoredEnvelopeInvalid
        }
        let storedTombstones = Dictionary(
            uniqueKeysWithValues: envelope.deletionTombstones.map {
                ($0.canonicalKey, $0)
            }
        )
        for required in authoritativeTombstones {
            guard required.isInternallyValid,
                  let stored = storedTombstones[required.canonicalKey],
                  stored.terminalRevision >= required.terminalRevision,
                  stored.terminalRevision != required.terminalRevision ||
                    constantTimeEqual(stored.tombstoneDigest, required.tombstoneDigest) else {
                throw LocalBackupError.snapshotPredatesDeletion
            }
        }
    }

    private static func ownedMediaLocatorsMatch(
        envelope: LocalStoreEnvelope,
        manifest: LocalBackupManifest,
        expectedRoot: URL
    ) -> Bool {
        let expectedMedia = expectedRoot
            .standardizedFileURL
            .appendingPathComponent("media", isDirectory: true)
        let backedUpNames = Set(manifest.media.map {
            URL(fileURLWithPath: $0.relativePath).lastPathComponent
        })
        for source in envelope.sourceObjects
            where source.rawOwnership == .appOwnedEncrypted && source.rawState == .available {
            guard let locator = source.sourceLocator else { return false }
            let url = URL(fileURLWithPath: locator).standardizedFileURL
            guard url.deletingLastPathComponent() == expectedMedia,
                  backedUpNames.contains(url.lastPathComponent) else {
                return false
            }
        }
        for event in envelope.events {
            guard let locator = event.sourceLocator,
                  let name = ownedMediaName(locator),
                  backedUpNames.contains(name) else {
                continue
            }
            let url = URL(fileURLWithPath: locator).standardizedFileURL
            guard url.deletingLastPathComponent() == expectedMedia else {
                return false
            }
        }
        return true
    }

    private static func validateLiveRoot(
        _ root: URL,
        fileManager: FileManager
    ) throws {
        guard fileManager.fileExists(atPath: root.path),
              let values = try? root.resourceValues(
                forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
              ),
              values.isDirectory == true,
              values.isSymbolicLink != true else {
            throw LocalBackupError.activationStoreBusy
        }
        let files = try regularFileSet(root: root, fileManager: fileManager)
        guard files.allSatisfy({ path in
            path == eventsName || isSafeMediaPath(path)
        }) else {
            throw LocalBackupError.activationStoreBusy
        }
    }

    private static func writeActivationJournal(
        _ journalURL: URL,
        state: ActivationJournalState,
        authorization: LocalRecoveryActivationAuthorization,
        key: SymmetricKey
    ) throws {
        let unsigned = ActivationJournal(
            version: ActivationJournal.currentVersion,
            state: state,
            confirmationID: authorization.confirmationID,
            backupID: authorization.candidateBackupID,
            authenticationMAC: ""
        )
        let journal = ActivationJournal(
            version: unsigned.version,
            state: unsigned.state,
            confirmationID: unsigned.confirmationID,
            backupID: unsigned.backupID,
            authenticationMAC: hmacHex(
                key: key,
                payload: unsigned.canonicalAuthenticationPayload
            )
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(journal).write(to: journalURL, options: [.atomic])
    }

    private static func readActivationJournal(
        _ journalURL: URL,
        key: SymmetricKey,
        fileManager: FileManager
    ) throws -> ActivationJournal {
        guard isRegularNonSymbolicFile(journalURL, fileManager: fileManager),
              let size = fileSize(journalURL, fileManager: fileManager),
              size > 0, size <= 4 * 1024,
              let journal = try? JSONDecoder().decode(
                ActivationJournal.self,
                from: Data(contentsOf: journalURL)
              ),
              journal.version == ActivationJournal.currentVersion,
              journal.confirmationID.hasPrefix(
                LocalRecoveryActivationAuthorization.confirmationPrefix
              ),
              UUID(
                uuidString: String(
                    journal.confirmationID.dropFirst(
                        LocalRecoveryActivationAuthorization.confirmationPrefix.count
                    )
                )
              ) != nil,
              journal.backupID.hasPrefix("backup_"),
              UUID(uuidString: String(journal.backupID.dropFirst("backup_".count))) != nil,
              isDigest(journal.authenticationMAC) else {
            throw LocalBackupError.activationJournalInvalid
        }
        let unsigned = ActivationJournal(
            version: journal.version,
            state: journal.state,
            confirmationID: journal.confirmationID,
            backupID: journal.backupID,
            authenticationMAC: ""
        )
        guard constantTimeEqual(
            hmacHex(key: key, payload: unsigned.canonicalAuthenticationPayload),
            journal.authenticationMAC
        ) else {
            throw LocalBackupError.activationJournalInvalid
        }
        return journal
    }

    private static func removeActivationRoot(
        _ root: URL,
        fileManager: FileManager
    ) throws {
        try validateLiveRoot(root, fileManager: fileManager)
        try fileManager.removeItem(at: root)
    }

    private static func removeActivationFile(
        _ file: URL,
        fileManager: FileManager
    ) throws {
        guard isRegularNonSymbolicFile(file, fileManager: fileManager) else {
            throw LocalBackupError.activationJournalInvalid
        }
        try fileManager.removeItem(at: file)
    }

    private static func collectMedia(
        events: [MemoryEvent],
        sourceObjects: [SourceObject],
        sourceMediaDirectory: URL,
        fileManager: FileManager
    ) throws -> [(LocalBackupMediaEntry, URL)] {
        let root = sourceMediaDirectory.standardizedFileURL
        var seen: Set<String> = []
        var result: [(LocalBackupMediaEntry, URL)] = []
        func appendOwnedMedia(locator: String, expectedDigest: String?) throws {
            guard locator.hasPrefix("/") else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            let source = URL(fileURLWithPath: locator).standardizedFileURL
            guard source.deletingLastPathComponent() == root else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            guard source.pathExtension == "enc",
                  UUID(uuidString: source.deletingPathExtension().lastPathComponent) != nil else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            let relativePath = "media/\(source.lastPathComponent)"
            guard seen.insert(relativePath).inserted else {
                guard expectedDigest == nil ||
                        result.first(where: { $0.0.relativePath == relativePath })?.0.sha256 ==
                            expectedDigest else {
                    throw LocalBackupError.sourceMediaUnavailable
                }
                return
            }
            guard isRegularNonSymbolicFile(source, fileManager: fileManager) else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            let data = try Data(contentsOf: source)
            let digest = sha256Hex(data)
            guard expectedDigest == nil || expectedDigest == digest else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            result.append((
                LocalBackupMediaEntry(
                    relativePath: relativePath,
                    sha256: digest,
                    size: Int64(data.count)
                ),
                source
            ))
        }
        for source in sourceObjects where source.rawOwnership == .appOwnedEncrypted {
            guard source.rawState != .pendingCleanup else {
                throw LocalBackupError.sourceStoreUnavailable
            }
            if source.rawState == .deleted { continue }
            guard source.rawState == .available,
                  let locator = source.sourceLocator,
                  let digest = source.rawCiphertextSHA256 else {
                throw LocalBackupError.sourceMediaUnavailable
            }
            try appendOwnedMedia(locator: locator, expectedDigest: digest)
        }
        for locator in events.compactMap(\.sourceLocator) {
            guard locator.hasPrefix("/") else { continue }
            let source = URL(fileURLWithPath: locator).standardizedFileURL
            guard source.deletingLastPathComponent() == root else { continue }
            try appendOwnedMedia(locator: locator, expectedDigest: nil)
        }
        return result.sorted { $0.0.relativePath < $1.0.relativePath }
    }

    private static func restoredEvent(
        _ event: MemoryEvent,
        backedUpMediaNames: Set<String>,
        destinationMediaDirectory: URL
    ) -> MemoryEvent {
        let restoredLocator: String?
        if let locator = event.sourceLocator, locator.hasPrefix("/") {
            let source = URL(fileURLWithPath: locator)
            let name = source.lastPathComponent
            let isOwnedMediaLocator = source.deletingLastPathComponent().lastPathComponent == "media" &&
                source.pathExtension == "enc" &&
                UUID(uuidString: source.deletingPathExtension().lastPathComponent) != nil
            restoredLocator = isOwnedMediaLocator && backedUpMediaNames.contains(name)
                ? destinationMediaDirectory.appendingPathComponent(name).path
                : locator
        } else {
            restoredLocator = event.sourceLocator
        }
        return MemoryEvent(
            id: event.id,
            localDate: event.localDate,
            time: event.time,
            title: event.title,
            detail: event.detail,
            factStatus: event.factStatus,
            sourceLabel: event.sourceLabel,
            sourceLocator: restoredLocator,
            captureKind: event.captureKind,
            isLocalOnly: event.isLocalOnly,
            userWords: event.userWords,
            revision: event.revision,
            eventType: event.eventType,
            evidenceState: event.evidenceState,
            sensitivity: event.sensitivity,
            importance: event.importance
        )
    }

    private static func restoredSourceObject(
        _ source: SourceObject,
        backedUpMediaNames: Set<String>,
        destinationMediaDirectory: URL
    ) throws -> SourceObject {
        let restoredLocator: String?
        if source.rawOwnership == .appOwnedEncrypted && source.rawState == .available {
            guard let locator = source.sourceLocator, locator.hasPrefix("/") else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            let sourceURL = URL(fileURLWithPath: locator)
            let name = sourceURL.lastPathComponent
            let isOwnedMediaLocator = sourceURL.deletingLastPathComponent().lastPathComponent == "media" &&
                sourceURL.pathExtension == "enc" &&
                UUID(uuidString: sourceURL.deletingPathExtension().lastPathComponent) != nil
            guard isOwnedMediaLocator, backedUpMediaNames.contains(name) else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            restoredLocator = destinationMediaDirectory.appendingPathComponent(name).path
        } else if source.rawOwnership == .appOwnedEncrypted {
            guard source.rawState == .deleted else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            restoredLocator = nil
        } else {
            restoredLocator = source.sourceLocator
        }
        return SourceObject(
            sourceObjectID: source.sourceObjectID,
            spaceID: source.spaceID,
            kind: source.kind,
            rawOwnership: source.rawOwnership,
            rawState: source.rawState,
            sourceLocator: restoredLocator,
            rawCiphertextSHA256: source.rawCiphertextSHA256,
            createdAtEpochMilliseconds: source.createdAtEpochMilliseconds,
            deletedAtEpochMilliseconds: source.deletedAtEpochMilliseconds,
            state: source.state
        )
    }

    private static func backupMediaMatchesEnvelope(
        manifest: LocalBackupManifest,
        envelope: LocalStoreEnvelope
    ) -> Bool {
        let entriesByName = Dictionary(uniqueKeysWithValues: manifest.media.map {
            (URL(fileURLWithPath: $0.relativePath).lastPathComponent, $0)
        })
        for source in envelope.sourceObjects where source.rawOwnership == .appOwnedEncrypted {
            guard source.rawState != .pendingCleanup else { return false }
            if source.rawState == .deleted {
                guard source.sourceLocator == nil else { return false }
                continue
            }
            guard source.rawState == .available,
                  let locator = source.sourceLocator,
                  let expectedDigest = source.rawCiphertextSHA256,
                  let name = ownedMediaName(locator),
                  entriesByName[name]?.sha256 == expectedDigest else {
                return false
            }
        }
        for event in envelope.events {
            guard let locator = event.sourceLocator,
                  let name = ownedMediaName(locator) else {
                continue
            }
            guard entriesByName[name] != nil else { return false }
        }
        return true
    }

    private static func ownedMediaName(_ locator: String) -> String? {
        guard locator.hasPrefix("/") else { return nil }
        let source = URL(fileURLWithPath: locator)
        guard source.deletingLastPathComponent().lastPathComponent == "media",
              source.pathExtension == "enc",
              UUID(uuidString: source.deletingPathExtension().lastPathComponent) != nil else {
            return nil
        }
        return source.lastPathComponent
    }

    private static func replacingMAC(
        _ manifest: LocalBackupManifest,
        with value: String
    ) -> LocalBackupManifest {
        LocalBackupManifest(
            schemaVersion: manifest.schemaVersion,
            backupID: manifest.backupID,
            createdAt: manifest.createdAt,
            localStoreSchemaVersion: manifest.localStoreSchemaVersion,
            eventsCiphertextSHA256: manifest.eventsCiphertextSHA256,
            eventsCiphertextSize: manifest.eventsCiphertextSize,
            media: manifest.media,
            deletionWatermarkDigest: manifest.deletionWatermarkDigest,
            keyMaterialState: manifest.keyMaterialState,
            productionRecoveryClaim: manifest.productionRecoveryClaim,
            manifestMAC: value
        )
    }

    private static func hmacHex(key: SymmetricKey, payload: Data) -> String {
        HMAC<SHA256>.authenticationCode(for: payload, using: key)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private static func sha256Hex(_ data: Data) -> String {
        DeletionTombstone.sha256Hex(data)
    }

    private static func isDigest(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy {
            ("0"..."9").contains(String($0)) || ("a"..."f").contains(String($0))
        }
    }

    private static func isSafeMediaPath(_ path: String) -> Bool {
        let components = NSString(string: path).pathComponents
        return !path.hasPrefix("/") &&
            components.count == 2 &&
            components[0] == "media" &&
            components[1] != "." &&
            components[1] != ".." &&
            !components[1].isEmpty &&
            URL(fileURLWithPath: components[1]).pathExtension == "enc" &&
            UUID(
                uuidString: URL(fileURLWithPath: components[1])
                    .deletingPathExtension()
                    .lastPathComponent
            ) != nil
    }

    private static func parseISO8601(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for index in left.indices {
            difference |= left[index] ^ right[index]
        }
        return difference == 0
    }

    private static func isRegularNonSymbolicFile(
        _ url: URL,
        fileManager: FileManager
    ) -> Bool {
        guard fileManager.fileExists(atPath: url.path),
              let values = try? url.resourceValues(
                  forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
              ) else {
            return false
        }
        return values.isRegularFile == true && values.isSymbolicLink != true
    }

    private static func fileSize(
        _ url: URL,
        fileManager: FileManager
    ) -> Int64? {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let size = attributes[.size] as? NSNumber else {
            return nil
        }
        return size.int64Value
    }

    private static func regularFileSet(
        root: URL,
        fileManager: FileManager
    ) throws -> Set<String> {
        guard let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .isDirectoryKey],
            options: []
        ) else {
            throw LocalBackupError.invalidArtifact
        }
        var files: Set<String> = []
        for case let url as URL in enumerator {
            let values = try url.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .isDirectoryKey]
            )
            guard values.isSymbolicLink != true else {
                throw LocalBackupError.invalidArtifact
            }
            let prefix = root.standardizedFileURL.path + "/"
            let path = url.standardizedFileURL.path
            guard path.hasPrefix(prefix) else {
                throw LocalBackupError.invalidArtifact
            }
            let relative = String(path.dropFirst(prefix.count))
            if values.isRegularFile == true {
                files.insert(relative)
            } else if values.isDirectory == true && relative != "media" {
                throw LocalBackupError.invalidArtifact
            }
        }
        return files
    }
}
