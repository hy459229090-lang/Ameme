import Foundation

public enum SourceObjectKind: String, Codable, Hashable, Sendable {
    case capturedLocator = "captured_locator"
    case coverageEvidence = "coverage_evidence"
}

public enum SourceRawOwnership: String, Codable, Hashable, Sendable {
    case appOwnedEncrypted = "app_owned_encrypted"
    case externalNotOwned = "external_not_owned"
    case noRaw = "no_raw"
}

public enum SourceRawState: String, Codable, Hashable, Sendable {
    case available
    case pendingCleanup = "pending_cleanup"
    case deleted
    case externalNotOwned = "external_not_owned"
    case unavailable
}

public enum SourceObjectState: String, Codable, Hashable, Sendable {
    case active
    case deleted
}

public struct SourceObject: Codable, Hashable, Sendable {
    public static let personalSpaceID = "space_personal"

    public let sourceObjectID: String
    public let spaceID: String
    public let kind: SourceObjectKind
    public let rawOwnership: SourceRawOwnership
    public var rawState: SourceRawState
    public var sourceLocator: String?
    public let rawCiphertextSHA256: String?
    public let createdAtEpochMilliseconds: Int64
    public var deletedAtEpochMilliseconds: Int64?
    public var state: SourceObjectState

    public init(
        sourceObjectID: String,
        spaceID: String = SourceObject.personalSpaceID,
        kind: SourceObjectKind,
        rawOwnership: SourceRawOwnership,
        rawState: SourceRawState,
        sourceLocator: String?,
        rawCiphertextSHA256: String?,
        createdAtEpochMilliseconds: Int64,
        deletedAtEpochMilliseconds: Int64? = nil,
        state: SourceObjectState = .active
    ) {
        self.sourceObjectID = sourceObjectID
        self.spaceID = spaceID
        self.kind = kind
        self.rawOwnership = rawOwnership
        self.rawState = rawState
        self.sourceLocator = sourceLocator
        self.rawCiphertextSHA256 = rawCiphertextSHA256
        self.createdAtEpochMilliseconds = createdAtEpochMilliseconds
        self.deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
        self.state = state
    }

    public var isInternallyValid: Bool {
        guard !sourceObjectID.isEmpty,
              spaceID == Self.personalSpaceID,
              createdAtEpochMilliseconds >= 0,
              rawCiphertextSHA256 == nil ||
                rawCiphertextSHA256!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
            return false
        }
        switch rawOwnership {
        case .appOwnedEncrypted:
            guard rawCiphertextSHA256 != nil,
                  rawState == .available || rawState == .pendingCleanup || rawState == .deleted else {
                return false
            }
            if rawState == .available || rawState == .pendingCleanup {
                guard let sourceLocator, sourceLocator.hasPrefix("/") else { return false }
                let source = URL(fileURLWithPath: sourceLocator)
                guard source.pathExtension == "enc",
                      UUID(uuidString: source.deletingPathExtension().lastPathComponent) != nil else {
                    return false
                }
            }
        case .externalNotOwned:
            guard rawState == .externalNotOwned, rawCiphertextSHA256 == nil else { return false }
        case .noRaw:
            guard rawState == .unavailable, sourceLocator == nil, rawCiphertextSHA256 == nil else {
                return false
            }
        }
        if rawState == .deleted, sourceLocator != nil { return false }
        return (state == .active && deletedAtEpochMilliseconds == nil) ||
            (state == .deleted && deletedAtEpochMilliseconds != nil)
    }
}

public enum EventSourceLinkState: String, Codable, Hashable, Sendable {
    case active
    case deleted
}

public enum EventFieldEvidenceState: String, Codable, Hashable, Sendable {
    case active
    case deleted
}

public enum UserConfirmationKind: String, Codable, Hashable, Sendable {
    case coverageAcceptance = "coverage_acceptance"
    case factStatusConfirmation = "fact_status_confirmation"
    case userRevision = "user_revision"
}

public enum UserConfirmationState: String, Codable, Hashable, Sendable {
    case active
    case deleted
}

/// Content-free, exact-revision proof of one explicit user action.
///
/// Field names are retained without values. `completeFieldSet` is true only when the confirmation
/// was bound to the complete accepted Candidate field set. Partial confirmations remain auditable
/// but never preserve unsupported Event content during source cascade.
public struct EventUserConfirmation: Codable, Hashable, Sendable {
    public let confirmationID: String
    public let eventID: UUID
    public let eventRevision: Int
    public let kind: UserConfirmationKind
    public let confirmedFields: [EvidenceField]
    public let completeFieldSet: Bool
    public let confirmedAtEpochMilliseconds: Int64
    public var state: UserConfirmationState
    public var deletedAtEpochMilliseconds: Int64?

