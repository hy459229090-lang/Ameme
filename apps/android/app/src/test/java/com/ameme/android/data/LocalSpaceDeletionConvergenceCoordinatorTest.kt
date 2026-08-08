package com.ameme.android.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpaceDeletionConvergenceCoordinatorTest {
    private val requestedAt = Instant.parse("2026-07-29T08:00:00Z")

    @Test
    fun successfulDeleteConvergesEveryLocalSurfaceInSafetyOrder() = runBlocking {
        val order = mutableListOf<String>()
        val coordinator = coordinator(
            stop = { order += "stop" },
            delete = {
                order += "freeze"
                deletionResult()
            },
            revoke = { order += "revoke" },
            disconnect = { order += "disconnect" },
            clearConnection = { order += "clear_connection" },
            freezePending = { order += "freeze_pending" },
            clearPending = { order += "clear_pending" },
        )

        val result = coordinator.delete(requestedAt)

        assertEquals(
            listOf(
                "stop",
                "freeze",
                "revoke",
                "disconnect",
                "clear_connection",
                "freeze_pending",
                "clear_pending",
            ),
            order,
        )
        assertEquals(LocalSpaceDeletionConvergenceStatus.CompletedLocalOnly, result.status)
        assertEquals(LocalSpaceDeletionConvergenceStep.entries.toSet(), result.completedSteps)
        assertTrue(result.pendingRetrySteps.isEmpty())
        assertTrue(result.localSpaceFrozen)
        assertTrue(result.localAgentAccessClosed)
        assertTrue(result.pendingActionSnapshotCleared)
        assertTrue(result.pendingActionResurrectionFrozen)
        assertFalse(result.accountDeletionClaim)
        assertFalse(result.peerDeletionProofClaim)
    }

    @Test
    fun failuresRemainContentFreeAndDoNotPreventLaterCleanupAttempts() = runBlocking {
        val attempted = mutableListOf<String>()
        val coordinator = coordinator(
            stop = {
                attempted += "stop"
                error("runtime close failed")
            },
            delete = {
                attempted += "freeze"
                error("database unavailable")
            },
            revoke = { attempted += "revoke" },
            disconnect = {
                attempted += "disconnect"
                error("transport close failed")
            },
            clearConnection = { attempted += "clear_connection" },
            freezePending = { attempted += "freeze_pending" },
            clearPending = { attempted += "clear_pending" },
        )

        val result = coordinator.delete(requestedAt)

        assertEquals(
            listOf("stop", "freeze", "revoke", "disconnect", "clear_connection", "clear_pending"),
            attempted,
        )
        assertEquals(LocalSpaceDeletionConvergenceStatus.PendingLocalRetry, result.status)
        assertNull(result.spaceDeletion)
        assertEquals(
            setOf(
                LocalSpaceDeletionConvergenceStep.IncomingAgentRuntimeStop,
                LocalSpaceDeletionConvergenceStep.LocalSpaceFreeze,
                LocalSpaceDeletionConvergenceStep.OutgoingAgentTransportClose,
                LocalSpaceDeletionConvergenceStep.PendingActionResurrectionFreeze,
            ),
            result.pendingRetrySteps,
        )
        assertFalse(result.localSpaceFrozen)
        assertFalse(result.localAgentAccessClosed)
        assertTrue(result.pendingActionSnapshotCleared)
    }

    @Test
    fun providerLocatorCleanupStaysSeparateFromLocalRetry() = runBlocking {
        val result = coordinator(
            delete = { deletionResult(pendingExternalCleanupCount = 2) },
        ).delete(requestedAt)

        assertEquals(LocalSpaceDeletionConvergenceStatus.PendingExternalCleanup, result.status)
        assertTrue(result.pendingRetrySteps.isEmpty())
        assertEquals(2, result.spaceDeletion?.pendingExternalCleanupCount)
        assertTrue(result.localAgentAccessClosed)
    }

    private fun coordinator(
        stop: suspend () -> Unit = {},
        delete: suspend (Instant) -> LocalSpaceDeletionResult = { deletionResult() },
        revoke: suspend () -> Unit = {},
        disconnect: suspend () -> Unit = {},
        clearConnection: suspend () -> Unit = {},
        freezePending: suspend () -> Unit = {},
        clearPending: suspend () -> Unit = {},
    ) = LocalSpaceDeletionConvergenceCoordinator(
        spaceId = SPACE_ID,
        stopIncomingAgentRuntime = stop,
        deleteLocalSpace = delete,
        revokeStoredAgentPairing = revoke,
        closeOutgoingAgentTransport = disconnect,
        clearStoredConnectionMetadata = clearConnection,
        freezePendingActionResurrection = freezePending,
        clearPendingActionSnapshot = clearPending,
    )

    private fun deletionResult(pendingExternalCleanupCount: Int = 0) = LocalSpaceDeletionResult(
        spaceId = SPACE_ID,
        status = if (pendingExternalCleanupCount == 0) {
            LocalSpaceDeletionStatus.CompletedLocalOnly
        } else {
            LocalSpaceDeletionStatus.PendingExternalCleanup
        },
        affectedEventCount = 2,
        affectedSourceCount = 1,
        pendingExternalCleanupCount = pendingExternalCleanupCount,
        deletedAt = requestedAt,
        externalOriginalsRetained = true,
    )

    private companion object {
        const val SPACE_ID = "space_personal"
    }
}
