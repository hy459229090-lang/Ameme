package com.ameme.android.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceDeletionRepositoryContractTest {
    @Test fun wireValuesDistinguishRawOnlyCascadeAndFailClosedOutcomes() {
        assertEquals("raw_only", SourceDeletionOperation.RawOnly.wireValue)
        assertEquals("source_cascade", SourceDeletionOperation.SourceCascade.wireValue)
        assertEquals("external_not_owned", SourceDeletionStatus.ExternalNotOwned.wireValue)
        assertEquals("completed_local_only", SourceDeletionStatus.CompletedLocalOnly.wireValue)
        assertEquals("lineage_unavailable", SourceDeletionStatus.LineageUnavailable.wireValue)
    }

    @Test fun deletedSourceRequiresATerminalTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSourceObject(
                sourceObjectId = "source_invalid",
                spaceId = "space_personal",
                kind = LocalSourceKind.CoverageEvidence,
                rawOwnership = LocalRawOwnership.NoRaw,
                state = LocalSourceState.Deleted,
                createdAt = Instant.EPOCH,
            )
        }
    }

    @Test fun deletionResultRejectsNegativeCountAndBackwardsTime() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceDeletionResult(
                jobId = "del_invalid",
                sourceObjectId = "source_invalid",
                operation = SourceDeletionOperation.SourceCascade,
                status = SourceDeletionStatus.Completed,
                affectedEventCount = -1,
                createdAt = Instant.EPOCH,
                completedAt = Instant.EPOCH,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceDeletionResult(
                jobId = "del_invalid",
                sourceObjectId = "source_invalid",
                operation = SourceDeletionOperation.SourceCascade,
                status = SourceDeletionStatus.Completed,
                affectedEventCount = 0,
                createdAt = Instant.ofEpochSecond(2),
                completedAt = Instant.ofEpochSecond(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceDeletionResult(
                jobId = "del_invalid_breakdown",
                sourceObjectId = "source_invalid",
                operation = SourceDeletionOperation.SourceCascade,
                status = SourceDeletionStatus.Completed,
                affectedEventCount = 1,
                recomputedEventCount = 1,
                deletedEventCount = 1,
                createdAt = Instant.EPOCH,
                completedAt = Instant.EPOCH,
            )
        }
    }
}
