package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.AgentAccessAuditObjectCountBucket
import com.ameme.android.data.AgentAccessAuditPhase
import com.ameme.android.data.AgentAccessAuditPolicy
import com.ameme.android.data.AgentAccessAuditRecord
import java.io.File
import java.time.Duration
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAgentAccessAuditInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile =
        File(context.cacheDir, "agent-access-audit-${System.nanoTime()}.db")
    private val key = ByteArray(32) { index -> (index + 61).toByte() }

    @After
    fun cleanUp() {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .forEach(File::delete)
        key.fill(0)
    }

    @Test
    fun contentFreeAuditPersistsAppendOnlyAndPrunesOnlyExpiredRows() {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val started = record(
            auditId = "audit_00000000-0000-4000-8000-000000000001",
            traceId = "trace_00000000-0000-4000-8000-000000000002",
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
            count = null,
            at = now.minusSeconds(2),
        )
        val completed = record(
            auditId = "audit_00000000-0000-4000-8000-000000000003",
            traceId = started.traceId,
            phase = AgentAccessAuditPhase.Completed,
            resultCode = AgentAccessAuditPolicy.RESULT_OK,
            count = 3,
            at = now.minusSeconds(1),
        )
        open().use { database ->
            database.appendAgentAccessAudit(started)
            database.appendAgentAccessAudit(completed)
        }

        open().use { reopened ->
            val recent = reopened.recentAgentAccessAudit(limit = 10, at = now)
            assertEquals(listOf(completed, started), recent)
            assertEquals(0, reopened.pruneExpiredAgentAccessAudit(now))
        }
        withRawDatabase { database ->
            val columns = database.rawQuery(
                "PRAGMA table_info(${LocalAgentAccessAuditPersistence.TABLE})",
                emptyArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            val normalized = columns.joinToString("|").lowercase()
            listOf(
                "content",
                "query",
                "payload",
                "digest",
                "object_id",
                "source",
                "path",
                "secret",
                "grant",
                "request",
                "error_text",
            ).forEach { forbidden ->
                assertFalse("$forbidden must not be an audit column", normalized.contains(forbidden))
            }
            assertSqlRejected {
                database.execSQL(
                    """
                        UPDATE ${LocalAgentAccessAuditPersistence.TABLE}
                        SET result_code = 'ALTERED'
                        WHERE audit_id = ?
                    """.trimIndent(),
                    arrayOf(completed.auditId),
                )
            }
            assertSqlRejected {
                database.execSQL(
                    "DELETE FROM ${LocalAgentAccessAuditPersistence.TABLE} WHERE audit_id = ?",
                    arrayOf(completed.auditId),
                )
            }
        }

        val expiredAt = now.minus(AgentAccessAuditPolicy.retention).minus(Duration.ofDays(1))
        open().use { database ->
            database.appendAgentAccessAudit(
                record(
                    auditId = "audit_00000000-0000-4000-8000-000000000004",
                    traceId = "trace_00000000-0000-4000-8000-000000000005",
                    phase = AgentAccessAuditPhase.Started,
                    resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
                    count = null,
                    at = expiredAt,
                ),
            )
            assertEquals(1, database.pruneExpiredAgentAccessAudit(now))
            assertEquals(2, database.recentAgentAccessAudit(limit = 10, at = now).size)
        }
    }

    @Test
    fun v13MigrationCreatesAuditSchemaWithoutSyntheticRows() {
        open().close()
        withRawDatabase { database ->
            database.execSQL("DROP TABLE ${LocalAgentAccessAuditPersistence.TABLE}")
            database.execSQL("DELETE FROM schema_migrations WHERE version = 14")
            database.version = LocalEventDatabase.USER_CONFIRMATION_SCHEMA_VERSION
        }

        open().use { migrated ->
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, migrated.schemaVersion())
            assertTrue(migrated.hasMigration(14))
            assertTrue(migrated.recentAgentAccessAudit(limit = 10, at = Instant.now()).isEmpty())
        }
    }

    private fun open(): LocalEventDatabase = LocalEventDatabase.open(
        databaseFile,
        SyntheticDatabaseKeyProvider(key),
        LocalEventDatabase.DEFAULT_SPACE_ID,
    )

    private fun withRawDatabase(block: (SQLiteDatabase) -> Unit) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, openKey, null, null).use(block)
        } finally {
            openKey.fill(0)
        }
    }

    private fun record(
        auditId: String,
        traceId: String,
        phase: AgentAccessAuditPhase,
        resultCode: String,
        count: Int?,
        at: Instant,
    ) = AgentAccessAuditRecord(
        auditId = auditId,
        traceId = traceId,
        phase = phase,
        callerId = "agent_synthetic",
        purpose = "autonomous_memory",
        spaces = listOf(LocalEventDatabase.DEFAULT_SPACE_ID),
        dataTypes = listOf("event"),
        operation = "visible_events",
        resultCode = resultCode,
        objectCountBucket = AgentAccessAuditObjectCountBucket.fromCount(count),
        occurredAt = at,
        retentionUntil = at.plus(AgentAccessAuditPolicy.retention),
    )

    private fun assertSqlRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected append-only SQL rejection")
        } catch (_: Exception) {
            // Expected trigger or constraint rejection.
        }
    }
}
