import CryptoKit
import Foundation

public enum ReuseIntent: String, CaseIterable, Codable, Hashable, Sendable {
    case historicalSearch = "historical_search"
    case projectResume = "project_resume"
    case preMeetingContext = "pre_meeting_context"
    case decisionCommitmentRecall = "decision_commitment_recall"
}

public enum ReuseRangeState: String, Codable, Hashable, Sendable {
    case completeForLocalScope = "complete_for_local_scope"
    case partialForLocalScope = "partial_for_local_scope"
    case empty
}

public enum ReuseObjectType: String, Codable, Hashable, Sendable {
    case event
    case longTermMemory = "long_term_memory"
}

public enum ReuseSelectionReason: String, Codable, Hashable, Sendable {
    case keywordMatch = "keyword_match"
    case dateMatch = "date_match"
    case meetingAnchor = "meeting_anchor"
    case activeDecision = "active_decision"
    case activeCommitment = "active_commitment"
}

public enum ReuseExclusion: String, Codable, Hashable, Sendable {
    case restricted
    case invalidated
    case deleted
    case expired
    case insufficientQuery = "insufficient_query"
    case policyFiltered = "policy_filtered"
}

public enum ReuseOutcome: String, CaseIterable, Codable, Hashable, Sendable {
    case useful
    case notUseful = "not_useful"
    case wrongMemory = "wrong_memory"
    case importantMiss = "important_miss"
    case outdated
    case permissionDenied = "permission_denied"
    case deletionFailure = "deletion_failure"
    case recoveryFailure = "recovery_failure"
    case restrictedEgressBlocked = "restricted_egress_blocked"
}

public enum ReuseUserAction: String, CaseIterable, Codable, Hashable, Sendable {
    case none
    case revised
    case hidden
    case deleted
}

public enum ReuseResultCountBucket: String, CaseIterable, Codable, Hashable, Sendable {
    case zero = "0"
    case one = "1"
    case twoToFive = "2_5"
    case sixToTen = "6_10"
    case elevenToFifty = "11_50"

    static func forCount(_ count: Int) -> Self {
        switch count {
        case 0: .zero
        case 1: .one
        case 2...5: .twoToFive
        case 6...10: .sixToTen
        default: .elevenToFifty
        }
    }
}

/// Query text is transient input only. It is never copied into a persisted reuse-attempt record.
///
/// Ameme does not yet model a Project entity. Project resume therefore requires explicit user
/// keywords and must not be presented as inferred project membership.
public struct ReuseRequest: Sendable {
    public let spaceID: String
    public let intent: ReuseIntent
    public let query: String
    public let startDate: Date?
    public let endDate: Date?
    public let meetingAnchorDate: Date?
    public let limit: Int
    public let requestedAt: Date

    public init(
        spaceID: String,
        intent: ReuseIntent,
        query: String = "",
        startDate: Date? = nil,
        endDate: Date? = nil,
        meetingAnchorDate: Date? = nil,
        limit: Int = 20,
        requestedAt: Date
    ) {
        self.spaceID = spaceID
        self.intent = intent
        self.query = query
        self.startDate = startDate
        self.endDate = endDate
        self.meetingAnchorDate = meetingAnchorDate
        self.limit = limit
        self.requestedAt = requestedAt
    }
}

/// Exact IDs and revisions exist only in the short-lived in-memory context. Callers must ask the
/// store to revalidate the context immediately before resolving any referenced content.
public struct ReuseReference: Hashable {
    public let objectType: ReuseObjectType
    public let objectID: UUID
    public let revision: Int
    public let localDate: Date
    public let sensitivity: Sensitivity
    public let selectionReason: ReuseSelectionReason
    public let sourceEventID: UUID?
    public let sourceEventRevision: Int?
}

public struct ReuseContext {
    public let attemptID: UUID
    public let intent: ReuseIntent
    public let rangeState: ReuseRangeState
    public let references: [ReuseReference]
    public let exclusions: Set<ReuseExclusion>
    public let createdAt: Date
    public let expiresAt: Date
}

/// Transient content resolved only after exact-revision revalidation. It is never persisted in
/// reuse telemetry and never crosses the Agent Event-only boundary.
public struct ResolvedReuseItem: Hashable {
    public let reference: ReuseReference
    public let sourceEvent: MemoryEvent
    public let memorySummary: String?
    public let memoryType: LongTermMemoryType?

