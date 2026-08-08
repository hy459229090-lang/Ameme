package com.ameme.android.data.local

import android.content.ContentValues
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryInvalidationReason
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryRecord
import com.ameme.android.data.LongTermMemoryState
import com.ameme.android.data.LongTermMemoryType
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/**
 * SQLCipher-local long-term Memory projection. Every candidate binds one exact current Event
 * revision. Event mutation invalidates that dependency conservatively; there is no implicit
 * recompile, reactivation, Agent operation, FTS entry, or DayLedger entry.
 *
 * Callers own the surrounding SQL transaction.
 */
internal class LocalLongTermMemoryPersistence(
    private val database: SQLiteDatabase,
    private val spaceId: String,
) {
    fun propose(proposal: LongTermMemoryProposal): LongTermMemoryRecord {
        existingFor(
            proposal.sourceEventId,
            proposal.expectedEventRevision,
            proposal.type,
        )?.let { return it }
        val source = sourceEvent(proposal.sourceEventId)
        check(source != null && source.state == LocalEventDatabase.STATE_ACTIVE) {
            "long-term Memory source Event is not active"
        }
        check(source.revision == proposal.expectedEventRevision) {
            "long-term Memory source Event revision changed"
        }
        val state = if (
            source.evidenceState == EvidenceState.Inferred ||
            source.sensitivity == Sensitivity.Restricted ||
            proposal.type.requiresUserConfirmation
        ) {
            LongTermMemoryState.CandidateUserConfirmationRequired
        } else {
            LongTermMemoryState.EligibleForMemoryCompiler
        }
        val record = LongTermMemoryRecord(
            memoryId = "mem_${UUID.randomUUID()}",
            spaceId = spaceId,
            type = proposal.type,
            valueSummary = proposal.valueSummary.trim(),
            sourceEventId = source.eventId,
            sourceEventRevision = source.revision,
            evidenceState = source.evidenceState,
            sensitivity = source.sensitivity,
            state = state,
            validFrom = proposal.validFrom,
            validUntil = proposal.validUntil,
            confirmedAt = null,
            supersedesMemoryId = null,
            supersededByMemoryId = null,
            invalidatedAt = null,
            invalidationReason = null,
            revision = 1,
            createdAt = proposal.proposedAt,
            updatedAt = proposal.proposedAt,
        )
        insertCurrent(record)
        insertDependency(record)
        insertRevision(record, reason = "compiler_proposal", baseRevisionId = null)
        return record
    }

    fun confirm(confirmation: LongTermMemoryConfirmation): LongTermMemoryRecord {
        val candidate = load(confirmation.memoryId)
            ?: error("long-term Memory candidate does not exist")
        check(
            candidate.state == LongTermMemoryState.EligibleForMemoryCompiler ||
                candidate.state == LongTermMemoryState.CandidateUserConfirmationRequired,
        ) { "long-term Memory candidate is not confirmable" }
        val source = sourceEvent(candidate.sourceEventId)
        check(source != null && source.state == LocalEventDatabase.STATE_ACTIVE) {
            "long-term Memory source Event is not active"
        }
        check(source.revision == candidate.sourceEventRevision) {
            "long-term Memory source Event revision changed"
        }

        confirmation.supersedesMemoryId?.let { previousId ->
            val previous = load(previousId) ?: error("superseded long-term Memory does not exist")
            check(previous.state == LongTermMemoryState.Active) {
                "only active long-term Memory can be superseded"
            }
            check(previous.type == candidate.type) {
                "superseded long-term Memory type differs"
            }
            val superseded = previous.copy(
                state = LongTermMemoryState.Superseded,
                supersededByMemoryId = candidate.memoryId,
                revision = previous.revision + 1,
                updatedAt = confirmation.confirmedAt,
            )
            updateCurrent(superseded)
            insertRevision(
                superseded,
                reason = "user_supersede",
                baseRevisionId = latestRevisionId(previous.memoryId),
            )
        }

        val active = candidate.copy(
            state = LongTermMemoryState.Active,
            confirmedAt = confirmation.confirmedAt,
            supersedesMemoryId = confirmation.supersedesMemoryId,
            revision = candidate.revision + 1,
            updatedAt = confirmation.confirmedAt,
        )
        updateCurrent(active)
        insertRevision(
            active,
            reason = "user_confirm",
            baseRevisionId = latestRevisionId(candidate.memoryId),
        )
        return active
    }

    fun load(memoryId: String): LongTermMemoryRecord? = database.rawQuery(
        """
            SELECT memory_id, memory_type, value_summary, source_event_id, source_event_revision,
                   evidence_state, sensitivity, state, valid_from, valid_until, confirmed_at,
                   supersedes_memory_id, superseded_by_memory_id, invalidated_at,
                   invalidation_reason, revision, created_at, updated_at
            FROM long_term_memories_current
            WHERE space_id = ? AND memory_id = ?
        """.trimIndent(),
        arrayOf(spaceId, memoryId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    fun visible(at: Instant): List<LongTermMemoryRecord> = database.rawQuery(
        """
            SELECT memory_id, memory_type, value_summary, source_event_id, source_event_revision,
                   evidence_state, sensitivity, state, valid_from, valid_until, confirmed_at,
                   supersedes_memory_id, superseded_by_memory_id, invalidated_at,
                   invalidation_reason, revision, created_at, updated_at
            FROM long_term_memories_current
            WHERE space_id = ? AND state = ? AND valid_from <= ?
              AND (valid_until IS NULL OR valid_until > ?)
            ORDER BY updated_at DESC, memory_id DESC
        """.trimIndent(),
        arrayOf(
            spaceId,
            LongTermMemoryState.Active.name,
            at.toEpochMilli().toString(),
            at.toEpochMilli().toString(),
        ),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toRecord())
        }
    }

    fun invalidateForEvent(
        eventId: String,
        currentEventRevision: Int?,
        reason: LongTermMemoryInvalidationReason,
        invalidatedAt: Instant,
    ) {
        val records = database.rawQuery(
            """
                SELECT memory_id, memory_type, value_summary, source_event_id, source_event_revision,
                       evidence_state, sensitivity, state, valid_from, valid_until, confirmed_at,
                       supersedes_memory_id, superseded_by_memory_id, invalidated_at,
                       invalidation_reason, revision, created_at, updated_at
                FROM long_term_memories_current
                WHERE space_id = ? AND source_event_id = ?
                  AND state IN (?, ?, ?)
            """.trimIndent(),
            arrayOf(
                spaceId,
                eventId,
                LongTermMemoryState.EligibleForMemoryCompiler.name,
                LongTermMemoryState.CandidateUserConfirmationRequired.name,
                LongTermMemoryState.Active.name,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toRecord())
            }
        }
        records
            .filter { currentEventRevision == null || it.sourceEventRevision != currentEventRevision }
            .forEach { record ->
                val invalidated = record.copy(
                    state = LongTermMemoryState.Invalidated,
                    invalidatedAt = invalidatedAt,
                    invalidationReason = reason,
                    revision = record.revision + 1,
                    updatedAt = invalidatedAt,
                )
                updateCurrent(invalidated)
                database.execSQL(
                    """
                        UPDATE long_term_memory_event_dependencies
                        SET state = ?, updated_at = ?
                        WHERE space_id = ? AND memory_id = ? AND event_id = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        "INVALIDATED",
                        invalidatedAt.toEpochMilli(),
                        spaceId,
                        record.memoryId,
                        eventId,
                    ),
                )
                insertRevision(
                    invalidated,
                    reason = reason.wireValue,
                    baseRevisionId = latestRevisionId(record.memoryId),
                )
            }
    }

    private fun existingFor(
        eventId: String,
        eventRevision: Int,
        type: LongTermMemoryType,
    ): LongTermMemoryRecord? = database.rawQuery(
        """
            SELECT memory_id FROM long_term_memories_current
            WHERE space_id = ? AND source_event_id = ? AND source_event_revision = ?
              AND memory_type = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId, eventRevision.toString(), type.name),
    ).use { cursor -> if (cursor.moveToFirst()) load(cursor.getString(0)) else null }

    private fun sourceEvent(eventId: String): SourceEvent? = database.rawQuery(
        """
            SELECT event_id, revision, evidence_state, sensitivity, state
            FROM events_current WHERE space_id = ? AND event_id = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SourceEvent(
            eventId = cursor.getString(0),
            revision = cursor.getInt(1),
            evidenceState = EvidenceState.valueOf(cursor.getString(2)),
            sensitivity = Sensitivity.valueOf(cursor.getString(3)),
            state = cursor.getString(4),
        )
    }

    private fun insertCurrent(record: LongTermMemoryRecord) {
        database.insertOrThrow("long_term_memories_current", null, record.toValues())
    }

    private fun updateCurrent(record: LongTermMemoryRecord) {
        check(
            database.update(
                "long_term_memories_current",
                record.toValues(includeIdentity = false),
                "space_id = ? AND memory_id = ?",
                arrayOf(spaceId, record.memoryId),
            ) == 1,
        ) { "long-term Memory projection update failed" }
    }

    private fun insertDependency(record: LongTermMemoryRecord) {
        database.insertOrThrow(
            "long_term_memory_event_dependencies",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("memory_id", record.memoryId)
                put("event_id", record.sourceEventId)
                put("event_revision", record.sourceEventRevision)
                put("state", "ACTIVE")
                put("created_at", record.createdAt.toEpochMilli())
                put("updated_at", record.updatedAt.toEpochMilli())
            },
        )
    }

    private fun insertRevision(
        record: LongTermMemoryRecord,
        reason: String,
        baseRevisionId: String?,
    ) {
        database.insertOrThrow(
            "long_term_memory_revisions",
            null,
            ContentValues().apply {
                put("revision_id", "memrev_${UUID.randomUUID()}")
                put("space_id", spaceId)
                put("memory_id", record.memoryId)
                put("revision", record.revision)
                if (baseRevisionId == null) putNull("base_revision_id") else put("base_revision_id", baseRevisionId)
                put("reason", reason)
                put("snapshot_json", record.toJson().toString())
                put("created_at", record.updatedAt.toEpochMilli())
            },
        )
    }

    private fun latestRevisionId(memoryId: String): String? = database.rawQuery(
        """
            SELECT revision_id FROM long_term_memory_revisions
            WHERE space_id = ? AND memory_id = ?
            ORDER BY revision DESC LIMIT 1
        """.trimIndent(),
        arrayOf(spaceId, memoryId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun LongTermMemoryRecord.toValues(includeIdentity: Boolean = true) = ContentValues().apply {
        if (includeIdentity) {
            put("space_id", spaceId)
            put("memory_id", memoryId)
        }
        put("memory_type", type.name)
        put("value_summary", valueSummary)
        put("source_event_id", sourceEventId)
        put("source_event_revision", sourceEventRevision)
        put("evidence_state", evidenceState.name)
        put("sensitivity", sensitivity.name)
        put("state", state.name)
        put("valid_from", validFrom.toEpochMilli())
        if (validUntil == null) putNull("valid_until") else put("valid_until", validUntil.toEpochMilli())
        if (confirmedAt == null) putNull("confirmed_at") else put("confirmed_at", confirmedAt.toEpochMilli())
        if (supersedesMemoryId == null) putNull("supersedes_memory_id") else put("supersedes_memory_id", supersedesMemoryId)
        if (supersededByMemoryId == null) putNull("superseded_by_memory_id") else put("superseded_by_memory_id", supersededByMemoryId)
        if (invalidatedAt == null) putNull("invalidated_at") else put("invalidated_at", invalidatedAt.toEpochMilli())
        if (invalidationReason == null) putNull("invalidation_reason") else put("invalidation_reason", invalidationReason.name)
        put("revision", revision)
        put("created_at", createdAt.toEpochMilli())
        put("updated_at", updatedAt.toEpochMilli())
    }

    private fun LongTermMemoryRecord.toJson() = JSONObject()
        .put("memory_id", memoryId)
        .put("space_id", spaceId)
        .put("memory_type", type.wireValue)
        .put("value_summary", valueSummary)
        .put("source_event_id", sourceEventId)
        .put("source_event_revision", sourceEventRevision)
        .put("evidence_state", evidenceState.wireValue)
        .put("sensitivity", sensitivity.wireValue)
        .put("state", state.wireValue)
        .put("valid_from", validFrom.toString())
        .put("valid_until", validUntil?.toString() ?: JSONObject.NULL)
        .put("confirmed_at", confirmedAt?.toString() ?: JSONObject.NULL)
        .put("supersedes_memory_id", supersedesMemoryId ?: JSONObject.NULL)
        .put("superseded_by_memory_id", supersededByMemoryId ?: JSONObject.NULL)
        .put("invalidated_at", invalidatedAt?.toString() ?: JSONObject.NULL)
        .put("invalidation_reason", invalidationReason?.wireValue ?: JSONObject.NULL)
        .put("revision", revision)
        .put("created_at", createdAt.toString())
        .put("updated_at", updatedAt.toString())

    private fun android.database.Cursor.toRecord() = LongTermMemoryRecord(
        memoryId = getString(0),
        spaceId = spaceId,
        type = LongTermMemoryType.valueOf(getString(1)),
        valueSummary = getString(2),
        sourceEventId = getString(3),
        sourceEventRevision = getInt(4),
        evidenceState = EvidenceState.valueOf(getString(5)),
        sensitivity = Sensitivity.valueOf(getString(6)),
        state = LongTermMemoryState.valueOf(getString(7)),
        validFrom = Instant.ofEpochMilli(getLong(8)),
        validUntil = if (isNull(9)) null else Instant.ofEpochMilli(getLong(9)),
        confirmedAt = if (isNull(10)) null else Instant.ofEpochMilli(getLong(10)),
        supersedesMemoryId = if (isNull(11)) null else getString(11),
        supersededByMemoryId = if (isNull(12)) null else getString(12),
        invalidatedAt = if (isNull(13)) null else Instant.ofEpochMilli(getLong(13)),
        invalidationReason = if (isNull(14)) null else LongTermMemoryInvalidationReason.valueOf(getString(14)),
        revision = getInt(15),
        createdAt = Instant.ofEpochMilli(getLong(16)),
        updatedAt = Instant.ofEpochMilli(getLong(17)),
    )

    private data class SourceEvent(
        val eventId: String,
        val revision: Int,
        val evidenceState: EvidenceState,
        val sensitivity: Sensitivity,
        val state: String,
    )

}
