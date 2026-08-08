import Foundation

public enum IncomingShareKind: String, Codable, Sendable {
    case text
    case image
    case pdf

    public var mimeType: String {
        switch self {
        case .text: "text/plain"
        case .image: "image/*"
        case .pdf: "application/pdf"
        }
    }

    public var suggestedFileExtension: String {
        switch self {
        case .text: "txt"
        case .image: "jpg"
        case .pdf: "pdf"
        }
    }
}

public enum IncomingSharePayloadError: Error, Equatable {
    case invalidSchema
    case missingText
    case textTooLong
    case invalidMimeType
    case fileTooLarge
    case missingFile
    case invalidHandoffURL
    case localSpaceDeleted
    case unavailable
}

public struct IncomingSharePayload: Codable, Equatable, Identifiable, Sendable {
    public static let currentSchemaVersion = 1
    public static let maxTextLength = 16_384
    public static let maxFileBytes = 32 * 1024 * 1024

    public let schemaVersion: Int
    public let id: UUID
    public let kind: IncomingShareKind
    public let text: String?
    public let mimeType: String?
    public let displayName: String?
    public let createdAt: Date

    public init(
        id: UUID = UUID(),
        kind: IncomingShareKind,
        text: String? = nil,
        mimeType: String? = nil,
        displayName: String? = nil,
        createdAt: Date = .now
    ) {
        self.schemaVersion = Self.currentSchemaVersion
        self.id = id
        self.kind = kind
        self.text = text
        self.mimeType = mimeType
        self.displayName = displayName
        self.createdAt = createdAt
    }

    public static func text(
        _ text: String,
        id: UUID = UUID(),
        displayName: String? = nil,
        createdAt: Date = .now
    ) throws -> Self {
        let payload = Self(
            id: id,
            kind: .text,
            text: text,
            mimeType: IncomingShareKind.text.mimeType,
            displayName: displayName,
            createdAt: createdAt,
        )
        try payload.validate()
        return payload
    }

    public static func file(
        kind: IncomingShareKind,
        id: UUID = UUID(),
        mimeType: String? = nil,
        displayName: String? = nil,
        createdAt: Date = .now
    ) throws -> Self {
        let payload = Self(
            id: id,
            kind: kind,
            mimeType: mimeType ?? kind.mimeType,
            displayName: displayName,
            createdAt: createdAt,
        )
        try payload.validate()
        return payload
    }

    public func validate(fileData: Data? = nil) throws {
        guard schemaVersion == Self.currentSchemaVersion else {
            throw IncomingSharePayloadError.invalidSchema
        }
        switch kind {
        case .text:
            let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !trimmed.isEmpty else { throw IncomingSharePayloadError.missingText }
            guard trimmed.count <= Self.maxTextLength else {
                throw IncomingSharePayloadError.textTooLong
            }
            guard mimeType == IncomingShareKind.text.mimeType else {
                throw IncomingSharePayloadError.invalidMimeType
            }
            guard fileData == nil else { throw IncomingSharePayloadError.invalidSchema }
        case .image:
            guard mimeType?.lowercased().hasPrefix("image/") == true else {
                throw IncomingSharePayloadError.invalidMimeType
            }
            guard text == nil else { throw IncomingSharePayloadError.invalidSchema }
            if let fileData {
                guard !fileData.isEmpty else { throw IncomingSharePayloadError.missingFile }
                guard fileData.count <= Self.maxFileBytes else {
                    throw IncomingSharePayloadError.fileTooLarge
                }
            }
        case .pdf:
            guard mimeType?.lowercased() == IncomingShareKind.pdf.mimeType else {
                throw IncomingSharePayloadError.invalidMimeType
            }
            guard text == nil else { throw IncomingSharePayloadError.invalidSchema }
            if let fileData {
                guard !fileData.isEmpty else { throw IncomingSharePayloadError.missingFile }
                guard fileData.count <= Self.maxFileBytes else {
                    throw IncomingSharePayloadError.fileTooLarge
                }
            }
        }
    }
}

public struct IncomingShareRead: Sendable {
    public let payload: IncomingSharePayload
    public let fileData: Data?

    public init(payload: IncomingSharePayload, fileData: Data?) {
        self.payload = payload
        self.fileData = fileData
    }
}

/// App/Share Extension handoff storage. The URL contains only an opaque UUID;
/// the payload and optional file bytes stay inside the shared container.
public struct IncomingShareHandoffStore {
    public static let appGroupIdentifier = "group.com.ameme.ios"

    private let rootDirectory: URL
    private let fileManager: FileManager

    public init(rootDirectory: URL, fileManager: FileManager = .default) {
        self.rootDirectory = rootDirectory
        self.fileManager = fileManager
    }