    public init(
        reference: ReuseReference,
        sourceEvent: MemoryEvent,
        memorySummary: String? = nil,
        memoryType: LongTermMemoryType? = nil
    ) {
        precondition(sourceEvent.sensitivity != .restricted)
        switch reference.objectType {
        case .event:
            precondition(reference.objectID == sourceEvent.id)
            precondition(reference.revision == sourceEvent.revision)
            precondition(memorySummary == nil && memoryType == nil)
        case .longTermMemory:
            precondition(reference.sourceEventID == sourceEvent.id)
            precondition(reference.sourceEventRevision == sourceEvent.revision)
            precondition(memorySummary?.isEmpty == false && memoryType != nil)
        }
        self.reference = reference
        self.sourceEvent = sourceEvent
        self.memorySummary = memorySummary
        self.memoryType = memoryType
    }
}

public struct ResolvedReuseContext {
    public let context: ReuseContext
    public let items: [ResolvedReuseItem]

    public init(context: ReuseContext, items: [ResolvedReuseItem]) {
        precondition(items.map(\.reference) == context.references)
        self.context = context
        self.items = items
    }
}

public struct ReuseOutcomeSubmission: Sendable {
    public let attemptID: UUID
    public let outcome: ReuseOutcome
    public let userAction: ReuseUserAction
    public let submittedAt: Date

    public init(
        attemptID: UUID,
        outcome: ReuseOutcome,
        userAction: ReuseUserAction = .none,
        submittedAt: Date
    ) {
        self.attemptID = attemptID
        self.outcome = outcome
        self.userAction = userAction
        self.submittedAt = submittedAt
    }
}

public struct ReuseTelemetryAggregate: Equatable, Sendable {
    public let intent: ReuseIntent
    public let outcome: ReuseOutcome?
    public let userAction: ReuseUserAction?
    public let resultCountBucket: ReuseResultCountBucket
    public let attemptCount: Int
}

public enum ReuseError: Error, Equatable {
    case unavailable
    case invalidRequest(String)
    case attemptNotFound
    case outcomeAlreadyRecorded
    case persistenceFailed
}

/// Content-free, immutable persistence form. Digests are salted by the random attempt ID, so the
/// encrypted local envelope contains no query, content, path, or reusable raw object/lineage ID.
public struct ReuseAttemptRecord: Codable, Hashable, Sendable {
    public let attemptID: UUID
    public let intent: ReuseIntent
    public let rangeState: ReuseRangeState
    public let resultCountBucket: ReuseResultCountBucket
    public let eventReferenceDigests: [String]
    public let memoryReferenceDigests: [String]
    public let lineageDigests: [String]
    public let exclusions: [ReuseExclusion]
    public let createdAt: Date
    public let expiresAt: Date

    var isInternallyValid: Bool {
        let allDigests = eventReferenceDigests + memoryReferenceDigests + lineageDigests
        return expiresAt > createdAt &&
            Set(exclusions).count == exclusions.count &&
            allDigests.allSatisfy(Self.isSHA256)
    }

    private static func isSHA256(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

public struct ReuseOutcomeRecord: Codable, Hashable, Sendable {
    public let attemptID: UUID
    public let outcome: ReuseOutcome
    public let userAction: ReuseUserAction
    public let submittedAt: Date
}

enum ReuseTelemetry {
    static func attempt(from context: ReuseContext) -> ReuseAttemptRecord {
        let events = context.references
            .filter { $0.objectType == .event }
            .map {
                digest(
                    context.attemptID.uuidString,
                    "event",
                    $0.objectID.uuidString,
                    String($0.revision)
                )
            }
        let memories = context.references
            .filter { $0.objectType == .longTermMemory }
            .map {
                digest(
                    context.attemptID.uuidString,
                    "memory",
                    $0.objectID.uuidString,
                    String($0.revision)
                )
            }
        let lineage = context.references.compactMap { reference -> String? in
            guard let sourceID = reference.sourceEventID,
                  let sourceRevision = reference.sourceEventRevision else {
                return nil
            }
            return digest(
                context.attemptID.uuidString,
                "lineage",
                sourceID.uuidString,
                String(sourceRevision)
            )
        }
        return ReuseAttemptRecord(
            attemptID: context.attemptID,
            intent: context.intent,
            rangeState: context.rangeState,
            resultCountBucket: .forCount(context.references.count),
            eventReferenceDigests: events,
            memoryReferenceDigests: memories,
            lineageDigests: lineage,
            exclusions: context.exclusions.sorted { $0.rawValue < $1.rawValue },
            createdAt: context.createdAt,
            expiresAt: context.expiresAt
        )
    }

    private static func digest(_ values: String...) -> String {
        let payload = values.joined(separator: "\u{1f}")
        return SHA256.hash(data: Data(payload.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
