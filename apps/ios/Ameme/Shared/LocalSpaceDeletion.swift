import Foundation

/// One encrypted local-store space only. This does not claim account, peer, provider-original,
/// physical-purge, Grant-revocation, or distributed deletion completion.
public enum LocalSpaceDeletionStatus: String, Codable, Hashable, Sendable {
    case completedLocalOnly = "completed_local_only"
    case pendingRawCleanup = "pending_raw_cleanup"
    case alreadyDeleted = "already_deleted"
    case persistenceFailed = "persistence_failed"
}

public struct LocalSpaceDeletionResult: Hashable, Sendable {
    public let spaceID: String
    public let status: LocalSpaceDeletionStatus
    public let affectedEventCount: Int
    public let affectedSourceCount: Int
    public let pendingRawCleanupCount: Int
    public let deletedAtEpochMilliseconds: Int64
    public let externalOriginalsRetained: Bool
    public let accountDeletionClaim: Bool
    public let peerDeletionProofClaim: Bool

    public init(
        spaceID: String = SourceObject.personalSpaceID,
        status: LocalSpaceDeletionStatus,
        affectedEventCount: Int,
        affectedSourceCount: Int,
        pendingRawCleanupCount: Int,
        deletedAtEpochMilliseconds: Int64,
        externalOriginalsRetained: Bool,
        accountDeletionClaim: Bool = false,
        peerDeletionProofClaim: Bool = false
    ) {
        precondition(!spaceID.isEmpty)
        precondition(affectedEventCount >= 0)
        precondition(affectedSourceCount >= 0)
        precondition(pendingRawCleanupCount >= 0)
        precondition(deletedAtEpochMilliseconds >= 0)
        precondition(!accountDeletionClaim)
        precondition(!peerDeletionProofClaim)
        self.spaceID = spaceID
        self.status = status
        self.affectedEventCount = affectedEventCount
        self.affectedSourceCount = affectedSourceCount
        self.pendingRawCleanupCount = pendingRawCleanupCount
        self.deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
        self.externalOriginalsRetained = externalOriginalsRetained
        self.accountDeletionClaim = accountDeletionClaim
        self.peerDeletionProofClaim = peerDeletionProofClaim
    }
}
