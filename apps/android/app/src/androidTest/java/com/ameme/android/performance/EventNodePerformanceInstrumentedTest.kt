package com.ameme.android.performance

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.SqlCipherRuntime
import com.ameme.android.data.local.SyntheticDatabaseKeyProvider
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventNodePerformanceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFiles = mutableListOf<File>()
    private val key = ByteArray(32) { index -> (index + 17).toByte() }

    @After
    fun cleanUpDatabases() {
        databaseFiles.forEach(::deleteDatabaseFiles)
    }

    @Test
    fun synthetic10kAnd100kCapacitySearchAndPaginationBaseline() {
        assumeTrue(
            "Run explicitly with -Pandroid.testInstrumentationRunnerArguments.amemePerformance=true",
            InstrumentationRegistry.getArguments().getString("amemePerformance") == "true",
        )

        val datasets = JSONArray()
        DATASET_SIZES.forEach { eventCount -> datasets.put(measureDataset(eventCount)) }
        val report = JSONObject()
            .put("schema", "ameme.android.db01.performance.v2")
            .put("synthetic_data_only", true)
            .put("contains_user_content", false)
            .put(
                "environment",
                JSONObject()
                    .put("sdk_int", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER),
            )
            .put("memory_sampling", "coarse process snapshots on one AVD; not peak or physical-device evidence")
            .put("datasets", datasets)
            .put("migration_v3_to_current", measureV3Migration())

        context.filesDir.resolve(REPORT_FILE).writeText(report.toString(2))
        assertEquals(DATASET_SIZES.size, datasets.length())
    }

    private fun measureDataset(eventCount: Int): JSONObject {
        val file = context.getDatabasePath("db01-performance-$eventCount.db").also {
            deleteDatabaseFiles(it)
            databaseFiles += it
            it.parentFile?.mkdirs()
        }
        val database = LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_ID)
        val insertMs = elapsedMs {
            var offset = 0
            while (offset < eventCount) {
                val end = minOf(offset + BATCH_SIZE, eventCount)
                val batch = (offset until end).map(::syntheticEvent)
                assertEquals(batch.size, database.insertCapturedBatch(batch))
                offset = end
            }
        }
        database.checkpointWal()
        val cipherVersion = database.cipherVersion()
        val initialBackend = database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).searchBackend.name
        database.close()

        val databaseBytes = databaseFilesSize(file)
        val memoryBeforeOpen = coarseProcessMemorySnapshot()
        lateinit var reopened: LocalEventDatabase
        val coldOpenMs = elapsedMs {
            reopened = LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_ID, enableFts = true)
        }
        val memoryAfterOpen = coarseProcessMemorySnapshot()
        val fts = try {
            measureFtsPath(reopened, eventCount)
        } finally {
            reopened.close()
        }

        lateinit var likeDatabase: LocalEventDatabase
        val likeOpenMs = elapsedMs {
            likeDatabase = LocalEventDatabase.open(
                file,
                SyntheticDatabaseKeyProvider(key),
                SPACE_ID,
                enableFts = false,
            )
        }
        val like = try {
            measureLikePath(likeDatabase)
        } finally {
            likeDatabase.close()
        }
        assertEquals(fts.keywordIds, like.keywordIds)
        assertEquals(fts.multiFieldIds, like.multiFieldIds)

        val ftsKeywordMetrics = fts.keyword.toJson()
        if (eventCount == 100_000) {
            ftsKeywordMetrics
                .put("target_p95_ms", FTS_100K_FIRST_PAGE_P95_TARGET_MS)
                .put("p95_le_target", fts.keyword.timings.p95 <= FTS_100K_FIRST_PAGE_P95_TARGET_MS)
                .put("target_scope", "DB query on one API 36 AVD; not UI search or physical-device evidence")
        }
        return JSONObject()
            .put("event_count", eventCount)
            .put("batch_size", BATCH_SIZE)
            .put("schema_version", fts.schemaVersion)
            .put("cipher_version", cipherVersion)
            .put("initial_search_backend", initialBackend)
            .put("insert_ms", insertMs)
            .put("events_per_second", eventCount * 1_000.0 / insertMs.coerceAtLeast(1.0))
            .put("database_bytes", databaseBytes)
            .put("bytes_per_event", databaseBytes.toDouble() / eventCount)
            .put("cold_open_with_existing_fts_ms", coldOpenMs)
            .put("first_page_ms", fts.firstPage.toJson())
            .put("fts_keyword_page_ms", ftsKeywordMetrics)
            .put("fts_multi_field_page_ms", fts.multiField.toJson())
            .put("date_filter_page_ms", fts.date.toJson())
            .put("pagination_walk", fts.paginationWalk)
            .put(
                "single_capture_commit_ms",
                fts.singleCapture.toJson()
                    .put("target_p95_ms", SINGLE_CAPTURE_P95_TARGET_MS)
                    .put("p95_le_target", fts.singleCapture.p95 <= SINGLE_CAPTURE_P95_TARGET_MS),
            )
            .put(
                "forced_like_comparison",
                JSONObject()
                    .put("open_ms", likeOpenMs)
                    .put("keyword_page_ms", like.keyword.toJson())
                    .put("multi_field_page_ms", like.multiField.toJson())
                    .put("keyword_result_ids_equal", fts.keywordIds == like.keywordIds)
                    .put("multi_field_result_ids_equal", fts.multiFieldIds == like.multiFieldIds)
                    .put("result_ids_emitted", false),
            )
            .put(
                "coarse_process_memory",
                JSONObject()
                    .put("before_cold_open", memoryBeforeOpen.toJson())
                    .put("after_cold_open", memoryAfterOpen.toJson())
                    .put("after_queries_and_single_commits", fts.memoryAfterQueries.toJson())
                    .put("pss_delta_open_kb", memoryAfterOpen.pssKb - memoryBeforeOpen.pssKb)
                    .put("pss_delta_total_kb", fts.memoryAfterQueries.pssKb - memoryBeforeOpen.pssKb),
            )
    }

    private fun measureFtsPath(database: LocalEventDatabase, eventCount: Int): FtsMeasurements {
        val firstPage = samples { database.readPage("", null, null, PAGE_SIZE).events.size }
        val keyword = samples { database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).events.size }
        val multiField = samples { database.readPage(MULTI_FIELD_KEYWORD, null, null, PAGE_SIZE).events.size }
        val date = samples {
            database.readPage("common", BASE_DATE.minusDays(42L), null, PAGE_SIZE).events.size
        }
        val keywordIds = database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).events.map(MemoryEvent::id)
        val multiFieldIds = database.readPage(MULTI_FIELD_KEYWORD, null, null, PAGE_SIZE).events.map(MemoryEvent::id)
        val walk = walkPages(database, eventCount)
        val singleCapture = singleCaptureSamples(database, eventCount)
        assertEquals(PAGE_SIZE, firstPage.lastResult)
        assertTrue(keyword.lastResult > 0)
        assertTrue(multiField.lastResult > 0)
        assertTrue(date.lastResult > 0)
        assertEquals("Fts5", database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).searchBackend.name)
        return FtsMeasurements(
            schemaVersion = database.schemaVersion(),
            firstPage = firstPage,
            keyword = keyword,
            multiField = multiField,
            date = date,
            keywordIds = keywordIds,
            multiFieldIds = multiFieldIds,
            paginationWalk = walk,
            singleCapture = singleCapture,
            memoryAfterQueries = coarseProcessMemorySnapshot(),
        )
    }

    private fun measureLikePath(database: LocalEventDatabase): LikeMeasurements {
        val keyword = samples { database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).events.size }
        val multiField = samples { database.readPage(MULTI_FIELD_KEYWORD, null, null, PAGE_SIZE).events.size }
        val keywordIds = database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).events.map(MemoryEvent::id)
        val multiFieldIds = database.readPage(MULTI_FIELD_KEYWORD, null, null, PAGE_SIZE).events.map(MemoryEvent::id)
        assertTrue(keyword.lastResult > 0)
        assertTrue(multiField.lastResult > 0)
        assertEquals("LikeFallback", database.readPage(COMMON_KEYWORD, null, null, PAGE_SIZE).searchBackend.name)
        return LikeMeasurements(keyword, multiField, keywordIds, multiFieldIds)
    }

    private fun singleCaptureSamples(database: LocalEventDatabase, eventCount: Int): TimingSeries {
        repeat(WARMUP_RUNS) { sample ->
            database.insertCaptured(syntheticSingleCaptureEvent(eventCount, "warmup-$sample"))
        }
        val values = mutableListOf<Double>()
        repeat(SINGLE_CAPTURE_SAMPLE_RUNS) { sample ->
            values += elapsedMs {
                database.insertCaptured(syntheticSingleCaptureEvent(eventCount, "sample-$sample"))
            }
        }
        return TimingSeries(values.sorted())
    }

    private fun measureV3Migration(): JSONObject {
        val timings = mutableListOf<Double>()
        var lastSchema = 0
        var lastFirstPageRows = 0
        repeat(MIGRATION_SAMPLE_RUNS) { sample ->
            val file = context.getDatabasePath("db01-migration-v3-$sample.db").also {
                deleteDatabaseFiles(it)
                databaseFiles += it
                it.parentFile?.mkdirs()
            }
            createVersionThreeDatabase(file, MIGRATION_EVENT_COUNT)
            lateinit var migrated: LocalEventDatabase
            timings += elapsedMs {
                migrated = LocalEventDatabase.open(
                    file,
                    SyntheticDatabaseKeyProvider(key),
                    SPACE_ID,
                    enableFts = false,
                )
            }
            try {
                lastSchema = migrated.schemaVersion()
                lastFirstPageRows = migrated.readPage("", null, null, PAGE_SIZE).events.size
                assertTrue(migrated.hasMigration(LocalEventDatabase.SCHEMA_VERSION))
            } finally {
                migrated.close()
            }
        }
        assertEquals(LocalEventDatabase.SCHEMA_VERSION, lastSchema)
        assertEquals(PAGE_SIZE, lastFirstPageRows)
        return JSONObject()
            .put("fixture_source_schema_version", 3)
            .put("target_schema_version", LocalEventDatabase.SCHEMA_VERSION)
            .put("fixture_event_count", MIGRATION_EVENT_COUNT)
            .put("samples", MIGRATION_SAMPLE_RUNS)
            .put("fts_backfill_included", false)
            .put("open_and_migrate_ms", TimingSeries(timings.sorted()).toJson())
            .put("post_migration_first_page_rows", lastFirstPageRows)
    }

    private fun createVersionThreeDatabase(file: File, eventCount: Int) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        val database = SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null)
        try {
            database.execSQL(
                """
                    CREATE TABLE events_current (
                        space_id TEXT NOT NULL, event_id TEXT NOT NULL, revision_head_id TEXT NOT NULL,
                        revision INTEGER NOT NULL, local_date TEXT NOT NULL, local_time TEXT,
                        title TEXT NOT NULL, detail TEXT NOT NULL, fact_status TEXT NOT NULL,
                        source_label TEXT NOT NULL, is_local_only INTEGER NOT NULL, user_words TEXT,
                        state TEXT NOT NULL, updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, event_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE event_revisions (
                        revision_id TEXT PRIMARY KEY NOT NULL, space_id TEXT NOT NULL,
                        event_id TEXT NOT NULL, revision INTEGER NOT NULL, base_revision_id TEXT,
                        reason TEXT NOT NULL, local_date TEXT NOT NULL, local_time TEXT,
                        title TEXT NOT NULL, detail TEXT NOT NULL, fact_status TEXT NOT NULL,
                        source_label TEXT NOT NULL, is_local_only INTEGER NOT NULL, user_words TEXT,
                        state TEXT NOT NULL, created_at INTEGER NOT NULL,
                        UNIQUE(space_id, event_id, revision)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, applied_at INTEGER NOT NULL)",
            )
            database.execSQL(
                "CREATE INDEX idx_events_current_space_date_state ON events_current(space_id, local_date, state)",
            )
            database.execSQL(
                "CREATE INDEX idx_event_revisions_space_event_revision ON event_revisions(space_id, event_id, revision)",
            )
            database.execSQL(
                "CREATE TRIGGER prevent_event_revisions_update BEFORE UPDATE ON event_revisions BEGIN SELECT RAISE(ABORT, 'event_revisions is append-only'); END",
            )
            database.execSQL(
                "CREATE TRIGGER prevent_event_revisions_delete BEFORE DELETE ON event_revisions BEGIN SELECT RAISE(ABORT, 'event_revisions is append-only'); END",
            )
            database.execSQL(
                "INSERT INTO schema_migrations(version, name, applied_at) VALUES (3, 'synthetic_v3_fixture', 1720000000000)",
            )
            database.beginTransaction()
            try {
                repeat(eventCount) { index ->
                    val eventId = "evt_migration_${index.toString().padStart(6, '0')}"
                    val revisionId = "rev_migration_${index.toString().padStart(6, '0')}"
                    val current = syntheticV3Values(index).apply {
                        put("space_id", SPACE_ID)
                        put("event_id", eventId)
                        put("revision_head_id", revisionId)
                        put("revision", 1)
                        put("updated_at", FIXED_UPDATED_AT + index)
                    }
                    database.insertOrThrow("events_current", null, current)
                    val revision = syntheticV3Values(index).apply {
                        put("revision_id", revisionId)
                        put("space_id", SPACE_ID)
                        put("event_id", eventId)
                        put("revision", 1)
                        putNull("base_revision_id")
                        put("reason", "synthetic_v3_fixture")
                        put("created_at", FIXED_UPDATED_AT + index)
                    }
                    database.insertOrThrow("event_revisions", null, revision)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            database.version = 3
        } finally {
            try {
                database.close()
            } finally {
                openKey.fill(0)
            }
        }
    }

    private fun syntheticV3Values(index: Int) = ContentValues().apply {
        put("local_date", BASE_DATE.minusDays((index % 365).toLong()).toString())
        put("local_time", LocalTime.of((index % 1440) / 60, index % 60).toString())
        put("title", "synthetic migration title")
        put("detail", "synthetic migration detail")
        put("fact_status", FactStatus.Confirmed.name)
        put("source_label", "synthetic DB-01 migration fixture")
        put("is_local_only", 1)
        putNull("user_words")
        put("state", LocalEventDatabase.STATE_ACTIVE)
    }

    private fun walkPages(database: LocalEventDatabase, eventCount: Int): JSONObject {
        val targetPages = minOf(MAX_WALK_PAGES, ceil(eventCount.toDouble() / PAGE_SIZE).toInt())
        var cursor: String? = null
        var pages = 0
        var events = 0
        val elapsed = elapsedMs {
            while (pages < targetPages) {
                val page = database.readPage("", null, cursor, PAGE_SIZE)
                events += page.events.size
                pages++
                cursor = page.nextCursor
                if (cursor == null) break
            }
        }
        return JSONObject()
            .put("page_size", PAGE_SIZE)
            .put("pages", pages)
            .put("events", events)
            .put("elapsed_ms", elapsed)
            .put("average_page_ms", elapsed / pages.coerceAtLeast(1))
    }

    private fun samples(block: () -> Int): CountedTiming {
        repeat(WARMUP_RUNS) { block() }
        val values = mutableListOf<Double>()
        var lastResult = 0
        repeat(QUERY_SAMPLE_RUNS) {
            val start = SystemClock.elapsedRealtimeNanos()
            lastResult = block()
            values += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        return CountedTiming(TimingSeries(values.sorted()), lastResult)
    }

    private fun elapsedMs(block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    private fun coarseProcessMemorySnapshot(): ProcessMemorySnapshot {
        Runtime.getRuntime().gc()
        SystemClock.sleep(MEMORY_SETTLE_MS)
        val runtime = Runtime.getRuntime()
        return ProcessMemorySnapshot(
            pssKb = Debug.getPss(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
        )
    }

    private fun syntheticEvent(index: Int): MemoryEvent {
        val dayOffset = index % 365
        val minute = index % (24 * 60)
        return MemoryEvent(
            id = "evt_perf_${index.toString().padStart(6, '0')}",
            localDate = BASE_DATE.minusDays(dayOffset.toLong()),
            time = LocalTime.of(minute / 60, minute % 60),
            title = "synthetic common marker${index % 100}",
            detail = "synthetic performance detail${index % 100}",
            factStatus = FactStatus.Confirmed,
            sourceLabel = "synthetic DB-01 fixture",
            isLocalOnly = true,
        )
    }

    private fun syntheticSingleCaptureEvent(eventCount: Int, sample: String) = MemoryEvent(
        id = "evt_perf_single_${eventCount}_$sample",
        localDate = BASE_DATE,
        time = LocalTime.NOON,
        title = "synthetic isolated commit",
        detail = "synthetic single transaction sample",
        factStatus = FactStatus.Confirmed,
        sourceLabel = "synthetic DB-01 single commit",
        isLocalOnly = true,
    )

    private fun databaseFilesSize(file: File): Long = listOf(
        file,
        File("${file.path}-wal"),
        File("${file.path}-shm"),
    ).sumOf { candidate -> if (candidate.exists()) candidate.length() else 0L }

    private fun deleteDatabaseFiles(file: File) {
        listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
            .forEach { candidate -> if (candidate.exists()) candidate.delete() }
    }

    private data class FtsMeasurements(
        val schemaVersion: Int,
        val firstPage: CountedTiming,
        val keyword: CountedTiming,
        val multiField: CountedTiming,
        val date: CountedTiming,
        val keywordIds: List<String>,
        val multiFieldIds: List<String>,
        val paginationWalk: JSONObject,
        val singleCapture: TimingSeries,
        val memoryAfterQueries: ProcessMemorySnapshot,
    )

    private data class LikeMeasurements(
        val keyword: CountedTiming,
        val multiField: CountedTiming,
        val keywordIds: List<String>,
        val multiFieldIds: List<String>,
    )

    private data class CountedTiming(
        val timings: TimingSeries,
        val lastResult: Int,
    ) {
        fun toJson(): JSONObject = timings.toJson().put("result_count", lastResult)
    }

    private data class TimingSeries(val sortedMs: List<Double>) {
        val p95: Double get() = percentile(0.95)

        fun toJson(): JSONObject = JSONObject()
            .put("samples", sortedMs.size)
            .put("p50", percentile(0.50))
            .put("p95", p95)
            .put("max", sortedMs.last())

        private fun percentile(fraction: Double): Double {
            val index = ceil(sortedMs.size * fraction).toInt().coerceIn(1, sortedMs.size) - 1
            return sortedMs[index]
        }
    }

    private data class ProcessMemorySnapshot(
        val pssKb: Long,
        val javaHeapUsedBytes: Long,
        val nativeHeapAllocatedBytes: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("pss_kb", pssKb)
            .put("java_heap_used_bytes", javaHeapUsedBytes)
            .put("native_heap_allocated_bytes", nativeHeapAllocatedBytes)
    }

    private companion object {
        val DATASET_SIZES = listOf(10_000, 100_000)
        val BASE_DATE: LocalDate = LocalDate.of(2026, 7, 14)
        const val SPACE_ID = "space_db01_performance"
        const val COMMON_KEYWORD = "common marker42"
        const val MULTI_FIELD_KEYWORD = "common detail42"
        const val BATCH_SIZE = 1_000
        const val PAGE_SIZE = 50
        const val MAX_WALK_PAGES = 1_000
        const val WARMUP_RUNS = 3
        const val QUERY_SAMPLE_RUNS = 20
        const val SINGLE_CAPTURE_SAMPLE_RUNS = 20
        const val SINGLE_CAPTURE_P95_TARGET_MS = 300.0
        const val FTS_100K_FIRST_PAGE_P95_TARGET_MS = 700.0
        const val MIGRATION_EVENT_COUNT = 10_000
        const val MIGRATION_SAMPLE_RUNS = 3
        const val FIXED_UPDATED_AT = 1_720_000_000_000L
        const val MEMORY_SETTLE_MS = 100L
        const val REPORT_FILE = "db01-performance.json"
    }
}
