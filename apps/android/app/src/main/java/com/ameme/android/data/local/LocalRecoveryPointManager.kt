package com.ameme.android.data.local

import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryActivationAuthorization
import com.ameme.android.data.RecoveryActivationReceipt
import com.ameme.android.data.RecoveryBackupRepository
import com.ameme.android.data.RecoveryCandidate
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

enum class LocalRecoveryPointAvailability {
    None,
    Verified,
    Unavailable,
}

data class LocalRecoveryPointStatus(
    val availability: LocalRecoveryPointAvailability,
    val backupId: String? = null,
    val createdAt: Instant? = null,
    val snapshotBytes: Long? = null,
    val verifiedAt: Instant? = null,
    val lastActivatedAt: Instant? = null,
    val cleanupPending: Boolean = false,
) {
    companion object {
        val None = LocalRecoveryPointStatus(LocalRecoveryPointAvailability.None)
        val Unavailable = LocalRecoveryPointStatus(LocalRecoveryPointAvailability.Unavailable)
    }
}

data class LocalRecoveryActivationPlan(
    val candidate: RecoveryCandidate,
    val authorization: RecoveryActivationAuthorization,
    val authoritativeWatermarks: List<DeletionWatermark>,
)

/**
 * Owns one bounded same-install recovery point outside the live SQLCipher directory.
 *
 * A point is healthy only after the authenticated artifact has been restored into a fresh,
 * isolated candidate under the current device-bound key and authoritative deletion watermarks.
 * This manager never exports key material and cannot claim uninstall, device-loss, or
 * cross-device recovery.
 */
