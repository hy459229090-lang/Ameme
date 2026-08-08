package com.ameme.android.data

import java.time.Instant

/**
 * One encrypted local-store space only. This is not account deletion, Grant revocation, peer
 * acknowledgement, provider-original deletion, physical-purge proof, or distributed completion.
 */
enum class LocalSpaceDeletionStatus(val wireValue: String) {
    CompletedLocalOnly("completed_local_only"),
    PendingExternalCleanup("pending_external_cleanup"),
    AlreadyDeleted("already_deleted"),
}

data class LocalSpaceDeletionResult(
    val spaceId: String,
    val status: LocalSpaceDeletionStatus,
    val affectedEventCount: Int,
    val affectedSourceCount: Int,
    val pendingExternalCleanupCount: Int,
    val deletedAt: Instant,
    val externalOriginalsRetained: Boolean,
    val accountDeletionClaim: Boolean = false,
    val peerDeletionProofClaim: Boolean = false,
) {
    init {
        require(spaceId.isNotBlank())
        require(affectedEventCount >= 0)
        require(affectedSourceCount >= 0)
        require(pendingExternalCleanupCount >= 0)
        require(!accountDeletionClaim)
        require(!peerDeletionProofClaim)
    }
}

interface LocalSpaceDeletionRepository {
    fun isLocalSpaceDeleted(): Boolean

    /**
     * Freezes this local space, makes its Event/Coverage/Memory/reuse projections non-readable,
     * and writes an authoritative root watermark. Android-owned Raw bytes do not exist; persisted
     * provider grants can remain pending until the existing locator-release coordinator succeeds.
     */
    fun deleteLocalSpace(requestedAt: Instant = Instant.now()): LocalSpaceDeletionResult
}