    /// Returns the shared App Group directory when this target has the entitlement.
    /// The command-line smoke target intentionally returns nil on macOS and injects
    /// a temporary root instead, so it never pretends to prove entitlement wiring.
    public static func appGroupRoot(fileManager: FileManager = .default) -> URL? {
        #if os(iOS)
        return fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier
        )?.appendingPathComponent("IncomingShares", isDirectory: true)
        #else
        return nil
        #endif
    }

    @discardableResult
    public func writeText(_ text: String, displayName: String? = nil) throws -> URL {
        let payload = try IncomingSharePayload.text(text, displayName: displayName)
        return try write(payload, fileData: nil)
    }

    @discardableResult
    public func writeFile(
        _ data: Data,
        kind: IncomingShareKind,
        mimeType: String? = nil,
        displayName: String? = nil
    ) throws -> URL {
        let payload = try IncomingSharePayload.file(
            kind: kind,
            mimeType: mimeType,
            displayName: displayName,
        )
        return try write(payload, fileData: data)
    }

    public func write(_ payload: IncomingSharePayload, fileData: Data?) throws -> URL {
        guard !isFrozenForDeletedSpace else {
            throw IncomingSharePayloadError.localSpaceDeleted
        }
        if payload.kind != .text, fileData == nil {
            throw IncomingSharePayloadError.missingFile
        }
        try payload.validate(fileData: fileData)
        try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        let envelopeURL = envelopeURL(for: payload.id)
        let dataURL = dataURL(for: payload.id)
        if let fileData {
            try fileData.write(to: dataURL, options: .atomic)
        }
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            try encoder.encode(payload).write(to: envelopeURL, options: .atomic)
        } catch {
            try? fileManager.removeItem(at: dataURL)
            throw error
        }
        if isFrozenForDeletedSpace {
            remove(id: payload.id)
            throw IncomingSharePayloadError.localSpaceDeleted
        }
        return Self.handoffURL(for: payload.id)
    }

    public func read(id: UUID) throws -> IncomingShareRead {
        guard !isFrozenForDeletedSpace else {
            throw IncomingSharePayloadError.localSpaceDeleted
        }
        let envelopeURL = envelopeURL(for: id)
        let encoded = try Data(contentsOf: envelopeURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let payload = try decoder.decode(IncomingSharePayload.self, from: encoded)
        guard payload.id == id else { throw IncomingSharePayloadError.invalidSchema }
        let fileData: Data?
        switch payload.kind {
        case .text:
            fileData = nil
        case .image, .pdf:
            fileData = try Data(contentsOf: dataURL(for: id))
        }
        try payload.validate(fileData: fileData)
        return IncomingShareRead(payload: payload, fileData: fileData)
    }

    /// Lists durable handoffs which have not yet been confirmed or cancelled by
    /// the containing app. The payload stays opaque until `read(id:)` validates it.
    public func pendingIDs() -> [UUID] {
        guard !isFrozenForDeletedSpace else { return [] }
        let urls = (try? fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        return urls
            .filter { $0.pathExtension.lowercased() == "json" }
            .compactMap { UUID(uuidString: $0.deletingPathExtension().lastPathComponent) }
            .sorted { $0.uuidString < $1.uuidString }
    }

    public func remove(id: UUID) {
        try? fileManager.removeItem(at: envelopeURL(for: id))
        try? fileManager.removeItem(at: dataURL(for: id))
    }

    public var isFrozenForDeletedSpace: Bool {
        fileManager.fileExists(atPath: deletionMarkerURL.path)
    }

    public func freezeForDeletedSpaceAndClear() throws {
        do {
            try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
            try Data("ameme.local-space-deleted.v1\n".utf8).write(
                to: deletionMarkerURL,
                options: .atomic
            )
            try clearPayloadFiles()
            guard isFrozenForDeletedSpace, remainingPayloads.isEmpty else {
                throw IncomingSharePayloadError.unavailable
            }
        } catch let error as IncomingSharePayloadError {
            throw error
        } catch {
            throw IncomingSharePayloadError.unavailable
        }
    }

    public func clearPendingHandoffs() throws {
        do {
            try clearPayloadFiles()
            guard remainingPayloads.isEmpty else {
                throw IncomingSharePayloadError.unavailable
            }
        } catch let error as IncomingSharePayloadError {
            throw error
        } catch {
            throw IncomingSharePayloadError.unavailable
        }
    }

    public func id(from url: URL) -> UUID? {
        guard url.scheme?.lowercased() == "ameme",
              url.host?.lowercased() == "incoming-share",
              url.pathComponents.count == 2,
              let id = UUID(uuidString: url.pathComponents[1]) else {
            return nil
        }
        return id
    }

    public static func handoffURL(for id: UUID) -> URL {
        URL(string: "ameme://incoming-share/\(id.uuidString)")!
    }

    private func envelopeURL(for id: UUID) -> URL {
        rootDirectory.appendingPathComponent("\(id.uuidString).json", isDirectory: false)
    }

    private func dataURL(for id: UUID) -> URL {
        rootDirectory.appendingPathComponent("\(id.uuidString).data", isDirectory: false)
    }

    private var deletionMarkerURL: URL {
        rootDirectory.appendingPathComponent(".space-deleted-v1", isDirectory: false)
    }

    private var remainingPayloads: [URL] {
        ((try? fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil
        )) ?? []).filter { url in
            url.standardizedFileURL != deletionMarkerURL.standardizedFileURL
        }
    }

    private func clearPayloadFiles() throws {
        for url in remainingPayloads {
            try fileManager.removeItem(at: url)
        }
    }
}
