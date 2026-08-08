import CryptoKit
import Foundation

public enum PendingExportStoreError: Error, Equatable {
    case emptyExport
    case invalidEnvelope
    case unsupportedVersion
    case localSpaceDeleted
    case unavailable
    case tooLarge
}

/// Durable, encrypted recovery for an export which is waiting for the system
/// Share Sheet. The snapshot is separate from the Event Node so a failed share
/// never mutates the source events or requires opening them during recovery.
public final class PendingExportStore: @unchecked Sendable {
    public static let currentSchemaVersion = 1
    public static let maxClearBytes = 64 * 1024 * 1024

    private let fileManager: FileManager
    private let fileURL: URL
    private let temporaryURL: URL
    private let deletionMarkerURL: URL
    private let keyStore: any KeyMaterialStore
    private let lock = NSLock()
    private let aad = Data("com.ameme.ios:pending-export:v1".utf8)

    public init(
        fileManager: FileManager = .default,
        keyStore: any KeyMaterialStore = KeyStore(),
        rootDirectory: URL
    ) {
        self.fileManager = fileManager
        self.keyStore = keyStore
        self.fileURL = rootDirectory.appendingPathComponent("pending-export-v1.json", isDirectory: false)
        self.temporaryURL = rootDirectory.appendingPathComponent("pending-export-v1.json.tmp", isDirectory: false)
        self.deletionMarkerURL = rootDirectory.appendingPathComponent(
            ".space-deleted-v1",
            isDirectory: false
        )
    }

    public var exists: Bool {
        lock.lock()
        defer { lock.unlock() }
        return fileManager.fileExists(atPath: fileURL.path)
    }

    public func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        if isFrozenForDeletedSpaceUnlocked {
            do {
                try clearPayloadFilesUnlocked()
                return nil
            } catch {
                throw PendingExportStoreError.unavailable
            }
        }
        guard fileManager.fileExists(atPath: fileURL.path) else { return nil }
        do {
            let encoded = try Data(contentsOf: fileURL)
            guard encoded.count <= Self.maxClearBytes + 16 * 1024 else {
                throw PendingExportStoreError.tooLarge
            }
            let decoder = JSONDecoder()
            let envelope = try decoder.decode(PendingExportEnvelope.self, from: encoded)
            guard envelope.schemaVersion == Self.currentSchemaVersion else {
                throw PendingExportStoreError.unsupportedVersion
            }
            guard let combined = Data(base64Encoded: envelope.ciphertext) else {
                throw PendingExportStoreError.invalidEnvelope
            }
            let key = try keyStore.loadOrCreateKey()
            let box = try AES.GCM.SealedBox(combined: combined)
            let clear = try AES.GCM.open(box, using: key, authenticating: aad)
            guard !clear.isEmpty else { throw PendingExportStoreError.emptyExport }
            guard clear.count <= Self.maxClearBytes else { throw PendingExportStoreError.tooLarge }
            return clear
        } catch let error as PendingExportStoreError {
            throw error
        } catch {
            throw PendingExportStoreError.unavailable
        }
    }

    public func save(_ data: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        guard !isFrozenForDeletedSpaceUnlocked else {
            throw PendingExportStoreError.localSpaceDeleted
        }
        guard !data.isEmpty else { throw PendingExportStoreError.emptyExport }
        guard data.count <= Self.maxClearBytes else { throw PendingExportStoreError.tooLarge }
        do {
            let key = try keyStore.loadOrCreateKey()
            let box = try AES.GCM.seal(data, using: key, authenticating: aad)
            guard let combined = box.combined else {
                throw PendingExportStoreError.unavailable
            }
            let envelope = PendingExportEnvelope(
                schemaVersion: Self.currentSchemaVersion,
                ciphertext: combined.base64EncodedString()
            )
            let encoded = try JSONEncoder().encode(envelope)
            try fileManager.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try encoded.write(to: temporaryURL, options: .atomic)
            if fileManager.fileExists(atPath: fileURL.path) {
                _ = try fileManager.replaceItemAt(fileURL, withItemAt: temporaryURL)
            } else {
                try fileManager.moveItem(at: temporaryURL, to: fileURL)
            }
            if isFrozenForDeletedSpaceUnlocked {
                try clearPayloadFilesUnlocked()
                throw PendingExportStoreError.localSpaceDeleted
            }
        } catch let error as PendingExportStoreError {
            try? fileManager.removeItem(at: temporaryURL)
            throw error
        } catch {
            try? fileManager.removeItem(at: temporaryURL)
            throw PendingExportStoreError.unavailable
        }
    }

    public func clear() throws {
        lock.lock()
        defer { lock.unlock() }
        do {
            try clearPayloadFilesUnlocked()
            guard remainingPayloadURLsUnlocked.isEmpty else {
                throw PendingExportStoreError.unavailable
            }
        } catch {
            throw PendingExportStoreError.unavailable
        }
    }

    public var isFrozenForDeletedSpace: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isFrozenForDeletedSpaceUnlocked
    }

    /// Persists the deletion boundary before removing pending payloads. The marker is kept
    /// across launches so a stale exporter cannot recreate a snapshot after Personal space
    /// deletion has already become authoritative.
    public func freezeForDeletedSpaceAndClear() throws {
        lock.lock()
        defer { lock.unlock() }
        do {
            try fileManager.createDirectory(
                at: deletionMarkerURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data("ameme.local-space-deleted.v1\n".utf8).write(
                to: deletionMarkerURL,
                options: .atomic
            )
            try clearPayloadFilesUnlocked()
            guard isFrozenForDeletedSpaceUnlocked, remainingPayloadURLsUnlocked.isEmpty else {
                throw PendingExportStoreError.unavailable
            }
        } catch let error as PendingExportStoreError {
            throw error
        } catch {
            throw PendingExportStoreError.unavailable
        }
    }

    private var isFrozenForDeletedSpaceUnlocked: Bool {
        fileManager.fileExists(atPath: deletionMarkerURL.path)
    }

    private var remainingPayloadURLsUnlocked: [URL] {
        ((try? fileManager.contentsOfDirectory(
            at: deletionMarkerURL.deletingLastPathComponent(),
            includingPropertiesForKeys: nil
        )) ?? []).filter {
            $0.standardizedFileURL != deletionMarkerURL.standardizedFileURL
        }
    }

    private func clearPayloadFilesUnlocked() throws {
        for url in remainingPayloadURLsUnlocked {
            try fileManager.removeItem(at: url)
        }
    }
}

private struct PendingExportEnvelope: Codable {
    let schemaVersion: Int
    let ciphertext: String
}
