package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryType
import com.ameme.android.data.ReuseExclusion
import com.ameme.android.data.ReuseIntent
import com.ameme.android.data.ReuseObjectType
import com.ameme.android.data.ReuseOutcome
import com.ameme.android.data.ReuseOutcomeSubmission
import com.ameme.android.data.ReuseRequest
import com.ameme.android.data.ReuseUserAction
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.Sensitivity
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalReusePersistenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 83).toByte() }
    private val now = Instant.parse("2026-07-26T08:00:00Z")
    private val localDate = LocalDate.parse("2026-07-26")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))

    @After fun cleanUp() {
        files.forEach { file ->
            listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
                .forEach { if (it.exists()) it.delete() }
        }
    }

    @Test fun fourReuseJourneysAreBoundedRevalidatedAndPersistOnlyContentFreeTelemetry() {
        val file = newDatabaseFile()
        val queryCanary = "CANARY_PROJECT_BODY_NEVER_TELEMETRY"
        lateinit var decisionEventId: String
        val attemptIds = mutableListOf<String>()
        open(file).use { repository ->
            repository.captureSource(
                source(
                    title = "$queryCanary 历史进展",
                    detail = "项目已进入封闭测试",
                    eventType = EventType.Milestone,
                ),
            )
            val decisionEvent = repository.captureSource(
                source(
                    title = "$queryCanary 决定",
                    detail = "按恢复门禁推进",
                    eventType = EventType.Decision,
                ),
            )
            decisionEventId = decisionEvent.id
            repository.captureSource(
                source(
                    title = "$queryCanary 受限",
                    detail = "不得参与复用",
                    eventType = EventType.Decision,
                    sensitivity = Sensitivity.Restricted,
                ),
            )
            val candidate = repository.proposeLongTermMemory(
                LongTermMemoryProposal(
                    sourceEventId = decisionEvent.id,
                    expectedEventRevision = decisionEvent.revision,
                    type = LongTermMemoryType.Decision,
                    valueSummary = "$queryCanary 恢复门禁决定",
                    validFrom = now,
                    proposedAt = now,
                ),
            )
            repository.confirmLongTermMemory(
                LongTermMemoryConfirmation(candidate.memoryId, now.plusSeconds(1)),
            )

            val contexts = listOf(
                request(ReuseIntent.HistoricalSearch, queryCanary),
                request(ReuseIntent.ProjectResume, queryCanary),
                request(ReuseIntent.PreMeetingContext, queryCanary, localDate),
                request(ReuseIntent.DecisionCommitmentRecall, ""),
            ).map(repository::buildReuseContext)
            val resolvedContexts = contexts.map {
                repository.resolveReuseContext(it, now.plusSeconds(3))
            }
            assertEquals(ReuseIntent.entries.toSet(), contexts.map { it.intent }.toSet())
            assertTrue(contexts.all { it.references.isNotEmpty() })
            assertTrue(resolvedContexts.all { it.items.isNotEmpty() })
            assertTrue(resolvedContexts.all { it.items.map { item -> item.reference } == it.context.references })
            assertTrue(resolvedContexts.flatMap { it.items }.none {
                it.sourceEvent.sensitivity == Sensitivity.Restricted
            })
            assertTrue(resolvedContexts.last().items.any {
                it.memorySummary == "$queryCanary 恢复门禁决定"
            })
            assertTrue(contexts.all { ReuseExclusion.Restricted in it.exclusions })
            assertTrue(contexts.flatMap { it.references }.none {
                it.sensitivity == Sensitivity.Restricted
            })
            assertTrue(contexts.last().references.any {
                it.objectType == ReuseObjectType.LongTermMemory
            })
            attemptIds += contexts.map { it.attemptId }

            contexts.forEachIndexed { index, reuseContext ->
                assertTrue(
                    repository.recordReuseOutcome(
                        ReuseOutcomeSubmission(
                            attemptId = reuseContext.attemptId,
                            outcome = if (index == 0) ReuseOutcome.Useful else ReuseOutcome.NotUseful,
                            userAction = if (index == 1) ReuseUserAction.Hidden else ReuseUserAction.None,
                            submittedAt = now.plusSeconds((10 + index).toLong()),
                        ),
                    ),
                )
            }
            assertFalse(
                repository.recordReuseOutcome(
                    ReuseOutcomeSubmission(
                        attemptId = contexts.first().attemptId,
                        outcome = ReuseOutcome.WrongMemory,
                        submittedAt = now.plusSeconds(30),
                    ),
                ),
            )
            assertEquals(1, repository.helpfulReuseCount(now.minusSeconds(1)))
            assertEquals(4, repository.reuseTelemetryAggregates(now.minusSeconds(1)).sumOf {
                it.attemptCount
            })

            repository.updateEvent(
                decisionEvent.id,
                factStatus = FactStatus.Confirmed,
                userWords = "用户已修订",
            )
            val stale = repository.revalidateReuseContext(contexts.last(), now.plusSeconds(60))
            assertTrue(stale.references.none {
                it.objectId == decisionEvent.id || it.sourceEventId == decisionEvent.id
            })
            assertTrue(ReuseExclusion.Invalidated in stale.exclusions)
            val expired = repository.revalidateReuseContext(
                contexts.first(),
                now.plusSeconds(903),
            )
            assertTrue(expired.references.isEmpty())
            assertTrue(ReuseExclusion.Expired in expired.exclusions)
        }

        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                assertEquals(LocalEventDatabase.SCHEMA_VERSION, database.version)
                database.rawQuery(
                    """
                        SELECT attempt_id, event_ref_digests, memory_ref_digests,
                               lineage_digests, exclusions
                        FROM reuse_attempts
                    """.trimIndent(),
                    emptyArray(),
                ).use { cursor ->
                    var rows = 0
                    while (cursor.moveToNext()) {
                        rows += 1
                        val persisted = (0..4).joinToString("|") { cursor.getString(it) }
                        assertFalse(persisted.contains(queryCanary))
                        assertFalse(persisted.contains(decisionEventId))
                        assertTrue(attemptIds.any(persisted::contains))
                    }
                    assertEquals(4, rows)
                }
            }
        } finally {
            openKey.fill(0)
        }
    }

    @Test fun v9MigratesThroughCurrentSchemaWithoutChangingExistingEvent() {
        val file = newDatabaseFile()
        val existing = open(file).use {
            it.captureSource(
                source(
                    title = "v9 survivor",
                    detail = "reuse migration",
                    eventType = EventType.Milestone,
                ),
            )
        }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                database.execSQL("DROP TRIGGER reuse_outcomes_no_delete")
                database.execSQL("DROP TRIGGER reuse_outcomes_no_update")
                database.execSQL("DROP TRIGGER reuse_attempts_no_delete")
                database.execSQL("DROP TRIGGER reuse_attempts_no_update")
                database.execSQL("DROP TABLE reuse_outcomes")
                database.execSQL("DROP TABLE reuse_attempts")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 10")
                database.version = 9
            }
        } finally {
            openKey.fill(0)
        }

        open(file).use { migrated ->
            assertEquals(existing.id, migrated.loadActiveEvents().single().id)
            val context = migrated.buildReuseContext(
                request(ReuseIntent.DecisionCommitmentRecall, ""),
            )
            assertTrue(context.references.isEmpty())
        }
        LocalEventDatabase.open(
            file,
            SyntheticDatabaseKeyProvider(key),
            SPACE_ID,
        ).use { migrated ->
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, migrated.schemaVersion())
            assertTrue(migrated.hasMigration(10))
            assertTrue(migrated.hasMigration(11))
            assertTrue(migrated.hasMigration(12))
            assertTrue(migrated.hasMigration(13))
        }
    }

    private fun request(
        intent: ReuseIntent,
        query: String,
        meetingAnchorDate: LocalDate? = null,
    ) = ReuseRequest(
        spaceId = SPACE_ID,
        intent = intent,
        query = query,
        meetingAnchorDate = meetingAnchorDate,
        requestedAt = now.plusSeconds(2),
    )

    private fun source(
        title: String,
        detail: String,
        eventType: EventType,
        sensitivity: Sensitivity = Sensitivity.Personal,
    ) = SourceCaptureRequest(
        sourceKind = SourceKind.AgentAutonomous,
        title = title,
        detail = detail,
        factStatus = FactStatus.UserAsserted,
        localDate = localDate,
        time = null,
        eventType = eventType,
        evidenceState = EvidenceState.UserAsserted,
        sensitivity = sensitivity,
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
        val file = File(context.cacheDir, "ameme-reuse-${UUID.randomUUID()}.db")
        files += file
        return file
    }

    companion object {
        private const val SPACE_ID = "space_personal"
    }
}
