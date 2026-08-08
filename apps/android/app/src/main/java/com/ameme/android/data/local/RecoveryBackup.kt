package com.ameme.android.data.local

import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryBackupManifest
import com.ameme.android.data.RecoveryCandidate
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Creates and verifies a same-install SQLCipher snapshot. The artifact deliberately excludes
 * database-key and Android Keystore material. Consequently it is a local recovery oracle, not a
 * cross-device or full-device-loss recovery claim.
 */
internal object LocalRecoveryBackup {
    private const val MANIFEST_FILE = "backup-manifest.json"
    private const val SNAPSHOT_FILE = "events.db"
    private const val MAX_MANIFEST_BYTES = 128 * 1024L
    private const val MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024 * 1024
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun create(
        database: SQLiteDatabase,
        databaseFile: File,
        activeKey: ByteArray,
        destinationDirectory: File,
        createdAt: Instant,
    ): RecoveryBackupManifest {
        require(!destinationDirectory.exists()) { "Backup destination must not already exist" }
        require(databaseFile.isFile) { "Encrypted database file is unavailable" }
        verifyOpenDatabaseHealth(database, LocalEventDatabase.SCHEMA_VERSION)
        checkpointWal(database)

        val parent = destinationDirectory.absoluteFile.parentFile
            ?: error("Backup destination has no parent directory")
        check(parent.mkdirs() || parent.isDirectory) { "Backup parent directory is unavailable" }
        val temporaryDirectory = File(
            parent,
            ".${destinationDirectory.name}.staging-${UUID.randomUUID()}",
        )
        check(temporaryDirectory.mkdir()) { "Backup staging directory could not be created" }

        try {
            val snapshot = File(temporaryDirectory, SNAPSHOT_FILE)
            Files.copy(databaseFile.toPath(), snapshot.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            val unsigned = RecoveryBackupManifest(
                schemaVersion = RecoveryBackupManifest.CURRENT_SCHEMA_VERSION,
                backupId = "backup_${UUID.randomUUID()}",
                createdAt = createdAt.toString(),
                localStoreSchemaVersion = LocalEventDatabase.SCHEMA_VERSION,
                snapshotFile = SNAPSHOT_FILE,
                snapshotSha256 = sha256Hex(snapshot),
                snapshotSize = snapshot.length(),
                deletionWatermarkDigest = allDeletionWatermarkDigest(database),
                manifestMac = "",
            )
            val manifest = unsigned.copy(
                manifestMac = hmacHex(activeKey, unsigned.canonicalAuthenticationPayload()),
            )
            Files.write(
                File(temporaryDirectory, MANIFEST_FILE).toPath(),
                json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8),
            )
            verify(temporaryDirectory, activeKey, emptyList())
            moveDirectoryAtomically(temporaryDirectory, destinationDirectory)
            return manifest
        } catch (error: Throwable) {
            temporaryDirectory.deleteRecursively()
            throw error
        }
    }

