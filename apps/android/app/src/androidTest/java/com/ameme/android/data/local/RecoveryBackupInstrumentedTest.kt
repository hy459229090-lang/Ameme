package com.ameme.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.AgentAccessAuditObjectCountBucket
import com.ameme.android.data.AgentAccessAuditPhase
import com.ameme.android.data.AgentAccessAuditPolicy
import com.ameme.android.data.AgentAccessAuditRecord
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.data.RecoveryActivationAuthorization
import com.ameme.android.data.RecoveryBackupManifest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryBackupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roots = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 91).toByte() }
    private val keyProvider = SyntheticDatabaseKeyProvider(key)

    @After fun cleanUp() {
        roots.forEach { if (it.exists()) it.deleteRecursively() }
    }

    @Test fun verifiedCandidatePreservesTombstoneAndRejectsOldSnapshotCorruptionAndNonemptyTarget() {
        val root = newRoot()
        val databaseFile = File(root, "live/events.db")
        val event = event("event_recovery_delete")
        val oldBackup = File(root, "old-backup")

        val authoritative = LocalEventDatabase.open(
            databaseFile,
            keyProvider,
            SPACE_ID,
        ).use { database ->
            database.insertCaptured(event)
            val oldManifest = database.createLocalRecoveryBackup(
                oldBackup,
                Instant.parse("2026-07-26T09:00:00Z"),
            )
            assertFalse(oldManifest.productionRecoveryClaim)
            assertEquals(
                RecoveryBackupManifest.KEY_MATERIAL_STATE,
                oldManifest.keyMaterialState,
            )
            assertTrue(database.deleteEvent(event.id))
            database.deletionWatermark(
                LocalEventDatabase.DELETION_OBJECT_EVENT,
                event.id,
            )!!
        }

        val staleCandidate = File(root, "stale-candidate")
        assertThrows(IllegalArgumentException::class.java) {
            LocalRecoveryBackup.restoreCandidate(
                oldBackup,
                staleCandidate,
                keyProvider,
                listOf(authoritative),
            )
        }
        assertFalse(staleCandidate.exists())

        val currentBackup = File(root, "current-backup")
        val candidate = LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_ID,
            keyProvider = keyProvider,
            databaseFile = databaseFile,
            seedSyntheticEvents = false,
        ).use { repository ->
            val manifest = repository.createLocalRecoveryBackup(
                currentBackup,
                Instant.parse("2026-07-26T09:01:00Z"),
            )
            assertEquals(manifest, repository.verifyLocalRecoveryBackup(currentBackup))
            repository.restoreLocalRecoveryCandidate(
                currentBackup,
                File(root, "candidate"),
            )
        }
        assertFalse(candidate.productionRecoveryClaim)
        LocalEventDatabase.open(candidate.databaseFile, keyProvider, SPACE_ID).use { recovered ->
            assertEquals(LocalEventDatabase.STATE_DELETED, recovered.currentState(event.id))
            assertEquals(
                authoritative.tombstoneDigest,
                recovered.deletionWatermark(
                    LocalEventDatabase.DELETION_OBJECT_EVENT,
                    event.id,
                )?.tombstoneDigest,
            )
            assertTrue(recovered.readActive().none { it.id == event.id })
            assertTrue(
                recovered.readPage(
                    query = "Recovery",
                    startDate = null,
                    endDate = null,
                    cursor = null,
                    pageSize = 20,
                ).events.none { it.id == event.id },
            )
        }

        val corrupted = File(root, "corrupted")
        currentBackup.copyRecursively(corrupted)
        val snapshot = File(corrupted, "events.db")
        val bytes = snapshot.readBytes()
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()
        snapshot.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            LocalRecoveryBackup.restoreCandidate(
                corrupted,
                File(root, "corrupt-candidate"),
                keyProvider,
                listOf(authoritative),
            )
        }

        val nonempty = File(root, "nonempty").apply { mkdirs() }
        val sentinel = File(nonempty, "keep.txt").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) {
            LocalRecoveryBackup.restoreCandidate(
                currentBackup,
                nonempty,
                keyProvider,
                listOf(authoritative),
            )
        }
        assertEquals("keep", sentinel.readText())

        val wrongKeyResult = runCatching {
            LocalRecoveryBackup.verify(
                currentBackup,
                ByteArray(32) { (it + 7).toByte() },
                listOf(authoritative),
            )
        }
        assertTrue(wrongKeyResult.isFailure)
    }

    @Test fun managedRecoveryPointCreatesActivatesReopensAndPersistsSuccessReceipt() {
        val root = newRoot()
        val databaseFile = File(root, "live/events.db")
        val recoveryRoot = File(root, "managed-recovery")
        val candidateEvent = event("event_managed_recovery_candidate")
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { database ->
            database.insertCaptured(candidateEvent)
        }
        val repository = LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_ID,
            keyProvider = keyProvider,
            databaseFile = databaseFile,
        )
        val manager = LocalRecoveryPointManager(recoveryRoot)
        val createdAt = Instant.parse("2026-07-29T09:00:00Z")
        val created = manager.create(repository, createdAt)
        assertEquals(LocalRecoveryPointAvailability.Verified, created.availability)
        assertEquals(createdAt, created.createdAt)
        assertTrue((created.snapshotBytes ?: 0) > 0)
        assertTrue(File(recoveryRoot, "candidate").isDirectory)
        val liveOnly = repository.capture(
            CaptureKind.Text,
            "恢复后不应保留的 live 事件",
        )
        val confirmedAt = createdAt.plusSeconds(120)
        val plan = manager.prepareActivation(repository, confirmedAt)
        repository.close()

        val receipt = LocalRecoveryActivationCoordinator(
            keyProvider = keyProvider,
            clock = Clock.fixed(confirmedAt, ZoneOffset.UTC),
        ).activate(
            candidate = plan.candidate,
            liveDatabaseFile = databaseFile,
            authorization = plan.authorization,
            authoritativeWatermarks = plan.authoritativeWatermarks,
        )
        assertTrue(manager.recordSuccessfulActivation(receipt))

        LocalMemoryRepository.open(
            context = context,
            spaceId = SPACE_ID,
            keyProvider = keyProvider,
            databaseFile = databaseFile,
        ).use { reopened ->
            assertTrue(reopened.loadActiveEvents().any { it.id == candidateEvent.id })
            assertTrue(reopened.loadActiveEvents().none { it.id == liveOnly.id })
            val restartedManager = LocalRecoveryPointManager(recoveryRoot)
            val refreshed = restartedManager.refresh(
                reopened,
                confirmedAt.plusSeconds(30),
            )
            assertEquals(LocalRecoveryPointAvailability.Verified, refreshed.availability)
            assertEquals(confirmedAt, refreshed.lastActivatedAt)
            assertEquals(created.backupId, refreshed.backupId)
        }
    }

    @Test fun v8MigratesThroughCurrentSchemaAndCreatesRecoverySafetyTables() {
        val root = newRoot()
        val databaseFile = File(root, "migration/events.db")
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use {
            it.insertCaptured(event("event_v8_survivor"))
        }
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        try {
            net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                databaseFile,
                openKey,
                null,
                null,
            ).use { database ->
                database.execSQL("DROP TRIGGER reuse_outcomes_no_delete")
                database.execSQL("DROP TRIGGER reuse_outcomes_no_update")
                database.execSQL("DROP TRIGGER reuse_attempts_no_delete")
                database.execSQL("DROP TRIGGER reuse_attempts_no_update")
                database.execSQL("DROP TABLE reuse_outcomes")
                database.execSQL("DROP TABLE reuse_attempts")
                database.execSQL("DROP TRIGGER deletion_watermarks_no_delete")
                database.execSQL("DROP TRIGGER deletion_watermarks_no_regression")
                database.execSQL("DROP TABLE backup_checkpoints")
                database.execSQL("DROP TABLE deletion_watermarks")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 9")
                database.execSQL("DELETE FROM schema_migrations WHERE version = 10")
                database.version = 8
            }
        } finally {
            openKey.fill(0)
        }

        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { migrated ->
            assertEquals(LocalEventDatabase.SCHEMA_VERSION, migrated.schemaVersion())
            assertTrue(migrated.hasMigration(9))
            assertTrue(migrated.hasMigration(10))
            assertTrue(migrated.hasMigration(11))
            assertTrue(migrated.hasMigration(12))
            assertTrue(migrated.hasMigration(13))
            assertTrue(migrated.hasMigration(14))
            assertEquals("event_v8_survivor", migrated.readActive().single().id)
            assertNotNull(migrated.deletionWatermarkDigest())
        }
    }

    @Test fun exactAuthorizationActivatesCandidateAndInjectedFailureRollsBackLive() {
        val root = newRoot()
        val databaseFile = File(root, "live/events.db")
        val candidateEvent = event("event_recovery_candidate")
        val liveOnlyEvent = event("event_recovery_live_only")
        val backupDirectory = File(root, "backup")
        val candidateDirectory = File(root, "candidate")

        val manifest = LocalEventDatabase.open(
            databaseFile,
            keyProvider,
            SPACE_ID,
        ).use { database ->
            database.insertCaptured(candidateEvent)
            database.createLocalRecoveryBackup(
                backupDirectory,
                Instant.parse("2026-07-26T09:00:00Z"),
            )
        }
        val candidate = LocalRecoveryBackup.restoreCandidate(
            backupDirectory = backupDirectory,
            destinationDirectory = candidateDirectory,
            keyProvider = keyProvider,
            authoritativeWatermarks = emptyList(),
        )
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use {
            it.insertCaptured(liveOnlyEvent)
        }

        val activatedAt = Instant.parse("2026-07-26T09:02:00Z")
        val coordinator = LocalRecoveryActivationCoordinator(
            keyProvider = keyProvider,
            clock = Clock.fixed(activatedAt, ZoneOffset.UTC),
        )
        val authorization = authorization(manifest.backupId, activatedAt)
        val receipt = coordinator.activate(
            candidate = candidate,
            liveDatabaseFile = databaseFile,
            authorization = authorization,
            authoritativeWatermarks = emptyList(),
        )
        assertEquals(authorization.confirmationId, receipt.confirmationId)
        assertEquals(manifest.backupId, receipt.candidateBackupId)
        assertFalse(receipt.cleanupPending)
        assertFalse(receipt.productionRecoveryClaim)
        assertTrue(candidate.directory.isDirectory)
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { activated ->
            assertTrue(activated.readActive().any { it.id == candidateEvent.id })
            assertTrue(activated.readActive().none { it.id == liveOnlyEvent.id })
            activated.insertCaptured(liveOnlyEvent)
        }

        val secondAuthorization = authorization(
            manifest.backupId,
            activatedAt.plusSeconds(60),
        )
        val failingCoordinator = LocalRecoveryActivationCoordinator(
            keyProvider = keyProvider,
            clock = Clock.fixed(activatedAt.plusSeconds(60), ZoneOffset.UTC),
        ).apply {
            failureInjector = { phase ->
                if (
                    phase ==
                    LocalRecoveryActivationCoordinator.RecoveryActivationPhase.CandidateMovedToLive
                ) {
                    error("injected recovery activation failure")
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            failingCoordinator.activate(
                candidate = candidate,
                liveDatabaseFile = databaseFile,
                authorization = secondAuthorization,
                authoritativeWatermarks = emptyList(),
            )
        }
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { rolledBack ->
            assertTrue(rolledBack.readActive().any { it.id == liveOnlyEvent.id })
        }
        assertFalse(File(databaseFile.parentFile, ".${databaseFile.name}.recovery-stage").exists())
        assertFalse(File(databaseFile.parentFile, ".${databaseFile.name}.recovery-rollback").exists())
        assertFalse(File(databaseFile.parentFile, ".${databaseFile.name}.recovery-journal").exists())

        val expired = RecoveryActivationAuthorization(
            confirmationId = RecoveryActivationAuthorization.CONFIRMATION_PREFIX +
                UUID.randomUUID(),
            candidateBackupId = manifest.backupId,
            confirmedAt = activatedAt.minusSeconds(120),
            expiresAt = activatedAt.minusSeconds(1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.activate(
                candidate = candidate,
                liveDatabaseFile = databaseFile,
                authorization = expired,
                authoritativeWatermarks = emptyList(),
            )
        }
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { unchanged ->
            assertTrue(unchanged.readActive().any { it.id == liveOnlyEvent.id })
        }

        val forgedJournal = File(
            databaseFile.parentFile,
            ".${databaseFile.name}.recovery-journal",
        )
        forgedJournal.writeText(
            listOf(
                "ameme-local-recovery-activation-v1",
                "Committed",
                RecoveryActivationAuthorization.CONFIRMATION_PREFIX + UUID.randomUUID(),
                manifest.backupId,
                "0".repeat(64),
            ).joinToString("\n", postfix = "\n"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalRecoveryActivationCoordinator.recoverInterruptedActivation(
                databaseFile,
                keyProvider,
            )
        }
        assertTrue(forgedJournal.isFile)
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { failClosed ->
            assertTrue(failClosed.readActive().any { it.id == liveOnlyEvent.id })
        }
    }

    @Test fun activationPreservesPostBackupAgentAuditAndMergeFailureLeavesLiveLedger() {
        val root = newRoot()
        val databaseFile = File(root, "live/events.db")
        val backupDirectory = File(root, "backup")
        val candidateDirectory = File(root, "candidate")
        val backupAt = Instant.parse("2026-07-29T08:00:00Z")
        val activatedAt = backupAt.plusSeconds(120)
        val traceId = "trace_00000000-0000-4000-8000-000000000110"
        val preBackupStart = auditRecord(
            auditId = "audit_00000000-0000-4000-8000-000000000111",
            traceId = traceId,
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
            at = backupAt.minusSeconds(10),
        )
        val postBackupCompletion = auditRecord(
            auditId = "audit_00000000-0000-4000-8000-000000000112",
            traceId = traceId,
            phase = AgentAccessAuditPhase.Completed,
            resultCode = AgentAccessAuditPolicy.RESULT_OK,
            at = backupAt.plusSeconds(10),
        )
        val postBackupStart = auditRecord(
            auditId = "audit_00000000-0000-4000-8000-000000000113",
            traceId = "trace_00000000-0000-4000-8000-000000000114",
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
            at = backupAt.plusSeconds(20),
        )

        val manifest = LocalEventDatabase.open(
            databaseFile,
            keyProvider,
            SPACE_ID,
        ).use { database ->
            database.appendAgentAccessAudit(preBackupStart)
            database.createLocalRecoveryBackup(backupDirectory, backupAt)
        }
        val candidate = LocalRecoveryBackup.restoreCandidate(
            backupDirectory = backupDirectory,
            destinationDirectory = candidateDirectory,
            keyProvider = keyProvider,
            authoritativeWatermarks = emptyList(),
        )
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { live ->
            live.appendAgentAccessAudit(postBackupCompletion)
            live.appendAgentAccessAudit(postBackupStart)
        }

        val authorization = authorization(manifest.backupId, activatedAt)
        val mergeFailure = LocalRecoveryActivationCoordinator(
            keyProvider = keyProvider,
            clock = Clock.fixed(activatedAt, ZoneOffset.UTC),
        ).apply {
            failureInjector = { phase ->
                if (
                    phase ==
                    LocalRecoveryActivationCoordinator.RecoveryActivationPhase.AgentAccessAuditMerged
                ) {
                    error("injected Agent access audit merge failure")
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            mergeFailure.activate(
                candidate = candidate,
                liveDatabaseFile = databaseFile,
                authorization = authorization,
                authoritativeWatermarks = emptyList(),
            )
        }
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { unchanged ->
            assertEquals(
                setOf(
                    preBackupStart.auditId,
                    postBackupCompletion.auditId,
                    postBackupStart.auditId,
                ),
                unchanged.recentAgentAccessAudit(limit = 10, at = activatedAt)
                    .map(AgentAccessAuditRecord::auditId)
                    .toSet(),
            )
        }
        assertFalse(File(databaseFile.parentFile, ".${databaseFile.name}.recovery-stage").exists())
        assertFalse(File(databaseFile.parentFile, ".${databaseFile.name}.recovery-journal").exists())
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(
                File(
                    databaseFile.parentFile,
                    ".${databaseFile.name}.recovery-stage$suffix",
                ).exists(),
            )
        }

        val receipt = LocalRecoveryActivationCoordinator(
            keyProvider = keyProvider,
            clock = Clock.fixed(activatedAt, ZoneOffset.UTC),
        ).activate(
            candidate = candidate,
            liveDatabaseFile = databaseFile,
            authorization = authorization,
            authoritativeWatermarks = emptyList(),
        )
        assertFalse(receipt.cleanupPending)
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { activated ->
            val merged = activated.recentAgentAccessAudit(limit = 10, at = activatedAt)
            assertEquals(
                setOf(
                    preBackupStart.auditId,
                    postBackupCompletion.auditId,
                    postBackupStart.auditId,
                ),
                merged.map(AgentAccessAuditRecord::auditId).toSet(),
            )
            assertEquals(1, merged.count { it.auditId == preBackupStart.auditId })
        }
    }

    @Test fun preparedRecoveryRemovesOrphanedAuditMergeSidecarsBeforeRestoringLive() {
        val root = newRoot()
        val databaseFile = File(root, "live/events.db")
        val event = event("event_recovery_prepared_sidecars")
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { database ->
            database.insertCaptured(event)
        }
        val rollback = File(databaseFile.parentFile, ".${databaseFile.name}.recovery-rollback")
        val staged = File(databaseFile.parentFile, ".${databaseFile.name}.recovery-stage")
        val journal = File(databaseFile.parentFile, ".${databaseFile.name}.recovery-journal")
        assertTrue(databaseFile.renameTo(rollback))
        rollback.copyTo(databaseFile)
        staged.writeBytes(byteArrayOf(1, 2, 3))
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File("${staged.path}$suffix").writeBytes(byteArrayOf(4, 5, 6))
            File("${databaseFile.path}$suffix").writeBytes(byteArrayOf(7, 8, 9))
        }
        writePreparedJournal(
            journal = journal,
            confirmationId =
                RecoveryActivationAuthorization.CONFIRMATION_PREFIX +
                    "00000000-0000-4000-8000-000000000120",
            backupId = "backup_00000000-0000-4000-8000-000000000121",
        )

        assertTrue(
            LocalRecoveryActivationCoordinator.recoverInterruptedActivation(
                databaseFile,
                keyProvider,
            ),
        )
        assertFalse(rollback.exists())
        assertFalse(staged.exists())
        assertFalse(journal.exists())
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(File("${staged.path}$suffix").exists())
            assertFalse(File("${databaseFile.path}$suffix").exists())
        }
        LocalEventDatabase.open(databaseFile, keyProvider, SPACE_ID).use { recovered ->
            assertEquals(event.id, recovered.readActive().single().id)
        }
    }

    private fun authorization(
        backupId: String,
        activatedAt: Instant,
    ) = RecoveryActivationAuthorization(
        confirmationId = RecoveryActivationAuthorization.CONFIRMATION_PREFIX +
            UUID.randomUUID(),
        candidateBackupId = backupId,
        confirmedAt = activatedAt.minusSeconds(30),
        expiresAt = activatedAt.plusSeconds(300),
    )

    private fun event(id: String) = MemoryEvent(
        id = id,
        localDate = LocalDate.parse("2026-07-26"),
        time = LocalTime.parse("17:00:00"),
        title = "Recovery safety",
        detail = "Authenticated local recovery candidate",
        factStatus = FactStatus.Confirmed,
        sourceLabel = "instrumented-test",
    )

    private fun auditRecord(
        auditId: String,
        traceId: String,
        phase: AgentAccessAuditPhase,
        resultCode: String,
        at: Instant,
    ) = AgentAccessAuditRecord(
        auditId = auditId,
        traceId = traceId,
        phase = phase,
        callerId = "agent_synthetic",
        purpose = "autonomous_memory",
        spaces = listOf(SPACE_ID),
        dataTypes = listOf("event"),
        operation = "visible_events",
        resultCode = resultCode,
        objectCountBucket = if (phase == AgentAccessAuditPhase.Started) {
            AgentAccessAuditObjectCountBucket.Unknown
        } else {
            AgentAccessAuditObjectCountBucket.One
        },
        occurredAt = at,
        retentionUntil = at.plus(AgentAccessAuditPolicy.retention),
    )

    private fun writePreparedJournal(
        journal: File,
        confirmationId: String,
        backupId: String,
    ) {
        val fields = listOf(
            "ameme-local-recovery-activation-v1",
            "Prepared",
            confirmationId,
            backupId,
        )
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(fields.joinToString("\u001f").toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        journal.writeText((fields + mac).joinToString("\n", postfix = "\n"))
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "ameme-recovery-${UUID.randomUUID()}")
        roots += root
        assertTrue(root.mkdirs())
        return root
    }

    companion object {
        private const val SPACE_ID = "space_personal"
    }
}
