package com.ameme.android.data.transport

import com.ameme.android.data.AgentAccessAuditObjectCountBucket
import com.ameme.android.data.AgentAccessAuditPhase
import com.ameme.android.data.AgentAccessAuditPolicy
import com.ameme.android.data.AgentAccessAuditRecord
import java.time.Instant
import java.util.UUID

internal interface AgentAccessAuditSink {
    fun begin(control: AgentLocalNodeControl, at: Instant): AgentAccessAuditAttempt

    fun complete(
        attempt: AgentAccessAuditAttempt,
        resultCode: String,
        objectCount: Int?,
        at: Instant,
    )
}

internal data class AgentAccessAuditAttempt(
    val traceId: String,
    val callerId: String,
    val purpose: String,
    val spaces: List<String>,
    val dataTypes: List<String>,
    val operation: String,
    val startedAt: Instant,
) {
    init {
        require(traceId.startsWith("trace_")) { "Agent access audit trace is invalid" }
        require(spaces == spaces.distinct().sorted()) { "Agent access audit spaces are not canonical" }
        require(dataTypes == dataTypes.distinct().sorted()) {
            "Agent access audit data types are not canonical"
        }
    }

    fun startedRecord(): AgentAccessAuditRecord = AgentAccessAuditRecord(
        auditId = newAuditId(),
        traceId = traceId,
        phase = AgentAccessAuditPhase.Started,
        callerId = callerId,
        purpose = purpose,
        spaces = spaces,
        dataTypes = dataTypes,
        operation = operation,
        resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
        objectCountBucket = AgentAccessAuditObjectCountBucket.Unknown,
        occurredAt = startedAt,
        retentionUntil = startedAt.plus(AgentAccessAuditPolicy.retention),
    )

    fun completedRecord(
        resultCode: String,
        objectCount: Int?,
        at: Instant,
    ): AgentAccessAuditRecord {
        require(!at.isBefore(startedAt)) { "Agent access audit completion predates its start" }
        return AgentAccessAuditRecord(
            auditId = newAuditId(),
            traceId = traceId,
            phase = AgentAccessAuditPhase.Completed,
            callerId = callerId,
            purpose = purpose,
            spaces = spaces,
            dataTypes = dataTypes,
            operation = operation,
            resultCode = resultCode,
            objectCountBucket = AgentAccessAuditObjectCountBucket.fromCount(objectCount),
            occurredAt = at,
            retentionUntil = at.plus(AgentAccessAuditPolicy.retention),
        )
    }

    companion object {
        fun from(control: AgentLocalNodeControl, at: Instant): AgentAccessAuditAttempt =
            AgentAccessAuditAttempt(
                traceId = "trace_${UUID.randomUUID().toString().lowercase()}",
                callerId = control.callerId,
                purpose = control.purpose,
                spaces = control.spaces.sorted(),
                dataTypes = control.memoryTypes.sorted(),
                operation = control.operation,
                startedAt = at,
            )

        private fun newAuditId(): String =
            "audit_${UUID.randomUUID().toString().lowercase()}"
    }
}
