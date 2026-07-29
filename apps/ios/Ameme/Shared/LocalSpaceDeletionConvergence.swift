import Foundation

/// Content-free checkpoints for data that lives outside the encrypted Event Node.
///
/// These checkpoints apply only to the current installation. They do not claim account deletion,
/// remote-peer acknowledgement, provider-original deletion, or physical media erasure.
public enum LocalSpaceDeletionConvergenceStep: String, Codable, CaseIterable, Hashable, Sendable {
    case activeAgentTransportClose = "active_agent_transport_close"
    case localSpaceFreeze = "local_space_freeze"
    case storedConnectionMetadataClear = "stored_connection_metadata_clear"
    case pendingExportSnapshotClear = "pending_export_snapshot_clear"
    case pendingExportResurrectionFreeze = "pending_export_resurrection_freeze"
    case incomingShareHandoffsClear = "incoming_share_handoffs_clear"
    case incomingShareResurrectionFreeze = "incoming_share_resurrection_freeze"
}

public enum LocalSpaceDeletionConvergenceStatus: String, Codable, Hashable, Sendable {
    case completedLocalOnly = "completed_local_only"
    case pendingLocalRetry = "pending_local_retry"
    case pendingExternalCleanup = "pending_external_cleanup"
}

public struct LocalSpaceDeletionConvergenceResult: Hashable, Sendable {
    public let spaceID: String
    public let status: LocalSpaceDeletionConvergenceStatus
    public let spaceDeletion: LocalSpaceDeletionResult?
    public let completedSteps: Set<LocalSpaceDeletionConvergenceStep>
    public let pendingRetrySteps: Set<LocalSpaceDeletionConvergenceStep>
    public let requestedAtEpochMilliseconds: Int64
    public let accountDeletionClaim: Bool
    public let peerDeletionProofClaim: Bool

    public var localSpaceFrozen: Bool {
        completedSteps.contains(.localSpaceFreeze)
    }

    public var localAgentAccessClosed: Bool {
        completedSteps.contains(.activeAgentTransportClose) &&
            completedSteps.contains(.storedConnectionMetadataClear)
    }

    public var pendingUserPayloadsCleared: Bool {
        completedSteps.contains(.pendingExportSnapshotClear) &&
            completedSteps.contains(.incomingShareHandoffsClear)
    }

    public var pendingUserPayloadResurrectionFrozen: Bool {
        completedSteps.contains(.pendingExportResurrectionFreeze) &&
            completedSteps.contains(.incomingShareResurrectionFreeze)
    }

    fileprivate init(
        spaceID: String,
        status: LocalSpaceDeletionConvergenceStatus,
        spaceDeletion: LocalSpaceDeletionResult?,
        completedSteps: Set<LocalSpaceDeletionConvergenceStep>,
        pendingRetrySteps: Set<LocalSpaceDeletionConvergenceStep>,
        requestedAt: Date
    ) {
        precondition(!spaceID.isEmpty)
        precondition(completedSteps.isDisjoint(with: pendingRetrySteps))
        precondition(
            completedSteps.union(pendingRetrySteps) ==
                Set(LocalSpaceDeletionConvergenceStep.allCases)
        )
        precondition(
            (spaceDeletion == nil || spaceDeletion?.status == .persistenceFailed) ==
                pendingRetrySteps.contains(.localSpaceFreeze)
        )
        precondition(spaceDeletion == nil || spaceDeletion?.spaceID == spaceID)
        self.spaceID = spaceID
        self.status = status
        self.spaceDeletion = spaceDeletion
        self.completedSteps = completedSteps
        self.pendingRetrySteps = pendingRetrySteps
        self.requestedAtEpochMilliseconds = Int64(requestedAt.timeIntervalSince1970 * 1_000)
        self.accountDeletionClaim = false
        self.peerDeletionProofClaim = false
    }
}

/// Attempts every local checkpoint even after a partial failure. Replaying `delete` retries all
/// checkpoints; thrown errors are reduced to content-free step identifiers in the result.
@MainActor
public struct LocalSpaceDeletionConvergenceCoordinator {
    private let spaceID: String
    private let closeActiveAgentTransport: @MainActor () async throws -> Void
    private let deleteLocalSpace: @MainActor (Date) throws -> LocalSpaceDeletionResult
    private let clearStoredConnectionMetadata: @MainActor () throws -> Void
    private let freezePendingExportResurrection: @MainActor () throws -> Void
    private let clearPendingExportSnapshot: @MainActor () throws -> Void
    private let freezeIncomingShareResurrection: @MainActor () throws -> Void
    private let clearIncomingShareHandoffs: @MainActor () throws -> Void