    public init(
        confirmationID: String,
        eventID: UUID,
        eventRevision: Int,
        kind: UserConfirmationKind,
        confirmedFields: Set<EvidenceField>,
        completeFieldSet: Bool,
        confirmedAtEpochMilliseconds: Int64,
        state: UserConfirmationState = .active,
        deletedAtEpochMilliseconds: Int64? = nil
    ) {
        self.confirmationID = confirmationID
        self.eventID = eventID
        self.eventRevision = eventRevision
        self.kind = kind
        self.confirmedFields = confirmedFields.sorted { $0.rawValue < $1.rawValue }
        self.completeFieldSet = completeFieldSet
        self.confirmedAtEpochMilliseconds = confirmedAtEpochMilliseconds
        self.state = state
        self.deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
    }

    public var isInternallyValid: Bool {
        !confirmationID.isEmpty &&
            eventRevision >= 1 &&
            !confirmedFields.isEmpty &&
            confirmedFields.count == Set(confirmedFields).count &&
            confirmedAtEpochMilliseconds >= 0 &&
            ((state == .active && deletedAtEpochMilliseconds == nil) ||
                (state == .deleted && deletedAtEpochMilliseconds != nil))
    }
}

/// Content-free, exact-revision field provenance. Values stay in the Event; this record only
/// proves which active source supported which accepted Coverage field at one revision.
public struct EventFieldEvidence: Codable, Hashable, Sendable {
    public let eventID: UUID
    public let eventRevision: Int
    public let field: EvidenceField
    public let sourceObjectID: String
    public let createdAtEpochMilliseconds: Int64
    public var state: EventFieldEvidenceState
    public var deletedAtEpochMilliseconds: Int64?

    public init(
        eventID: UUID,
        eventRevision: Int,
        field: EvidenceField,
        sourceObjectID: String,
        createdAtEpochMilliseconds: Int64,
        state: EventFieldEvidenceState = .active,
        deletedAtEpochMilliseconds: Int64? = nil
    ) {
        self.eventID = eventID
        self.eventRevision = eventRevision
        self.field = field
        self.sourceObjectID = sourceObjectID
        self.createdAtEpochMilliseconds = createdAtEpochMilliseconds
        self.state = state
        self.deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
    }

    public var isInternallyValid: Bool {
        eventRevision >= 1 &&
            !sourceObjectID.isEmpty &&
            createdAtEpochMilliseconds >= 0 &&
            ((state == .active && deletedAtEpochMilliseconds == nil) ||
                (state == .deleted && deletedAtEpochMilliseconds != nil))
    }
}

public struct EventSourceLink: Codable, Hashable, Sendable {
    public let eventID: UUID
    public let sourceObjectID: String
    public let createdRevision: Int
    public let createdAtEpochMilliseconds: Int64
    public var state: EventSourceLinkState
    public var deletedAtEpochMilliseconds: Int64?

    public init(
        eventID: UUID,
        sourceObjectID: String,
        createdRevision: Int,
        createdAtEpochMilliseconds: Int64,
        state: EventSourceLinkState = .active,
        deletedAtEpochMilliseconds: Int64? = nil
    ) {
        self.eventID = eventID
        self.sourceObjectID = sourceObjectID
        self.createdRevision = createdRevision
        self.createdAtEpochMilliseconds = createdAtEpochMilliseconds
        self.state = state
        self.deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
    }

    public var isInternallyValid: Bool {
        !sourceObjectID.isEmpty &&
            createdRevision >= 1 &&
            createdAtEpochMilliseconds >= 0 &&
            ((state == .active && deletedAtEpochMilliseconds == nil) ||
                (state == .deleted && deletedAtEpochMilliseconds != nil))
    }
}

public enum SourceDeletionOperation: String, Codable, Hashable, Sendable {
    case rawOnly = "raw_only"
    case sourceCascade = "source_cascade"
}

public enum SourceDeletionStatus: String, Codable, Hashable, Sendable {
    case completed
    case completedLocalOnly = "completed_local_only"
    case pendingCleanup = "pending_cleanup"
    case externalNotOwned = "external_not_owned"
    case noRaw = "no_raw"
    case notFound = "not_found"
    case lineageUnavailable = "lineage_unavailable"
    case persistenceFailed = "persistence_failed"
}

