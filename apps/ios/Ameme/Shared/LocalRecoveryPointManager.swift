import Foundation

public enum LocalRecoveryPointAvailability: String, Equatable, Sendable {
    case none
    case verified
    case unavailable
}

public struct LocalRecoveryPointStatus: Equatable, Sendable {
    public let availability: LocalRecoveryPointAvailability
    public let backupID: String?
    public let createdAt: Date?
    public let snapshotBytes: Int64?
    public let verifiedAt: Date?
    public let lastActivatedAt: Date?
    public let cleanupPending: Bool

    public static let none = LocalRecoveryPointStatus(
        availability: .none,
        backupID: nil,
        createdAt: nil,
        snapshotBytes: nil,
        verifiedAt: nil,
        lastActivatedAt: nil,
        cleanupPending: false
    )

    public static let unavailable = LocalRecoveryPointStatus(
        availability: .unavailable,
        backupID: nil,
        createdAt: nil,
        snapshotBytes: nil,
        verifiedAt: nil,
        lastActivatedAt: nil,
        cleanupPending: false
    )
}

/// Owns one bounded same-install recovery point outside the live encrypted-store root.
///
/// A point is reported as verified only after the artifact has been authenticated and restored
/// into a new isolated candidate directory under the current device-bound key. It never exports
/// key material and cannot make an uninstall, device-loss, or cross-device recovery claim.
@MainActor
public final class LocalRecoveryPointManager {
    private struct ActivationRecord: Codable {
        static let currentSchemaVersion = 1

        let schemaVersion: Int
        let backupID: String
        let activatedAtEpochMilliseconds: Int64
        let cleanupPending: Bool
        let productionRecoveryClaim: Bool
    }

    private let fileManager: FileManager
    private let rootDirectory: URL
    private var preparedCandidate: LocalRecoveryCandidate?
    private var preparedStatus: LocalRecoveryPointStatus = .none

    private var currentDirectory: URL {
        rootDirectory.appendingPathComponent("current", isDirectory: true)
    }

    private var previousDirectory: URL {
        rootDirectory.appendingPathComponent("previous", isDirectory: true)
    }

    private var candidateDirectory: URL {
        rootDirectory.appendingPathComponent("candidate", isDirectory: true)
    }

    private var activationRecordURL: URL {
        rootDirectory.appendingPathComponent("last-activation-v1.json")
    }

    public init(
        rootDirectory: URL,
        fileManager: FileManager = .default
    ) {
        self.rootDirectory = rootDirectory.standardizedFileURL
        self.fileManager = fileManager
    }

    @discardableResult
    public func refresh(
        using store: LocalMemoryStore,
        verifiedAt: Date = .now
    ) -> LocalRecoveryPointStatus {
        preparedCandidate = nil
        preparedStatus = .none
        do {
            try ensureSafeRoot()
            try removeCoreStagingDirectories()
            let sources = try recoverySources()
            guard !sources.isEmpty else {
                try removeCandidateIfPresent()
                return .none
            }

            var lastError: Error?
            for source in sources {
                do {
                    let prepared = try prepare(
                        source: source,
                        using: store,
                        verifiedAt: verifiedAt
                    )
                    if source.standardizedFileURL != currentDirectory.standardizedFileURL {
                        try replaceCurrent(with: source)
                    }
                    try removeSupersededSlots()
                    preparedCandidate = prepared.candidate
                    preparedStatus = prepared.status
                    return prepared.status
                } catch {
                    lastError = error
                    try? removeCandidateIfPresent()
                }
            }
            if let lastError {
                throw lastError
            }
            return .unavailable
        } catch {
            preparedCandidate = nil
            preparedStatus = .unavailable
            return .unavailable
        }
    }

    @discardableResult
    public func create(
        using store: LocalMemoryStore,
        createdAt: Date = .now
    ) throws -> LocalRecoveryPointStatus {
        try ensureSafeRoot()
        try recoverInterruptedRotation()
        try removeTransientSlots()
        let next = rootDirectory.appendingPathComponent(
            ".next-\(UUID().uuidString.lowercased())",
            isDirectory: true
        )
        do {
            _ = try store.createLocalRecoveryBackup(at: next, createdAt: createdAt)
            _ = try store.verifyLocalRecoveryBackup(at: next)
            try removeCandidateIfPresent()
            _ = try store.restoreLocalRecoveryCandidate(
                from: next,
                to: candidateDirectory
            )
            try rotateVerifiedNextIntoCurrent(next)
            let status = refresh(using: store)
            guard status.availability == .verified else {
                throw LocalBackupError.invalidArtifact
            }
            return status
        } catch {
            try? recoverInterruptedRotation()
            try? removeOwnedTree(next)
            _ = refresh(using: store)
            throw error
        }
    }

