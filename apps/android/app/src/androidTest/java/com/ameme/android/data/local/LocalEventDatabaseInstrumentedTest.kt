package com.ameme.android.data.local

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalEventDatabaseInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFiles = mutableListOf<File>()
    private val key = ByteArray(32) { index -> (index + 1).toByte() }
    private val clock = Clock.fixed(
        Instant.parse("2026-07-14T04:30:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

    @After
    fun cleanUp() {
        databaseFiles.forEach(::deleteDatabaseFiles)
    }

    @Test
    fun encryptedDatabase_usesWalAndRejectsWrongKeyAndPlaintextHeader() {
        val file = newDatabaseFile()
        LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_A,
            keyProvider = SyntheticDatabaseKeyProvider(key),
            databaseFile = file,
            clock = clock,
            seedSyntheticEvents = false,
        ).use { repository ->
            repository.capture(CaptureKind.Text, "synthetic encrypted record")
        }

        val header = file.inputStream().use { it.readNBytes(SQLITE_HEADER.size) }
        assertFalse(header.contentEquals(SQLITE_HEADER))

        val reopened = LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_A)
        assertEquals("wal", reopened.journalMode().lowercase())
        reopened.close()

        val wrongKeyProvider = TrackingDatabaseKeyProvider(ByteArray(32) { index -> (index + 41).toByte() })
        val result = runCatching {
            LocalEventDatabase.open(file, wrongKeyProvider, SPACE_A).close()
        }
        assertTrue("A wrong SQLCipher key must be denied", result.isFailure)
        assertTrue("Rejected key material must be cleared", wrongKeyProvider.lastIssued.all { it == 0.toByte() })
    }

    @Test
    fun closeClearsIssuedKeyMaterial() {
        val file = newDatabaseFile()
        val provider = TrackingDatabaseKeyProvider(key)
        val database = LocalEventDatabase.open(file, provider, SPACE_A)

        assertTrue(provider.lastIssued.any { it != 0.toByte() })
        database.close()

        assertTrue(provider.lastIssued.all { it == 0.toByte() })
    }

    @Test
    fun keystoreWrappedKey_survivesInitialDatabaseCreationFailureAndCanRetry() {
        val file = newDatabaseFile()
        assertTrue(file.mkdirs())
        val provider = AndroidKeystoreDatabaseKeyProvider(context, file)

        val failedOpen = runCatching { LocalEventDatabase.open(file, provider, SPACE_A).close() }
        assertTrue("A directory at the database path must make creation fail", failedOpen.isFailure)
        assertTrue(file.delete())

        LocalEventDatabase.open(file, provider, SPACE_A).use { database ->
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, database.schemaVersion())
        }
    }

    @Test
    fun closeAndReconstruct_restoresCaptureAndSupportsDateKeywordRead() {
        val file = newDatabaseFile()
        val first = LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_A,
            keyProvider = SyntheticDatabaseKeyProvider(key),
            databaseFile = file,
            clock = clock,
            seedSyntheticEvents = false,
        )
        val captured = first.capture(CaptureKind.Text, "reconstruction keyword")
        first.close()

        LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_A,
            keyProvider = SyntheticDatabaseKeyProvider(key),
            databaseFile = file,
            clock = clock,
            seedSyntheticEvents = false,
        ).use { reconstructed ->
            assertTrue(reconstructed.loadActiveEvents().any { it.id == captured.id })
            val groups = reconstructed.search("keyword", LocalDate.of(2026, 7, 14))
            assertEquals(1, groups.size)
            assertEquals(captured.id, groups.single().events.single().id)
            assertTrue(reconstructed.search("keyword", LocalDate.of(2026, 7, 13)).isEmpty())
        }
    }

    @Test
    fun deleteAppendsTombstoneAndRemainsHiddenAfterReconstruction() {
        val file = newDatabaseFile()
        val repository = LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_A,
            keyProvider = SyntheticDatabaseKeyProvider(key),
            databaseFile = file,
            clock = clock,
            seedSyntheticEvents = false,
        )
        val captured = repository.capture(CaptureKind.Text, "delete durability")
        assertTrue(repository.deleteEvent(captured.id))
        assertTrue(repository.loadActiveEvents().isEmpty())
        repository.close()

        LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_A).use { database ->
            assertEquals(2, database.revisionCount(captured.id))
            assertEquals(LocalEventDatabase.STATE_DELETED, database.currentState(captured.id))
            assertTrue(database.readActive().isEmpty())
        }
    }

    @Test
    fun blankTextAndUnsupportedVoiceAreRejectedWithoutPersistingPlaceholders() {
        val file = newDatabaseFile()
        LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_A,
            keyProvider = SyntheticDatabaseKeyProvider(key),
            databaseFile = file,
            clock = clock,
            seedSyntheticEvents = false,
        ).use { repository ->
            assertTrue(runCatching { repository.capture(CaptureKind.Text, "   ") }.isFailure)
            assertTrue(repository.loadActiveEvents().isEmpty())

            assertTrue(runCatching { repository.capture(CaptureKind.Voice, "   ") }.isFailure)
            assertTrue(repository.loadActiveEvents().isEmpty())
        }
    }

    @Test
    fun v1ToV2ToV3ToV4Migration_preservesRowAndBackfillsLegacySpace() {
        val file = newDatabaseFile()
        createVersionOneDatabase(file)

        LocalEventDatabase.open(
            file,
            SyntheticDatabaseKeyProvider(key),
            LocalEventDatabase.LEGACY_SPACE_ID,
        ).use { database ->
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, database.schemaVersion())
            assertTrue(database.hasMigration(2))
            assertTrue(database.hasMigration(3))
            assertTrue(database.hasMigration(4))
            val restored = database.readActive().single()
            assertEquals("evt_v1_synthetic", restored.id)
            assertEquals("v1 synthetic title", restored.title)
            assertEquals(1, database.revisionCount(restored.id))
            assertNotEquals(null, database.currentState(restored.id))
        }
    }

    @Test
    fun sourceLocatorCommitsWithEventAndRetainsExplicitSessionLifecycle() {
        val file = newDatabaseFile()
        val request = SourceCaptureRequest(
            sourceKind = SourceKind.PhotoPicker,
            title = "合成照片引用",
            detail = "合成测试，不访问真实媒体",
            factStatus = FactStatus.Processing,
            localDate = LocalDate.of(2026, 7, 14),
            time = null,
            locatorUri = "content://synthetic.provider/photo/1",
            mimeType = "image/png",
            locatorPermissionState = LocatorPermissionState.SessionRead,
        )
        val captured = LocalMemoryRepository.open(
            context, SPACE_A, SyntheticDatabaseKeyProvider(key), file, clock, seedSyntheticEvents = false,
        ).use { it.captureSource(request) }

        LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_A).use { database ->
            assertEquals(captured.id, database.readActive().single().id)
            assertEquals(LocatorPermissionState.SessionRead.name, database.sourceLocatorState(captured.id))
            assertEquals(1, database.revisionCount(captured.id))
        }
    }

    @Test
    fun api36SqlCipherCreatesFts5AndFtsAndLikeFallbackReturnEquivalentScopedResults() {
        val ftsFile = newDatabaseFile()
        val likeFile = newDatabaseFile()
        val fixtures = listOf(
            syntheticEvent("evt_search_a", "alpha shared keyword"),
            syntheticEvent("evt_search_b", "beta hidden"),
        )
        LocalEventDatabase.open(ftsFile, SyntheticDatabaseKeyProvider(key), SPACE_A, enableFts = true).use { fts ->
            fixtures.forEach(fts::insertCaptured)
            assertTrue("FTS5 virtual table must be created on the API 36 SQLCipher runtime", fts.hasSearchTable())
            assertEquals(SearchBackend.Fts5, fts.readPage("shared", null, null, 20).searchBackend)
        }
        LocalEventDatabase.open(likeFile, SyntheticDatabaseKeyProvider(key), SPACE_A, enableFts = false).use { like ->
            fixtures.forEach(like::insertCaptured)
            assertEquals(SearchBackend.LikeFallback, like.readPage("shared", null, null, 20).searchBackend)
        }

        val ftsIds = LocalEventDatabase.open(ftsFile, SyntheticDatabaseKeyProvider(key), SPACE_A, true).use {
            it.readPage("shared", LocalDate.of(2026, 7, 14), null, 20).events.map(MemoryEvent::id)
        }
        val likeIds = LocalEventDatabase.open(likeFile, SyntheticDatabaseKeyProvider(key), SPACE_A, false).use {
            it.readPage("shared", LocalDate.of(2026, 7, 14), null, 20).events.map(MemoryEvent::id)
        }
        assertEquals(likeIds, ftsIds)
    }

    @Test
    fun keysetDatePaginationHasStableOrderNoDuplicatesAndHonorsDelete() {
        val file = newDatabaseFile()
        LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_A).use { database ->
            (0 until 5).forEach { offset ->
                database.insertCaptured(
                    syntheticEvent("evt_page_$offset", "page fixture $offset").copy(
                        localDate = LocalDate.of(2026, 7, 14).minusDays(offset.toLong()),
                    ),
                )
            }
            val first = database.readPage("page", null, null, 2)
            val second = database.readPage("page", null, requireNotNull(first.nextCursor), 2)
            val third = database.readPage("page", null, requireNotNull(second.nextCursor), 2)
            val combined = first.events + second.events + third.events

            assertEquals(5, combined.size)
            assertEquals(5, combined.map(MemoryEvent::id).toSet().size)
            assertEquals(combined.map(MemoryEvent::localDate).sortedDescending(), combined.map(MemoryEvent::localDate))
            assertEquals(null, third.nextCursor)

            assertTrue(database.deleteEvent("evt_page_2"))
            assertTrue(database.readPage("page", null, null, 20).events.none { it.id == "evt_page_2" })
        }
    }

    @Test
    fun repositoryBoundSpace_preventsCrossSpaceReadDeleteAndIdCollision() {
        val file = newDatabaseFile()
        val providerA = SyntheticDatabaseKeyProvider(key)
        val providerB = SyntheticDatabaseKeyProvider(key)
        LocalEventDatabase.open(file, providerA, SPACE_A).use { spaceA ->
            LocalEventDatabase.open(file, providerB, SPACE_B).use { spaceB ->
                val eventA = syntheticEvent(SHARED_EVENT_ID, "shared keyword in A")
                spaceA.insertCaptured(eventA)

                assertTrue(spaceB.readActive("shared").isEmpty())
                assertFalse(spaceB.deleteEvent(SHARED_EVENT_ID))
                assertEquals(1, spaceA.readActive("shared").size)

                val eventB = syntheticEvent(SHARED_EVENT_ID, "shared keyword in B")
                spaceB.insertCaptured(eventB)
                assertEquals("shared keyword in A", spaceA.readActive("shared").single().title)
                assertEquals("shared keyword in B", spaceB.readActive("shared").single().title)

                assertTrue(spaceB.deleteEvent(SHARED_EVENT_ID))
                assertTrue(spaceB.readActive("shared").isEmpty())
                assertEquals(1, spaceA.readActive("shared").size)
            }
        }
    }

    @Test
    fun revisionTable_rejectsDirectUpdateAndDelete() {
        val file = newDatabaseFile()
        val event = syntheticEvent("evt_append_only", "immutable revision title")
        LocalEventDatabase.open(file, SyntheticDatabaseKeyProvider(key), SPACE_A).use { database ->
            database.insertCaptured(event)
        }

        val openKey = key.copyOf()
        val database = SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null)
        try {
            val update = runCatching {
                database.execSQL(
                    "UPDATE event_revisions SET title = ? WHERE space_id = ? AND event_id = ?",
                    arrayOf("mutated title", SPACE_A, event.id),
                )
            }
            val delete = runCatching {
                database.execSQL(
                    "DELETE FROM event_revisions WHERE space_id = ? AND event_id = ?",
                    arrayOf(SPACE_A, event.id),
                )
            }
            assertTrue("Direct revision UPDATE must fail", update.isFailure)
            assertTrue("Direct revision DELETE must fail", delete.isFailure)
            database.rawQuery(
                "SELECT COUNT(*), MIN(title) FROM event_revisions WHERE space_id = ? AND event_id = ?",
                arrayOf(SPACE_A, event.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(event.title, cursor.getString(1))
            }
        } finally {
            database.close()
            openKey.fill(0)
        }
    }

    private fun syntheticEvent(id: String, title: String) = MemoryEvent(
        id = id,
        localDate = LocalDate.of(2026, 7, 14),
        time = null,
        title = title,
        detail = "synthetic space isolation fixture",
        factStatus = FactStatus.Confirmed,
        sourceLabel = "synthetic fixture",
        isLocalOnly = true,
    )

    private fun createVersionOneDatabase(file: File) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        val database = SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null)
        try {
            database.use {
                it.execSQL(
                """
                    CREATE TABLE events_current (
                        event_id TEXT PRIMARY KEY NOT NULL,
                        revision INTEGER NOT NULL,
                        local_date TEXT NOT NULL,
                        local_time TEXT,
                        title TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        fact_status TEXT NOT NULL,
                        source_label TEXT NOT NULL,
                        is_local_only INTEGER NOT NULL,
                        user_words TEXT,
                        state TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent(),
            )
                val values = ContentValues().apply {
                put("event_id", "evt_v1_synthetic")
                put("revision", 1)
                put("local_date", "2026-07-14")
                put("local_time", "09:15")
                put("title", "v1 synthetic title")
                put("detail", "v1 synthetic detail")
                put("fact_status", FactStatus.Confirmed.name)
                put("source_label", "synthetic migration fixture")
                put("is_local_only", 1)
                put("user_words", "synthetic words")
                put("state", LocalEventDatabase.STATE_ACTIVE)
                put("updated_at", 1_720_000_000_000L)
            }
                it.insertOrThrow("events_current", null, values)
                it.version = 1
            }
        } finally {
            openKey.fill(0)
        }
    }

    private fun newDatabaseFile(): File = context.getDatabasePath("test-${UUID.randomUUID()}.db").also {
        databaseFiles += it
        it.parentFile?.mkdirs()
    }

    private fun deleteDatabaseFiles(file: File) {
        listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
            .forEach { candidate -> if (candidate.exists()) candidate.delete() }
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
        const val SPACE_A = "space_test_a"
        const val SPACE_B = "space_test_b"
        const val SHARED_EVENT_ID = "evt_shared_across_spaces"
    }

    private class TrackingDatabaseKeyProvider(key: ByteArray) : DatabaseKeyProvider {
        private val stored = key.copyOf()
        lateinit var lastIssued: ByteArray
            private set

        override fun getOrCreateKey(): ByteArray = stored.copyOf().also { lastIssued = it }
    }
}
