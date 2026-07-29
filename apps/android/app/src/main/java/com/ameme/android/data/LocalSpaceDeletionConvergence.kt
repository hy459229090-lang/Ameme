package com.ameme.android.data

import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Content-free checkpoints for converging data that lives outside the encrypted Event Node.
 *
 * These checkpoints are local-install guarantees only. They do not represent account deletion,
 * a remote peer acknowledgement, provider-original deletion, or physical media erasure.
 */
enum class LocalSpaceDeletionConvergenceStep(val wireValue: String) {
    IncomingAgentRuntimeStop("incoming_agent_runtime_stop"),
    LocalSpaceFreeze("local_space_freeze"),
    StoredAgentPairingRevocation("stored_agent_pairing_revocation"),
    OutgoingAgentTransportClose("outgoing_agent_transport_close"),
    StoredConnectionMetadataClear("stored_connection_metadata_clear"),
    PendingActionSnapshotClear("pending_action_snapshot_clear"),
    PendingActionResurrectionFreeze("pending_action_resurrection_freeze"),
}

enum class LocalSpaceDeletionConvergenceStatus(val wireValue: String) {
    CompletedLocalOnly("completed_local_only"),
    PendingLocalRetry("pending_local_retry"),
    PendingExternalCleanup("pending_external_cleanup"),
}

class LocalSpaceDeletionConvergenceResult internal constructor(
    val spaceId: String,
    val status: LocalSpaceDeletionConvergenceStatus,
    val spaceDeletion: LocalSpaceDeletionResult?,
    val completedSteps: Set<LocalSpaceDeletionConvergenceStep>,
    val pendingRetrySteps: Set<LocalSpaceDeletionConvergenceStep>,
    val requestedAt: Instant,
    val accountDeletionClaim: Boolean = false,
    val peerDeletionProofClaim: Boolean = false,
) {
    init {
        require(spaceId.isNotBlank())
        require(completedSteps.intersect(pendingRetrySteps).isEmpty())
        require(completedSteps + pendingRetrySteps == LocalSpaceDeletionConvergenceStep.entries.toSet())
        require(
            (spaceDeletion == null) ==
                (LocalSpaceDeletionConvergenceStep.LocalSpaceFreeze in pendingRetrySteps),
        )
        require(spaceDeletion == null || spaceDeletion.spaceId == spaceId)
        require(!accountDeletionClaim)
        require(!peerDeletionProofClaim)
    }

    val localSpaceFrozen: Boolean
        get() = LocalSpaceDeletionConvergenceStep.LocalSpaceFreeze in completedSteps

    val localAgentAccessClosed: Boolean
        get() = LOCAL_AGENT_ACCESS_STEPS.all(completedSteps::contains)

    val pendingActionSnapshotCleared: Boolean
        get() = LocalSpaceDeletionConvergenceStep.PendingActionSnapshotClear in completedSteps

    val pendingActionResurrectionFrozen: Boolean
        get() = LocalSpaceDeletionConvergenceStep.PendingActionResurrectionFreeze in completedSteps

    private companion object {
        val LOCAL_AGENT_ACCESS_STEPS = setOf(
            LocalSpaceDeletionConvergenceStep.IncomingAgentRuntimeStop,
            LocalSpaceDeletionConvergenceStep.StoredAgentPairingRevocation,
            LocalSpaceDeletionConvergenceStep.OutgoingAgentTransportClose,
            LocalSpaceDeletionConvergenceStep.StoredConnectionMetadataClear,
        )
    }
}

/**
 * Coordinates one irreversible local-space freeze with all local-install authorization and
 * pending-action surfaces. Every step is attempted even if another step fails, and a replay
 * retries all checkpoints. Exceptions are deliberately reduced to content-free step identifiers.
 */
class LocalSpaceDeletionConvergenceCoordinator(
    private val spaceId: String,
    private val stopIncomingAgentRuntime: suspend () -> Unit,
    private val deleteLocalSpace: suspend (Instant) -> LocalSpaceDeletionResult,
    private val revokeStoredAgentPairing: suspend () -> Unit,
    private val closeOutgoingAgentTransport: suspend () -> Unit,
    private val clearStoredConnectionMetadata: suspend () -> Unit,
    private val freezePendingActionResurrection: suspend () -> Unit,
    private val clearPendingActionSnapshot: suspend () -> Unit,
) {
    init {
        require(spaceId.isNotBlank())
    }

    suspend fun delete(requestedAt: Instant = Instant.now()): LocalSpaceDeletionConvergenceResult =
        withContext(NonCancellable) {
            val completed = linkedSetOf<LocalSpaceDeletionConvergenceStep>()
            val pending = linkedSetOf<LocalSpaceDeletionConvergenceStep>()
            var deletion: LocalSpaceDeletionResult? = null

            suspend fun attempt(
                step: LocalSpaceDeletionConvergenceStep,
                operation: suspend () -> Unit,
            ) {
                runCatching { operation() }
                    .onSuccess { completed += step }
                    .onFailure { pending += step }
            }

            attempt(LocalSpaceDeletionConvergenceStep.IncomingAgentRuntimeStop) {
                stopIncomingAgentRuntime()
            }
            attempt(LocalSpaceDeletionConvergenceStep.LocalSpaceFreeze) {
                deletion = deleteLocalSpace(requestedAt).also {
                    check(it.spaceId == spaceId) { "Local-space deletion result changed space" }
                }
            }
            attempt(LocalSpaceDeletionConvergenceStep.StoredAgentPairingRevocation) {
                revokeStoredAgentPairing()
            }
            attempt(LocalSpaceDeletionConvergenceStep.OutgoingAgentTransportClose) {
                closeOutgoingAgentTransport()
            }
            attempt(LocalSpaceDeletionConvergenceStep.StoredConnectionMetadataClear) {
                clearStoredConnectionMetadata()
            }
            if (deletion != null) {
                attempt(LocalSpaceDeletionConvergenceStep.PendingActionResurrectionFreeze) {
                    freezePendingActionResurrection()
                }
            } else {
                pending += LocalSpaceDeletionConvergenceStep.PendingActionResurrectionFreeze
            }
            attempt(LocalSpaceDeletionConvergenceStep.PendingActionSnapshotClear) {
                clearPendingActionSnapshot()
            }

            val status = when {
                pending.isNotEmpty() -> LocalSpaceDeletionConvergenceStatus.PendingLocalRetry
                requireNotNull(deletion).pendingExternalCleanupCount > 0 ->
                    LocalSpaceDeletionConvergenceStatus.PendingExternalCleanup
                else -> LocalSpaceDeletionConvergenceStatus.CompletedLocalOnly
            }
            LocalSpaceDeletionConvergenceResult(
                spaceId = spaceId,
                status = status,
                spaceDeletion = deletion,
                completedSteps = completed,
                pendingRetrySteps = pending,
                requestedAt = requestedAt,
            )
        }
}
