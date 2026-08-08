package com.ameme.android.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Builds the monotonic security-ledger union used by same-install recovery.
 *
 * Business state may roll back to a backup, but unexpired Agent access evidence from the old
 * live store must not roll back with it. The planner is deliberately independent from SQLite so
 * conflict, capacity, and digest semantics remain JVM-testable.
 */
internal object AgentAccessAuditRecoveryPlanner {
    fun plan(
        candidateRecords: List<AgentAccessAuditRecord>,
        liveRetainedRecords: List<AgentAccessAuditRecord>,
        retainedAt: Instant,
    ): AgentAccessAuditRecoveryPlan {
        val candidateRetainedRecords =
            candidateRecords.filter { it.retentionUntil.isAfter(retainedAt) }
        require(candidateRetainedRecords.size <= AgentAccessAuditPolicy.MAX_RETAINED_RECORDS) {
            "Recovery candidate Agent access audit exceeds capacity"
        }
        requireUniqueLedger(candidateRecords, "candidate")
        requireUniqueLedger(liveRetainedRecords, "live")
        require(liveRetainedRecords.all { it.retentionUntil.isAfter(retainedAt) }) {
            "Recovery live Agent access audit contains expired evidence"
        }

        val candidateById =
            candidateRetainedRecords.associateBy(AgentAccessAuditRecord::auditId)
        val candidateByPhase = candidateRetainedRecords.associateBy(::phaseKey)
        val insertions = buildList {
            liveRetainedRecords.forEach { live ->
                val idMatch = candidateById[live.auditId]
                val phaseMatch = candidateByPhase[phaseKey(live)]
                require(idMatch == null || idMatch == live) {
                    "Recovery Agent access audit id conflict"
                }
                require(phaseMatch == null || phaseMatch == live) {
                    "Recovery Agent access audit trace-phase conflict"
                }
                if (idMatch == null && phaseMatch == null) add(live)
            }
        }.sortedWith(recordOrder)
        require(
            candidateRetainedRecords.size + insertions.size <=
                AgentAccessAuditPolicy.MAX_RETAINED_RECORDS,
        ) { "Recovery Agent access audit union exceeds capacity" }

        val retainedUnion =
            (candidateRetainedRecords + insertions).sortedWith(recordOrder)
        requireUniqueLedger(retainedUnion, "merged")
        return AgentAccessAuditRecoveryPlan(
            insertions = insertions,
            retainedRecordCount = retainedUnion.size,
            retainedDigest = digest(retainedUnion),
        )
    }

    fun digest(records: List<AgentAccessAuditRecord>): String {
        val canonical = records.sortedWith(recordOrder)
        requireUniqueLedger(canonical, "digest")
        val digest = MessageDigest.getInstance("SHA-256")
        canonical.forEach { record ->
            digest.put(record.auditId)
            digest.put(record.traceId)
            digest.put(record.phase.name)
            digest.put(record.callerId)
            digest.put(record.purpose)
            digest.put(record.spaces)
            digest.put(record.dataTypes)
            digest.put(record.operation)
            digest.put(record.resultCode)
            digest.put(record.objectCountBucket.wireValue)
            digest.put(record.occurredAt.toEpochMilli())
            digest.put(record.retentionUntil.toEpochMilli())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireUniqueLedger(
        records: List<AgentAccessAuditRecord>,
        label: String,
    ) {
        require(records.map(AgentAccessAuditRecord::auditId).distinct().size == records.size) {
            "Recovery $label Agent access audit ids are not unique"
        }
        require(records.map(::phaseKey).distinct().size == records.size) {
            "Recovery $label Agent access audit trace phases are not unique"
        }
    }

    private fun phaseKey(record: AgentAccessAuditRecord): Pair<String, AgentAccessAuditPhase> =
        record.traceId to record.phase

    private fun MessageDigest.put(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(encoded.size).array())
        update(encoded)
    }

    private fun MessageDigest.put(values: List<String>) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(values.size).array())
        values.forEach { value -> put(value) }
    }

    private fun MessageDigest.put(value: Long) {
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private val recordOrder =
        compareBy<AgentAccessAuditRecord>(
            AgentAccessAuditRecord::occurredAt,
            AgentAccessAuditRecord::auditId,
        )
}

internal data class AgentAccessAuditRecoveryPlan(
    val insertions: List<AgentAccessAuditRecord>,
    val retainedRecordCount: Int,
    val retainedDigest: String,
) {
    init {
        require(retainedRecordCount in 0..AgentAccessAuditPolicy.MAX_RETAINED_RECORDS) {
            "Recovery Agent access audit count is invalid"
        }
        require(retainedDigest.matches(Regex("[0-9a-f]{64}"))) {
            "Recovery Agent access audit digest is invalid"
        }
    }
}