    fun verify(
        backupDirectory: File,
        key: ByteArray,
        authoritativeWatermarks: List<DeletionWatermark>,
    ): RecoveryBackupManifest {
        require(backupDirectory.isDirectory) { "Backup directory is unavailable" }
        val files = backupDirectory.listFiles()?.toList() ?: error("Backup directory is unreadable")
        require(files.none { Files.isSymbolicLink(it.toPath()) }) {
            "Backup artifact must not contain symbolic links"
        }
        require(files.map(File::getName).toSet() == setOf(MANIFEST_FILE, SNAPSHOT_FILE)) {
            "Backup artifact file set is not exact"
        }
        val manifestFile = File(backupDirectory, MANIFEST_FILE)
        require(manifestFile.length() in 1..MAX_MANIFEST_BYTES) { "Backup manifest size is invalid" }
        val manifest = json.decodeFromString<RecoveryBackupManifest>(
            manifestFile.readText(StandardCharsets.UTF_8),
        )
        require(manifest.schemaVersion == RecoveryBackupManifest.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup manifest schema"
        }
        require(manifest.backupId.startsWith("backup_")) { "Backup id is invalid" }
        require(manifest.localStoreSchemaVersion == LocalEventDatabase.SCHEMA_VERSION) {
            "Unsupported local store schema"
        }
        require(manifest.snapshotFile == SNAPSHOT_FILE) { "Backup snapshot path is invalid" }
        require(manifest.keyMaterialState == RecoveryBackupManifest.KEY_MATERIAL_STATE) {
            "Unsupported backup key-material state"
        }
        require(!manifest.productionRecoveryClaim) {
            "Local recovery artifact cannot claim production disaster recovery"
        }
        require(runCatching { UUID.fromString(manifest.backupId.removePrefix("backup_")) }.isSuccess) {
            "Backup id is invalid"
        }
        require(runCatching { Instant.parse(manifest.createdAt) }.isSuccess) {
            "Backup timestamp is invalid"
        }
        require(manifest.snapshotSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Backup snapshot digest is invalid"
        }
        require(manifest.deletionWatermarkDigest.matches(Regex("[0-9a-f]{64}"))) {
            "Deletion-watermark digest is invalid"
        }
        val expectedMac = hmacHex(
            key,
            manifest.copy(manifestMac = "").canonicalAuthenticationPayload(),
        )
        require(
            MessageDigest.isEqual(
                expectedMac.toByteArray(StandardCharsets.US_ASCII),
                manifest.manifestMac.toByteArray(StandardCharsets.US_ASCII),
            ),
        ) { "Backup manifest authentication failed" }

        val snapshot = File(backupDirectory, SNAPSHOT_FILE)
        require(snapshot.isFile && snapshot.length() in 1..MAX_SNAPSHOT_BYTES) {
            "Backup snapshot size is invalid"
        }
        require(snapshot.length() == manifest.snapshotSize) { "Backup snapshot size changed" }
        require(sha256Hex(snapshot) == manifest.snapshotSha256) { "Backup snapshot digest changed" }
        verifySnapshot(
            snapshot = snapshot,
            key = key,
            expectedWatermarkDigest = manifest.deletionWatermarkDigest,
            authoritativeWatermarks = authoritativeWatermarks,
        )
        return manifest
    }

    fun restoreCandidate(
        backupDirectory: File,
        destinationDirectory: File,
        keyProvider: DatabaseKeyProvider,
        authoritativeWatermarks: List<DeletionWatermark>,
    ): RecoveryCandidate {
        val key = keyProvider.getOrCreateKey()
        return try {
            restoreCandidateWithKey(
                backupDirectory = backupDirectory,
                destinationDirectory = destinationDirectory,
                key = key,
                authoritativeWatermarks = authoritativeWatermarks,
            )
        } finally {
            key.fill(0)
        }
    }

