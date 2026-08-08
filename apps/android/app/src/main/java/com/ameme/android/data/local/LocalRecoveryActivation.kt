package com.ameme.android.data.local

import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryActivationAuthorization
import com.ameme.android.data.RecoveryActivationReceipt
import com.ameme.android.data.RecoveryCandidate
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Activates an already-restored same-install candidate while the live SQLCipher repository is
 * closed. This is a local rollback primitive only: it neither exports key material nor turns the
 * current artifact into cross-device disaster recovery.
 *
 * Callers must serialize this operation with repository open/close. Startup calls
 * [recoverInterruptedActivation] before opening SQLCipher, so a crash before COMMITTED restores
 * the old live file and a crash after COMMITTED keeps the verified candidate.
 */
class LocalRecoveryActivationCoordinator(
    private val keyProvider: DatabaseKeyProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    internal var failureInjector: ((RecoveryActivationPhase) -> Unit)? = null

    fun activate(
        candidate: RecoveryCandidate,
        liveDatabaseFile: File,
        authorization: RecoveryActivationAuthorization,
        authoritativeWatermarks: List<DeletionWatermark>,
    ): RecoveryActivationReceipt {
        val activatedAt = clock.instant()
        require(!candidate.productionRecoveryClaim && !candidate.sourceManifest.productionRecoveryClaim) {
            "Same-install recovery candidate cannot claim production disaster recovery"
        }
        authorization.validate(candidate.sourceManifest.backupId, activatedAt)
        require(
            candidate.databaseFile.canonicalFile ==
                File(candidate.directory, CANDIDATE_DATABASE_NAME).canonicalFile,
        ) { "Recovery candidate database path is invalid" }
        require(
            candidate.directory.isDirectory &&
                !Files.isSymbolicLink(candidate.directory.toPath()),
        ) { "Recovery candidate root must be a non-symbolic directory" }
        require(candidate.databaseFile.canonicalFile != liveDatabaseFile.canonicalFile) {
            "Recovery candidate and live database must be distinct"
        }

        val files = ActivationFiles(liveDatabaseFile)
        val key = keyProvider.getOrCreateKey()
        try {
            recoverInterruptedActivation(liveDatabaseFile, key)
            files.requireReadyForActivation()
            LocalRecoveryBackup.verify(
                backupDirectory = candidate.directory,
                key = key,
                authoritativeWatermarks = authoritativeWatermarks,
            )
            writeJournal(
                journal = files.journal,
                state = JournalState.Prepared,
                authorization = authorization,
                key = key,
            )
            failureInjector?.invoke(RecoveryActivationPhase.JournalPrepared)

            Files.copy(
                candidate.databaseFile.toPath(),
                files.staged.toPath(),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            LocalRecoveryBackup.verifyCandidateDatabase(
                databaseFile = files.staged,
                manifest = candidate.sourceManifest,
                key = key,
                authoritativeWatermarks = authoritativeWatermarks,
            )
            failureInjector?.invoke(RecoveryActivationPhase.CandidateStaged)
            val agentAccessAuditEvidence =
                LocalRecoveryAgentAccessAudit.mergeIntoStagedCandidate(
                    liveDatabaseFile = files.live,
                    stagedDatabaseFile = files.staged,
                    key = key,
                    retainedAt = activatedAt,
                )
            LocalRecoveryBackup.verifyActivatedCandidateDatabase(
                databaseFile = files.staged,
                manifest = candidate.sourceManifest,
                key = key,
                authoritativeWatermarks = authoritativeWatermarks,
                agentAccessAuditEvidence = agentAccessAuditEvidence,
            )
            failureInjector?.invoke(RecoveryActivationPhase.AgentAccessAuditMerged)

            atomicMove(files.live, files.rollback)
            failureInjector?.invoke(RecoveryActivationPhase.LiveMovedToRollback)

            atomicMove(files.staged, files.live)
            failureInjector?.invoke(RecoveryActivationPhase.CandidateMovedToLive)

            LocalRecoveryBackup.verifyActivatedCandidateDatabase(
                databaseFile = files.live,
                manifest = candidate.sourceManifest,
                key = key,
                authoritativeWatermarks = authoritativeWatermarks,
                agentAccessAuditEvidence = agentAccessAuditEvidence,
            )
            failureInjector?.invoke(RecoveryActivationPhase.LiveVerified)

            writeJournal(
                journal = files.journal,
                state = JournalState.Committed,
                authorization = authorization,
                key = key,
            )
            val cleanupPending = runCatching {
                deleteReservedFile(files.rollback)
                deleteReservedFile(files.journal)
            }.isFailure
            return RecoveryActivationReceipt(
                confirmationId = authorization.confirmationId,
                candidateBackupId = candidate.sourceManifest.backupId,
                activatedAt = activatedAt,
                cleanupPending = cleanupPending,
            )
        } catch (error: Throwable) {
            val recovery = runCatching {
                recoverInterruptedActivation(liveDatabaseFile, key)
            }.onFailure(error::addSuppressed)
            if (
                recovery.getOrNull() == false &&
                (files.staged.exists() || files.stagedSidecars.any { it.exists() })
            ) {
                runCatching { deleteStagedArtifacts(files) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            key.fill(0)
        }
    }

    internal enum class RecoveryActivationPhase {
        JournalPrepared,
        CandidateStaged,
        AgentAccessAuditMerged,
        LiveMovedToRollback,
        CandidateMovedToLive,
        LiveVerified,
    }

    private enum class JournalState {
        Prepared,
        Committed,
    }

    private data class ActivationJournal(
        val state: JournalState,
        val confirmationId: String,
        val backupId: String,
    )

    private data class ActivationFiles(
        val live: File,
        val staged: File,
        val rollback: File,
        val journal: File,
    ) {
        constructor(live: File) : this(
            live = live.absoluteFile,
            staged = File(live.absoluteFile.parentFile, ".${live.name}.recovery-stage"),
            rollback = File(live.absoluteFile.parentFile, ".${live.name}.recovery-rollback"),
            journal = File(live.absoluteFile.parentFile, ".${live.name}.recovery-journal"),
        )

        fun requireReadyForActivation() {
            val parent = live.parentFile
                ?: error("Live recovery database has no parent directory")
            require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) {
                "Live recovery database parent is unavailable"
            }
            require(live.isFile && !Files.isSymbolicLink(live.toPath())) {
                "Live recovery database must be a regular non-symbolic file"
            }
            require(!staged.exists() && !rollback.exists() && !journal.exists()) {
                "Reserved recovery activation files already exist"
            }
            require(stagedSidecars.none { it.exists() }) {
                "Reserved recovery staging sidecars already exist"
            }
            SIDECAR_SUFFIXES.forEach { suffix ->
                require(!File(parent, "${live.name}$suffix").exists()) {
                    "Live SQLCipher sidecars must be closed before recovery activation"
                }
            }
        }

        val stagedSidecars: List<File>
            get() = SIDECAR_SUFFIXES.map { suffix -> File("${staged.path}$suffix") }
    }

    companion object {
        private const val CANDIDATE_DATABASE_NAME = "events.db"
        private const val JOURNAL_VERSION = "ameme-local-recovery-activation-v1"
        private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        /**
         * Repairs a crash-interrupted activation before SQLCipher is opened.
         *
         * PREPARED is always rolled back when the old live file exists. COMMITTED keeps the
         * already-verified new live file and only removes the old rollback copy.
         */
        fun recoverInterruptedActivation(
            liveDatabaseFile: File,
            keyProvider: DatabaseKeyProvider,
        ): Boolean {
            if (!ActivationFiles(liveDatabaseFile).journal.exists()) return false
            val key = keyProvider.getOrCreateKey()
            return try {
                recoverInterruptedActivation(liveDatabaseFile, key)
            } finally {
                key.fill(0)
            }
        }

        private fun recoverInterruptedActivation(
            liveDatabaseFile: File,
            key: ByteArray,
        ): Boolean {
            val files = ActivationFiles(liveDatabaseFile)
            if (!files.journal.exists()) return false
            require(files.journal.isFile && !Files.isSymbolicLink(files.journal.toPath())) {
                "Recovery activation journal is unsafe"
            }
            val journal = readJournal(files.journal, key)
            when (journal.state) {
                JournalState.Prepared -> {
                    deleteDatabaseSidecars(files.live)
                    if (files.rollback.exists()) {
                        require(
                            files.rollback.isFile &&
                                !Files.isSymbolicLink(files.rollback.toPath()),
                        ) { "Recovery rollback file is unsafe" }
                        if (files.live.exists()) {
                            deleteReservedFile(files.live)
                        }
                        atomicMove(files.rollback, files.live)
                    } else {
                        require(files.live.isFile && !Files.isSymbolicLink(files.live.toPath())) {
                            "Prepared recovery activation has no recoverable live database"
                        }
                    }
                }

                JournalState.Committed -> {
                    require(files.live.isFile && !Files.isSymbolicLink(files.live.toPath())) {
                        "Committed recovery activation has no live database"
                    }
                    deleteDatabaseSidecars(files.live)
                    if (files.rollback.exists()) {
                        deleteReservedFile(files.rollback)
                    }
                }
            }
            deleteStagedArtifacts(files)
            deleteReservedFile(files.journal)
            return true
        }

        private fun writeJournal(
            journal: File,
            state: JournalState,
            authorization: RecoveryActivationAuthorization,
            key: ByteArray,
        ) {
            val temporary = File(
                journal.parentFile,
                ".${journal.name}.tmp-${UUID.randomUUID()}",
            )
            val authenticatedFields = listOf(
                JOURNAL_VERSION,
                state.name,
                authorization.confirmationId,
                authorization.candidateBackupId,
            )
            val authenticationPayload = authenticatedFields
                .joinToString("\u001f")
                .toByteArray(StandardCharsets.UTF_8)
            val payload = (
                authenticatedFields + hmacHex(key, authenticationPayload)
                ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
            require(payload.size <= 4 * 1024) { "Recovery activation journal is too large" }
            try {
                RandomAccessFile(temporary, "rw").use { output ->
                    output.setLength(0)
                    output.write(payload)
                    output.fd.sync()
                }
                atomicMove(temporary, journal, replaceExisting = true)
            } finally {
                if (temporary.exists()) {
                    deleteReservedFile(temporary)
                }
            }
        }

        private fun readJournal(journal: File, key: ByteArray): ActivationJournal {
            require(journal.length() in 1..(4 * 1024)) {
                "Recovery activation journal size is invalid"
            }
            val lines = journal.readLines(StandardCharsets.UTF_8)
            require(lines.size == 5 && lines[0] == JOURNAL_VERSION) {
                "Recovery activation journal is invalid"
            }
            val state = runCatching { JournalState.valueOf(lines[1]) }
                .getOrElse { throw IllegalArgumentException("Recovery activation journal is invalid") }
            require(lines[2].startsWith(RecoveryActivationAuthorization.CONFIRMATION_PREFIX)) {
                "Recovery activation journal confirmation is invalid"
            }
            require(lines[3].startsWith("backup_")) {
                "Recovery activation journal backup is invalid"
            }
            val authenticationPayload = lines.take(4)
                .joinToString("\u001f")
                .toByteArray(StandardCharsets.UTF_8)
            val expectedMac = hmacHex(key, authenticationPayload)
            require(
                MessageDigest.isEqual(
                    expectedMac.toByteArray(StandardCharsets.US_ASCII),
                    lines[4].toByteArray(StandardCharsets.US_ASCII),
                ),
            ) { "Recovery activation journal authentication failed" }
            return ActivationJournal(
                state = state,
                confirmationId = lines[2],
                backupId = lines[3],
            )
        }

        private fun atomicMove(
            source: File,
            destination: File,
            replaceExisting: Boolean = false,
        ) {
            val options = buildList {
                add(StandardCopyOption.ATOMIC_MOVE)
                if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()
            Files.move(source.toPath(), destination.toPath(), *options)
        }

        private fun deleteReservedFile(file: File) {
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
                "Recovery activation reserved path is unsafe"
            }
            Files.delete(file.toPath())
        }

        private fun deleteStagedArtifacts(files: ActivationFiles) {
            files.stagedSidecars.forEach { sidecar ->
                if (sidecar.exists()) deleteReservedFile(sidecar)
            }
            if (files.staged.exists()) deleteReservedFile(files.staged)
        }

        private fun deleteDatabaseSidecars(databaseFile: File) {
            SIDECAR_SUFFIXES.forEach { suffix ->
                val sidecar = File("${databaseFile.path}$suffix")
                if (sidecar.exists()) deleteReservedFile(sidecar)
            }
        }

        private fun hmacHex(key: ByteArray, payload: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
        }
    }
}
