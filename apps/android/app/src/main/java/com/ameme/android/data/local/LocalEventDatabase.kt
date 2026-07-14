package com.ameme.android.data.local

import android.content.ContentValues
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.DaySummary
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.PendingSourceLocatorRelease
import com.ameme.android.domain.Sensitivity
import com.ameme.android.data.transport.AgentLocalNodeCaptureOutcome
import com.ameme.android.data.transport.AgentLocalNodeIdempotencyBinding
import com.ameme.android.data.transport.AgentLocalNodeIdempotencyResult
import java.nio.charset.StandardCharsets
import java.io.Closeable
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase

class LocalEventDatabase private constructor(
    private val database: SQLiteDatabase,
    // SQLCipher 4.15.0 keeps this exact array in SQLiteDatabaseConfiguration for pooled/WAL connections.
    // This is not a second key copy; it is cleared immediately after SQLCipher closes.
    private val activeKey: ByteArray,
    private val spaceId: String,
    private val searchBackend: SearchBackend,
) : Closeable {
    fun seedIfEmpty(events: List<MemoryEvent>) {
        if (currentRowCount() != 0L) return
        inTransaction {
            events.forEach { event ->
                appendRevision(event, reason = "synthetic_seed", state = STATE_ACTIVE)
                refreshSearchIndex(event, STATE_ACTIVE)
            }
            events.map(MemoryEvent::localDate).toSet().forEach(::touchDayLedger)
        }
    }

    fun insertCaptured(event: MemoryEvent, source: SourceCaptureRequest? = null): MemoryEvent = inTransaction {
        source?.let { findActiveSourceInstance(event, it) }?.let { return@inTransaction it }
        check(findCurrent(event.id, includeDeleted = true) == null) { "Event id already exists" }
        appendRevision(event, reason = "capture", state = STATE_ACTIVE)
        source?.let { insertSourceLocator(event.id, it) }
        refreshSearchIndex(event, STATE_ACTIVE)
        touchDayLedger(event.localDate)
        event
    }

    fun insertCapturedBatch(events: List<MemoryEvent>): Int = inTransaction {
        val eventIds = events.map(MemoryEvent::id)
        check(eventIds.toSet().size == eventIds.size) { "Event ids in a batch must be unique" }
        checkNoExistingEvents(eventIds)
        events.forEach { event ->
            appendNewRevision(event, reason = "capture_batch", state = STATE_ACTIVE)
            insertSearchIndex(event)
        }
        events.map(MemoryEvent::localDate).toSet().forEach(::touchDayLedger)
        events.size
    }

    fun insertCapturedSourceBatch(
        entries: List<Pair<MemoryEvent, SourceCaptureRequest>>,
    ): List<MemoryEvent> = inTransaction {
        val inserted = mutableListOf<MemoryEvent>()
        entries.forEach { (event, source) ->
            if (findActiveSourceInstance(event, source) != null) return@forEach
            check(findCurrent(event.id, includeDeleted = true) == null) { "Event id already exists" }
            appendRevision(event, reason = "source_batch_capture", state = STATE_ACTIVE)
            insertSourceLocator(event.id, source)
            refreshSearchIndex(event, STATE_ACTIVE)
            inserted += event
        }
        inserted.map(MemoryEvent::localDate).toSet().forEach(::touchDayLedger)
        inserted
    }

    fun readPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage {
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        val decodedCursor = cursor?.let(::decodeCursor)
        val where = mutableListOf("e.space_id = ?", "e.state = ?")
        val args = mutableListOf(spaceId, STATE_ACTIVE)
        if (date != null) {
            where += "e.local_date = ?"
            args += date.toString()
        }
        val terms = searchTerms(query)
        val useFts = terms.isNotEmpty() && searchBackend == SearchBackend.Fts5
        if (terms.isNotEmpty()) {
            if (useFts) {
                where += "events_fts MATCH ?"
                args += toFtsExpression(terms)
            } else {
                terms.forEach { term ->
                    where += "(e.title LIKE ? ESCAPE '\\' OR e.detail LIKE ? ESCAPE '\\' OR e.source_label LIKE ? ESCAPE '\\' OR COALESCE(e.user_words, '') LIKE ? ESCAPE '\\')"
                    val pattern = "%${escapeLike(term)}%"
                    repeat(4) { args += pattern }
                }
            }
        }
        decodedCursor?.let { position ->
            where += """
                (e.local_date < ? OR
                 (e.local_date = ? AND COALESCE(e.local_time, '') < ?) OR
                 (e.local_date = ? AND COALESCE(e.local_time, '') = ? AND e.updated_at < ?) OR
                 (e.local_date = ? AND COALESCE(e.local_time, '') = ? AND e.updated_at = ? AND e.event_id < ?))
            """.trimIndent()
            args += listOf(
                position.date,
                position.date, position.time,
                position.date, position.time, position.updatedAt.toString(),
                position.date, position.time, position.updatedAt.toString(), position.eventId,
            )
        }
        val from = if (useFts) {
            "events_current e JOIN events_fts ON events_fts.space_id = e.space_id AND events_fts.event_id = e.event_id"
        } else {
            "events_current e"
        }
        val rows = database.rawQuery(
            """
                SELECT e.event_id, e.local_date, e.local_time, e.title, e.detail, e.fact_status,
                       e.source_label, e.is_local_only, e.user_words, e.revision, e.event_type,
                       e.evidence_state, e.sensitivity, e.importance, e.updated_at
                FROM $from
                WHERE ${where.joinToString(" AND ")}
                ORDER BY e.local_date DESC, COALESCE(e.local_time, '') DESC, e.updated_at DESC, e.event_id DESC
                LIMIT ?
            """.trimIndent(),
            (args + (pageSize + 1).toString()).toTypedArray(),
        ).use { cursorResult ->
            buildList {
                while (cursorResult.moveToNext()) {
                    add(
                        EventRow(
                            event = cursorResult.toMemoryEvent(),
                            updatedAt = cursorResult.getLong(14),
                        ),
                    )
                }
            }
        }
        val visible = rows.take(pageSize)
        return MemoryPage(
            events = visible.map(EventRow::event),
            nextCursor = if (rows.size > pageSize) visible.lastOrNull()?.let(::encodeCursor) else null,
            searchBackend = searchBackend,
        )
    }

    fun readActive(query: String = "", date: LocalDate? = null): List<MemoryEvent> {
        val where = mutableListOf("space_id = ?", "state = ?")
        val args = mutableListOf(spaceId, STATE_ACTIVE)
        if (date != null) {
            where += "local_date = ?"
            args += date.toString()
        }
        if (query.isNotBlank()) {
            where += "(title LIKE ? ESCAPE '\\' OR detail LIKE ? ESCAPE '\\' OR source_label LIKE ? ESCAPE '\\' OR COALESCE(user_words, '') LIKE ? ESCAPE '\\')"
            val pattern = "%${escapeLike(query.trim())}%"
            repeat(4) { args += pattern }
        }
        return database.rawQuery(
            """
                SELECT event_id, local_date, local_time, title, detail, fact_status,
                       source_label, is_local_only, user_words, revision, event_type,
                       evidence_state, sensitivity, importance
                FROM events_current
                WHERE ${where.joinToString(" AND ")}
                ORDER BY local_date DESC, local_time DESC, updated_at DESC
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryEvent(
                            id = cursor.getString(0),
                            localDate = LocalDate.parse(cursor.getString(1)),
                            time = cursor.getString(2)?.let(LocalTime::parse),
                            title = cursor.getString(3),
                            detail = cursor.getString(4),
                            factStatus = FactStatus.valueOf(cursor.getString(5)),
                            sourceLabel = cursor.getString(6),
                            isLocalOnly = cursor.getInt(7) == 1,
                            userWords = if (cursor.isNull(8)) null else cursor.getString(8),
                            revision = cursor.getInt(9),
                            eventType = EventType.valueOf(cursor.getString(10)),
                            evidenceState = EvidenceState.valueOf(cursor.getString(11)),
                            sensitivity = Sensitivity.valueOf(cursor.getString(12)),
                            importance = cursor.getInt(13),
                        ),
                    )
                }
            }
        }
    }

    fun deleteEvent(eventId: String): Boolean = inTransaction {
        val current = findCurrent(eventId, includeDeleted = false) ?: return@inTransaction false
        appendRevision(current.event, reason = "user_delete", state = STATE_DELETED)
        if (searchBackend == SearchBackend.Fts5) {
            database.delete("events_fts", "space_id = ? AND event_id = ?", arrayOf(spaceId, eventId))
        }
        database.execSQL(
            """
                UPDATE source_locators
                SET state = CASE WHEN permission_state = ? THEN ? ELSE ? END,
                    updated_at = ?
                WHERE space_id = ? AND event_id = ? AND state = ?
            """.trimIndent(),
            arrayOf<Any>(
                LocatorPermissionState.PersistedRead.name,
                LOCATOR_STATE_RELEASE_PENDING,
                LOCATOR_STATE_DELETED,
                System.currentTimeMillis(),
                spaceId,
                eventId,
                STATE_ACTIVE,
            ),
        )
        clearSummaryAfterDeletion(current.event.localDate)
        true
    }

    fun sourceLocatorState(eventId: String): String? = database.rawQuery(
        "SELECT permission_state FROM source_locators WHERE space_id = ? AND event_id = ? AND state = ?",
        arrayOf(spaceId, eventId, STATE_ACTIVE),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun sourceLocator(eventId: String): SourceLocator? = database.rawQuery(
        "SELECT locator_uri, permission_state FROM source_locators WHERE space_id = ? AND event_id = ? AND state = ?",
        arrayOf(spaceId, eventId, STATE_ACTIVE),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SourceLocator(
            uri = cursor.getString(0),
            permissionState = LocatorPermissionState.valueOf(cursor.getString(1)),
        )
    }

    fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> = database.rawQuery(
        """
            SELECT event_id, locator_uri FROM source_locators
            WHERE space_id = ? AND permission_state = ? AND state = ?
            ORDER BY updated_at ASC, event_id ASC
        """.trimIndent(),
        arrayOf(spaceId, LocatorPermissionState.PersistedRead.name, LOCATOR_STATE_RELEASE_PENDING),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(PendingSourceLocatorRelease(cursor.getString(0), cursor.getString(1)))
            }
        }
    }

    fun markSourceLocatorReleased(eventId: String): Boolean = inTransaction {
        database.update(
            "source_locators",
            ContentValues().apply {
                put("state", LOCATOR_STATE_RELEASED)
                put("updated_at", System.currentTimeMillis())
            },
            "space_id = ? AND event_id = ? AND permission_state = ? AND state = ?",
            arrayOf(
                spaceId,
                eventId,
                LocatorPermissionState.PersistedRead.name,
                LOCATOR_STATE_RELEASE_PENDING,
            ),
        ) == 1
    }

    fun sourceLocatorLifecycleState(eventId: String): String? = database.rawQuery(
        "SELECT state FROM source_locators WHERE space_id = ? AND event_id = ?",
        arrayOf(spaceId, eventId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun revisionCount(eventId: String): Int = database.rawQuery(
        "SELECT COUNT(*) FROM event_revisions WHERE event_id = ? AND space_id = ?",
        arrayOf(eventId, spaceId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    fun currentState(eventId: String): String? = database.rawQuery(
        "SELECT state FROM events_current WHERE event_id = ? AND space_id = ?",
        arrayOf(eventId, spaceId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun journalMode(): String = database.rawQuery("PRAGMA journal_mode", emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    fun cipherVersion(): String = database.rawQuery("PRAGMA cipher_version", emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    fun checkpointWal() {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
        }
    }

    fun schemaVersion(): Int = database.version

    fun hasMigration(version: Int): Boolean = database.rawQuery(
        "SELECT 1 FROM schema_migrations WHERE version = ?",
        arrayOf(version.toString()),
    ).use { it.moveToFirst() }

    fun hasSearchTable(): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'events_fts'",
        emptyArray(),
    ).use { it.moveToFirst() }

    override fun close() {
        try {
            database.close()
        } finally {
            activeKey.fill(0)
        }
    }

    private fun appendRevision(event: MemoryEvent, reason: String, state: String) {
        val current = findCurrent(event.id, includeDeleted = true)
        writeRevision(event, reason, state, current)
    }

    private fun appendNewRevision(event: MemoryEvent, reason: String, state: String) {
        writeRevision(event, reason, state, current = null)
    }

    private fun writeRevision(event: MemoryEvent, reason: String, state: String, current: CurrentEvent?) {
        val revision = (current?.revision ?: 0) + 1
        val revisionId = "rev_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val common = eventValues(event, state)

        val revisionValues = ContentValues(common).apply {
            put("revision_id", revisionId)
            put("event_id", event.id)
            put("revision", revision)
            if (current == null) putNull("base_revision_id") else put("base_revision_id", current.revisionId)
            put("reason", reason)
            put("created_at", now)
        }
        database.insertOrThrow("event_revisions", null, revisionValues)

        val currentValues = ContentValues(common).apply {
            put("event_id", event.id)
            put("revision_head_id", revisionId)
            put("revision", revision)
            put("updated_at", now)
        }
        if (current == null) {
            database.insertOrThrow("events_current", null, currentValues)
        } else {
            check(
                database.update(
                    "events_current",
                    currentValues,
                    "space_id = ? AND event_id = ?",
                    arrayOf(spaceId, event.id),
                ) == 1,
            ) { "Current event projection update failed" }
        }
    }

    fun readDaySummary(localDate: LocalDate): DaySummarySnapshot {
        val events = readActive(date = localDate)
        val ledger = database.rawQuery(
            "SELECT revision, summary_state FROM day_ledgers WHERE space_id = ? AND local_date = ?",
            arrayOf(spaceId, localDate.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0) to DaySummaryState.valueOf(cursor.getString(1))
            } else {
                0 to DaySummaryState.Absent
            }
        }
        val summary = database.rawQuery(
            """
                SELECT summary_id, based_on_revision, text, state, model_or_rule_version, created_at
                FROM day_summaries WHERE space_id = ? AND local_date = ?
            """.trimIndent(),
            arrayOf(spaceId, localDate.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else DaySummary(
                id = cursor.getString(0),
                localDate = localDate,
                basedOnLedgerRevision = cursor.getInt(1),
                text = cursor.getString(2),
                state = DaySummaryState.valueOf(cursor.getString(3)),
                modelOrRuleVersion = cursor.getString(4),
                createdAtEpochMillis = cursor.getLong(5),
            )
        }
        val effectiveState = when {
            events.count(::isSummaryEligible) < MIN_SUMMARY_EVENTS -> DaySummaryState.Insufficient
            summary != null && summary.basedOnLedgerRevision != ledger.first -> DaySummaryState.Stale
            else -> ledger.second
        }
        return DaySummarySnapshot(
            localDate = localDate,
            ledgerRevision = ledger.first,
            events = events,
            state = effectiveState,
            summary = summary?.copy(state = if (effectiveState == DaySummaryState.Stale) DaySummaryState.Stale else summary.state),
        )
    }

    fun beginDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot = inTransaction {
        require(expectedLedgerRevision >= 1) { "Day ledger revision must be positive" }
        val snapshot = readDaySummary(localDate)
        require(snapshot.ledgerRevision == expectedLedgerRevision) { "Day ledger revision changed" }
        if (snapshot.eligibleEvents.size < MIN_SUMMARY_EVENTS) {
            database.execSQL(
                "UPDATE day_ledgers SET summary_state = ?, updated_at = ? WHERE space_id = ? AND local_date = ? AND revision = ?",
                arrayOf<Any>(
                    DaySummaryState.Insufficient.name,
                    System.currentTimeMillis(),
                    spaceId,
                    localDate.toString(),
                    expectedLedgerRevision,
                ),
            )
            return@inTransaction snapshot.copy(state = DaySummaryState.Insufficient)
        }
        check(
            database.update(
                "day_ledgers",
                ContentValues().apply {
                    put("summary_state", DaySummaryState.Processing.name)
                    put("updated_at", System.currentTimeMillis())
                },
                "space_id = ? AND local_date = ? AND revision = ?",
                arrayOf(spaceId, localDate.toString(), expectedLedgerRevision.toString()),
            ) == 1,
        ) { "Day ledger revision changed" }
        snapshot.copy(state = DaySummaryState.Processing)
    }

    fun completeDaySummary(
        localDate: LocalDate,
        expectedLedgerRevision: Int,
        text: String,
        modelOrRuleVersion: String,
    ): DaySummarySnapshot = inTransaction {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty() && normalizedText.length <= 4_000) { "Summary text is invalid" }
        require(modelOrRuleVersion.isNotBlank() && modelOrRuleVersion.length <= 256) {
            "Summary model version is invalid"
        }
        val snapshot = readDaySummary(localDate)
        if (snapshot.ledgerRevision != expectedLedgerRevision || snapshot.state != DaySummaryState.Processing) {
            return@inTransaction snapshot.copy(state = DaySummaryState.Stale)
        }
        val now = System.currentTimeMillis()
        val summary = DaySummary(
            id = "sum_${UUID.randomUUID()}",
            localDate = localDate,
            basedOnLedgerRevision = expectedLedgerRevision,
            text = normalizedText,
            state = DaySummaryState.Ready,
            modelOrRuleVersion = modelOrRuleVersion,
            createdAtEpochMillis = now,
        )
        database.insertWithOnConflict(
            "day_summaries",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("local_date", localDate.toString())
                put("summary_id", summary.id)
                put("based_on_revision", expectedLedgerRevision)
                put("text", normalizedText)
                put("state", DaySummaryState.Ready.name)
                put("model_or_rule_version", modelOrRuleVersion)
                put("created_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(
            database.update(
                "day_ledgers",
                ContentValues().apply {
                    put("summary_state", DaySummaryState.Ready.name)
                    put("updated_at", now)
                },
                "space_id = ? AND local_date = ? AND revision = ?",
                arrayOf(spaceId, localDate.toString(), expectedLedgerRevision.toString()),
            ) == 1,
        ) { "Day ledger revision changed" }
        snapshot.copy(state = DaySummaryState.Ready, summary = summary)
    }

    fun failDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot = inTransaction {
        val hasSummary = database.rawQuery(
            "SELECT 1 FROM day_summaries WHERE space_id = ? AND local_date = ?",
            arrayOf(spaceId, localDate.toString()),
        ).use { it.moveToFirst() }
        val fallback = if (hasSummary) DaySummaryState.Stale else DaySummaryState.Absent
        val updated = database.update(
            "day_ledgers",
            ContentValues().apply {
                put("summary_state", fallback.name)
                put("updated_at", System.currentTimeMillis())
            },
            "space_id = ? AND local_date = ? AND revision = ?",
            arrayOf(spaceId, localDate.toString(), expectedLedgerRevision.toString()),
        )
        val current = readDaySummary(localDate)
        if (updated == 1) current.copy(state = fallback) else current
    }

    internal fun resolveAgentIdempotency(
        binding: AgentLocalNodeIdempotencyBinding,
        payloadDigest: String,
        capture: () -> AgentLocalNodeCaptureOutcome,
    ): AgentLocalNodeIdempotencyResult = inTransaction {
        require(payloadDigest.matches(Regex("sha256_[0-9a-f]{64}"))) { "payload digest is invalid" }
        val args = arrayOf(
            binding.callerId,
            binding.grantId,
            binding.purpose,
            binding.spaceId,
            binding.memoryType,
            binding.operation,
            binding.slot,
        )
        val existing = database.rawQuery(
            """
                SELECT payload_digest, event_id, revision
                FROM agent_idempotency
                WHERE caller_id = ? AND grant_id = ? AND purpose = ? AND space_id = ?
                  AND memory_type = ? AND operation = ? AND slot = ?
            """.trimIndent(),
            args,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
        }
        if (existing != null) {
            return@inTransaction if (existing.first == payloadDigest) {
                AgentLocalNodeIdempotencyResult.Applied(
                    AgentLocalNodeCaptureOutcome(existing.second, existing.third),
                )
            } else {
                AgentLocalNodeIdempotencyResult.Conflict
            }
        }
        val outcome = capture()
        database.insertOrThrow(
            "agent_idempotency",
            null,
            ContentValues().apply {
                put("caller_id", binding.callerId)
                put("grant_id", binding.grantId)
                put("purpose", binding.purpose)
                put("space_id", binding.spaceId)
                put("memory_type", binding.memoryType)
                put("operation", binding.operation)
                put("slot", binding.slot)
                put("payload_digest", payloadDigest)
                put("event_id", outcome.eventId)
                put("revision", outcome.revision)
                put("created_at", System.currentTimeMillis())
            },
        )
        AgentLocalNodeIdempotencyResult.Applied(outcome)
    }

    private fun checkNoExistingEvents(eventIds: List<String>) {
        eventIds.chunked(BATCH_EXISTENCE_CHECK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = arrayOf(spaceId, *chunk.toTypedArray())
            val exists = database.rawQuery(
                "SELECT 1 FROM events_current WHERE space_id = ? AND event_id IN ($placeholders) LIMIT 1",
                args,
            ).use { it.moveToFirst() }
            check(!exists) { "Event id already exists" }
        }
    }

    private fun eventValues(event: MemoryEvent, state: String) = ContentValues().apply {
        require(event.revision >= 1) { "Event revision must be positive" }
        require(event.importance in 0..100) { "Event importance must be between 0 and 100" }
        put("local_date", event.localDate.toString())
        if (event.time == null) putNull("local_time") else put("local_time", event.time.toString())
        put("title", event.title)
        put("detail", event.detail)
        put("fact_status", event.factStatus.name)
        put("source_label", event.sourceLabel)
        put("is_local_only", if (event.isLocalOnly) 1 else 0)
        if (event.userWords == null) putNull("user_words") else put("user_words", event.userWords)
        put("event_type", event.eventType.name)
        put("evidence_state", event.evidenceState.name)
        put("sensitivity", event.sensitivity.name)
        put("importance", event.importance)
        put("state", state)
        put("space_id", spaceId)
    }

    private fun findCurrent(eventId: String, includeDeleted: Boolean): CurrentEvent? {
        val stateClause = if (includeDeleted) "" else " AND state = '$STATE_ACTIVE'"
        return database.rawQuery(
            """
                SELECT revision_head_id, revision, event_id, local_date, local_time, title, detail,
                       fact_status, source_label, is_local_only, user_words, event_type,
                       evidence_state, sensitivity, importance
                FROM events_current WHERE event_id = ? AND space_id = ?$stateClause
            """.trimIndent(),
            arrayOf(eventId, spaceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CurrentEvent(
                revisionId = cursor.getString(0),
                revision = cursor.getInt(1),
                event = MemoryEvent(
                    id = cursor.getString(2),
                    localDate = LocalDate.parse(cursor.getString(3)),
                    time = cursor.getString(4)?.let(LocalTime::parse),
                    title = cursor.getString(5),
                    detail = cursor.getString(6),
                    factStatus = FactStatus.valueOf(cursor.getString(7)),
                    sourceLabel = cursor.getString(8),
                    isLocalOnly = cursor.getInt(9) == 1,
                    userWords = if (cursor.isNull(10)) null else cursor.getString(10),
                    revision = cursor.getInt(1),
                    eventType = EventType.valueOf(cursor.getString(11)),
                    evidenceState = EvidenceState.valueOf(cursor.getString(12)),
                    sensitivity = Sensitivity.valueOf(cursor.getString(13)),
                    importance = cursor.getInt(14),
                ),
            )
        }
    }

    private fun currentRowCount(): Long = database.rawQuery(
        "SELECT COUNT(*) FROM events_current WHERE space_id = ?",
        arrayOf(spaceId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun insertSourceLocator(eventId: String, source: SourceCaptureRequest) {
        if (source.locatorUri == null) return
        val now = System.currentTimeMillis()
        database.insertOrThrow(
            "source_locators",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("event_id", eventId)
                put("source_kind", source.sourceKind.name)
                put("locator_uri", source.locatorUri)
                if (source.sourceInstanceKey == null) {
                    putNull("source_instance_key")
                } else {
                    put("source_instance_key", source.sourceInstanceKey)
                }
                if (source.mimeType == null) putNull("mime_type") else put("mime_type", source.mimeType)
                put("permission_state", source.locatorPermissionState.name)
                put("state", STATE_ACTIVE)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    private fun findActiveSourceInstance(event: MemoryEvent, source: SourceCaptureRequest): MemoryEvent? {
        val locatorUri = source.locatorUri ?: return null
        val instanceKey = source.sourceInstanceKey ?: return null
        val existingId = database.rawQuery(
            """
                SELECT e.event_id
                FROM source_locators s
                JOIN events_current e
                  ON e.space_id = s.space_id AND e.event_id = s.event_id
                WHERE s.space_id = ? AND s.source_kind = ? AND s.locator_uri = ?
                  AND (
                    s.source_instance_key = ? OR
                    (s.source_instance_key IS NULL AND e.local_date = ? AND COALESCE(e.local_time, '') = ?)
                  )
                  AND s.state = ? AND e.state = ?
                LIMIT 1
            """.trimIndent(),
            arrayOf(
                spaceId,
                source.sourceKind.name,
                locatorUri,
                instanceKey,
                event.localDate.toString(),
                event.time?.toString().orEmpty(),
                STATE_ACTIVE,
                STATE_ACTIVE,
            ),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return existingId?.let { findCurrent(it, includeDeleted = false)?.event }
    }

    private fun refreshSearchIndex(event: MemoryEvent, state: String) {
        if (searchBackend != SearchBackend.Fts5) return
        database.delete("events_fts", "space_id = ? AND event_id = ?", arrayOf(spaceId, event.id))
        if (state != STATE_ACTIVE) return
        insertSearchIndex(event)
    }

    private fun insertSearchIndex(event: MemoryEvent) {
        if (searchBackend != SearchBackend.Fts5) return
        database.insertOrThrow(
            "events_fts",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("event_id", event.id)
                put("title", event.title)
                put("detail", event.detail)
                put("source_label", event.sourceLabel)
                put("user_words", event.userWords.orEmpty())
            },
        )
    }

    private fun android.database.Cursor.toMemoryEvent() = MemoryEvent(
        id = getString(0),
        localDate = LocalDate.parse(getString(1)),
        time = getString(2)?.let(LocalTime::parse),
        title = getString(3),
        detail = getString(4),
        factStatus = FactStatus.valueOf(getString(5)),
        sourceLabel = getString(6),
        isLocalOnly = getInt(7) == 1,
        userWords = if (isNull(8)) null else getString(8),
        revision = getInt(9),
        eventType = EventType.valueOf(getString(10)),
        evidenceState = EvidenceState.valueOf(getString(11)),
        sensitivity = Sensitivity.valueOf(getString(12)),
        importance = getInt(13),
    )

    private fun touchDayLedger(localDate: LocalDate) {
        val now = System.currentTimeMillis()
        database.execSQL(
            """
                INSERT INTO day_ledgers(space_id, local_date, revision, summary_state, updated_at)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(space_id, local_date) DO UPDATE SET
                    revision = day_ledgers.revision + 1,
                    summary_state = CASE
                        WHEN day_ledgers.summary_state IN (?, ?, ?) THEN ?
                        ELSE ?
                    END,
                    updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(
                spaceId,
                localDate.toString(),
                DaySummaryState.Absent.name,
                now,
                DaySummaryState.Ready.name,
                DaySummaryState.Processing.name,
                DaySummaryState.Stale.name,
                DaySummaryState.Stale.name,
                DaySummaryState.Absent.name,
            ),
        )
    }

    private fun clearSummaryAfterDeletion(localDate: LocalDate) {
        database.delete("day_summaries", "space_id = ? AND local_date = ?", arrayOf(spaceId, localDate.toString()))
        val eligibleCount = readActive(date = localDate).count(::isSummaryEligible)
        val state = if (eligibleCount < MIN_SUMMARY_EVENTS) DaySummaryState.Insufficient else DaySummaryState.Absent
        val now = System.currentTimeMillis()
        database.execSQL(
            """
                INSERT INTO day_ledgers(space_id, local_date, revision, summary_state, updated_at)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(space_id, local_date) DO UPDATE SET
                    revision = day_ledgers.revision + 1,
                    summary_state = excluded.summary_state,
                    updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(spaceId, localDate.toString(), state.name, now),
        )
    }

    private fun isSummaryEligible(event: MemoryEvent): Boolean =
        event.sensitivity != Sensitivity.Restricted &&
            event.factStatus in setOf(FactStatus.Confirmed, FactStatus.UserAsserted, FactStatus.Planned)

    private fun encodeCursor(row: EventRow): String {
        val payload = listOf(
            row.event.localDate.toString(),
            row.event.time?.toString().orEmpty(),
            row.updatedAt.toString(),
            row.event.id,
        ).joinToString("\u001f")
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): CursorPosition {
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Invalid search cursor") }
        val parts = decoded.split('\u001f')
        require(parts.size == 4 && parts[0].isNotBlank() && parts[2].toLongOrNull() != null && parts[3].isNotBlank()) {
            "Invalid search cursor"
        }
        return CursorPosition(parts[0], parts[1], parts[2].toLong(), parts[3])
    }

    private inline fun <T> inTransaction(block: () -> T): T {
        if (database.inTransaction()) return block()
        database.beginTransaction()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    private data class CurrentEvent(
        val revisionId: String,
        val revision: Int,
        val event: MemoryEvent,
    )

    private data class EventRow(val event: MemoryEvent, val updatedAt: Long)

    private data class CursorPosition(
        val date: String,
        val time: String,
        val updatedAt: Long,
        val eventId: String,
    )

    companion object {
        const val SCHEMA_VERSION = 6
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_DELETED = "DELETED"
        const val DEFAULT_SPACE_ID = "space_personal"
        const val LEGACY_SPACE_ID = "space_legacy"
        const val LOCATOR_STATE_RELEASE_PENDING = "RELEASE_PENDING"
        const val LOCATOR_STATE_RELEASED = "RELEASED"
        const val LOCATOR_STATE_DELETED = "DELETED"
        const val BATCH_EXISTENCE_CHECK_SIZE = 500

        fun open(
            databaseFile: File,
            keyProvider: DatabaseKeyProvider,
            spaceId: String,
            enableFts: Boolean = true,
        ): LocalEventDatabase {
            require(spaceId.isNotBlank()) { "spaceId must not be blank" }
            SqlCipherRuntime.initialize()
            databaseFile.parentFile?.mkdirs()
            val key = keyProvider.getOrCreateKey()
            // Do not clear after open: SQLCipher 4.15.0 retains this array for connection-pool reopen.
            // Every failure path below clears it, and close() clears it after the pool is closed.
            val database = try {
                SQLiteDatabase.openOrCreateDatabase(databaseFile, key, null, null)
            } catch (error: Throwable) {
                key.fill(0)
                throw error
            }
            return try {
                database.setForeignKeyConstraintsEnabled(true)
                migrate(database)
                val backend = configureSearch(database, enableFts)
                check(database.enableWriteAheadLogging() || database.isWriteAheadLoggingEnabled) {
                    "SQLCipher WAL could not be enabled"
                }
                LocalEventDatabase(database, key, spaceId, backend)
            } catch (error: Throwable) {
                try {
                    database.close()
                } finally {
                    key.fill(0)
                }
                throw error
            }
        }

        private fun migrate(database: SQLiteDatabase) {
            when (database.version) {
                0 -> inMigration(database) {
                    createCurrentTableV3(database)
                    createRevisionTableV3(database)
                    createMigrationTable(database)
                    createIndexes(database)
                    createAppendOnlyTriggers(database)
                    recordMigration(database, 3, "create_v3")
                    createSourceLocatorTable(database)
                    recordMigration(database, 4, "create_v4_source_locators")
                    migrateSourceLocatorInstances(database)
                    recordMigration(database, 5, "create_v5_source_instance_identity")
                    addEventProjectionColumns(database)
                    createDaySummaryTables(database)
                    backfillDayLedgers(database)
                    recordMigration(database, SCHEMA_VERSION, "create_v6_event_policy_and_day_summary")
                    database.version = SCHEMA_VERSION
                }
                1 -> {
                    migrateV1ToV2(database)
                    migrateV2ToV3(database)
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                }
                2 -> {
                    migrateV2ToV3(database)
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                }
                3 -> {
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                }
                4 -> {
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                }
                5 -> migrateV5ToV6(database)
                SCHEMA_VERSION -> Unit
                else -> error("Unsupported local event schema version ${database.version}")
            }
        }

        private fun migrateV1ToV2(database: SQLiteDatabase) = inMigration(database) {
            createRevisionTableV2(database)
            createMigrationTable(database)
            if (!hasColumn(database, "events_current", "revision_head_id")) {
                database.execSQL("ALTER TABLE events_current ADD COLUMN revision_head_id TEXT")
            }
            database.execSQL(
                """
                    INSERT INTO event_revisions (
                        revision_id, event_id, revision, base_revision_id, reason,
                        local_date, local_time, title, detail, fact_status, source_label,
                        is_local_only, user_words, state, created_at
                    )
                    SELECT 'rev_migrated_' || lower(hex(randomblob(16))), event_id, revision,
                           NULL, 'migration_v1_to_v2', local_date, local_time, title, detail,
                           fact_status, source_label, is_local_only, user_words, state, updated_at
                    FROM events_current
                """.trimIndent(),
            )
            database.execSQL(
                """
                    UPDATE events_current
                    SET revision_head_id = (
                        SELECT revision_id FROM event_revisions
                        WHERE event_revisions.event_id = events_current.event_id
                        ORDER BY revision DESC LIMIT 1
                    )
                """.trimIndent(),
            )
            recordMigration(database, 2, "migrate_v1_to_v2")
            database.version = 2
        }

        private fun migrateV2ToV3(database: SQLiteDatabase) = inMigration(database) {
            createCurrentTableV3(database, tableName = "events_current_v3")
            createRevisionTableV3(database, tableName = "event_revisions_v3")
            database.execSQL(
                """
                    INSERT INTO events_current_v3 (
                        space_id, event_id, revision_head_id, revision, local_date, local_time,
                        title, detail, fact_status, source_label, is_local_only, user_words, state, updated_at
                    )
                    SELECT '$LEGACY_SPACE_ID', event_id, revision_head_id, revision, local_date, local_time,
                           title, detail, fact_status, source_label, is_local_only, user_words, state, updated_at
                    FROM events_current
                """.trimIndent(),
            )
            database.execSQL(
                """
                    INSERT INTO event_revisions_v3 (
                        revision_id, space_id, event_id, revision, base_revision_id, reason,
                        local_date, local_time, title, detail, fact_status, source_label,
                        is_local_only, user_words, state, created_at
                    )
                    SELECT revision_id, '$LEGACY_SPACE_ID', event_id, revision, base_revision_id, reason,
                           local_date, local_time, title, detail, fact_status, source_label,
                           is_local_only, user_words, state, created_at
                    FROM event_revisions
                """.trimIndent(),
            )
            database.execSQL("DROP TABLE events_current")
            database.execSQL("DROP TABLE event_revisions")
            database.execSQL("ALTER TABLE events_current_v3 RENAME TO events_current")
            database.execSQL("ALTER TABLE event_revisions_v3 RENAME TO event_revisions")
            createIndexes(database)
            createAppendOnlyTriggers(database)
            recordMigration(database, 3, "migrate_v2_to_v3_space_isolation")
            database.version = 3
        }

        private fun migrateV3ToV4(database: SQLiteDatabase) = inMigration(database) {
            createSourceLocatorTable(database)
            recordMigration(database, 4, "migrate_v3_to_v4_source_locators")
            database.version = 4
        }

        private fun migrateV4ToV5(database: SQLiteDatabase) = inMigration(database) {
            migrateSourceLocatorInstances(database)
            recordMigration(database, 5, "migrate_v4_to_v5_source_instance_identity")
            database.version = 5
        }

        private fun migrateV5ToV6(database: SQLiteDatabase) = inMigration(database) {
            addEventProjectionColumns(database)
            createDaySummaryTables(database)
            backfillDayLedgers(database)
            recordMigration(database, SCHEMA_VERSION, "migrate_v5_to_v6_event_policy_and_day_summary")
            database.version = SCHEMA_VERSION
        }

        private fun createCurrentTableV3(
            database: SQLiteDatabase,
            tableName: String = "events_current",
        ) = database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS $tableName (
                    space_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    revision_head_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    local_date TEXT NOT NULL,
                    local_time TEXT,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    fact_status TEXT NOT NULL,
                    source_label TEXT NOT NULL,
                    is_local_only INTEGER NOT NULL,
                    user_words TEXT,
                    event_type TEXT NOT NULL DEFAULT 'Experience',
                    evidence_state TEXT NOT NULL DEFAULT 'UserAsserted',
                    sensitivity TEXT NOT NULL DEFAULT 'Personal',
                    importance INTEGER NOT NULL DEFAULT 50,
                    state TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(space_id, event_id)
                )
            """.trimIndent(),
        )

        private fun createRevisionTableV2(database: SQLiteDatabase) = database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS event_revisions (
                    revision_id TEXT PRIMARY KEY NOT NULL,
                    event_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    base_revision_id TEXT,
                    reason TEXT NOT NULL,
                    local_date TEXT NOT NULL,
                    local_time TEXT,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    fact_status TEXT NOT NULL,
                    source_label TEXT NOT NULL,
                    is_local_only INTEGER NOT NULL,
                    user_words TEXT,
                    event_type TEXT NOT NULL DEFAULT 'Experience',
                    evidence_state TEXT NOT NULL DEFAULT 'UserAsserted',
                    sensitivity TEXT NOT NULL DEFAULT 'Personal',
                    importance INTEGER NOT NULL DEFAULT 50,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    UNIQUE(event_id, revision)
                )
            """.trimIndent(),
        )

        private fun createRevisionTableV3(
            database: SQLiteDatabase,
            tableName: String = "event_revisions",
        ) = database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS $tableName (
                    revision_id TEXT PRIMARY KEY NOT NULL,
                    space_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    base_revision_id TEXT,
                    reason TEXT NOT NULL,
                    local_date TEXT NOT NULL,
                    local_time TEXT,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    fact_status TEXT NOT NULL,
                    source_label TEXT NOT NULL,
                    is_local_only INTEGER NOT NULL,
                    user_words TEXT,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    UNIQUE(space_id, event_id, revision)
                )
            """.trimIndent(),
        )

        private fun createMigrationTable(database: SQLiteDatabase) = database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    applied_at INTEGER NOT NULL
                )
            """.trimIndent(),
        )

        private fun createSourceLocatorTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS source_locators (
                        space_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        source_kind TEXT NOT NULL,
                        locator_uri TEXT NOT NULL,
                        mime_type TEXT,
                        permission_state TEXT NOT NULL,
                        state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, event_id),
                        FOREIGN KEY(space_id, event_id) REFERENCES events_current(space_id, event_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_source_locators_space_state ON source_locators(space_id, state)",
            )
        }

        private fun migrateSourceLocatorInstances(database: SQLiteDatabase) {
            if (!hasColumn(database, "source_locators", "source_instance_key")) {
                database.execSQL("ALTER TABLE source_locators ADD COLUMN source_instance_key TEXT")
            }
            database.execSQL(
                """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_source_locators_active_instance
                    ON source_locators(space_id, source_kind, locator_uri, source_instance_key)
                    WHERE state = '$STATE_ACTIVE' AND source_instance_key IS NOT NULL
                """.trimIndent(),
            )
        }

        private fun addEventProjectionColumns(database: SQLiteDatabase) {
            listOf("events_current", "event_revisions").forEach { table ->
                if (!hasColumn(database, table, "event_type")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN event_type TEXT NOT NULL DEFAULT 'Experience'")
                }
                if (!hasColumn(database, table, "evidence_state")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN evidence_state TEXT NOT NULL DEFAULT 'UserAsserted'")
                }
                if (!hasColumn(database, table, "sensitivity")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN sensitivity TEXT NOT NULL DEFAULT 'Personal'")
                }
                if (!hasColumn(database, table, "importance")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN importance INTEGER NOT NULL DEFAULT 50")
                }
            }
        }

        private fun createDaySummaryTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS day_ledgers (
                        space_id TEXT NOT NULL,
                        local_date TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        summary_state TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, local_date)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS day_summaries (
                        space_id TEXT NOT NULL,
                        local_date TEXT NOT NULL,
                        summary_id TEXT NOT NULL,
                        based_on_revision INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        state TEXT NOT NULL,
                        model_or_rule_version TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, local_date),
                        FOREIGN KEY(space_id, local_date) REFERENCES day_ledgers(space_id, local_date)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_day_ledgers_space_date ON day_ledgers(space_id, local_date)",
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS agent_idempotency (
                        caller_id TEXT NOT NULL,
                        grant_id TEXT NOT NULL,
                        purpose TEXT NOT NULL,
                        space_id TEXT NOT NULL,
                        memory_type TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        payload_digest TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(caller_id, grant_id, purpose, space_id, memory_type, operation, slot)
                    )
                """.trimIndent(),
            )
        }

        private fun backfillDayLedgers(database: SQLiteDatabase) {
            database.execSQL(
                """
                    INSERT OR IGNORE INTO day_ledgers(space_id, local_date, revision, summary_state, updated_at)
                    SELECT space_id, local_date, 1, '${DaySummaryState.Absent.name}', MAX(updated_at)
                    FROM events_current
                    WHERE state = '$STATE_ACTIVE'
                    GROUP BY space_id, local_date
                """.trimIndent(),
            )
        }

        private fun configureSearch(database: SQLiteDatabase, enableFts: Boolean): SearchBackend {
            if (!enableFts) return SearchBackend.LikeFallback
            return runCatching {
                database.execSQL(
                    """
                        CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
                            space_id UNINDEXED,
                            event_id UNINDEXED,
                            title,
                            detail,
                            source_label,
                            user_words,
                            tokenize = 'unicode61'
                        )
                    """.trimIndent(),
                )
                if (searchIndexNeedsRebuild(database)) {
                    database.beginTransaction()
                    try {
                        database.delete("events_fts", null, null)
                        database.execSQL(
                            """
                                INSERT INTO events_fts(space_id, event_id, title, detail, source_label, user_words)
                                SELECT space_id, event_id, title, detail, source_label, COALESCE(user_words, '')
                                FROM events_current WHERE state = '$STATE_ACTIVE'
                            """.trimIndent(),
                        )
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                }
                SearchBackend.Fts5
            }.getOrElse { SearchBackend.LikeFallback }
        }

        private fun searchIndexNeedsRebuild(database: SQLiteDatabase): Boolean {
            val activeCount = database.rawQuery(
                "SELECT COUNT(*) FROM events_current WHERE state = '$STATE_ACTIVE'",
                emptyArray(),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            val indexCount = database.rawQuery("SELECT COUNT(*) FROM events_fts", emptyArray()).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            return activeCount != indexCount
        }

        private fun createIndexes(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_events_current_space_date_state ON events_current(space_id, local_date, state)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_event_revisions_space_event_revision ON event_revisions(space_id, event_id, revision)",
            )
        }

        private fun createAppendOnlyTriggers(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS prevent_event_revisions_update
                    BEFORE UPDATE ON event_revisions
                    BEGIN
                        SELECT RAISE(ABORT, 'event_revisions is append-only');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS prevent_event_revisions_delete
                    BEFORE DELETE ON event_revisions
                    BEGIN
                        SELECT RAISE(ABORT, 'event_revisions is append-only');
                    END
                """.trimIndent(),
            )
        }

        private fun recordMigration(database: SQLiteDatabase, version: Int, name: String) {
            database.execSQL(
                "INSERT OR REPLACE INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)",
                arrayOf<Any>(version, name, System.currentTimeMillis()),
            )
        }

        private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean =
            database.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return@use true
                }
                false
            }

        private inline fun inMigration(database: SQLiteDatabase, block: () -> Unit) {
            database.beginTransaction()
            try {
                block()
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

        private fun escapeLike(value: String): String = value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        private fun searchTerms(value: String): List<String> = value
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(MAX_SEARCH_TERMS)

        private fun toFtsExpression(terms: List<String>): String = terms
            .joinToString(" AND ") { token -> "\"${token.replace("\"", "\"\"")}\"" }

        private const val MAX_SEARCH_TERMS = 16
        private const val MIN_SUMMARY_EVENTS = 2

    }
}