    fun restoreCandidateWithKey(
        backupDirectory: File,
        destinationDirectory: File,
        key: ByteArray,
        authoritativeWatermarks: List<DeletionWatermark>,
    ): RecoveryCandidate {
        require(!destinationDirectory.exists()) {
            "Recovery candidate destination must not already exist"
        }
        val manifest = verify(backupDirectory, key, authoritativeWatermarks)
        val parent = destinationDirectory.absoluteFile.parentFile
            ?: error("Recovery candidate destination has no parent directory")
        check(parent.mkdirs() || parent.isDirectory) {
            "Recovery candidate parent directory is unavailable"
        }
        val temporaryDirectory = File(
            parent,
            ".${destinationDirectory.name}.staging-${UUID.randomUUID()}",
        )
        check(temporaryDirectory.mkdir()) {
            "Recovery candidate staging directory could not be created"
        }
        try {
            Files.copy(
                File(backupDirectory, SNAPSHOT_FILE).toPath(),
                File(temporaryDirectory, SNAPSHOT_FILE).toPath(),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            Files.copy(
                File(backupDirectory, MANIFEST_FILE).toPath(),
                File(temporaryDirectory, MANIFEST_FILE).toPath(),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            verify(temporaryDirectory, key, authoritativeWatermarks)
            moveDirectoryAtomically(temporaryDirectory, destinationDirectory)
            return RecoveryCandidate(
                directory = destinationDirectory,
                databaseFile = File(destinationDirectory, SNAPSHOT_FILE),
                sourceManifest = manifest,
            )
        } catch (error: Throwable) {
            temporaryDirectory.deleteRecursively()
            throw error
        }
    }

    fun deletionWatermarkDigest(database: SQLiteDatabase, spaceId: String): String {
        val canonical = database.rawQuery(
            """
                SELECT object_type, object_id, terminal_revision, deleted_at, reason, tombstone_digest
                FROM deletion_watermarks
                WHERE space_id = ?
                ORDER BY object_type, object_id
            """.trimIndent(),
            arrayOf(spaceId),
        ).use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    append(cursor.getString(0)).append('\u001f')
                    append(cursor.getString(1)).append('\u001f')
                    append(cursor.getInt(2)).append('\u001f')
                    append(cursor.getLong(3)).append('\u001f')
                    append(cursor.getString(4)).append('\u001f')
                    append(cursor.getString(5)).append('\n')
                }
            }
        }
        return sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    fun tombstoneDigest(
        spaceId: String,
        objectType: String,
        objectId: String,
        terminalRevision: Int,
        deletedAtEpochMillis: Long,
        reason: String,
    ): String = sha256Hex(
        listOf(
            spaceId,
            objectType,
            objectId,
            terminalRevision.toString(),
            deletedAtEpochMillis.toString(),
            reason,
        ).joinToString("\u001f").toByteArray(StandardCharsets.UTF_8),
    )

    internal fun verifyCandidateDatabase(
        databaseFile: File,
        manifest: RecoveryBackupManifest,
        key: ByteArray,
        authoritativeWatermarks: List<DeletionWatermark>,
    ) {
        require(databaseFile.isFile && !Files.isSymbolicLink(databaseFile.toPath())) {
            "Recovery candidate database is unavailable"
        }
        require(databaseFile.length() == manifest.snapshotSize) {
            "Recovery candidate database size changed"
        }
        require(sha256Hex(databaseFile) == manifest.snapshotSha256) {
            "Recovery candidate database digest changed"
        }
        verifySnapshot(
            snapshot = databaseFile,
            key = key,
            expectedWatermarkDigest = manifest.deletionWatermarkDigest,
            authoritativeWatermarks = authoritativeWatermarks,
        )
    }

    internal fun verifyActivatedCandidateDatabase(
        databaseFile: File,
        manifest: RecoveryBackupManifest,
        key: ByteArray,
        authoritativeWatermarks: List<DeletionWatermark>,
        agentAccessAuditEvidence: RecoveryAgentAccessAuditEvidence,
    ) {
        require(databaseFile.isFile && !Files.isSymbolicLink(databaseFile.toPath())) {
            "Activated recovery database is unavailable"
        }
        require(databaseFile.length() == agentAccessAuditEvidence.mergedSnapshotSize) {
            "Activated recovery database size changed"
        }
        require(sha256Hex(databaseFile) == agentAccessAuditEvidence.mergedSnapshotSha256) {
            "Activated recovery database digest changed"
        }
        verifySnapshot(
            snapshot = databaseFile,
            key = key,
            expectedWatermarkDigest = manifest.deletionWatermarkDigest,
            authoritativeWatermarks = authoritativeWatermarks,
        )
        LocalRecoveryAgentAccessAudit.verifyRetainedEvidence(
            databaseFile = databaseFile,
            key = key,
            expected = agentAccessAuditEvidence,
        )
    }

