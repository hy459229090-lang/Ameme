package com.ameme.android.data.local

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Preserves the content-free Agent security ledger while business state is restored to a backup.
 *
 * Both stores are closed by the caller. The old live ledger is read-only; the already-authenticated
 * staging copy is merged under rollback-journal mode so no WAL sidecar is omitted from the atomic
 * file swap.
 */
internal object LocalRecoveryAgentAccessAudit {
    fun mergeIntoStagedCandidate(
        liveDatabaseFile: File,
        stagedDatabaseFile: File,
        key: ByteArray,
        retainedAt: Instant,
    ): RecoveryAgentAccessAuditEvidence {
        requireRegularDatabase(liveDatabaseFile, "live")
        requireRegularDatabase(stagedDatabaseFile, "staged")
        val liveRetained = readOnly(liveDatabaseFile, key) { database ->
            LocalAgentAccessAuditPersistence(database).retainedForRecovery(retainedAt)
        }

        SqlCipherRuntime.initialize()
        val writeKey = key.copyOf()
        val staged = try {
            SQLiteDatabase.openDatabase(
                stagedDatabaseFile.absolutePath,
                writeKey,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
            )
        } catch (error: Throwable) {
            writeKey.fill(0)
            throw error
        }
        val plan = try {
            val journalMode = staged.rawQuery(
                "PRAGMA journal_mode=DELETE",
                emptyArray(),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Recovery staging journal mode is unavailable" }
                cursor.getString(0).lowercase()
            }
            check(journalMode == "delete") {
                "Recovery staging database must use rollback journal"
            }
            LocalAgentAccessAuditPersistence(staged).mergeRetainedForRecovery(
                liveRetainedRecords = liveRetained,
                at = retainedAt,
            )
        } finally {
            try {
                staged.close()
            } finally {
                writeKey.fill(0)
                removeClosedStagingSidecars(stagedDatabaseFile)
            }
        }
        return RecoveryAgentAccessAuditEvidence(
            retainedRecordCount = plan.retainedRecordCount,
            retainedDigest = plan.retainedDigest,
            retainedAt = retainedAt,
            mergedSnapshotSize = stagedDatabaseFile.length(),
            mergedSnapshotSha256 = sha256Hex(stagedDatabaseFile),
        )
    }

    fun verifyRetainedEvidence(
        databaseFile: File,
        key: ByteArray,
        expected: RecoveryAgentAccessAuditEvidence,
    ) {
        requireRegularDatabase(databaseFile, "activated")
        val records = readOnly(databaseFile, key) { database ->
            LocalAgentAccessAuditPersistence(database).retainedForRecovery(expected.retainedAt)
        }
        check(records.size == expected.retainedRecordCount) {
            "Activated Agent access audit count changed"
        }
        check(
            com.ameme.android.data.AgentAccessAuditRecoveryPlanner.digest(records) ==
                expected.retainedDigest,
        ) { "Activated Agent access audit digest changed" }
    }

    private inline fun <T> readOnly(
        databaseFile: File,
        key: ByteArray,
        block: (SQLiteDatabase) -> T,
    ): T {
        SqlCipherRuntime.initialize()
        val readKey = key.copyOf()
        val database = try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                readKey,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            )
        } catch (error: Throwable) {
            readKey.fill(0)
            throw error
        }
        return try {
            block(database)
        } finally {
            try {
                database.close()
            } finally {
                readKey.fill(0)
                LocalRecoveryBackup.removeReadOnlyVerificationSidecars(databaseFile)
            }
        }
    }

    private fun requireRegularDatabase(databaseFile: File, label: String) {
        require(
            databaseFile.isFile && !Files.isSymbolicLink(databaseFile.toPath()),
        ) { "Recovery $label Agent access audit database is unsafe" }
    }

    private fun removeClosedStagingSidecars(databaseFile: File) {
        val wal = File("${databaseFile.path}-wal")
        if (wal.exists()) {
            check(wal.isFile && !Files.isSymbolicLink(wal.toPath()) && wal.length() == 0L) {
                "Recovery staging WAL was not checkpointed"
            }
            check(wal.delete()) { "Recovery staging WAL could not be removed" }
        }
        val sharedMemory = File("${databaseFile.path}-shm")
        if (sharedMemory.exists()) {
            check(
                sharedMemory.isFile && !Files.isSymbolicLink(sharedMemory.toPath()),
            ) { "Recovery staging shared-memory path is unsafe" }
            check(sharedMemory.delete()) {
                "Recovery staging shared-memory file could not be removed"
            }
        }
        check(!File("${databaseFile.path}-journal").exists()) {
            "Recovery staging rollback journal was not finalized"
        }
    }

    private fun sha256Hex(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class RecoveryAgentAccessAuditEvidence(
    val retainedRecordCount: Int,
    val retainedDigest: String,
    val retainedAt: Instant,
    val mergedSnapshotSize: Long,
    val mergedSnapshotSha256: String,
) {
    init {
        require(
            retainedRecordCount in 0..com.ameme.android.data.AgentAccessAuditPolicy.MAX_RETAINED_RECORDS,
        ) { "Recovery Agent access audit evidence count is invalid" }
        require(retainedDigest.matches(Regex("[0-9a-f]{64}"))) {
            "Recovery Agent access audit evidence digest is invalid"
        }
        require(mergedSnapshotSize > 0L) {
            "Recovery merged snapshot size is invalid"
        }
        require(mergedSnapshotSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Recovery merged snapshot digest is invalid"
        }
    }
}
