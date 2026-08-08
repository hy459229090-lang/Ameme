package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.LocalSpaceDeletionStatus
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryType
import com.ameme.android.data.ReuseIntent
import com.ameme.android.data.ReuseOutcome
import com.ameme.android.data.ReuseOutcomeSubmission
import com.ameme.android.data.ReuseRequest
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSpaceDeletionInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cleanupRoots = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 97).toByte() }
    private val now = Instant.parse("2026-07-26T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @After fun cleanUp() {
        cleanupRoots.forEach { root ->
            if (root.isDirectory) {
                root.deleteRecursively()
            } else {
                listOf(
                    root,
                    File("${root.path}-wal"),
                    File("${root.path}-shm"),
                    File("${root.path}-journal"),
                ).forEach { if (it.exists()) it.delete() }
            }
        }
    }

    @Test fun localSpaceDeleteFreezesWritesRejectsOldBackupAndLeavesOtherSpaceUntouched() {
        val databaseFile = newDatabaseFile()
        val backupRoot = newDirectory("space-delete-backup")
        val otherEvent = open(databaseFile, OTHER_SPACE_ID).use {
            it.capture(CaptureKind.Text, "另一个空间必须保持可用")
        }

        lateinit var memoryID: String
        open(databaseFile).use { repository ->
            val textEvent = repository.capture(CaptureKind.Text, "重要项目决定")
            val externalEvent = repository.captureSource(
                SourceCaptureRequest(
                    sourceKind = SourceKind.PhotoPicker,
                    title = "用户选择的外部照片",
                    detail = "Ameme 只持有 provider locator。",
                    factStatus = FactStatus.Confirmed,
                    localDate = LocalDate.parse("2026-07-26"),
                    time = null,
                    locatorUri = "content://photos/space-delete",
                    locatorPermissionState = LocatorPermissionState.PersistedRead,
                    sourceInstanceKey = "space-delete-photo",
                ),
            )
            val candidate = repository.proposeLongTermMemory(
                LongTermMemoryProposal(
                    sourceEventId = textEvent.id,
                    expectedEventRevision = textEvent.revision,
                    type = LongTermMemoryType.Decision,
                    valueSummary = "继续推进重要项目",
                    validFrom = now,
                    proposedAt = now,
                ),
            )
            memoryID = candidate.memoryId
            repository.confirmLongTermMemory(LongTermMemoryConfirmation(memoryID, now.plusSeconds(1)))
            val reuse = repository.buildReuseContext(
                ReuseRequest(
                    spaceId = SPACE_ID,
                    intent = ReuseIntent.HistoricalSearch,
                    query = "重要项目",
                    requestedAt = now.plusSeconds(2),
                ),
            )
            assertTrue(repository.recordReuseOutcome(
                ReuseOutcomeSubmission(
                    attemptId = reuse.attemptId,
                    outcome = ReuseOutcome.Useful,
                    submittedAt = now.plusSeconds(3),
                ),
            ))
            repository.createLocalRecoveryBackup(backupRoot, now.plusSeconds(4))

            val result = repository.deleteLocalSpace(now.plusSeconds(5))
            assertEquals(LocalSpaceDeletionStatus.PendingExternalCleanup, result.status)
            assertEquals(2, result.affectedEventCount)
            assertEquals(1, result.affectedSourceCount)
            assertEquals(1, result.pendingExternalCleanupCount)
            assertTrue(result.externalOriginalsRetained)
            assertFalse(result.accountDeletionClaim)
            assertFalse(result.peerDeletionProofClaim)
            assertTrue(repository.isLocalSpaceDeleted())
            assertTrue(repository.loadActiveEvents().isEmpty())
            assertNull(repository.longTermMemory(memoryID))
            assertEquals(0, repository.helpfulReuseCount(now.minusSeconds(1)))
            assertTrue(repository.reuseTelemetryAggregates(now.minusSeconds(1)).isEmpty())
            assertThrows(IllegalStateException::class.java) {
                repository.capture(CaptureKind.Text, "删除后的写入必须失败")
            }
            assertThrows(IllegalArgumentException::class.java) {
                repository.verifyLocalRecoveryBackup(backupRoot)
            }

            assertEquals(externalEvent.id, repository.pendingSourceLocatorReleases().single().eventId)
            assertTrue(repository.markSourceLocatorReleased(externalEvent.id))
            val replay = repository.deleteLocalSpace(now.plusSeconds(6))
            assertEquals(LocalSpaceDeletionStatus.AlreadyDeleted, replay.status)
            assertEquals(0, replay.pendingExternalCleanupCount)
        }

        open(databaseFile).use { reloaded ->
            assertTrue(reloaded.isLocalSpaceDeleted())
            assertTrue(reloaded.loadActiveEvents().isEmpty())
            assertThrows(IllegalStateException::class.java) {
                reloaded.capture(CaptureKind.Text, "重启后也不能复活")
            }
            assertThrows(IllegalArgumentException::class.java) {
                reloaded.restoreLocalRecoveryCandidate(
                    backupRoot,
                    newDirectory("rejected-space-restore"),
                )
            }
        }
        open(databaseFile, OTHER_SPACE_ID).use { other ->
            assertFalse(other.isLocalSpaceDeleted())
            assertEquals(otherEvent.id, other.loadActiveEvents().single().id)
        }
    }

    @Test fun rootWatermarkFailureRollsBackWholeLocalSpaceDelete() {
        val databaseFile = newDatabaseFile()
        val event = open(databaseFile).use {
            it.capture(CaptureKind.Text, "失败时必须仍然可见")
        }
        withRawDatabase(databaseFile) { database ->
            database.execSQL(
                """
                    CREATE TRIGGER force_space_root_watermark_failure
                    BEFORE INSERT ON deletion_watermarks
                    WHEN NEW.object_type = 'SPACE'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced space root failure');
                    END
                """.trimIndent(),
            )
        }
        open(databaseFile).use { repository ->
            assertThrows(RuntimeException::class.java) {
                repository.deleteLocalSpace(now.plusSeconds(1))
            }
            assertFalse(repository.isLocalSpaceDeleted())
            assertEquals(event.id, repository.loadActiveEvents().single().id)
        }
    }

    private fun open(file: File, spaceID: String = SPACE_ID) = LocalMemoryRepository.open(
        context = context,
        spaceId = spaceID,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = file,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private fun withRawDatabase(file: File, block: (SQLiteDatabase) -> Unit) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use(block)
        } finally {
            openKey.fill(0)
        }
    }

    private fun newDatabaseFile(): File =
        File(context.cacheDir, "ameme-space-delete-${UUID.randomUUID()}.db").also {
            cleanupRoots += it
        }

    private fun newDirectory(prefix: String): File =
        File(context.cacheDir, "$prefix-${UUID.randomUUID()}").also {
            cleanupRoots += it
        }

    private companion object {
        const val SPACE_ID = "space_personal"
        const val OTHER_SPACE_ID = "space_other"
    }
}