    private fun verifySnapshot(
        snapshot: File,
        key: ByteArray,
        expectedWatermarkDigest: String,
        authoritativeWatermarks: List<DeletionWatermark>,
    ) {
        SqlCipherRuntime.initialize()
        val openKey = key.copyOf()
        val snapshotDatabase = try {
            SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                openKey,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            )
        } catch (error: Throwable) {
            openKey.fill(0)
            throw error
        }
        try {
            verifyOpenDatabaseHealth(snapshotDatabase, LocalEventDatabase.SCHEMA_VERSION)
            val combinedDigest = allDeletionWatermarkDigest(snapshotDatabase)
            require(combinedDigest == expectedWatermarkDigest) {
                "Deletion-watermark digest changed"
            }
            authoritativeWatermarks.forEach { required ->
                val stored = readWatermark(
                    snapshotDatabase,
                    required.spaceId,
                    required.objectType,
                    required.objectId,
                )
                require(stored != null &&
                        stored.terminalRevision >= required.terminalRevision &&
                        MessageDigest.isEqual(
                            stored.tombstoneDigest.toByteArray(StandardCharsets.US_ASCII),
                            required.tombstoneDigest.toByteArray(StandardCharsets.US_ASCII),
                        )) {
                    "Recovery snapshot predates an authoritative deletion watermark"
                }
            }
        } finally {
            try {
                snapshotDatabase.close()
            } finally {
                openKey.fill(0)
                removeReadOnlyVerificationSidecars(snapshot)
            }
        }
    }

    internal fun removeReadOnlyVerificationSidecars(snapshot: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(snapshot.parentFile, "${snapshot.name}$suffix")
            if (!sidecar.exists()) return@forEach
            check(sidecar.isFile && !Files.isSymbolicLink(sidecar.toPath())) {
                "Recovery verification created an unsafe database sidecar"
            }
            check(sidecar.delete()) {
                "Recovery verification database sidecar could not be removed"
            }
        }
    }

    private fun readWatermark(
        database: SQLiteDatabase,
        spaceId: String,
        objectType: String,
        objectId: String,
    ): DeletionWatermark? = database.rawQuery(
        """
            SELECT terminal_revision, deleted_at, reason, tombstone_digest
            FROM deletion_watermarks
            WHERE space_id = ? AND object_type = ? AND object_id = ?
        """.trimIndent(),
        arrayOf(spaceId, objectType, objectId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            DeletionWatermark(
                spaceId = spaceId,
                objectType = objectType,
                objectId = objectId,
                terminalRevision = cursor.getInt(0),
                deletedAtEpochMillis = cursor.getLong(1),
                reason = cursor.getString(2),
                tombstoneDigest = cursor.getString(3),
            )
        }
    }

    private fun allDeletionWatermarkDigest(database: SQLiteDatabase): String {
        val canonical = database.rawQuery(
            """
                SELECT space_id, object_type, object_id, terminal_revision,
                       deleted_at, reason, tombstone_digest
                FROM deletion_watermarks
                ORDER BY space_id, object_type, object_id
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    append(cursor.getString(0)).append('\u001f')
                    append(cursor.getString(1)).append('\u001f')
                    append(cursor.getString(2)).append('\u001f')
                    append(cursor.getInt(3)).append('\u001f')
                    append(cursor.getLong(4)).append('\u001f')
                    append(cursor.getString(5)).append('\u001f')
                    append(cursor.getString(6)).append('\n')
                }
            }
        }
        return sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    private fun checkpointWal(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.getInt(0) == 0) { "SQLCipher WAL checkpoint is busy" }
        }
    }

    private fun verifyOpenDatabaseHealth(database: SQLiteDatabase, expectedSchemaVersion: Int) {
        require(database.version == expectedSchemaVersion) { "Local store schema is unsupported" }
        val cipherIntegrityErrors = database.rawQuery(
            "PRAGMA cipher_integrity_check",
            emptyArray(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        require(cipherIntegrityErrors.isEmpty()) {
            "Encrypted snapshot integrity check failed"
        }
        val databaseIntegrityRows = database.rawQuery(
            "PRAGMA integrity_check",
            emptyArray(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        require(databaseIntegrityRows == listOf("ok")) {
            "Encrypted snapshot integrity check failed"
        }
    }

    private fun moveDirectoryAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun hmacHex(key: ByteArray, payload: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
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

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