public struct SourceDeletionRecord: Codable, Hashable, Sendable {
    public let deletionID: String
    public let sourceObjectID: String
    public let operation: SourceDeletionOperation
    public let status: SourceDeletionStatus
    public let affectedEventCount: Int
    public let recomputedEventCount: Int
    public let deletedEventCount: Int
    public let createdAtEpochMilliseconds: Int64
    public let rawDigestOnly: String?

    public init(
        deletionID: String = "del_\(UUID().uuidString.lowercased())",
        sourceObjectID: String,
        operation: SourceDeletionOperation,
        status: SourceDeletionStatus,
        affectedEventCount: Int,
        recomputedEventCount: Int = 0,
        deletedEventCount: Int = 0,
        createdAtEpochMilliseconds: Int64,
        rawDigestOnly: String?
    ) {
        self.deletionID = deletionID
        self.sourceObjectID = sourceObjectID
        self.operation = operation
        self.status = status
        self.affectedEventCount = affectedEventCount
        self.recomputedEventCount = recomputedEventCount
        self.deletedEventCount = deletedEventCount
        self.createdAtEpochMilliseconds = createdAtEpochMilliseconds
        self.rawDigestOnly = rawDigestOnly
    }

    public var isInternallyValid: Bool {
        deletionID.hasPrefix("del_") &&
            !sourceObjectID.isEmpty &&
            affectedEventCount >= 0 &&
            recomputedEventCount >= 0 &&
            deletedEventCount >= 0 &&
            recomputedEventCount + deletedEventCount <= affectedEventCount &&
            createdAtEpochMilliseconds >= 0 &&
            (rawDigestOnly == nil ||
                rawDigestOnly!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil)
    }

    private enum CodingKeys: String, CodingKey {
        case deletionID
        case sourceObjectID
        case operation
        case status
        case affectedEventCount
        case recomputedEventCount
        case deletedEventCount
        case createdAtEpochMilliseconds
        case rawDigestOnly
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        deletionID = try values.decode(String.self, forKey: .deletionID)
        sourceObjectID = try values.decode(String.self, forKey: .sourceObjectID)
        operation = try values.decode(SourceDeletionOperation.self, forKey: .operation)
        status = try values.decode(SourceDeletionStatus.self, forKey: .status)
        affectedEventCount = try values.decode(Int.self, forKey: .affectedEventCount)
        recomputedEventCount =
            try values.decodeIfPresent(Int.self, forKey: .recomputedEventCount) ?? 0
        deletedEventCount =
            try values.decodeIfPresent(Int.self, forKey: .deletedEventCount) ?? 0
        createdAtEpochMilliseconds = try values.decode(
            Int64.self,
            forKey: .createdAtEpochMilliseconds
        )
        rawDigestOnly = try values.decodeIfPresent(String.self, forKey: .rawDigestOnly)
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(deletionID, forKey: .deletionID)
        try values.encode(sourceObjectID, forKey: .sourceObjectID)
        try values.encode(operation, forKey: .operation)
        try values.encode(status, forKey: .status)
        try values.encode(affectedEventCount, forKey: .affectedEventCount)
        try values.encode(recomputedEventCount, forKey: .recomputedEventCount)
        try values.encode(deletedEventCount, forKey: .deletedEventCount)
        try values.encode(createdAtEpochMilliseconds, forKey: .createdAtEpochMilliseconds)
        try values.encodeIfPresent(rawDigestOnly, forKey: .rawDigestOnly)
    }
}

public struct SourceDeletionResult: Equatable, Sendable {
    public let sourceObjectID: String
    public let operation: SourceDeletionOperation
    public let status: SourceDeletionStatus
    public let affectedEventCount: Int
    public let recomputedEventCount: Int
    public let deletedEventCount: Int

    public init(
        sourceObjectID: String,
        operation: SourceDeletionOperation,
        status: SourceDeletionStatus,
        affectedEventCount: Int,
        recomputedEventCount: Int = 0,
        deletedEventCount: Int = 0
    ) {
        precondition(affectedEventCount >= 0)
        precondition(recomputedEventCount >= 0 && deletedEventCount >= 0)
        precondition(recomputedEventCount + deletedEventCount <= affectedEventCount)
        self.sourceObjectID = sourceObjectID
        self.operation = operation
        self.status = status
        self.affectedEventCount = affectedEventCount
        self.recomputedEventCount = recomputedEventCount
        self.deletedEventCount = deletedEventCount
    }
}
