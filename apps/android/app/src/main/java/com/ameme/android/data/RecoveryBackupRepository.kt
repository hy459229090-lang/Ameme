package com.ameme.android.data

import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecoveryBackupManifest(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("backup_id")
    val backupId: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("local_store_schema_version")
    val localStoreSchemaVersion: Int,
    @SerialName("snapshot_file")
    val snapshotFile: String,
    @SerialName("snapshot_sha256")
    val snapshotSha256: String,
    @SerialName("snapshot_size")
    val snapshotSize: Long,
    @SerialName("deletion_watermark_digest")
    val deletionWatermarkDigest: String,
    @SerialName("key_material_state")
    val keyMaterialState: String = KEY_MATERIAL_STATE,
    @SerialName("production_recovery_claim")
    val productionRecoveryClaim: Boolean = false,
    @SerialName("manifest_mac")
    val manifestMac: String,
) {
    internal fun canonicalAuthenticationPayload(): ByteArray = listOf(
        schemaVersion.toString(),
        backupId,
        createdAt,
        localStoreSchemaVersion.toString(),
        snapshotFile,
        snapshotSha256,
        snapshotSize.toString(),
        deletionWatermarkDigest,
        keyMaterialState,
        productionRecoveryClaim.toString(),
    ).joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val KEY_MATERIAL_STATE = "external_same_install_required"
    }
}

data class DeletionWatermark(
    val spaceId: String,
    val objectType: String,
    val objectId: String,
    val terminalRevision: Int,
    val deletedAtEpochMillis: Long,
    val reason: String,
    val tombstoneDigest: String,
)

data class RecoveryCandidate(
    val directory: File,
    val databaseFile: File,
    val sourceManifest: RecoveryBackupManifest,
    val productionRecoveryClaim: Boolean = false,
)

data class RecoveryActivationAuthorization(
    val confirmationId: String,
    val candidateBackupId: String,
    val confirmedAt: Instant,
    val expiresAt: Instant,
    val productionRecoveryClaim: Boolean = false,
) {
    internal fun validate(expectedBackupId: String, activatedAt: Instant) {
        require(!productionRecoveryClaim) {
            "Same-install recovery activation cannot claim production disaster recovery"
        }
        require(candidateBackupId == expectedBackupId) {
            "Recovery activation authorization targets a different backup"
        }
        require(confirmationId.startsWith(CONFIRMATION_PREFIX)) {
            "Recovery activation confirmation id is invalid"
        }
        require(
            runCatching {
                UUID.fromString(confirmationId.removePrefix(CONFIRMATION_PREFIX))
            }.isSuccess,
        ) { "Recovery activation confirmation id is invalid" }
        val validity = Duration.between(confirmedAt, expiresAt)
        require(!validity.isNegative && !validity.isZero && validity <= MAX_VALIDITY) {
            "Recovery activation authorization validity is invalid"
        }
        require(!activatedAt.isBefore(confirmedAt) && activatedAt.isBefore(expiresAt)) {
            "Recovery activation authorization is not currently valid"
        }
    }

    companion object {
        const val CONFIRMATION_PREFIX = "recovery_confirmation_"
        val MAX_VALIDITY: Duration = Duration.ofMinutes(15)
    }
}

data class RecoveryActivationReceipt(
    val confirmationId: String,
    val candidateBackupId: String,
    val activatedAt: Instant,
    val cleanupPending: Boolean,
    val productionRecoveryClaim: Boolean = false,
)

/**
 * Exposes only same-install backup verification and isolated recovery candidates. Implementations
 * must never overwrite or switch the live store, export key material, or claim disaster recovery.
 */
interface RecoveryBackupRepository {
    fun deletionWatermarks(): List<DeletionWatermark>

    fun createLocalRecoveryBackup(
        destinationDirectory: File,
        createdAt: Instant = Instant.now(),
    ): RecoveryBackupManifest

    fun verifyLocalRecoveryBackup(backupDirectory: File): RecoveryBackupManifest

    fun restoreLocalRecoveryCandidate(
        backupDirectory: File,
        destinationDirectory: File,
    ): RecoveryCandidate
}
