package com.ameme.android.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalSpaceDeletionRepositoryContractTest {
    @Test fun localOnlyResultCannotClaimAccountOrPeerCompletion() {
        val result = LocalSpaceDeletionResult(
            spaceId = "space_personal",
            status = LocalSpaceDeletionStatus.CompletedLocalOnly,
            affectedEventCount = 2,
            affectedSourceCount = 1,
            pendingExternalCleanupCount = 0,
            deletedAt = Instant.EPOCH,
            externalOriginalsRetained = true,
        )

        assertFalse(result.accountDeletionClaim)
        assertFalse(result.peerDeletionProofClaim)
        assertEquals("completed_local_only", result.status.wireValue)
    }

    @Test fun accountDeletionClaimFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSpaceDeletionResult(
                spaceId = "space_personal",
                status = LocalSpaceDeletionStatus.CompletedLocalOnly,
                affectedEventCount = 0,
                affectedSourceCount = 0,
                pendingExternalCleanupCount = 0,
                deletedAt = Instant.EPOCH,
                externalOriginalsRetained = false,
                accountDeletionClaim = true,
            )
        }
    }

    @Test fun peerDeletionProofClaimAndNegativeCountsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSpaceDeletionResult(
                spaceId = "space_personal",
                status = LocalSpaceDeletionStatus.CompletedLocalOnly,
                affectedEventCount = -1,
                affectedSourceCount = 0,
                pendingExternalCleanupCount = 0,
                deletedAt = Instant.EPOCH,
                externalOriginalsRetained = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalSpaceDeletionResult(
                spaceId = "space_personal",
                status = LocalSpaceDeletionStatus.CompletedLocalOnly,
                affectedEventCount = 0,
                affectedSourceCount = 0,
                pendingExternalCleanupCount = 0,
                deletedAt = Instant.EPOCH,
                externalOriginalsRetained = false,
                peerDeletionProofClaim = true,
            )
        }
    }
}
