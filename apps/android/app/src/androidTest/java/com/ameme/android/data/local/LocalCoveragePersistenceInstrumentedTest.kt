package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.coverage.CandidateEventType
import com.ameme.android.coverage.ContextType
import com.ameme.android.coverage.CoverageCompileRequest
import com.ameme.android.coverage.CoverageCompiler
import com.ameme.android.coverage.CoverageFactStatus
import com.ameme.android.coverage.CoverageSignal
import com.ameme.android.coverage.EvidenceField
import com.ameme.android.coverage.EventHint
import com.ameme.android.coverage.GapHint
import com.ameme.android.coverage.GapReason
import com.ameme.android.coverage.MobileSourceCapabilities
import com.ameme.android.coverage.SourceState
import com.ameme.android.coverage.TimePrecision
import com.ameme.android.coverage.TimeRange
import com.ameme.android.coverage.ValueLevel
import com.ameme.android.data.CoverageAcceptanceMode
import com.ameme.android.data.CoverageCandidateAcceptance
import com.ameme.android.data.CoverageCandidateLifecycle
import com.ameme.android.data.CoverageEventLinkLifecycle
import com.ameme.android.data.PersistedCoverageDay
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.Sensitivity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCoveragePersistenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 17).toByte() }
    private val clock = Clock.fixed(
        Instant.parse("2026-07-26T08:00:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

    @After fun cleanUp() {
        files.forEach { file ->
            listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
                .forEach { if (it.exists()) it.delete() }
        }
    }

    @Test fun coveragePersistsWithoutLedgerThenExplicitAcceptAndDeleteStayConverged() {
        val file = newDatabaseFile()
        val compilation = compilation()
        val repository = open(file)
        val before = repository.loadDaySummary(compilation.localDate)
        assertEquals(0, before.ledgerRevision)
        repository.persist(PersistedCoverageDay.compiled(
            compilation,
            registryVersion = "mobile-v1",
            compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
        ))
        assertTrue(repository.loadActiveEvents().isEmpty())
        assertEquals(0, repository.loadDaySummary(compilation.localDate).ledgerRevision)
        repository.close()

        val reloaded = open(file)
        assertEquals(
            CoverageCandidateLifecycle.Open,
            reloaded.load(compilation.localDate)?.candidateStates?.get(CANDIDATE_ID),
        )
        val link = reloaded.acceptCandidate(
            CoverageCandidateAcceptance(
                dayId = DAY_ID,
                candidateId = CANDIDATE_ID,
                acceptedAt = Instant.parse("2026-07-26T08:01:00Z"),
                localDate = compilation.localDate,
                time = LocalTime.parse("16:00"),
                detail = "用户显式选择保存该计划。",
                sourceLabel = "用户选择的日历",
                captureKind = CaptureKind.Import,
                sensitivity = Sensitivity.Confidential,
                mode = CoverageAcceptanceMode.PreserveEvidence,
            ),
        )
        val event = reloaded.loadActiveEvents().single()
        assertEquals(link.eventId, event.id)
        assertEquals(FactStatus.Planned, event.factStatus)
        assertEquals(1, reloaded.loadDaySummary(compilation.localDate).ledgerRevision)
        assertEquals(CoverageCandidateLifecycle.Consumed, reloaded.load(compilation.localDate)?.candidateStates?.get(CANDIDATE_ID))

        reloaded.persist(PersistedCoverageDay.compiled(
            compilation,
            registryVersion = "mobile-v1",
            compiledAt = Instant.parse("2026-07-26T08:02:00Z"),
        ))
        assertEquals(CoverageCandidateLifecycle.Consumed, reloaded.load(compilation.localDate)?.candidateStates?.get(CANDIDATE_ID))
        assertTrue(reloaded.deleteEvent(event.id))
        assertEquals(CoverageEventLinkLifecycle.Detached, reloaded.link(DAY_ID, CANDIDATE_ID)?.lifecycle)
        assertThrows(IllegalStateException::class.java) {
            reloaded.acceptCandidate(
                CoverageCandidateAcceptance(
                    dayId = DAY_ID,
                    candidateId = CANDIDATE_ID,
                    acceptedAt = Instant.parse("2026-07-26T08:03:00Z"),
                    localDate = compilation.localDate,
                    time = null,
                    detail = "不得复活",
                    sourceLabel = "测试",
                    captureKind = CaptureKind.Import,
                ),
            )
        }
        reloaded.close()

        open(file).use { afterDelete ->
            assertTrue(afterDelete.loadActiveEvents().isEmpty())
            assertEquals(CoverageCandidateLifecycle.Consumed, afterDelete.load(compilation.localDate)?.candidateStates?.get(CANDIDATE_ID))
            assertEquals(CoverageEventLinkLifecycle.Detached, afterDelete.link(DAY_ID, CANDIDATE_ID)?.lifecycle)
        }
    }

    @Test fun v6DatabaseMigratesThroughV7ToCurrentWithoutChangingExistingEvent() {
        val file = newDatabaseFile()
        val created = open(file).use { it.capture(CaptureKind.Text, "v6 migration survivor") }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                database.setForeignKeyConstraintsEnabled(false)
                database.execSQL("DROP TABLE coverage_event_links")
                database.execSQL("DROP TABLE coverage_source_index")
                database.execSQL("DROP TABLE coverage_candidate_states")
                database.execSQL("DROP TABLE coverage_days")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 7")
                database.version = 6
            }
        } finally {
            openKey.fill(0)
        }

        open(file).use { migrated ->
            assertEquals(13, LocalEventDatabase.SCHEMA_VERSION)
            assertEquals(created.id, migrated.loadActiveEvents().single().id)
            assertNotNull(migrated.loadDaySummary(created.localDate))
        }
        LocalEventDatabase.open(
            file,
            SyntheticDatabaseKeyProvider(key),
            SPACE_ID,
        ).use {
            assertTrue(it.hasMigration(7))
            assertTrue(it.hasMigration(8))
            assertTrue(it.hasMigration(9))
            assertTrue(it.hasMigration(10))
            assertTrue(it.hasMigration(11))
            assertTrue(it.hasMigration(12))
            assertTrue(it.hasMigration(13))
        }
    }

    private fun open(file: File) = LocalMemoryRepository.open(
        context = context,
        spaceId = SPACE_ID,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = file,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private fun compilation() = CoverageCompiler(MobileSourceCapabilities.createRegistry()).compileDay(
        CoverageCompileRequest(
            dayId = DAY_ID,
            ownerId = "owner_test",
            spaceId = SPACE_ID,
            localDate = LocalDate.parse("2026-07-26"),
            timezone = "Asia/Shanghai",
            signals = listOf(
                CoverageSignal(
                    signalId = "signal_android_persist_001",
                    capabilityId = "cap_calendar",
                    sourceState = SourceState.Available,
                    contextTypes = setOf(ContextType.TimeSchedule),
                    observedFields = setOf(EvidenceField.Time, EvidenceField.Action),
                    factStatus = CoverageFactStatus.Planned,
                    confidence = 1.0,
                    importance = ValueLevel.High,
                    sourceObjectIds = setOf("source_android_calendar_001"),
                    observedAt = Instant.parse("2026-07-26T08:00:00Z"),
                    timeRange = TimeRange(
                        start = Instant.parse("2026-07-26T10:00:00Z"),
                        end = Instant.parse("2026-07-26T11:00:00Z"),
                        timezone = "Asia/Shanghai",
                        precision = TimePrecision.Range,
                    ),
                    eventHint = EventHint(CandidateEventType.Activity, "用户选择的日历计划"),
                    gapHints = listOf(
                        GapHint(
                            ContextType.ActivityResult,
                            GapReason.ActualityUnconfirmed,
                            ValueLevel.High,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun newDatabaseFile(): File = context.getDatabasePath("coverage-${UUID.randomUUID()}.db").also {
        files += it
        it.parentFile?.mkdirs()
    }

    private companion object {
        const val SPACE_ID = "space_test_coverage"
        const val DAY_ID = "day_android_persist_001"
        const val CANDIDATE_ID = "candidate_signal_android_persist_001"
    }
}
