import Foundation

/// Lifecycle state for a compiled candidate. A candidate is not an Event until
/// an explicit consumer creates a separate CoverageEventLink.
public enum CoverageCandidateState: String, Codable, Hashable, Sendable {
    case open
    case consumed
    case dismissed
    case deleted
}

public struct PersistedCoverageCandidate: Codable, Hashable, Sendable {
    public let candidate: CandidateEvent
    public var state: CoverageCandidateState
    public var updatedAt: String

    public init(
        candidate: CandidateEvent,
        state: CoverageCandidateState = .open,
        updatedAt: String
    ) {
        self.candidate = candidate
        self.state = state
        self.updatedAt = updatedAt
    }
}

/// A versioned, persistence-ready snapshot of one compiled coverage day.
/// It intentionally contains no coverage percentage or market-size fields.
public struct PersistedCoverageDay: Codable, Hashable, Sendable {
    public static let currentSchemaVersion = 1

    public let schemaVersion: Int
    public let dayID: String
    public let ownerID: String
    public let spaceID: String
    public let localDate: String
    public let timezone: String
    public let registryVersion: String
    public let compiledAt: String
    public let coverageState: CoverageState
    public let coveredContextTypes: [ContextType]
    public let unknownContextTypes: [ContextType]
    public let observations: [CoverageObservation]
    public var candidates: [PersistedCoverageCandidate]
    public let contextGaps: [ContextGap]

    public init(
        schemaVersion: Int = PersistedCoverageDay.currentSchemaVersion,
        dayID: String,
        ownerID: String,
        spaceID: String,
        localDate: String,
        timezone: String,
        registryVersion: String,
        compiledAt: String,
        coverageState: CoverageState,
        coveredContextTypes: [ContextType],
        unknownContextTypes: [ContextType],
        observations: [CoverageObservation],
        candidates: [PersistedCoverageCandidate],
        contextGaps: [ContextGap]
    ) {
        self.schemaVersion = schemaVersion
        self.dayID = dayID
        self.ownerID = ownerID
        self.spaceID = spaceID
        self.localDate = localDate
        self.timezone = timezone
        self.registryVersion = registryVersion
        self.compiledAt = compiledAt
        self.coverageState = coverageState
        self.coveredContextTypes = coveredContextTypes
        self.unknownContextTypes = unknownContextTypes
        self.observations = observations
        self.candidates = candidates
        self.contextGaps = contextGaps
    }

    public init(
        compilation: CoverageCompilation,
        registryVersion: String,
        compiledAt: String
    ) {
        self.init(
            dayID: compilation.dayID,
            ownerID: compilation.ownerID,
            spaceID: compilation.spaceID,
            localDate: compilation.localDate,
            timezone: compilation.timezone,
            registryVersion: registryVersion,
            compiledAt: compiledAt,
            coverageState: compilation.coverageState,
            coveredContextTypes: compilation.coveredContextTypes,
            unknownContextTypes: compilation.unknownContextTypes,
            observations: compilation.observations,
            candidates: compilation.candidateEvents.map {
                PersistedCoverageCandidate(candidate: $0, updatedAt: compiledAt)
            },
            contextGaps: compilation.contextGaps
        )
    }
}

public enum CoverageEventLinkState: String, Codable, Hashable, Sendable {
    case active
    case detached
}

public enum CoverageAcceptanceMode: String, Codable, Hashable, Sendable {
    /// Preserve the source fact status. Observations that have not been confirmed
    /// remain reviewable rather than silently becoming confirmed facts.
    case preserveEvidence = "preserve_evidence"
    /// Record an explicit user confirmation as user-asserted evidence.
    case userConfirmed = "user_confirmed"
}

public struct CoverageCandidateAcceptance: Hashable {
    public let dayID: String
    public let candidateID: String
    public let detail: String
    public let sourceLabel: String
    public let captureKind: CaptureKind
    public let localDate: Date
    public let time: Date?
    public let userWords: String?
    public let sensitivity: Sensitivity
    public let importance: Int
    public let mode: CoverageAcceptanceMode
    /// Exact-revision field provenance. Empty preserves legacy behavior; multi-source deletion
    /// remains fail-closed until callers provide a complete field-to-source map.
    public let fieldSourceObjectIDs: [EvidenceField: Set<String>]
    public let linkedAt: String

    public init(
        dayID: String,
        candidateID: String,
        detail: String,
        sourceLabel: String,
        captureKind: CaptureKind,
        localDate: Date,
        time: Date?,
        userWords: String? = nil,
        sensitivity: Sensitivity = .personal,
        importance: Int = 50,
        mode: CoverageAcceptanceMode = .preserveEvidence,
        fieldSourceObjectIDs: [EvidenceField: Set<String>] = [:],
        linkedAt: String
    ) {
        self.dayID = dayID
        self.candidateID = candidateID
        self.detail = detail
        self.sourceLabel = sourceLabel
        self.captureKind = captureKind
        self.localDate = localDate
        self.time = time
        self.userWords = userWords
        self.sensitivity = sensitivity
        self.importance = importance
        self.mode = mode
        precondition(fieldSourceObjectIDs.values.allSatisfy {
            !$0.isEmpty && $0.allSatisfy { !$0.isEmpty }
        })
        self.fieldSourceObjectIDs = fieldSourceObjectIDs
        self.linkedAt = linkedAt
    }
}

public enum CoveragePersistenceError: Error, Equatable {
    case invalid(String)
    case dayNotFound
    case candidateNotFound
    case candidateNotOpen
    case persistenceFailed
}

/// Records an explicit candidate-to-Event association without changing either
/// object. It provides the minimum lineage needed for a later transactional store.
public struct CoverageEventLink: Codable, Hashable, Sendable {
    public let dayID: String
    public let spaceID: String
    public let candidateID: String
    public let eventID: UUID
    public let createdRevision: Int
    public let sourceObjectIDs: [String]
    public let linkedAt: String
    public var state: CoverageEventLinkState

    public init(
        dayID: String,
        spaceID: String,
        candidateID: String,
        eventID: UUID,
        createdRevision: Int,
        sourceObjectIDs: [String],
        linkedAt: String,
        state: CoverageEventLinkState = .active
    ) {
        self.dayID = dayID
        self.spaceID = spaceID
        self.candidateID = candidateID
        self.eventID = eventID
        self.createdRevision = createdRevision
        self.sourceObjectIDs = sourceObjectIDs
        self.linkedAt = linkedAt
        self.state = state
    }
}
