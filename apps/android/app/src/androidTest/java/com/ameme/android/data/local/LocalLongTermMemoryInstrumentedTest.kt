package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryInvalidationReason
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryState
import com.ameme.android.data.LongTermMemoryType
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLongTermMemoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 61).toByte() }
    private val now = Instant.parse("2026-07-26T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))

    @After fun cleanUp() {
        files.forEach { file ->
            listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
                .forEach { if (it.exists()) it.delete() }
        }
    }

    @Test fun proposalNeedsExplicitConfirmationAndEventRevisionInvalidatesAfterReopen() {
        val file = newDatabaseFile()
        val repository = open(file)
        val event = repository.capture(CaptureKind.Text, "通过生产证据完成里程碑")
        val candidate = repository.proposeLongTermMemory(
            proposal(event.id, event.revision, LongTermMemoryType.Fact, "里程碑已完成"),
        )
        assertEquals(LongTermMemoryState.EligibleForMemoryCompiler, candidate.state)
        assertTrue(repository.visibleLongTermMemories(now.plusSeconds(3)).isEmpty())

        val active = repository.confirmLongTermMemory(
            LongTermMemoryConfirmation(candidate.memoryId, now.plusSeconds(1)),
        )
        assertEquals(LongTermMemoryState.Active, active.state)
        assertEquals(listOf(active.memoryId), repository.visibleLongTermMemories(now.plusSeconds(3)).map { it.memoryId })

        val revised = repository.updateEvent(event.id, FactStatus.Confirmed, "用户补充后需重新编译")
        assertEquals(2, revised?.revision)
        assertEquals(
            LongTermMemoryState.Invalidated,
            repository.longTermMemory(active.memoryId)?.state,
        )
        assertEquals(
            LongTermMemoryInvalidationReason.EventRevisionChanged,
            repository.longTermMemory(active.memoryId)?.invalidationReason,
        )
        repository.close()

        open(file).use { reopened ->
            assertTrue(reopened.visibleLongTermMemories(now.plusSeconds(5)).isEmpty())
            assertEquals(LongTermMemoryState.Invalidated, reopened.longTermMemory(active.memoryId)?.state)
            assertThrows(IllegalStateException::class.java) {
                reopened.confirmLongTermMemory(
                    LongTermMemoryConfirmation(active.memoryId, now.plusSeconds(6)),
                )
            }
            val replay = reopened.proposeLongTermMemory(
                proposal(event.id, event.revision, LongTermMemoryType.Fact, "不得复活"),
            )
            assertEquals(active.memoryId, replay.memoryId)
            assertEquals(LongTermMemoryState.Invalidated, replay.state)
        }
    }

    @Test fun inferredAndSensitiveMemoriesStayCandidatesUntilUserConfirmationAndDeleteInvalidates() {
        val file = newDatabaseFile()
        open(file).use { repository ->
            val inferred = repository.captureSource(
                SourceCaptureRequest(
                    sourceKind = SourceKind.AgentAutonomous,
                    title = "推断偏好",
                    detail = "只用于验证边界",
                    factStatus = FactStatus.Inferred,
                    localDate = LocalDate.parse("2026-07-26"),
                    time = null,
                    evidenceState = EvidenceState.Inferred,
                ),
            )
            val candidate = repository.proposeLongTermMemory(
                proposal(inferred.id, inferred.revision, LongTermMemoryType.Preference, "偏好候选"),
            )
            assertEquals(LongTermMemoryState.CandidateUserConfirmationRequired, candidate.state)
            assertTrue(repository.visibleLongTermMemories(now.plusSeconds(3)).isEmpty())
            repository.confirmLongTermMemory(
                LongTermMemoryConfirmation(candidate.memoryId, now.plusSeconds(1)),
            )
            assertTrue(repository.deleteEvent(inferred.id))
            assertEquals(
                LongTermMemoryInvalidationReason.EventDeleted,
                repository.longTermMemory(candidate.memoryId)?.invalidationReason,
            )
            assertTrue(repository.visibleLongTermMemories(now.plusSeconds(3)).isEmpty())
        }
    }

    @Test fun explicitReplacementSupersedesOldMemoryWithoutReactivation() {
        val file = newDatabaseFile()
        open(file).use { repository ->
            val firstEvent = repository.capture(CaptureKind.Text, "原决定")
            val first = repository.proposeLongTermMemory(
                proposal(firstEvent.id, 1, LongTermMemoryType.Decision, "原决定"),
            )
            repository.confirmLongTermMemory(LongTermMemoryConfirmation(first.memoryId, now.plusSeconds(1)))

            val nextEvent = repository.capture(CaptureKind.Text, "新决定")
            val next = repository.proposeLongTermMemory(
                proposal(nextEvent.id, 1, LongTermMemoryType.Decision, "新决定"),
            )
            val replacement = repository.confirmLongTermMemory(
                LongTermMemoryConfirmation(
                    memoryId = next.memoryId,
                    confirmedAt = now.plusSeconds(2),
                    supersedesMemoryId = first.memoryId,
                ),
            )
            assertEquals(LongTermMemoryState.Superseded, repository.longTermMemory(first.memoryId)?.state)
            assertEquals(replacement.memoryId, repository.longTermMemory(first.memoryId)?.supersededByMemoryId)
            assertEquals(listOf(replacement.memoryId), repository.visibleLongTermMemories(now.plusSeconds(3)).map { it.memoryId })
        }
    }

    @Test fun v7DatabaseMigratesToV8WithoutChangingExistingEvent() {
        val file = newDatabaseFile()
        val created = open(file).use { it.capture(CaptureKind.Text, "v7 migration survivor") }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                database.setForeignKeyConstraintsEnabled(false)
                database.execSQL("DROP TRIGGER reuse_outcomes_no_delete")
                database.execSQL("DROP TRIGGER reuse_outcomes_no_update")
                database.execSQL("DROP TRIGGER reuse_attempts_no_delete")
                database.execSQL("DROP TRIGGER reuse_attempts_no_update")
                database.execSQL("DROP TABLE reuse_outcomes")
                database.execSQL("DROP TABLE reuse_attempts")
                database.execSQL("DROP TRIGGER prevent_long_term_memory_revisions_delete")
                database.execSQL("DROP TRIGGER prevent_long_term_memory_revisions_update")
                database.execSQL("DROP TABLE long_term_memory_event_dependencies")
                database.execSQL("DROP TABLE long_term_memory_revisions")
                database.execSQL("DROP TABLE long_term_memories_current")
                database.execSQL("DROP TRIGGER deletion_watermarks_no_delete")
                database.execSQL("DROP TRIGGER deletion_watermarks_no_regression")
                database.execSQL("DROP TABLE backup_checkpoints")
                database.execSQL("DROP TABLE deletion_watermarks")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 8")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 9")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 10")
                database.version = 7
            }
        } finally {
            openKey.fill(0)
        }

        open(file).use { migrated ->
            assertEquals(created.id, migrated.loadActiveEvents().single().id)
            assertTrue(migrated.visibleLongTermMemories(now).isEmpty())
        }
        LocalEventDatabase.open(
            file,
            SyntheticDatabaseKeyProvider(key),
            SPACE_ID,
        ).use {
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, it.schemaVersion())
            assertTrue(it.hasMigration(8))
            assertTrue(it.hasMigration(9))
            assertTrue(it.hasMigration(10))
            assertTrue(it.hasMigration(11))
            assertTrue(it.hasMigration(12))
            assertTrue(it.hasMigration(13))
            assertTrue(it.hasMigration(14))
        }
    }

    private fun proposal(
        eventId: String,
        revision: Int,
        type: LongTermMemoryType,
        summary: String,
    ) = LongTermMemoryProposal(
        sourceEventId = eventId,
        expectedEventRevision = revision,
        type = type,
        valueSummary = summary,
        validFrom = now,
        proposedAt = now,
    )

    private fun open(file: File) = LocalMemoryRepository.open(
        context = context,
        spaceId = SPACE_ID,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = file,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private fun newDatabaseFile(): File {
        val file = File(context.cacheDir, "ameme-long-term-memory-${java.util.UUID.randomUUID()}.db")
        files += file
        return file
    }

    companion object {
        private const val SPACE_ID = "space_personal"
    }
}
