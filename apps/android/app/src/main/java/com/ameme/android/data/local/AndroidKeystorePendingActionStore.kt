package com.ameme.android.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ameme.android.domain.EventType
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.time.LocalDate
import java.time.LocalTime

/**
 * Durable, encrypted recovery for actions which are waiting for user confirmation
 * or for a system document picker. It is intentionally separate from the SQLCipher
 * event node: a failed handoff must not mutate or require opening the event node.
 */
data class PendingActionSnapshot(
    val incomingShare: SourceCaptureRequest? = null,
    val exportContent: String? = null,
)

class PendingActionStoreUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class AndroidKeystorePendingActionStore(
    context: Context,
    rootDirectoryOverride: File? = null,
) {
    private val appContext = context.applicationContext
    private val rootDirectory =
        rootDirectoryOverride ?: File(appContext.noBackupFilesDir, "pending-actions")
    private val snapshotFile = File(rootDirectory, "snapshot-v1.bin")
    private val temporaryFile = File(rootDirectory, "snapshot-v1.bin.tmp")
    private val deletionMarkerFile = File(rootDirectory, "space-deleted-v1")
    private val deletionMarkerTemporaryFile = File(rootDirectory, "space-deleted-v1.tmp")
    private val alias = "${appContext.packageName}.pending-actions.v1"
    private val aad = "${appContext.packageName}:pending-actions:v1".toByteArray(StandardCharsets.UTF_8)

    @Synchronized
    fun load(): PendingActionSnapshot? {
        if (isFrozenForDeletedSpace()) {
            clear()
            return null
        }
        if (!snapshotFile.isFile) return null
        return try {
            val encrypted = snapshotFile.readBytes()
            require(encrypted.size in 3..MAX_ENCRYPTED_BYTES) { "Pending action snapshot is out of bounds" }
            val clear = decrypt(encrypted)
            try {
                val envelope = json.decodeFromString<PendingActionEnvelope>(
                    String(clear, StandardCharsets.UTF_8),
                )
                require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported pending action schema" }
                PendingActionSnapshot(
                    incomingShare = envelope.incomingShare?.toRequest(),
                    exportContent = envelope.exportContent,
                )
            } finally {
                clear.fill(0)
            }
        } catch (error: PendingActionStoreUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw PendingActionStoreUnavailableException(
                "Pending action snapshot cannot be recovered",
                error,
            )
        }
    }

    @Synchronized
    fun save(snapshot: PendingActionSnapshot) {
        check(!isFrozenForDeletedSpace()) {
            "Pending actions are frozen because the local space is deleted"
        }
        if (snapshot.incomingShare == null && snapshot.exportContent == null) {
            clear()
            return
        }
        val clear = try {
            json.encodeToString(
                PendingActionEnvelope(
                    schemaVersion = SCHEMA_VERSION,
                    incomingShare = snapshot.incomingShare?.toPersisted(),
                    exportContent = snapshot.exportContent,
                ),
            ).toByteArray(StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            throw PendingActionStoreUnavailableException("Pending action snapshot cannot be encoded", error)
        }
        try {
            require(clear.size <= MAX_CLEAR_BYTES) { "Pending action snapshot is too large" }
            writeAtomic(encrypt(clear))
            if (isFrozenForDeletedSpace()) {
                clear()
                throw PendingActionStoreUnavailableException(
                    "Pending actions became frozen while saving",
                )
            }
        } catch (error: PendingActionStoreUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw PendingActionStoreUnavailableException("Pending action snapshot cannot be saved", error)
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun saveIncomingShare(request: SourceCaptureRequest) {
        val current = load() ?: PendingActionSnapshot()
        save(current.copy(incomingShare = request))
    }

    @Synchronized
    fun clearIncomingShare() {
        val current = load() ?: return
        save(current.copy(incomingShare = null))
    }

    @Synchronized
    fun saveExport(content: String) {
        require(content.isNotEmpty()) { "Export content must not be empty" }
        val current = load() ?: PendingActionSnapshot()
        save(current.copy(exportContent = content))
    }

    @Synchronized
    fun clearExport() {
        val current = load() ?: return
        save(current.copy(exportContent = null))
    }

    private fun encrypt(clear: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(clear)
        ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    } catch (error: PendingActionStoreUnavailableException) {
        throw error
    } catch (error: Throwable) {
        throw PendingActionStoreUnavailableException("Pending action encryption is unavailable", error)
    }

    private fun decrypt(encrypted: ByteArray): ByteArray = try {
        val payload = ByteBuffer.wrap(encrypted)
        require(payload.get() == FORMAT_VERSION) { "Unsupported pending action format" }
        val ivSize = payload.get().toInt() and 0xff
        require(ivSize in 12..32 && payload.remaining() > ivSize + GCM_TAG_BYTES) {
            "Invalid pending action ciphertext"
        }
        val iv = ByteArray(ivSize).also(payload::get)
        val ciphertext = ByteArray(payload.remaining()).also(payload::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext).also {
            require(it.size <= MAX_CLEAR_BYTES) { "Pending action cleartext is out of bounds" }
        }
    } catch (error: PendingActionStoreUnavailableException) {
        throw error
    } catch (error: Throwable) {
        throw PendingActionStoreUnavailableException("Pending action decryption failed", error)
    }

    private fun key(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (error: Throwable) {
            throw PendingActionStoreUnavailableException("Android Keystore is unavailable", error)
        }
        try {
            if (keyStore.containsAlias(alias)) {
                return keyStore.getKey(alias, null) as? SecretKey
                    ?: throw PendingActionStoreUnavailableException("Pending action key is not an AES key")
            }
            if (snapshotFile.exists()) {
                throw PendingActionStoreUnavailableException(
                    "Pending action ciphertext exists but its Keystore key is unavailable",
                )
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        } catch (error: PendingActionStoreUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw PendingActionStoreUnavailableException("Pending action key is unavailable", error)
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        rootDirectory.mkdirs()
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    snapshotFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    snapshotFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    @Synchronized
    fun clear() {
        rootDirectory.listFiles().orEmpty()
            .filterNot { it == deletionMarkerFile }
            .forEach { payload ->
                check(payload.deleteRecursively()) {
                    "Could not remove pending action artifact"
                }
            }
        check(rootDirectory.listFiles().orEmpty().all { it == deletionMarkerFile }) {
            "Pending action files remained after clear"
        }
    }

    @Synchronized
    fun freezeForDeletedSpace() {
        rootDirectory.mkdirs()
        try {
            FileOutputStream(deletionMarkerTemporaryFile).use { output ->
                output.write(DELETION_MARKER_BYTES)
                output.fd.sync()
            }
            try {
                Files.move(
                    deletionMarkerTemporaryFile.toPath(),
                    deletionMarkerFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    deletionMarkerTemporaryFile.toPath(),
                    deletionMarkerFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            clear()
            check(isFrozenForDeletedSpace()) { "Pending action deletion marker was not persisted" }
        } catch (error: Throwable) {
            deletionMarkerTemporaryFile.delete()
            throw PendingActionStoreUnavailableException(
                "Pending actions could not be frozen for deleted space",
                error,
            )
        }
    }

    fun isFrozenForDeletedSpace(): Boolean = deletionMarkerFile.isFile

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val FORMAT_VERSION: Byte = 1
        const val SCHEMA_VERSION = 1
        const val MAX_CLEAR_BYTES = 64 * 1024 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_CLEAR_BYTES + 2 + 32 + GCM_TAG_BYTES
        val DELETION_MARKER_BYTES = "ameme.local-space-deleted.v1\n"
            .toByteArray(StandardCharsets.UTF_8)
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

@Serializable
private data class PendingActionEnvelope(
    val schemaVersion: Int,
    val incomingShare: PersistedSourceCaptureRequest? = null,
    val exportContent: String? = null,
)

@Serializable
private data class PersistedSourceCaptureRequest(
    val sourceKind: String,
    val title: String,
    val detail: String,
    val factStatus: String,
    val localDate: String,
    val time: String?,
    val userWords: String?,
    val locatorUri: String?,
    val mimeType: String?,
    val locatorPermissionState: String,
    val sourceInstanceKey: String?,
    val eventType: String,
    val evidenceState: String,
    val sensitivity: String,
    val importance: Int,
)

private fun SourceCaptureRequest.toPersisted() = PersistedSourceCaptureRequest(
    sourceKind = sourceKind.name,
    title = title,
    detail = detail,
    factStatus = factStatus.name,
    localDate = localDate.toString(),
    time = time?.toString(),
    userWords = userWords,
    locatorUri = locatorUri,
    mimeType = mimeType,
    locatorPermissionState = locatorPermissionState.name,
    sourceInstanceKey = sourceInstanceKey,
    eventType = eventType.name,
    evidenceState = evidenceState.name,
    sensitivity = sensitivity.name,
    importance = importance,
)

private fun PersistedSourceCaptureRequest.toRequest() = SourceCaptureRequest(
    sourceKind = SourceKind.valueOf(sourceKind),
    title = title,
    detail = detail,
    factStatus = FactStatus.valueOf(factStatus),
    localDate = LocalDate.parse(localDate),
    time = time?.let(LocalTime::parse),
    userWords = userWords,
    locatorUri = locatorUri,
    mimeType = mimeType,
    locatorPermissionState = LocatorPermissionState.valueOf(locatorPermissionState),
    sourceInstanceKey = sourceInstanceKey,
    eventType = EventType.valueOf(eventType),
    evidenceState = EvidenceState.valueOf(evidenceState),
    sensitivity = Sensitivity.valueOf(sensitivity),
    importance = importance,
)