    @discardableResult
    public func activate(
        using store: LocalMemoryStore,
        confirmedAt: Date = .now
    ) throws -> LocalRecoveryPointStatus {
        guard preparedStatus.availability == .verified,
              let candidate = preparedCandidate,
              preparedStatus.backupID == candidate.sourceManifest.backupID else {
            throw LocalBackupError.invalidArtifact
        }
        let authorization = LocalRecoveryActivationAuthorization(
            confirmationID:
                LocalRecoveryActivationAuthorization.confirmationPrefix +
                UUID().uuidString.lowercased(),
            candidateBackupID: candidate.sourceManifest.backupID,
            confirmedAt: confirmedAt,
            expiresAt: confirmedAt.addingTimeInterval(10 * 60)
        )
        let receipt = try store.activateLocalRecoveryCandidate(
            candidate,
            authorization: authorization,
            activatedAt: confirmedAt
        )
        guard !receipt.productionRecoveryClaim,
              receipt.candidateBackupID == candidate.sourceManifest.backupID else {
            throw LocalBackupError.activationAuthorizationInvalid
        }
        try writeActivationRecord(receipt)
        let status = refresh(using: store)
        guard status.availability == .verified,
              status.lastActivatedAt != nil else {
            throw LocalBackupError.invalidArtifact
        }
        return status
    }

    public func clear() throws {
        preparedCandidate = nil
        preparedStatus = .none
        guard fileManager.fileExists(atPath: rootDirectory.path) else { return }
        try removeOwnedTree(rootDirectory, allowRoot: true)
    }

    private func prepare(
        source: URL,
        using store: LocalMemoryStore,
        verifiedAt: Date
    ) throws -> (candidate: LocalRecoveryCandidate, status: LocalRecoveryPointStatus) {
        try requireOwnedSlot(source)
        let manifest = try store.verifyLocalRecoveryBackup(at: source)
        try removeCandidateIfPresent()
        let candidate = try store.restoreLocalRecoveryCandidate(
            from: source,
            to: candidateDirectory
        )
        guard candidate.sourceManifest == manifest,
              !candidate.productionRecoveryClaim,
              !manifest.productionRecoveryClaim else {
            throw LocalBackupError.invalidArtifact
        }
        let record = readActivationRecord(matching: manifest.backupID)
        let status = LocalRecoveryPointStatus(
            availability: .verified,
            backupID: manifest.backupID,
            createdAt: Self.parseISO8601(manifest.createdAt),
            snapshotBytes: manifest.eventsCiphertextSize +
                manifest.media.reduce(0) { $0 + $1.size },
            verifiedAt: verifiedAt,
            lastActivatedAt: record.map {
                Date(
                    timeIntervalSince1970:
                        Double($0.activatedAtEpochMilliseconds) / 1_000
                )
            },
            cleanupPending: record?.cleanupPending ?? false
        )
        return (candidate, status)
    }

    private func recoverySources() throws -> [URL] {
        try recoverInterruptedRotation()
        let currentExists = fileManager.fileExists(atPath: currentDirectory.path)
        let previousExists = fileManager.fileExists(atPath: previousDirectory.path)
        let next = try transientDirectories(prefix: ".next-")
        guard next.count <= 1 else {
            throw LocalBackupError.invalidArtifact
        }
        if currentExists {
            return [currentDirectory] + next + (previousExists ? [previousDirectory] : [])
        }
        if next.count == 1 {
            return next + (previousExists ? [previousDirectory] : [])
        }
        if next.count > 1 {
            throw LocalBackupError.invalidArtifact
        }
        return previousExists ? [previousDirectory] : []
    }

    private func recoverInterruptedRotation() throws {
        guard fileManager.fileExists(atPath: rootDirectory.path) else { return }
        try validateRootEntries()
        try removeCoreStagingDirectories()
        if !fileManager.fileExists(atPath: currentDirectory.path) {
            let next = try transientDirectories(prefix: ".next-")
            if next.count == 1 {
                try fileManager.moveItem(at: next[0], to: currentDirectory)
            } else if next.isEmpty,
                      fileManager.fileExists(atPath: previousDirectory.path) {
                try fileManager.moveItem(at: previousDirectory, to: currentDirectory)
            } else if next.count > 1 {
                throw LocalBackupError.invalidArtifact
            }
        }
    }

