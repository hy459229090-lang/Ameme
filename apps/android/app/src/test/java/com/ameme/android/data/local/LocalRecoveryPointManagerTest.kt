package com.ameme.android.data.local

import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryActivationReceipt
import com.ameme.android.data.RecoveryBackupManifest
import com.ameme.android.data.RecoveryBackupRepository
import com.ameme.android.data.RecoveryCandidate
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecoveryPointManagerTest {
    private val roots = mutableListOf<File>()

    @After fun cleanUp() {
        roots.forEach { root ->
            if (root.exists()) root.deleteRecursively()
        }
    }

    @Test fun createPrepareReceiptRestartAndClearRemainBounded() {
        val root = newRoot()
        val repository = FakeRecoveryRepository()
        val manager = LocalRecoveryPointManager(root)
        val createdAt = Instant.parse("2026-07-29T09:00:00Z")

        val created = manager.create(repository, createdAt)
        assertEquals(LocalRecoveryPointAvailability.Verified, created.availability)
        assertEquals(createdAt, created.createdAt)
        assertTrue((created.snapshotBytes ?: 0) > 0)
        assertTrue(File(root, "candidate").isDirectory)
        assertEquals(
            setOf("candidate", "current"),
            root.listFiles().orEmpty().map(File::getName).toSet(),
        )

        val confirmedAt = createdAt.plusSeconds(120)
        val plan = manager.prepareActivation(repository, confirmedAt)
        assertEquals(created.backupId, plan.authorization.candidateBackupId)
        assertEquals(confirmedAt, plan.authorization.confirmedAt)
        assertTrue(plan.authorization.expiresAt.isAfter(confirmedAt))
        assertFalse(plan.authorization.productionRecoveryClaim)
        assertTrue(
            manager.recordSuccessfulActivation(
                RecoveryActivationReceipt(
                    confirmationId = plan.authorization.confirmationId,
                    candidateBackupId = plan.authorization.candidateBackupId,
                    activatedAt = confirmedAt,
                    cleanupPending = false,
                ),
            ),
        )

        val reopened = LocalRecoveryPointManager(root)
        val refreshed = reopened.refresh(repository, confirmedAt.plusSeconds(30))
        assertEquals(LocalRecoveryPointAvailability.Verified, refreshed.availability)
        assertEquals(confirmedAt, refreshed.lastActivatedAt)
        assertEquals(created.backupId, refreshed.backupId)
        reopened.clear()
        assertFalse(root.exists())
    }

    @Test fun staleWatermarkCorruptionAndUnknownArtifactFailClosed() {
        val root = newRoot()
        val repository = FakeRecoveryRepository()
        val manager = LocalRecoveryPointManager(root)
        assertEquals(
            LocalRecoveryPointAvailability.Verified,
            manager.create(repository).availability,
        )

        repository.watermarkRevision += 1
        assertEquals(
            LocalRecoveryPointAvailability.Unavailable,
            manager.refresh(repository).availability,
        )
        assertThrows(IllegalArgumentException::class.java) {
            manager.prepareActivation(repository)
        }

        assertEquals(
            LocalRecoveryPointAvailability.Verified,
            manager.create(repository).availability,
        )
        val snapshot = File(root, "current/events.db")
        snapshot.appendText("corrupt")
        assertEquals(
            LocalRecoveryPointAvailability.Unavailable,
            manager.refresh(repository).availability,
        )

        val unknown = File(root, "not-owned.txt")
        unknown.writeText("sentinel")
        assertEquals(
            LocalRecoveryPointAvailability.Unavailable,
            manager.refresh(repository).availability,
        )
        assertEquals("sentinel", unknown.readText())
    }

    private fun newRoot(): File {
        val root = Files.createTempDirectory("ameme-recovery-point-test-").toFile()
        root.delete()
        roots += root
        return root
    }

    private class FakeRecoveryRepository : RecoveryBackupRepository {
        var watermarkRevision = 0
        private var generation = 0

        override fun deletionWatermarks(): List<DeletionWatermark> =
            if (watermarkRevision == 0) {
                emptyList()
            } else {
                listOf(
                    DeletionWatermark(
                        spaceId = "space_personal",
                        objectType = "EVENT",
                        objectId = "event_deleted",
                        terminalRevision = watermarkRevision,
                        deletedAtEpochMillis = 1_759_100_000_000,
                        reason = "test_delete",
                        tombstoneDigest = digest("watermark-$watermarkRevision"),
                    ),
                )
            }

        override fun createLocalRecoveryBackup(
            destinationDirectory: File,
            createdAt: Instant,
        ): RecoveryBackupManifest {
            require(!destinationDirectory.exists())
            assertTrue(destinationDirectory.mkdirs())
            generation += 1
            val snapshot = File(destinationDirectory, "events.db")
            snapshot.writeText("generation=$generation\nwatermark=$watermarkRevision\n")
            val manifest = RecoveryBackupManifest(
                schemaVersion = RecoveryBackupManifest.CURRENT_SCHEMA_VERSION,
                backupId = "backup_${UUID.randomUUID()}",
                createdAt = createdAt.toString(),
                localStoreSchemaVersion = 14,
                snapshotFile = "events.db",
                snapshotSha256 = digest(snapshot.readText()),
                snapshotSize = snapshot.length(),
                deletionWatermarkDigest = digest("watermark-$watermarkRevision"),
                manifestMac = digest("fake-manager-contract-$generation"),
            )
            File(destinationDirectory, "backup-manifest.txt").writeText(
                listOf(
                    manifest.backupId,
                    manifest.createdAt,
                    manifest.snapshotSha256,
                    manifest.snapshotSize.toString(),
                    manifest.deletionWatermarkDigest,
                    manifest.manifestMac,
                ).joinToString("\n", postfix = "\n"),
            )
            return manifest
        }

        override fun verifyLocalRecoveryBackup(
            backupDirectory: File,
        ): RecoveryBackupManifest {
            require(
                backupDirectory.isDirectory &&
                    !Files.isSymbolicLink(backupDirectory.toPath()),
            )
            val files = backupDirectory.listFiles().orEmpty()
            require(files.map(File::getName).toSet() == setOf("events.db", "backup-manifest.txt"))
            require(files.none { Files.isSymbolicLink(it.toPath()) })
            val lines = File(backupDirectory, "backup-manifest.txt").readLines()
            require(lines.size == 6)
            val snapshot = File(backupDirectory, "events.db")
            require(lines[2] == digest(snapshot.readText()))
            require(lines[3].toLong() == snapshot.length())
            return RecoveryBackupManifest(
                schemaVersion = RecoveryBackupManifest.CURRENT_SCHEMA_VERSION,
                backupId = lines[0],
                createdAt = lines[1],
                localStoreSchemaVersion = 14,
                snapshotFile = "events.db",
                snapshotSha256 = lines[2],
                snapshotSize = lines[3].toLong(),
                deletionWatermarkDigest = lines[4],
                manifestMac = lines[5],
            )
        }

        override fun restoreLocalRecoveryCandidate(
            backupDirectory: File,
            destinationDirectory: File,
        ): RecoveryCandidate {
            val manifest = verifyLocalRecoveryBackup(backupDirectory)
            require(
                manifest.deletionWatermarkDigest ==
                    digest("watermark-$watermarkRevision"),
            ) { "Snapshot predates an authoritative deletion watermark" }
            require(!destinationDirectory.exists())
            assertTrue(backupDirectory.copyRecursively(destinationDirectory))
            return RecoveryCandidate(
                directory = destinationDirectory,
                databaseFile = File(destinationDirectory, "events.db"),
                sourceManifest = manifest,
            )
        }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