class LocalRecoveryPointManager(
    rootDirectory: File,
) {
    private data class Prepared(
        val candidate: RecoveryCandidate,
        val status: LocalRecoveryPointStatus,
    )

    private data class ActivationRecord(
        val backupId: String,
        val activatedAt: Instant,
        val cleanupPending: Boolean,
    )

    private val rootDirectory = rootDirectory.absoluteFile
    private val currentDirectory = File(this.rootDirectory, CURRENT)
    private val previousDirectory = File(this.rootDirectory, PREVIOUS)
    private val candidateDirectory = File(this.rootDirectory, CANDIDATE)
    private val activationRecordFile = File(this.rootDirectory, ACTIVATION_RECORD)
    private var preparedCandidate: RecoveryCandidate? = null
    private var preparedStatus: LocalRecoveryPointStatus = LocalRecoveryPointStatus.None

    fun refresh(
        repository: RecoveryBackupRepository,
        verifiedAt: Instant = Instant.now(),
    ): LocalRecoveryPointStatus {
        preparedCandidate = null
        preparedStatus = LocalRecoveryPointStatus.None
        return runCatching {
            ensureSafeRoot()
            removeCoreStagingDirectories()
            val sources = recoverySources()
            if (sources.isEmpty()) {
                removeCandidateIfPresent()
                return@runCatching LocalRecoveryPointStatus.None
            }

            var lastError: Throwable? = null
            for (source in sources) {
                try {
                    val prepared = prepare(source, repository, verifiedAt)
                    if (source.canonicalFile != currentDirectory.canonicalFile) {
                        replaceCurrent(source)
                    }
                    removeSupersededSlots()
                    preparedCandidate = prepared.candidate
                    preparedStatus = prepared.status
                    return@runCatching prepared.status
                } catch (error: Throwable) {
                    lastError = error
                    runCatching(::removeCandidateIfPresent)
                }
            }
            throw lastError ?: IllegalArgumentException("Recovery point is unavailable")
        }.getOrElse {
            preparedCandidate = null
            preparedStatus = LocalRecoveryPointStatus.Unavailable
            LocalRecoveryPointStatus.Unavailable
        }
    }

    fun create(
        repository: RecoveryBackupRepository,
        createdAt: Instant = Instant.now(),
    ): LocalRecoveryPointStatus {
        ensureSafeRoot()
        recoverInterruptedRotation()
        removeTransientSlots()
        val next = File(rootDirectory, "$NEXT_PREFIX${UUID.randomUUID()}")
        try {
            repository.createLocalRecoveryBackup(next, createdAt)
            repository.verifyLocalRecoveryBackup(next)
            removeCandidateIfPresent()
            repository.restoreLocalRecoveryCandidate(next, candidateDirectory)
            rotateVerifiedNextIntoCurrent(next)
            return refresh(repository).also {
                check(it.availability == LocalRecoveryPointAvailability.Verified) {
                    "Published recovery point did not pass isolated restore verification"
                }
            }
        } catch (error: Throwable) {
            runCatching(::recoverInterruptedRotation).onFailure(error::addSuppressed)
            runCatching { if (next.exists()) removeOwnedTree(next) }
                .onFailure(error::addSuppressed)
            refresh(repository)
            throw error
        }
    }

    fun prepareActivation(
        repository: RecoveryBackupRepository,
        confirmedAt: Instant = Instant.now(),
    ): LocalRecoveryActivationPlan {
        val candidate = requireNotNull(preparedCandidate) {
            "No verified same-install recovery candidate is prepared"
        }
        require(
            preparedStatus.availability == LocalRecoveryPointAvailability.Verified &&
                preparedStatus.backupId == candidate.sourceManifest.backupId,
        ) { "Prepared recovery candidate does not match the verified recovery point" }
        return LocalRecoveryActivationPlan(
            candidate = candidate,
            authorization = RecoveryActivationAuthorization(
                confirmationId =
                    RecoveryActivationAuthorization.CONFIRMATION_PREFIX +
                        UUID.randomUUID(),
                candidateBackupId = candidate.sourceManifest.backupId,
                confirmedAt = confirmedAt,
                expiresAt = confirmedAt.plusSeconds(ACTIVATION_VALIDITY_SECONDS),
            ),
            authoritativeWatermarks = repository.deletionWatermarks(),
        )
    }

    fun recordSuccessfulActivation(receipt: RecoveryActivationReceipt): Boolean {
        val expectedBackupId = preparedStatus.backupId ?: return false
        if (
            receipt.productionRecoveryClaim ||
            receipt.candidateBackupId != expectedBackupId
        ) {
            return false
        }
        return runCatching {
            writeActivationRecord(receipt)
            true
        }.getOrDefault(false)
    }

    fun clear() {
        preparedCandidate = null
        preparedStatus = LocalRecoveryPointStatus.None
        if (rootDirectory.exists()) {
            removeOwnedTree(rootDirectory, allowRoot = true)
        }
    }

    private fun prepare(
        source: File,
        repository: RecoveryBackupRepository,
        verifiedAt: Instant,
    ): Prepared {
        requireOwnedSlot(source)
        val manifest = repository.verifyLocalRecoveryBackup(source)
        removeCandidateIfPresent()
        val candidate = repository.restoreLocalRecoveryCandidate(source, candidateDirectory)
        require(
            candidate.sourceManifest == manifest &&
                !candidate.productionRecoveryClaim &&
                !manifest.productionRecoveryClaim,
        ) { "Prepared recovery candidate is inconsistent" }
        val activation = readActivationRecord(manifest.backupId)
        return Prepared(
            candidate = candidate,
            status = LocalRecoveryPointStatus(
                availability = LocalRecoveryPointAvailability.Verified,
                backupId = manifest.backupId,
                createdAt = Instant.parse(manifest.createdAt),
                snapshotBytes = manifest.snapshotSize,
                verifiedAt = verifiedAt,
                lastActivatedAt = activation?.activatedAt,
                cleanupPending = activation?.cleanupPending ?: false,
            ),
        )
    }

    private fun recoverySources(): List<File> {
        recoverInterruptedRotation()
        val next = transientDirectories(NEXT_PREFIX)
        require(next.size <= 1) { "Multiple interrupted recovery points are ambiguous" }
        return buildList {
            if (currentDirectory.exists()) add(currentDirectory)
            addAll(next)
            if (previousDirectory.exists()) add(previousDirectory)
        }.distinctBy { it.canonicalPath }
    }

    private fun recoverInterruptedRotation() {
        if (!rootDirectory.exists()) return
        validateRootEntries()
        removeCoreStagingDirectories()
        if (!currentDirectory.exists()) {
            val next = transientDirectories(NEXT_PREFIX)
            when {
                next.size == 1 -> atomicMove(next.single(), currentDirectory)
                next.size > 1 -> error("Multiple interrupted recovery points are ambiguous")
                previousDirectory.exists() -> atomicMove(previousDirectory, currentDirectory)
            }
        }
    }

    private fun replaceCurrent(source: File) {
        requireOwnedSlot(source)
        if (source.canonicalFile == currentDirectory.canonicalFile) return
        if (currentDirectory.exists()) removeOwnedTree(currentDirectory)
        atomicMove(source, currentDirectory)
    }

    private fun rotateVerifiedNextIntoCurrent(next: File) {
        requireOwnedSlot(next)
        if (previousDirectory.exists()) removeOwnedTree(previousDirectory)
        if (currentDirectory.exists()) atomicMove(currentDirectory, previousDirectory)
        atomicMove(next, currentDirectory)
        if (previousDirectory.exists()) removeOwnedTree(previousDirectory)
        removeActivationRecordIfPresent()
    }

    private fun removeSupersededSlots() {
        if (previousDirectory.exists()) removeOwnedTree(previousDirectory)
        removeTransientSlots()
        removeCoreStagingDirectories()
    }

    private fun removeTransientSlots() {
        transientDirectories(NEXT_PREFIX).forEach(::removeOwnedTree)
        rootDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(ACTIVATION_TEMP_PREFIX) }
            .forEach { temporary ->
                requireRegularNonSymbolicFile(temporary)
                Files.delete(temporary.toPath())
            }
    }

    private fun removeCoreStagingDirectories() {
        if (!rootDirectory.isDirectory) return
        rootDirectory.listFiles()
            .orEmpty()
            .filter { isCoreStagingName(it.name) }
            .forEach(::removeOwnedTree)
    }

    private fun removeCandidateIfPresent() {
        if (candidateDirectory.exists()) removeOwnedTree(candidateDirectory)
    }

    private fun removeActivationRecordIfPresent() {
        if (activationRecordFile.exists()) {
            requireRegularNonSymbolicFile(activationRecordFile)
            Files.delete(activationRecordFile.toPath())
        }
    }

    private fun ensureSafeRoot() {
        if (!rootDirectory.exists()) {
            check(rootDirectory.mkdirs()) { "Recovery point root could not be created" }
        }
        require(
            rootDirectory.isDirectory &&
                !Files.isSymbolicLink(rootDirectory.toPath()),
        ) { "Recovery point root is unsafe" }
        validateRootEntries()
    }

    private fun validateRootEntries() {
        rootDirectory.listFiles().orEmpty().forEach { child ->
            require(!Files.isSymbolicLink(child.toPath())) {
                "Recovery point root contains a symbolic link"
            }
            require(
                child.name in setOf(CURRENT, PREVIOUS, CANDIDATE, ACTIVATION_RECORD) ||
                    isTransientDirectoryName(child.name, NEXT_PREFIX) ||
                    isCoreStagingName(child.name) ||
                    isActivationTemporaryName(child.name),
            ) { "Recovery point root contains an unknown artifact" }
        }
    }

    private fun transientDirectories(prefix: String): List<File> =
        rootDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(prefix) }
            .onEach { directory ->
                require(
                    directory.isDirectory &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        isTransientDirectoryName(directory.name, prefix),
                ) { "Recovery point transient directory is invalid" }
            }
            .sortedBy(File::getName)

    private fun requireOwnedSlot(file: File) {
        val normalized = file.canonicalFile
        require(normalized.parentFile == rootDirectory.canonicalFile && normalized != rootDirectory) {
            "Recovery point path escapes its private root"
        }
        require(
            normalized.name in setOf(CURRENT, PREVIOUS, CANDIDATE) ||
                isTransientDirectoryName(normalized.name, NEXT_PREFIX) ||
                isCoreStagingName(normalized.name),
        ) { "Recovery point slot name is invalid" }
    }

    private fun removeOwnedTree(file: File, allowRoot: Boolean = false) {
        val normalized = file.canonicalFile
        if (allowRoot) {
            require(normalized == rootDirectory.canonicalFile) {
                "Only the exact recovery point root may be cleared"
            }
        } else {
            requireOwnedSlot(normalized)
        }
        require(normalized.exists()) { "Recovery point path does not exist" }
        val paths = Files.walk(normalized.toPath()).use { stream ->
            stream.toList()
        }
        paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) {
                "Recovery point tree contains a symbolic link"
            }
            require(
                path.toFile().canonicalPath == normalized.canonicalPath ||
                    path.toFile().canonicalPath.startsWith("${normalized.canonicalPath}${File.separator}"),
            ) { "Recovery point tree escapes its private slot" }
        }
        paths.sortedByDescending { it.nameCount }.forEach(Files::delete)
    }

    private fun writeActivationRecord(receipt: RecoveryActivationReceipt) {
        val fields = listOf(
            ACTIVATION_RECORD_VERSION,
            receipt.candidateBackupId,
            receipt.activatedAt.toEpochMilli().toString(),
            receipt.cleanupPending.toString(),
            false.toString(),
        )
        val payload = fields.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_ACTIVATION_RECORD_BYTES) {
            "Recovery activation record is too large"
        }
        val temporary = File(
            rootDirectory,
            "$ACTIVATION_TEMP_PREFIX${UUID.randomUUID()}",
        )
        try {
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0)
                output.write(payload)
                output.fd.sync()
            }
            if (activationRecordFile.exists()) removeActivationRecordIfPresent()
            atomicMove(temporary, activationRecordFile)
        } finally {
            if (temporary.exists()) {
                requireRegularNonSymbolicFile(temporary)
                Files.delete(temporary.toPath())
            }
        }
    }

    private fun readActivationRecord(expectedBackupId: String): ActivationRecord? {
        if (!activationRecordFile.exists()) return null
        return runCatching {
            requireRegularNonSymbolicFile(activationRecordFile)
            require(activationRecordFile.length() in 1..MAX_ACTIVATION_RECORD_BYTES.toLong()) {
                "Recovery activation record size is invalid"
            }
            val lines = activationRecordFile.readLines(StandardCharsets.UTF_8)
            require(
                lines.size == 5 &&
                    lines[0] == ACTIVATION_RECORD_VERSION &&
                    lines[1] == expectedBackupId &&
                    lines[1].startsWith("backup_") &&
                    runCatching {
                        UUID.fromString(lines[1].removePrefix("backup_"))
                    }.isSuccess &&
                    lines[4] == false.toString(),
            ) { "Recovery activation record is invalid" }
            val activatedAt = Instant.ofEpochMilli(lines[2].toLong())
            require(!activatedAt.isBefore(Instant.EPOCH)) {
                "Recovery activation timestamp is invalid"
            }
            val cleanupPending = when (lines[3]) {
                true.toString() -> true
                false.toString() -> false
                else -> error("Recovery activation cleanup state is invalid")
            }
            ActivationRecord(lines[1], activatedAt, cleanupPending)
        }.getOrNull()
    }

    private fun requireRegularNonSymbolicFile(file: File) {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Recovery point metadata file is unsafe"
        }
    }

    private fun isTransientDirectoryName(name: String, prefix: String): Boolean =
        name.startsWith(prefix) &&
            runCatching { UUID.fromString(name.removePrefix(prefix)) }.isSuccess

    private fun isCoreStagingName(name: String): Boolean {
        if (name.startsWith(CANDIDATE_STAGING_PREFIX)) {
            return runCatching {
                UUID.fromString(name.removePrefix(CANDIDATE_STAGING_PREFIX))
            }.isSuccess
        }
        if (!name.startsWith("..next-")) return false
        val parts = name.removePrefix("..next-").split(".staging-", limit = 2)
        return parts.size == 2 &&
            parts.all { runCatching { UUID.fromString(it) }.isSuccess }
    }

    private fun isActivationTemporaryName(name: String): Boolean =
        name.startsWith(ACTIVATION_TEMP_PREFIX) &&
            runCatching {
                UUID.fromString(name.removePrefix(ACTIVATION_TEMP_PREFIX))
            }.isSuccess

    private fun atomicMove(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val CURRENT = "current"
        const val PREVIOUS = "previous"
        const val CANDIDATE = "candidate"
        const val NEXT_PREFIX = ".next-"
        const val CANDIDATE_STAGING_PREFIX = ".candidate.staging-"
        const val ACTIVATION_RECORD = "last-activation-v1.txt"
        const val ACTIVATION_TEMP_PREFIX = ".last-activation-v1.txt.tmp-"
        const val ACTIVATION_RECORD_VERSION = "ameme-local-recovery-activation-record-v1"
        const val MAX_ACTIVATION_RECORD_BYTES = 4 * 1024
        const val ACTIVATION_VALIDITY_SECONDS = 10L * 60
    }
}
