import Foundation

public enum LongTermMemoryType: String, CaseIterable, Codable, Hashable, Sendable {
    case fact
    case decision
    case commitment
    case insight
    case preference
    case relationship
    case health
    case financial
    case majorDecision = "major_decision"

    public var requiresUserConfirmation: Bool {
        switch self {
        case .preference, .relationship, .health, .financial, .majorDecision: true
        case .fact, .decision, .commitment, .insight: false
        }
    }
}

/// The first two states are both candidates. Neither is a confirmed long-term
/// Memory until an explicit user confirmation is committed.
public enum LongTermMemoryState: String, Codable, Hashable, Sendable {
    case eligibleForMemoryCompiler = "eligible_for_memory_compiler"
    case candidateUserConfirmationRequired = "candidate_user_confirmation_required"
    case active
    case invalidated
    case superseded
}

public enum LongTermMemoryInvalidationReason: String, Codable, Hashable, Sendable {
    case eventRevisionChanged = "event_revision_changed"
    case eventDeleted = "event_deleted"
}

public enum LongTermMemoryRevisionReason: String, Codable, Hashable, Sendable {
    case compilerProposal = "compiler_proposal"
    case userConfirm = "user_confirm"
    case userSupersede = "user_supersede"
    case eventRevisionChanged = "event_revision_changed"
    case eventDeleted = "event_deleted"
}

public struct LongTermMemoryRevision: Codable, Hashable, Sendable {
    public let revision: Int
    public let reason: LongTermMemoryRevisionReason
    public let state: LongTermMemoryState
    public let changedAt: Date

    public init(
        revision: Int,
        reason: LongTermMemoryRevisionReason,
        state: LongTermMemoryState,
        changedAt: Date
    ) {
        self.revision = revision
        self.reason = reason
        self.state = state
        self.changedAt = changedAt
    }
}

public struct LongTermMemoryRecord: Identifiable, Codable, Hashable {
    public var id: UUID { memoryID }

    public let memoryID: UUID
    public let ownerID: String
    public let spaceID: String
    public let type: LongTermMemoryType
    public let valueSummary: String
    public let sourceEventID: UUID
    public let sourceEventRevision: Int
    public let evidenceState: EvidenceState
    public let sensitivity: Sensitivity
    public var state: LongTermMemoryState
    public let validFrom: Date
    public let validUntil: Date?
    public var confirmedAt: Date?
    public var supersedesMemoryID: UUID?
    public var supersededByMemoryID: UUID?
    public var invalidatedAt: Date?
    public var invalidationReason: LongTermMemoryInvalidationReason?
    public var revision: Int
    public let createdAt: Date
    public var updatedAt: Date
    public var revisions: [LongTermMemoryRevision]

    public init(
        memoryID: UUID = UUID(),
        ownerID: String,
        spaceID: String,
        type: LongTermMemoryType,
        valueSummary: String,
        sourceEventID: UUID,
        sourceEventRevision: Int,
        evidenceState: EvidenceState,
        sensitivity: Sensitivity,
        state: LongTermMemoryState,
        validFrom: Date,
        validUntil: Date?,
        confirmedAt: Date? = nil,
        supersedesMemoryID: UUID? = nil,
        supersededByMemoryID: UUID? = nil,
        invalidatedAt: Date? = nil,
        invalidationReason: LongTermMemoryInvalidationReason? = nil,
        revision: Int = 1,
        createdAt: Date,
        updatedAt: Date,
        revisions: [LongTermMemoryRevision]
    ) {
        self.memoryID = memoryID
        self.ownerID = ownerID
        self.spaceID = spaceID
        self.type = type
        self.valueSummary = valueSummary
        self.sourceEventID = sourceEventID
        self.sourceEventRevision = sourceEventRevision
        self.evidenceState = evidenceState
        self.sensitivity = sensitivity
        self.state = state
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.confirmedAt = confirmedAt
        self.supersedesMemoryID = supersedesMemoryID
        self.supersededByMemoryID = supersededByMemoryID
        self.invalidatedAt = invalidatedAt
        self.invalidationReason = invalidationReason
        self.revision = revision
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.revisions = revisions
    }

    public func isVisible(at date: Date) -> Bool {
        state == .active &&
            validFrom <= date &&
            (validUntil == nil || validUntil! > date)
    }

    var isInternallyValid: Bool {
        guard !ownerID.isEmpty, !spaceID.isEmpty,
              !valueSummary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              valueSummary.count <= 4_096,
              sourceEventRevision >= 1,
              revision >= 1,
              validUntil == nil || validUntil! > validFrom,
              revisions.count == revision,
              revisions.map(\.revision) == Array(1...revision),
              revisions.last?.state == state else {
            return false
        }
        switch state {
        case .active:
            return confirmedAt != nil &&
                invalidatedAt == nil &&
                invalidationReason == nil &&
                supersededByMemoryID == nil
        case .invalidated:
            return invalidatedAt != nil &&
                invalidationReason != nil &&
                supersededByMemoryID == nil
        case .superseded:
            return supersededByMemoryID != nil &&
                invalidatedAt == nil &&
                invalidationReason == nil
        case .eligibleForMemoryCompiler, .candidateUserConfirmationRequired:
            return confirmedAt == nil &&
                supersedesMemoryID == nil &&
                supersededByMemoryID == nil &&
                invalidatedAt == nil &&
                invalidationReason == nil
        }
    }
}

/// Evidence and sensitivity are derived from the current encrypted Event; callers
/// cannot manufacture either through a proposal.
public struct LongTermMemoryProposal {
    public let ownerID: String
    public let spaceID: String
    public let sourceEventID: UUID
    public let expectedEventRevision: Int
    public let type: LongTermMemoryType
    public let valueSummary: String
    public let validFrom: Date
    public let validUntil: Date?
    public let proposedAt: Date

    public init(
        ownerID: String,
        spaceID: String,
        sourceEventID: UUID,
        expectedEventRevision: Int,
        type: LongTermMemoryType,
        valueSummary: String,
        validFrom: Date,
        validUntil: Date? = nil,
        proposedAt: Date
    ) {
        self.ownerID = ownerID
        self.spaceID = spaceID
        self.sourceEventID = sourceEventID
        self.expectedEventRevision = expectedEventRevision
        self.type = type
        self.valueSummary = valueSummary
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.proposedAt = proposedAt
    }
}

public struct LongTermMemoryConfirmation {
    public let memoryID: UUID
    public let confirmedAt: Date
    public let supersedesMemoryID: UUID?

    public init(
        memoryID: UUID,
        confirmedAt: Date,
        supersedesMemoryID: UUID? = nil
    ) {
        self.memoryID = memoryID
        self.confirmedAt = confirmedAt
        self.supersedesMemoryID = supersedesMemoryID
    }
}

public enum LongTermMemoryError: Error, Equatable {
    case invalid(String)
    case sourceEventNotFound
    case sourceEventRevisionChanged
    case memoryNotFound
    case memoryNotConfirmable
    case supersededMemoryInvalid
    case persistenceFailed
}
