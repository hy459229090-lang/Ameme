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
import com.ameme.android.coverage.MobileSourceCapabilities
import com.ameme.android.coverage.SourceState
import com.ameme.android.coverage.TimePrecision
import com.ameme.android.coverage.TimeRange
import com.ameme.android.coverage.ValueLevel
import com.ameme.android.data.CoverageCandidateAcceptance
import com.ameme.android.data.CoverageAcceptanceMode
import com.ameme.android.data.CoverageEventLinkLifecycle
import com.ameme.android.data.LocalSourceState
import com.ameme.android.data.PersistedCoverageDay
import com.ameme.android.data.SourceDeletionStatus
import com.ameme.android.data.UserConfirmationKind
import com.ameme.android.data.UserConfirmationState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSourceDeletionInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 73).toByte() }
    private val clock = Clock.fixed(Instant.parse("2026-07-26T08:00:00Z"), ZoneOffset.UTC)

    @After fun cleanUp() {
        files.forEach { file ->
            listOf(file, File("${file.path}-wal"), File("${file.path}-shm"), File("${file.path}-journal"))
                .forEach { if (it.exists()) it.delete() }
        }
    }

    @Test fun rawOnlyReportsExternalOwnershipAndPreservesStructuredEvent() {
        val file = newDatabaseFile()
        open(file).use { repository ->
            val event = repository.captureSource(
                SourceCaptureRequest(
                    sourceKind = SourceKind.PhotoPicker,
                    title = "外部照片引用",
                    detail = "只保存 provider locator。",
                    factStatus = FactStatus.Confirmed,
                    localDate = LocalDate.parse("2026-07-26"),
                    time = null,
                    locatorUri = "content://photos/external-1",
                    locatorPermissionState = LocatorPermissionState.ProviderRead,
                    sourceInstanceKey = "photo-external-1",
                ),
            )
            val source = repository.sourceObjectsForEvent(event.id).single()
            val result = repository.deleteRawOnly(
                source.sourceObjectId,
                Instant.parse("2026-07-26T08:01:00Z"),
            )
            assertEquals(SourceDeletionStatus.ExternalNotOwned, result.status)
            assertEquals(event.id, repository.loadActiveEvents().single().id)
            assertNotNull(repository.sourceLocator(event.id))

            val cascade = repository.deleteSourceCascade(
                source.sourceObjectId,
                Instant.parse("2026-07-26T08:02:00Z"),
            )
            assertEquals(SourceDeletionStatus.CompletedLocalOnly, cascade.status)
            assertTrue(repository.loadActiveEvents().isEmpty())
        }
    }

    @Test fun rawOnlyReportsNoRawForCoverageEvidenceWithoutMutation() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_no_raw"))
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            val eventId = repository.acceptCandidate(acceptance(compilation)).eventId

            val result = repository.deleteRawOnly(
                "source_no_raw",
                Instant.parse("2026-07-26T08:01:30Z"),
            )

            assertEquals(SourceDeletionStatus.NoRaw, result.status)
            assertEquals(eventId, repository.loadActiveEvents().single().id)
            assertEquals(
                LocalSourceState.Active,
                repository.sourceObjectsForEvent(eventId).single().state,
            )
            assertEquals(
                CoverageEventLinkLifecycle.Active,
                repository.link(DAY_ID, CANDIDATE_ID)?.lifecycle,
            )
        }
    }

    @Test fun singleSourceCascadeTombstonesEventAndRejectsCoverageResurrection() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_delete_single"))
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            val link = repository.acceptCandidate(acceptance(compilation))
            val result = repository.deleteSourceCascade(
                "source_delete_single",
                Instant.parse("2026-07-26T08:02:00Z"),
            )
            assertEquals(SourceDeletionStatus.Completed, result.status)
            assertEquals(1, result.affectedEventCount)
            assertTrue(repository.loadActiveEvents().isEmpty())
            assertEquals(
                CoverageEventLinkLifecycle.Detached,
                repository.link(DAY_ID, CANDIDATE_ID)?.lifecycle,
            )
            assertEquals(
                LocalSourceState.Deleted,
                repository.sourceObjectsForEvent(link.eventId).single().state,
            )
            assertTrue(repository.deletionWatermarks().any {
                it.objectType == LocalEventDatabase.DELETION_OBJECT_SOURCE &&
                    it.objectId == "source_delete_single"
            })
            assertThrows(IllegalArgumentException::class.java) {
                repository.persist(PersistedCoverageDay.compiled(
                    compilation,
                    registryVersion = "mobile-v1",
                    compiledAt = Instant.parse("2026-07-26T08:03:00Z"),
                ))
            }
        }
        open(file).use { reloaded ->
            assertTrue(reloaded.loadActiveEvents().isEmpty())
            assertEquals(
                LocalSourceState.Deleted,
                reloaded.sourceObjectsForEvent(
                    reloaded.link(DAY_ID, CANDIDATE_ID)!!.eventId,
                ).single().state,
            )
        }
    }

    @Test fun multiSourceCascadeFailsClosedWithoutMutation() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_multi_a", "source_multi_b"))
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            val eventId = repository.acceptCandidate(acceptance(compilation)).eventId
            val result = repository.deleteSourceCascade(
                "source_multi_a",
                Instant.parse("2026-07-26T08:02:00Z"),
            )
            assertEquals(SourceDeletionStatus.LineageUnavailable, result.status)
            assertEquals(eventId, repository.loadActiveEvents().single().id)
            assertTrue(repository.sourceObjectsForEvent(eventId).all {
                it.state == LocalSourceState.Active
            })
        }
    }

    @Test fun completeExactRevisionFieldEvidenceRecomputesWithOnlySupportedContent() {
        val file = newDatabaseFile()
        val sources = setOf("source_recompute_a", "source_recompute_b")
        val compilation = compilation(sources)
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            val eventId = repository.acceptCandidate(
                acceptance(
                    compilation,
                    fieldSourceObjectIds = mapOf(
                        EvidenceField.Time to sources,
                        EvidenceField.Action to sources,
                    ),
                ),
            ).eventId

            val result = repository.deleteSourceCascade(
                "source_recompute_a",
                Instant.parse("2026-07-26T08:02:00Z"),
            )

            assertEquals(SourceDeletionStatus.Completed, result.status)
            assertEquals(1, result.affectedEventCount)
            assertEquals(1, result.recomputedEventCount)
            assertEquals(0, result.deletedEventCount)
            val recomputed = repository.loadActiveEvents().single()
            assertEquals(eventId, recomputed.id)
            assertEquals(2, recomputed.revision)
            assertEquals(LocalEventDatabase.RECOMPUTED_SOURCE_LABEL, recomputed.sourceLabel)
            assertEquals(
                LocalSourceState.Deleted,
                repository.sourceObjectsForEvent(eventId)
                    .single { it.sourceObjectId == "source_recompute_a" }.state,
            )
            assertEquals(
                LocalSourceState.Active,
                repository.sourceObjectsForEvent(eventId)
                    .single { it.sourceObjectId == "source_recompute_b" }.state,
            )
            assertEquals(
                CoverageEventLinkLifecycle.Detached,
                repository.link(DAY_ID, CANDIDATE_ID)?.lifecycle,
            )
        }
        open(file).use { reloaded ->
            assertEquals(2, reloaded.loadActiveEvents().single().revision)
            assertEquals(
                LocalSourceState.Active,
                reloaded.sourceObjectsForEvent(reloaded.loadActiveEvents().single().id)
                    .single { it.sourceObjectId == "source_recompute_b" }.state,
            )
        }
    }

    @Test fun completeUserConfirmationPreservesSingleSourceEventWithoutRetainingSourceClaim() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_user_confirmed"))
        lateinit var confirmationId: String
        lateinit var eventId: String
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            eventId = repository.acceptCandidate(
                acceptance(
                    compilation,
                    mode = CoverageAcceptanceMode.UserConfirmed,
                ),
            ).eventId
            val original = repository.userConfirmationsForEvent(eventId).single()
            confirmationId = original.confirmationId
            assertEquals(UserConfirmationKind.CoverageAcceptance, original.kind)
            assertEquals(setOf(EvidenceField.Time, EvidenceField.Action), original.confirmedFields)
            assertTrue(original.completeFieldSet)
            assertEquals(UserConfirmationState.Active, original.state)

            val result = repository.deleteSourceCascade(
                "source_user_confirmed",
                Instant.parse("2026-07-26T08:02:00Z"),
            )

            assertEquals(SourceDeletionStatus.Completed, result.status)
            assertEquals(1, result.recomputedEventCount)
            assertEquals(0, result.deletedEventCount)
            val retained = repository.loadActiveEvents().single()
            assertEquals(eventId, retained.id)
            assertEquals(2, retained.revision)
            assertEquals(
                LocalEventDatabase.USER_CONFIRMED_SOURCE_DELETED_LABEL,
                retained.sourceLabel,
            )
            val confirmations = repository.userConfirmationsForEvent(eventId)
            assertEquals(2, confirmations.size)
            assertTrue(confirmations.any {
                it.confirmationId == confirmationId &&
                    it.eventRevision == 1 &&
                    it.state == UserConfirmationState.Deleted
            })
            assertTrue(confirmations.any {
                it.confirmationId == confirmationId &&
                    it.eventRevision == 2 &&
                    it.state == UserConfirmationState.Active &&
                    it.completeFieldSet
            })
        }
        open(file).use { reloaded ->
            assertEquals(2, reloaded.loadActiveEvents().single().revision)
            assertTrue(reloaded.userConfirmationsForEvent(eventId).any {
                it.confirmationId == confirmationId &&
                    it.eventRevision == 2 &&
                    it.state == UserConfirmationState.Active
            })
            assertEquals(
                LocalSourceState.Deleted,
                reloaded.sourceObjectsForEvent(eventId).single().state,
            )
        }
    }

    @Test fun partialConfirmationIsAuditedButCannotPreserveUnsupportedCapturedEvent() {
        val file = newDatabaseFile()
        open(file).use { repository ->
            val event = repository.captureSource(
                SourceCaptureRequest(
                    sourceKind = SourceKind.Calendar,
                    title = "没有字段映射的旧式来源",
                    detail = "用户确认动作会保留审计，但不能伪造完整字段支持。",
                    factStatus = FactStatus.NeedsReview,
                    localDate = LocalDate.parse("2026-07-26"),
                    time = null,
                    locatorUri = "content://calendar/legacy-confirmation",
                    locatorPermissionState = LocatorPermissionState.ProviderRead,
                    sourceInstanceKey = "legacy-confirmation",
                ),
            )
            val confirmed = repository.updateEvent(event.id, factStatus = FactStatus.Confirmed)
            assertEquals(2, confirmed?.revision)
            val confirmation = repository.userConfirmationsForEvent(event.id).single()
            assertEquals(UserConfirmationKind.FactStatusConfirmation, confirmation.kind)
            assertTrue(!confirmation.completeFieldSet)

            val source = repository.sourceObjectsForEvent(event.id).single()
            val result = repository.deleteSourceCascade(
                source.sourceObjectId,
                Instant.parse("2026-07-26T08:02:00Z"),
            )

            assertEquals(SourceDeletionStatus.CompletedLocalOnly, result.status)
            assertEquals(0, result.recomputedEventCount)
            assertEquals(1, result.deletedEventCount)
            assertTrue(repository.loadActiveEvents().isEmpty())
            assertTrue(repository.userConfirmationsForEvent(event.id).all {
                it.state == UserConfirmationState.Deleted
            })
        }
    }

    @Test fun fieldThatLosesItsFinalSourceDeletesEventInsteadOfRetainingUnsupportedText() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_unique_action", "source_time_only"))
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            repository.acceptCandidate(
                acceptance(
                    compilation,
                    fieldSourceObjectIds = mapOf(
                        EvidenceField.Time to setOf("source_time_only"),
                        EvidenceField.Action to setOf("source_unique_action"),
                    ),
                ),
            )

            val result = repository.deleteSourceCascade(
                "source_unique_action",
                Instant.parse("2026-07-26T08:02:00Z"),
            )

            assertEquals(SourceDeletionStatus.Completed, result.status)
            assertEquals(0, result.recomputedEventCount)
            assertEquals(1, result.deletedEventCount)
            assertTrue(repository.loadActiveEvents().isEmpty())
        }
    }

    @Test fun userRevisionMakesFieldEvidenceStaleAndCascadeFailsClosed() {
        val file = newDatabaseFile()
        val sources = setOf("source_stale_a", "source_stale_b")
        val compilation = compilation(sources)
        open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            val eventId = repository.acceptCandidate(
                acceptance(
                    compilation,
                    fieldSourceObjectIds = mapOf(
                        EvidenceField.Time to sources,
                        EvidenceField.Action to sources,
                    ),
                ),
            ).eventId
            assertEquals(2, repository.updateEvent(eventId, userWords = "独立用户修订")?.revision)

            val result = repository.deleteSourceCascade(
                "source_stale_a",
                Instant.parse("2026-07-26T08:02:00Z"),
            )

            assertEquals(SourceDeletionStatus.LineageUnavailable, result.status)
            assertEquals(2, repository.loadActiveEvents().single().revision)
            assertTrue(repository.sourceObjectsForEvent(eventId).all {
                it.state == LocalSourceState.Active
            })
        }
    }

    @Test fun sourceCascadeIsScopedToOneSpaceWhenSourceIdentityMatches() {
        val file = newDatabaseFile()
        val sourceObjectId = "source_shared_between_spaces"
        val compilationA = compilation(setOf(sourceObjectId), spaceId = SPACE_ID)
        val compilationB = compilation(setOf(sourceObjectId), spaceId = SECOND_SPACE_ID)
        val eventA = open(file, SPACE_ID).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilationA,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            repository.acceptCandidate(acceptance(compilationA)).eventId
        }
        val eventB = open(file, SECOND_SPACE_ID).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilationB,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:02Z"),
            ))
            repository.acceptCandidate(acceptance(compilationB)).eventId
        }

        open(file, SPACE_ID).use { repository ->
            assertEquals(
                SourceDeletionStatus.Completed,
                repository.deleteSourceCascade(
                    sourceObjectId,
                    Instant.parse("2026-07-26T08:02:00Z"),
                ).status,
            )
            assertTrue(repository.loadActiveEvents().isEmpty())
            assertEquals(LocalSourceState.Deleted, repository.sourceObjectsForEvent(eventA).single().state)
        }
        open(file, SECOND_SPACE_ID).use { repository ->
            assertEquals(eventB, repository.loadActiveEvents().single().id)
            assertEquals(LocalSourceState.Active, repository.sourceObjectsForEvent(eventB).single().state)
            assertTrue(repository.deletionWatermarks().none {
                it.objectType == LocalEventDatabase.DELETION_OBJECT_SOURCE &&
                    it.objectId == sourceObjectId
            })
        }
    }

    @Test fun lateDeletionJobFailureRollsBackEntireSourceCascade() {
        val file = newDatabaseFile()
        val sourceObjectId = "source_transaction_rollback"
        val compilation = compilation(setOf(sourceObjectId))
        val eventId = open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            repository.acceptCandidate(acceptance(compilation)).eventId
        }

        withRawDatabase(file) { database ->
            database.execSQL(
                """
                    CREATE TRIGGER force_source_cascade_job_failure
                    BEFORE INSERT ON deletion_jobs
                    WHEN NEW.operation = 'SourceCascade'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced late source cascade failure');
                    END
                """.trimIndent(),
            )
        }
        open(file).use { repository ->
            assertThrows(RuntimeException::class.java) {
                repository.deleteSourceCascade(
                    sourceObjectId,
                    Instant.parse("2026-07-26T08:02:00Z"),
                )
            }
            assertEquals(eventId, repository.loadActiveEvents().single().id)
            assertEquals(LocalSourceState.Active, repository.sourceObjectsForEvent(eventId).single().state)
            assertEquals(
                CoverageEventLinkLifecycle.Active,
                repository.link(DAY_ID, CANDIDATE_ID)?.lifecycle,
            )
            assertTrue(repository.deletionWatermarks().none {
                it.objectType == LocalEventDatabase.DELETION_OBJECT_SOURCE &&
                    it.objectId == sourceObjectId
            })
        }
        withRawDatabase(file) { database ->
            database.execSQL("DROP TRIGGER force_source_cascade_job_failure")
        }
        open(file).use { repository ->
            assertEquals(eventId, repository.loadActiveEvents().single().id)
            assertEquals(
                SourceDeletionStatus.Completed,
                repository.deleteSourceCascade(
                    sourceObjectId,
                    Instant.parse("2026-07-26T08:03:00Z"),
                ).status,
            )
        }
    }

    @Test fun v10MigrationPreservesLegacyEventWithoutInventingSourceLineage() {
        val file = newDatabaseFile()
        val event = open(file).use { it.capture(CaptureKind.Text, "v10 migration survivor") }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                database.setForeignKeyConstraintsEnabled(false)
                database.execSQL("DROP TABLE deletion_jobs")
                database.execSQL("DROP TABLE event_source_links")
                database.execSQL("DROP TABLE event_field_evidence")
                database.execSQL("DROP TABLE event_user_confirmations")
                database.execSQL("DROP TABLE agent_access_audit")
                database.execSQL("DROP TABLE source_objects")
                database.execSQL("DELETE FROM schema_migrations WHERE version IN (11, 12, 13, 14)")
                database.version = 10
            }
        } finally {
            openKey.fill(0)
        }
        open(file).use { migrated ->
            assertEquals(14, LocalEventDatabase.SCHEMA_VERSION)
            assertEquals(event.id, migrated.loadActiveEvents().single().id)
            assertTrue(migrated.sourceObjectsForEvent(event.id).isEmpty())
            assertEquals(
                SourceDeletionStatus.NotFound,
                migrated.deleteSourceCascade(
                    "legacy_source_not_invented",
                    Instant.parse("2026-07-26T08:02:00Z"),
                ).status,
            )
            assertEquals(event.id, migrated.loadActiveEvents().single().id)
        }
    }

    @Test fun v12MigrationPreservesFieldEvidenceWithoutInventingUserConfirmation() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_v12_confirmation_migration"))
        val eventId = open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            repository.acceptCandidate(acceptance(compilation)).eventId
        }
        withRawDatabase(file) { database ->
            database.execSQL("DROP TABLE event_user_confirmations")
            database.execSQL("DROP TABLE agent_access_audit")
            database.execSQL("DELETE FROM schema_migrations WHERE version IN (13, 14)")
            database.version = 12
        }

        open(file).use { migrated ->
            assertEquals(eventId, migrated.loadActiveEvents().single().id)
            assertTrue(migrated.userConfirmationsForEvent(eventId).isEmpty())
        }
        withRawDatabase(file) { database ->
            assertEquals(14, database.version)
            database.rawQuery(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 13",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.rawQuery(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 14",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.rawQuery(
                """
                    SELECT COUNT(*)
                    FROM event_field_evidence
                    WHERE space_id = ? AND event_id = ? AND state = ?
                """.trimIndent(),
                arrayOf(SPACE_ID, eventId, "Active"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    @Test fun terminalSourceAndLinkIdentityCannotBeRewrittenByDirectSql() {
        val file = newDatabaseFile()
        val compilation = compilation(setOf("source_identity_guard"))
        val eventId = open(file).use { repository ->
            repository.persist(PersistedCoverageDay.compiled(
                compilation,
                registryVersion = "mobile-v1",
                compiledAt = Instant.parse("2026-07-26T08:00:01Z"),
            ))
            repository.acceptCandidate(acceptance(compilation)).eventId
        }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                database.setForeignKeyConstraintsEnabled(false)
                assertThrows(RuntimeException::class.java) {
                    database.execSQL(
                        """
                            UPDATE source_objects
                            SET source_object_id = ?, state = ?, deleted_at = ?
                            WHERE space_id = ? AND source_object_id = ?
                        """.trimIndent(),
                        arrayOf<Any>(
                            "source_identity_rewritten",
                            LocalSourceState.Deleted.name,
                            Instant.parse("2026-07-26T08:02:00Z").toEpochMilli(),
                            SPACE_ID,
                            "source_identity_guard",
                        ),
                    )
                }
                assertThrows(RuntimeException::class.java) {
                    database.execSQL(
                        """
                            UPDATE event_source_links
                            SET event_id = ?, state = ?, deleted_at = ?
                            WHERE space_id = ? AND event_id = ? AND source_object_id = ?
                        """.trimIndent(),
                        arrayOf<Any>(
                            UUID.randomUUID().toString(),
                            "DELETED",
                            Instant.parse("2026-07-26T08:02:00Z").toEpochMilli(),
                            SPACE_ID,
                            eventId,
                            "source_identity_guard",
                        ),
                    )
                }
            }
        } finally {
            openKey.fill(0)
        }
        open(file).use { repository ->
            assertEquals(eventId, repository.loadActiveEvents().single().id)
            assertEquals(
                SourceDeletionStatus.Completed,
                repository.deleteSourceCascade(
                    "source_identity_guard",
                    Instant.parse("2026-07-26T08:03:00Z"),
                ).status,
            )
        }
    }

    private fun open(file: File, spaceId: String = SPACE_ID) = LocalMemoryRepository.open(
        context = context,
        spaceId = spaceId,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = file,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private fun withRawDatabase(file: File, block: (SQLiteDatabase) -> Unit) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, openKey, null, null).use { database ->
                block(database)
            }
        } finally {
            openKey.fill(0)
        }
    }

    private fun acceptance(
        compilation: com.ameme.android.coverage.CoverageCompileResult,
        fieldSourceObjectIds: Map<EvidenceField, Set<String>> = emptyMap(),
        mode: CoverageAcceptanceMode = CoverageAcceptanceMode.PreserveEvidence,
    ) =
        CoverageCandidateAcceptance(
            dayId = compilation.dayId,
            candidateId = compilation.candidateEvents.single().candidateId,
            acceptedAt = Instant.parse("2026-07-26T08:01:00Z"),
            localDate = compilation.localDate,
            time = null,
            detail = "显式接受删除测试候选。",
            sourceLabel = "删除测试来源",
            captureKind = CaptureKind.Import,
            mode = mode,
            fieldSourceObjectIds = fieldSourceObjectIds,
        )

    private fun compilation(
        sourceObjectIds: Set<String>,
        spaceId: String = SPACE_ID,
    ) =
        CoverageCompiler(MobileSourceCapabilities.createRegistry()).compileDay(
            CoverageCompileRequest(
                dayId = DAY_ID,
                ownerId = "owner_source_delete",
                spaceId = spaceId,
                localDate = LocalDate.parse("2026-07-26"),
                timezone = "UTC",
                signals = listOf(
                    CoverageSignal(
                        signalId = "signal_source_delete",
                        capabilityId = "cap_calendar",
                        sourceState = SourceState.Available,
                        contextTypes = setOf(ContextType.TimeSchedule),
                        observedFields = setOf(EvidenceField.Time, EvidenceField.Action),
                        factStatus = CoverageFactStatus.Planned,
                        confidence = 1.0,
                        importance = ValueLevel.High,
                        sourceObjectIds = sourceObjectIds,
                        observedAt = Instant.parse("2026-07-26T08:00:00Z"),
                        timeRange = TimeRange(
                            start = Instant.parse("2026-07-26T10:00:00Z"),
                            end = Instant.parse("2026-07-26T11:00:00Z"),
                            timezone = "UTC",
                            precision = TimePrecision.Range,
                        ),
                        eventHint = EventHint(CandidateEventType.Activity, "来源删除候选"),
                        gapHints = emptyList(),
                    ),
                ),
            ),
        )

    private fun newDatabaseFile(): File =
        context.getDatabasePath("source-deletion-${UUID.randomUUID()}.db").also {
            files += it
            it.parentFile?.mkdirs()
        }

    private companion object {
        const val SPACE_ID = "space_source_deletion_test"
        const val SECOND_SPACE_ID = "space_source_deletion_test_secondary"
        const val DAY_ID = "day_source_deletion"
        const val CANDIDATE_ID = "candidate_signal_source_delete"
    }
}
