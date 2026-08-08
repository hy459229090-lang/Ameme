package com.ameme.android.data.local

import android.content.ContentValues
import com.ameme.android.data.AgentAccessAuditObjectCountBucket
import com.ameme.android.data.AgentAccessAuditPhase
import com.ameme.android.data.AgentAccessAuditPolicy
import com.ameme.android.data.AgentAccessAuditRecord
import com.ameme.android.data.AgentAccessAuditRecoveryPlan
import com.ameme.android.data.AgentAccessAuditRecoveryPlanner
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

/** SQLCipher-backed append-only Agent access audit with bounded reads and explicit expiry. */
internal class LocalAgentAccessAuditPersistence(
    private val database: SQLiteDatabase,
) {
    fun append(record: AgentAccessAuditRecord) {
        database.beginTransaction()
        try {
            if (record.phase == AgentAccessAuditPhase.Started) {
                pruneExpired(record.occurredAt)
                check(rowCount() <= AgentAccessAuditPolicy.MAX_RETAINED_RECORDS - 2) {
                    "Agent access audit capacity is exhausted"
                }
            } else {
                requireMatchingStart(record)
            }
            insertRecord(record)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recent(limit: Int, at: Instant): List<AgentAccessAuditRecord> {
        require(limit in 1..AgentAccessAuditPolicy.MAX_RECENT_RECORDS) {
            "Agent access audit limit is invalid"
        }
        return queryRecords(
            """
                SELECT audit_id, trace_id, phase, caller_id, purpose, spaces_json,
                       data_types_json, operation, result_code, object_count_bucket,
                       occurred_at, retention_until
                FROM $TABLE
                WHERE retention_until > ?
                ORDER BY occurred_at DESC, audit_id DESC
                LIMIT ?
            """.trimIndent(),
            arrayOf(at.toEpochMilli().toString(), limit.toString()),
        )
    }

    fun pruneExpired(at: Instant): Int = database.delete(
        TABLE,
        "retention_until <= ?",
        arrayOf(at.toEpochMilli().toString()),
    )

    internal fun retainedForRecovery(at: Instant): List<AgentAccessAuditRecord> = queryRecords(
        """
            SELECT audit_id, trace_id, phase, caller_id, purpose, spaces_json,
                   data_types_json, operation, result_code, object_count_bucket,
                   occurred_at, retention_until
            FROM $TABLE
            WHERE retention_until > ?
            ORDER BY occurred_at, audit_id
        """.trimIndent(),
        arrayOf(at.toEpochMilli().toString()),
    )

    internal fun mergeRetainedForRecovery(
        liveRetainedRecords: List<AgentAccessAuditRecord>,
        at: Instant,
    ): AgentAccessAuditRecoveryPlan {
        database.beginTransaction()
        val plan = try {
            pruneExpired(at)
            val planned = AgentAccessAuditRecoveryPlanner.plan(
                candidateRecords = allForRecovery(),
                liveRetainedRecords = liveRetainedRecords,
                retainedAt = at,
            )
            planned.insertions.forEach(::insertRecord)
            database.setTransactionSuccessful()
            planned
        } finally {
            database.endTransaction()
        }
        val mergedRecords = retainedForRecovery(at)
        check(mergedRecords.size == plan.retainedRecordCount) {
            "Recovery Agent access audit count changed during merge"
        }
        check(AgentAccessAuditRecoveryPlanner.digest(mergedRecords) == plan.retainedDigest) {
            "Recovery Agent access audit digest changed during merge"
        }
        return plan
    }

    internal fun retainedDigestForRecovery(at: Instant): String =
        AgentAccessAuditRecoveryPlanner.digest(retainedForRecovery(at))

    private fun allForRecovery(): List<AgentAccessAuditRecord> = queryRecords(
        """
            SELECT audit_id, trace_id, phase, caller_id, purpose, spaces_json,
                   data_types_json, operation, result_code, object_count_bucket,
                   occurred_at, retention_until
            FROM $TABLE
            ORDER BY occurred_at, audit_id
        """.trimIndent(),
        emptyArray(),
    )

    private fun insertRecord(record: AgentAccessAuditRecord) {
        database.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("audit_id", record.auditId)
                put("trace_id", record.traceId)
                put("phase", record.phase.name)
                put("caller_id", record.callerId)
                put("purpose", record.purpose)
                put("spaces_json", canonicalArray(record.spaces))
                put("data_types_json", canonicalArray(record.dataTypes))
                put("operation", record.operation)
                put("result_code", record.resultCode)
                put("object_count_bucket", record.objectCountBucket.wireValue)
                put("occurred_at", record.occurredAt.toEpochMilli())
                put("retention_until", record.retentionUntil.toEpochMilli())
            },
        )
    }

    private fun queryRecords(
        sql: String,
        arguments: Array<String>,
    ): List<AgentAccessAuditRecord> = database.rawQuery(sql, arguments).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    AgentAccessAuditRecord(
                        auditId = cursor.getString(0),
                        traceId = cursor.getString(1),
                        phase = AgentAccessAuditPhase.valueOf(cursor.getString(2)),
                        callerId = cursor.getString(3),
                        purpose = cursor.getString(4),
                        spaces = decodeCanonicalArray(cursor.getString(5)),
                        dataTypes = decodeCanonicalArray(cursor.getString(6)),
                        operation = cursor.getString(7),
                        resultCode = cursor.getString(8),
                        objectCountBucket = AgentAccessAuditObjectCountBucket.entries
                            .single { it.wireValue == cursor.getString(9) },
                        occurredAt = Instant.ofEpochMilli(cursor.getLong(10)),
                        retentionUntil = Instant.ofEpochMilli(cursor.getLong(11)),
                    ),
                )
            }
        }
    }

    private fun requireMatchingStart(completed: AgentAccessAuditRecord) {
        val started = database.rawQuery(
            """
                SELECT caller_id, purpose, spaces_json, data_types_json, operation,
                       occurred_at, retention_until
                FROM $TABLE
                WHERE trace_id = ? AND phase = ?
            """.trimIndent(),
            arrayOf(completed.traceId, AgentAccessAuditPhase.Started.name),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Agent access audit completion has no start" }
            StartedRow(
                callerId = cursor.getString(0),
                purpose = cursor.getString(1),
                spacesJson = cursor.getString(2),
                dataTypesJson = cursor.getString(3),
                operation = cursor.getString(4),
                occurredAt = cursor.getLong(5),
                retentionUntil = cursor.getLong(6),
            )
        }
        require(
            started.callerId == completed.callerId &&
                started.purpose == completed.purpose &&
                started.spacesJson == canonicalArray(completed.spaces) &&
                started.dataTypesJson == canonicalArray(completed.dataTypes) &&
                started.operation == completed.operation &&
                completed.occurredAt.toEpochMilli() >= started.occurredAt &&
                completed.retentionUntil.toEpochMilli() >= started.retentionUntil,
        ) { "Agent access audit completion does not match its start" }
    }

    private fun rowCount(): Int = database.rawQuery(
        "SELECT COUNT(*) FROM $TABLE",
        emptyArray(),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun canonicalArray(values: List<String>): String =
        JSONArray().also { array -> values.forEach(array::put) }.toString()

    private fun decodeCanonicalArray(encoded: String): List<String> {
        val array = JSONArray(encoded)
        val values = List(array.length()) { index -> array.getString(index) }
        require(values == values.distinct().sorted()) {
            "Agent access audit scope is not canonical"
        }
        return values
    }

    private data class StartedRow(
        val callerId: String,
        val purpose: String,
        val spacesJson: String,
        val dataTypesJson: String,
        val operation: String,
        val occurredAt: Long,
        val retentionUntil: Long,
    )

    companion object {
        const val TABLE = "agent_access_audit"
    }
}