    public init(
        spaceID: String = SourceObject.personalSpaceID,
        closeActiveAgentTransport: @escaping @MainActor () async throws -> Void,
        deleteLocalSpace: @escaping @MainActor (Date) throws -> LocalSpaceDeletionResult,
        clearStoredConnectionMetadata: @escaping @MainActor () throws -> Void,
        freezePendingExportResurrection: @escaping @MainActor () throws -> Void,
        clearPendingExportSnapshot: @escaping @MainActor () throws -> Void,
        freezeIncomingShareResurrection: @escaping @MainActor () throws -> Void,
        clearIncomingShareHandoffs: @escaping @MainActor () throws -> Void
    ) {
        precondition(!spaceID.isEmpty)
        self.spaceID = spaceID
        self.closeActiveAgentTransport = closeActiveAgentTransport
        self.deleteLocalSpace = deleteLocalSpace
        self.clearStoredConnectionMetadata = clearStoredConnectionMetadata
        self.freezePendingExportResurrection = freezePendingExportResurrection
        self.clearPendingExportSnapshot = clearPendingExportSnapshot
        self.freezeIncomingShareResurrection = freezeIncomingShareResurrection
        self.clearIncomingShareHandoffs = clearIncomingShareHandoffs
    }

    public func delete(requestedAt: Date = .now) async -> LocalSpaceDeletionConvergenceResult {
        var completed = Set<LocalSpaceDeletionConvergenceStep>()
        var pending = Set<LocalSpaceDeletionConvergenceStep>()
        var deletion: LocalSpaceDeletionResult?

        do {
            try await closeActiveAgentTransport()
            completed.insert(.activeAgentTransportClose)
        } catch {
            pending.insert(.activeAgentTransportClose)
        }

        do {
            let result = try deleteLocalSpace(requestedAt)
            precondition(result.spaceID == spaceID)
            deletion = result
            if result.status == .persistenceFailed {
                pending.insert(.localSpaceFreeze)
            } else {
                completed.insert(.localSpaceFreeze)
            }
        } catch {
            pending.insert(.localSpaceFreeze)
        }

        attempt(.storedConnectionMetadataClear, completed: &completed, pending: &pending) {
            try clearStoredConnectionMetadata()
        }
        if completed.contains(.localSpaceFreeze) {
            attempt(
                .pendingExportResurrectionFreeze,
                completed: &completed,
                pending: &pending
            ) {
                try freezePendingExportResurrection()
            }
            attempt(
                .incomingShareResurrectionFreeze,
                completed: &completed,
                pending: &pending
            ) {
                try freezeIncomingShareResurrection()
            }
        } else {
            pending.insert(.pendingExportResurrectionFreeze)
            pending.insert(.incomingShareResurrectionFreeze)
        }
        attempt(.pendingExportSnapshotClear, completed: &completed, pending: &pending) {
            try clearPendingExportSnapshot()
        }
        attempt(.incomingShareHandoffsClear, completed: &completed, pending: &pending) {
            try clearIncomingShareHandoffs()
        }

        let status: LocalSpaceDeletionConvergenceStatus
        if !pending.isEmpty {
            status = .pendingLocalRetry
        } else if let deletion, deletion.pendingRawCleanupCount > 0 {
            status = .pendingExternalCleanup
        } else {
            status = .completedLocalOnly
        }
        return LocalSpaceDeletionConvergenceResult(
            spaceID: spaceID,
            status: status,
            spaceDeletion: deletion,
            completedSteps: completed,
            pendingRetrySteps: pending,
            requestedAt: requestedAt
        )
    }

    private func attempt(
        _ step: LocalSpaceDeletionConvergenceStep,
        completed: inout Set<LocalSpaceDeletionConvergenceStep>,
        pending: inout Set<LocalSpaceDeletionConvergenceStep>,
        operation: () throws -> Void
    ) {
        do {
            try operation()
            completed.insert(step)
        } catch {
            pending.insert(step)
        }
    }

}