    private func replaceCurrent(with source: URL) throws {
        try requireOwnedSlot(source)
        if source.standardizedFileURL == currentDirectory.standardizedFileURL {
            return
        }
        if fileManager.fileExists(atPath: currentDirectory.path) {
            try removeOwnedTree(currentDirectory)
        }
        try fileManager.moveItem(at: source, to: currentDirectory)
    }

    private func rotateVerifiedNextIntoCurrent(_ next: URL) throws {
        try requireOwnedSlot(next)
        if fileManager.fileExists(atPath: previousDirectory.path) {
            try removeOwnedTree(previousDirectory)
        }
        if fileManager.fileExists(atPath: currentDirectory.path) {
            try fileManager.moveItem(at: currentDirectory, to: previousDirectory)
        }
        try fileManager.moveItem(at: next, to: currentDirectory)
        if fileManager.fileExists(atPath: previousDirectory.path) {
            try removeOwnedTree(previousDirectory)
        }
        try removeActivationRecordIfPresent()
    }

    private func removeSupersededSlots() throws {
        if fileManager.fileExists(atPath: previousDirectory.path) {
            try removeOwnedTree(previousDirectory)
        }
        try removeTransientSlots()
        try removeCoreStagingDirectories()
    }

    private func removeTransientSlots() throws {
        for url in try transientDirectories(prefix: ".next-") {
            try removeOwnedTree(url)
        }
        let temporaryRecords = try fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]
        ).filter { isActivationTemporaryName($0.lastPathComponent) }
        for temporary in temporaryRecords {
            guard Self.isRegularNonSymbolicFile(
                temporary,
                fileManager: fileManager
            ) else {
                throw LocalBackupError.invalidArtifact
            }
            try fileManager.removeItem(at: temporary)
        }
    }

    private func removeCoreStagingDirectories() throws {
        guard fileManager.fileExists(atPath: rootDirectory.path) else { return }
        let staging = try fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]
        ).filter { isCoreStagingName($0.lastPathComponent) }
        for directory in staging {
            try removeOwnedTree(directory)
        }
    }

    private func removeCandidateIfPresent() throws {
        if fileManager.fileExists(atPath: candidateDirectory.path) {
            try removeOwnedTree(candidateDirectory)
        }
    }

    private func removeActivationRecordIfPresent() throws {
        if fileManager.fileExists(atPath: activationRecordURL.path) {
            guard Self.isRegularNonSymbolicFile(
                activationRecordURL,
                fileManager: fileManager
            ) else {
                throw LocalBackupError.invalidArtifact
            }
            try fileManager.removeItem(at: activationRecordURL)
        }
    }

    private func ensureSafeRoot() throws {
        if !fileManager.fileExists(atPath: rootDirectory.path) {
            try fileManager.createDirectory(
                at: rootDirectory,
                withIntermediateDirectories: true
            )
        }
        guard let values = try? rootDirectory.resourceValues(
            forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
        ),
        values.isDirectory == true,
        values.isSymbolicLink != true else {
            throw LocalBackupError.invalidArtifact
        }
        try validateRootEntries()
    }

    private func validateRootEntries() throws {
        let urls = try fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
        )
        for url in urls {
            let name = url.lastPathComponent
            let allowed = name == "current" ||
                name == "previous" ||
                name == "candidate" ||
                name == activationRecordURL.lastPathComponent ||
                isTransientDirectoryName(name, prefix: ".next-") ||
                isCoreStagingName(name) ||
                isActivationTemporaryName(name)
            guard allowed else {
                throw LocalBackupError.invalidArtifact
            }
            let values = try url.resourceValues(
                forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
            )
            guard values.isSymbolicLink != true else {
                throw LocalBackupError.invalidArtifact
            }
        }
    }

    private func transientDirectories(prefix: String) throws -> [URL] {
        let urls = try fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]
        ).filter { $0.lastPathComponent.hasPrefix(prefix) }
        for url in urls {
            let values = try url.resourceValues(
                forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
            )
            guard values.isDirectory == true,
                  values.isSymbolicLink != true,
                  UUID(
                    uuidString: String(url.lastPathComponent.dropFirst(prefix.count))
                  ) != nil else {
                throw LocalBackupError.invalidArtifact
            }
        }
        return urls.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func requireOwnedSlot(_ url: URL) throws {
        let normalized = url.standardizedFileURL
        guard normalized.deletingLastPathComponent() == rootDirectory,
              normalized != rootDirectory else {
            throw LocalBackupError.invalidArtifact
        }
        let name = normalized.lastPathComponent
        guard name == "current" ||
                name == "previous" ||
                name == "candidate" ||
                isTransientDirectoryName(name, prefix: ".next-") ||
                isCoreStagingName(name) else {
            throw LocalBackupError.invalidArtifact
        }
    }

    private func removeOwnedTree(_ url: URL, allowRoot: Bool = false) throws {
        let normalized = url.standardizedFileURL
        if allowRoot {
            guard normalized == rootDirectory else {
                throw LocalBackupError.invalidArtifact
            }
        } else {
            try requireOwnedSlot(normalized)
        }
        guard fileManager.fileExists(atPath: normalized.path),
              let enumerator = fileManager.enumerator(
                at: normalized,
                includingPropertiesForKeys: [.isSymbolicLinkKey],
                options: []
              ) else {
            throw LocalBackupError.invalidArtifact
        }
        var descendants: [URL] = []
        for case let child as URL in enumerator {
            let values = try child.resourceValues(forKeys: [.isSymbolicLinkKey])
            guard values.isSymbolicLink != true,
                  child.standardizedFileURL.path.hasPrefix(
                    normalized.path + "/"
                  ) else {
                throw LocalBackupError.invalidArtifact
            }
            descendants.append(child)
        }
        for child in descendants.sorted(by: { $0.path.count > $1.path.count }) {
            try fileManager.removeItem(at: child)
        }
        try fileManager.removeItem(at: normalized)
    }

    private func writeActivationRecord(
        _ receipt: LocalRecoveryActivationReceipt
    ) throws {
        let record = ActivationRecord(
            schemaVersion: ActivationRecord.currentSchemaVersion,
            backupID: receipt.candidateBackupID,
            activatedAtEpochMilliseconds: Int64(
                (receipt.activatedAt.timeIntervalSince1970 * 1_000).rounded()
            ),
            cleanupPending: receipt.cleanupPending,
            productionRecoveryClaim: false
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(record)
        guard data.count <= 4 * 1_024 else {
            throw LocalBackupError.invalidArtifact
        }
        let temporary = rootDirectory.appendingPathComponent(
            ".last-activation-v1.json.tmp-\(UUID().uuidString.lowercased())"
        )
        do {
            try data.write(to: temporary, options: [.atomic])
            if fileManager.fileExists(atPath: activationRecordURL.path) {
                try removeActivationRecordIfPresent()
            }
            try fileManager.moveItem(at: temporary, to: activationRecordURL)
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
    }

    private func readActivationRecord(
        matching backupID: String
    ) -> ActivationRecord? {
        guard Self.isRegularNonSymbolicFile(
            activationRecordURL,
            fileManager: fileManager
        ),
        let attributes = try? fileManager.attributesOfItem(
            atPath: activationRecordURL.path
        ),
        let size = attributes[.size] as? NSNumber,
        size.intValue > 0,
        size.intValue <= 4 * 1_024,
        let data = try? Data(contentsOf: activationRecordURL),
        let record = try? JSONDecoder().decode(ActivationRecord.self, from: data),
        record.schemaVersion == ActivationRecord.currentSchemaVersion,
        record.backupID == backupID,
        record.backupID.hasPrefix("backup_"),
        UUID(
            uuidString: String(record.backupID.dropFirst("backup_".count))
        ) != nil,
        record.activatedAtEpochMilliseconds >= 0,
        !record.productionRecoveryClaim else {
            return nil
        }
        return record
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

    private static func parseISO8601(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    private func isTransientDirectoryName(
        _ name: String,
        prefix: String
    ) -> Bool {
        name.hasPrefix(prefix) &&
            UUID(uuidString: String(name.dropFirst(prefix.count))) != nil
    }

    private func isCoreStagingName(_ name: String) -> Bool {
        let candidatePrefix = ".candidate.staging-"
        if name.hasPrefix(candidatePrefix) {
            return UUID(
                uuidString: String(name.dropFirst(candidatePrefix.count))
            ) != nil
        }
        let nextPrefix = "..next-"
        guard name.hasPrefix(nextPrefix) else { return false }
        let fields = String(name.dropFirst(nextPrefix.count))
            .components(separatedBy: ".staging-")
        return fields.count == 2 &&
            fields.allSatisfy { UUID(uuidString: $0) != nil }
    }

    private func isActivationTemporaryName(_ name: String) -> Bool {
        let prefix = ".last-activation-v1.json.tmp-"
        return name.hasPrefix(prefix) &&
            UUID(uuidString: String(name.dropFirst(prefix.count))) != nil
    }
}
