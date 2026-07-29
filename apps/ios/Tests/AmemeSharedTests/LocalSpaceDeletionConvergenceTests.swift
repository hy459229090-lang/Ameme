import Foundation
import XCTest
@testable import AmemeShared

@MainActor
final class LocalSpaceDeletionConvergenceTests: XCTestCase {
    private let requestedAt = Date(timeIntervalSince1970: 1_759_100_500)

    func testSuccessfulDeleteConvergesEveryLocalSurfaceInSafetyOrder() async {
        var order: [String] = []
        let coordinator = LocalSpaceDeletionConvergenceCoordinator(
            closeActiveAgentTransport: {
                order.append("disconnect")
            },
            deleteLocalSpace: { requestedAt in
                order.append("freeze")
                return self.deletionResult(requestedAt: requestedAt)
            },
            clearStoredConnectionMetadata: {
                order.append("clear_connection")
            },
            freezePendingExportResurrection: {
                order.append("freeze_export")
            },
            clearPendingExportSnapshot: {
                order.append("clear_export")
            },
            freezeIncomingShareResurrection: {
                order.append("freeze_share")
            },
            clearIncomingShareHandoffs: {
                order.append("clear_share")
            }
        )

        let result = await coordinator.delete(requestedAt: requestedAt)

        XCTAssertEqual(
            order,
            [
                "disconnect",
                "freeze",
                "clear_connection",
                "freeze_export",
                "freeze_share",
                "clear_export",
                "clear_share",
            ]
        )
        XCTAssertEqual(result.status, .completedLocalOnly)
        XCTAssertEqual(result.completedSteps, Set(LocalSpaceDeletionConvergenceStep.allCases))
        XCTAssertTrue(result.pendingRetrySteps.isEmpty)
        XCTAssertTrue(result.localSpaceFrozen)
        XCTAssertTrue(result.localAgentAccessClosed)
        XCTAssertTrue(result.pendingUserPayloadsCleared)
        XCTAssertTrue(result.pendingUserPayloadResurrectionFrozen)
        XCTAssertFalse(result.accountDeletionClaim)
        XCTAssertFalse(result.peerDeletionProofClaim)
    }

    func testPartialFailureStillAttemptsLaterCleanupAndRemainsRetryable() async {
        enum SyntheticFailure: Error { case unavailable }
        var order: [String] = []
        let coordinator = LocalSpaceDeletionConvergenceCoordinator(
            closeActiveAgentTransport: {
                order.append("disconnect")
                throw SyntheticFailure.unavailable
            },
            deleteLocalSpace: { requestedAt in
                order.append("freeze")
                return LocalSpaceDeletionResult(
                    status: .persistenceFailed,
                    affectedEventCount: 0,
                    affectedSourceCount: 0,
                    pendingRawCleanupCount: 0,
                    deletedAtEpochMilliseconds: Int64(requestedAt.timeIntervalSince1970 * 1_000),
                    externalOriginalsRetained: false
                )
            },
            clearStoredConnectionMetadata: {
                order.append("clear_connection")
            },
            freezePendingExportResurrection: {
                order.append("freeze_export")
            },
            clearPendingExportSnapshot: {
                order.append("clear_export")
                throw SyntheticFailure.unavailable
            },
            freezeIncomingShareResurrection: {
                order.append("freeze_share")
            },
            clearIncomingShareHandoffs: {
                order.append("clear_share")
            }
        )

        let result = await coordinator.delete(requestedAt: requestedAt)

        XCTAssertEqual(
            order,
            ["disconnect", "freeze", "clear_connection", "clear_export", "clear_share"]
        )
        XCTAssertEqual(result.status, .pendingLocalRetry)
        XCTAssertEqual(
            result.pendingRetrySteps,
            [
                .activeAgentTransportClose,
                .localSpaceFreeze,
                .pendingExportSnapshotClear,
                .pendingExportResurrectionFreeze,
                .incomingShareResurrectionFreeze,
            ]
        )
        XCTAssertFalse(result.localSpaceFrozen)
        XCTAssertFalse(result.localAgentAccessClosed)
        XCTAssertFalse(result.pendingUserPayloadsCleared)
    }

    private func deletionResult(requestedAt: Date) -> LocalSpaceDeletionResult {
        LocalSpaceDeletionResult(
            status: .completedLocalOnly,
            affectedEventCount: 2,
            affectedSourceCount: 1,
            pendingRawCleanupCount: 0,
            deletedAtEpochMilliseconds: Int64(requestedAt.timeIntervalSince1970 * 1_000),
            externalOriginalsRetained: true
        )
    }
}
