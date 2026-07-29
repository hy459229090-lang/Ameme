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
import com.ameme.android.data.transport.AgentLocalNodeUndoIdempotencyResult
import com.ameme.android.data.AgentCaptureUndoConflictException
import com.ameme.android.data.AgentCaptureUndoResult
import com.ameme.android.data.AgentCaptureUndoTarget
import com.ameme.android.data.AgentRevisionAppendResult
import com.ameme.android.data.AgentVisibleEventsReadResult
import com.ameme.android.data.CoverageAcceptanceMode
import com.ameme.android.data.CoverageCandidateAcceptance
import com.ameme.android.data.CoverageEventLink
import com.ameme.android.data.CoverageEventLinkLifecycle
import com.ameme.android.data.PersistedCoverageDay
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryInvalidationReason
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryRecord
import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryBackupManifest
import com.ameme.android.data.RecoveryCandidate
import com.ameme.android.data.ReuseContext
import com.ameme.android.data.ReuseExclusion
import com.ameme.android.data.ReuseIntent
import com.ameme.android.data.ReuseObjectType
import com.ameme.android.data.ReuseOutcomeSubmission
import com.ameme.android.data.ReuseRangeState
import com.ameme.android.data.ReuseReference
import com.ameme.android.data.ReuseRequest
import com.ameme.android.data.ResolvedReuseContext
import com.ameme.android.data.ResolvedReuseItem
import com.ameme.android.data.ReuseSelectionReason
import com.ameme.android.data.ReuseTelemetryAggregate
import com.ameme.android.data.LocalRawOwnership
import com.ameme.android.data.LocalSourceKind
import com.ameme.android.data.LocalSourceObject
import com.ameme.android.data.LocalSourceState
import com.ameme.android.data.EventFieldEvidenceState
import com.ameme.android.data.LocalEventUserConfirmation
import com.ameme.android.data.UserConfirmationKind
import com.ameme.android.data.UserConfirmationState
import com.ameme.android.data.SourceDeletionOperation
import com.ameme.android.data.SourceDeletionResult
import com.ameme.android.data.SourceDeletionStatus
import com.ameme.android.data.LocalSpaceDeletionResult
import com.ameme.android.data.LocalSpaceDeletionStatus
import com.ameme.android.coverage.CandidateEventType
import com.ameme.android.coverage.CoverageFactStatus
import com.ameme.android.coverage.EvidenceField
import java.nio.charset.StandardCharsets
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

class LocalEventDatabase private constructor(
    private val database: SQLiteDatabase,
    private val databaseFile: File,
    // SQLCipher 4.15.0 keeps this exact array in SQLiteDatabaseConfiguration for pooled/WAL connections.
    // This is not a second key copy; it is cleared immediately after SQLCipher closes.
    private val activeKey: ByteArray,
    private val spaceId: String,
    private val searchBackend: SearchBackend,
) : Closeable {
    private val coveragePersistence = LocalCoveragePersistence(database, spaceId)
    private val longTermMemoryPersistence = LocalLongTermMemoryPersistence(database, spaceId)
    private val reusePersistence = LocalReusePersistence(database, spaceId)

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
        source?.let {
            insertSourceLocator(event.id, it)
            registerCapturedSource(event.id, it)
        }
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
            registerCapturedSource(event.id, source)
            refreshSearchIndex(event, STATE_ACTIVE)
            inserted += event
        }
        inserted.map(MemoryEvent::localDate).toSet().forEach(::touchDayLedger)
        inserted
    }

    fun readPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage {
        return readPage(query, date, date, cursor, pageSize)
    }

    fun readPage(
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage {
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        require(startDate == null || endDate == null || !endDate.isBefore(startDate)) {
            "Search end date must not be before start date"
        }
        if (isLocalSpaceDeleted()) {
            return MemoryPage(emptyList(), null, searchBackend)
        }
        val decodedCursor = cursor?.let(::decodeCursor)
        val where = mutableListOf("e.space_id = ?", "e.state = ?")
        val args = mutableListOf(spaceId, STATE_ACTIVE)
        if (startDate != null) {
            where += "e.local_date >= ?"
            args += startDate.toString()
        }
        if (endDate != null) {
            where += "e.local_date <= ?"
            args += endDate.toString()
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
        if (isLocalSpaceDeleted()) return emptyList()
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

    fun readAgentVisibleEvents(
        query: String,
        startAt: Instant?,
        endAt: Instant?,
        timeZone: ZoneId,
        allowedSensitivities: Set<Sensitivity>,
        allowHighRisk: Boolean,
        limit: Int,
    ): AgentVisibleEventsReadResult {
        require(query.codePointCount(0, query.length) <= 1_000) { "Agent query is too long" }
        require(allowedSensitivities.isNotEmpty()) { "Agent sensitivity scope is empty" }
        require(limit in 1..100) { "Agent limit is invalid" }
        require(startAt == null || endAt == null || !endAt.isBefore(startAt)) {
            "Agent time range is invalid"
        }
        if (isLocalSpaceDeleted()) {
            return AgentVisibleEventsReadResult(emptyList(), riskFiltered = false)
        }

        val where = mutableListOf("e.space_id = ?", "e.state = ?")
        val args = mutableListOf(spaceId, STATE_ACTIVE)
        startAt?.let { instant ->
            val local = LocalDateTime.ofInstant(instant, timeZone)
            where +=
                "(e.local_date > ? OR (e.local_date = ? AND substr(COALESCE(e.local_time, '00:00:00') || ':00:00', 1, 8) >= ?))"
            args += listOf(
                local.toLocalDate().toString(),
                local.toLocalDate().toString(),
                local.toLocalTime().withNano(0).format(AGENT_READ_TIME_FORMAT),
            )
        }
        endAt?.let { instant ->
            val local = LocalDateTime.ofInstant(instant, timeZone)
            where +=
                "(e.local_date < ? OR (e.local_date = ? AND substr(COALESCE(e.local_time, '00:00:00') || ':00:00', 1, 8) <= ?))"
            args += listOf(
                local.toLocalDate().toString(),
                local.toLocalDate().toString(),
                local.toLocalTime().withNano(0).format(AGENT_READ_TIME_FORMAT),
            )
        }
        val terms = searchTerms(query)
        val useFts = terms.isNotEmpty() && searchBackend == SearchBackend.Fts5
        if (terms.isNotEmpty()) {
            if (useFts) {
                where += "events_fts MATCH ?"
                args += toAgentFtsExpression(terms)
            } else {
                terms.forEach { term ->
                    where +=
                        "(e.title LIKE ? ESCAPE '\\' OR e.detail LIKE ? ESCAPE '\\')"
                    val pattern = "%${escapeLike(term)}%"
                    repeat(2) { args += pattern }
                }
            }
        }
        val from = if (useFts) {
            "events_current e JOIN events_fts ON events_fts.space_id = e.space_id AND events_fts.event_id = e.event_id"
        } else {
            "events_current e"
        }
        val effectiveSensitivities = if (allowHighRisk) {
            allowedSensitivities
        } else {
            allowedSensitivities - Sensitivity.Restricted
        }
        val events = if (effectiveSensitivities.isEmpty()) {
            emptyList()
        } else {
            val placeholders = effectiveSensitivities.joinToString(",") { "?" }
            val visibleArgs =
                args + effectiveSensitivities.map { it.name }.sorted() + limit.toString()
            database.rawQuery(
                """
                    SELECT e.event_id, e.local_date, e.local_time, e.title, e.detail, e.fact_status,
                           e.source_label, e.is_local_only, e.user_words, e.revision, e.event_type,
                           e.evidence_state, e.sensitivity, e.importance
                    FROM $from
                    WHERE ${where.joinToString(" AND ")}
                      AND e.sensitivity IN ($placeholders)
                    ORDER BY e.local_date DESC, COALESCE(e.local_time, '') DESC,
                             e.updated_at DESC, e.event_id DESC
                    LIMIT ?
                """.trimIndent(),
                visibleArgs.toTypedArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toMemoryEvent())
                }
            }
        }
        val riskFiltered =
            !allowHighRisk &&
                Sensitivity.Restricted in allowedSensitivities &&
                database.rawQuery(
                    """
                        SELECT 1 FROM $from
                        WHERE ${where.joinToString(" AND ")}
                          AND e.sensitivity = ?
                        LIMIT 1
                    """.trimIndent(),
                    (args + Sensitivity.Restricted.name).toTypedArray(),
                ).use { it.moveToFirst() }
        return AgentVisibleEventsReadResult(events, riskFiltered)
    }

    fun deleteEvent(eventId: String): Boolean = inTransaction {
        tombstoneEvent(eventId, reason = "user_delete", deletedAt = Instant.now())
    }

    private fun tombstoneEvent(
        eventId: String,
        reason: String,
        deletedAt: Instant,
    ): Boolean {
        val current = findCurrent(eventId, includeDeleted = false) ?: return false
        val deletedAtEpochMillis = deletedAt.toEpochMilli()
        appendRevision(current.event, reason = reason, state = STATE_DELETED)
        writeDeletionWatermark(
            objectType = DELETION_OBJECT_EVENT,
            objectId = eventId,
            terminalRevision = current.revision + 1,
            deletedAtEpochMillis = deletedAtEpochMillis,
            reason = reason,
        )
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
                    deletedAtEpochMillis,
                    spaceId,
                    eventId,
                    STATE_ACTIVE,
                ),
            )
        coveragePersistence.detachEvent(eventId, deletedAt)
        longTermMemoryPersistence.invalidateForEvent(
            eventId = eventId,
            currentEventRevision = null,
            reason = LongTermMemoryInvalidationReason.EventDeleted,
            invalidatedAt = deletedAt,
        )
        database.execSQL(
            """
                UPDATE event_source_links
                SET state = ?, deleted_at = ?
                WHERE space_id = ? AND event_id = ? AND state = ?
            """.trimIndent(),
            arrayOf<Any>(
                SOURCE_LINK_STATE_DELETED,
                deletedAtEpochMillis,
                spaceId,
                eventId,
                SOURCE_LINK_STATE_ACTIVE,
            ),
        )
        terminalizeEventFieldEvidence(eventId, deletedAtEpochMillis)
        terminalizeUserConfirmations(eventId, deletedAtEpochMillis)
        clearSummaryAfterDeletion(current.event.localDate)
        return true
    }

    fun deletionWatermark(
        objectType: String,
        objectId: String,
    ): DeletionWatermark? = database.rawQuery(
        """
            SELECT terminal_revision, deleted_at, reason, tombstone_digest
            FROM deletion_watermarks
            WHERE space_id = ? AND object_type = ? AND object_id = ?
        """.trimIndent(),
        arrayOf(spaceId, objectType, objectId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            DeletionWatermark(
                spaceId = spaceId,
                objectType = objectType,
                objectId = objectId,
                terminalRevision = cursor.getInt(0),
                deletedAtEpochMillis = cursor.getLong(1),
                reason = cursor.getString(2),
                tombstoneDigest = cursor.getString(3),
            )
        }
    }

    fun deletionWatermarkDigest(): String =
        LocalRecoveryBackup.deletionWatermarkDigest(database, spaceId)

    fun deletionWatermarks(): List<DeletionWatermark> = database.rawQuery(
        """
            SELECT object_type, object_id, terminal_revision, deleted_at, reason, tombstone_digest
            FROM deletion_watermarks
            WHERE space_id = ?
            ORDER BY object_type, object_id
        """.trimIndent(),
        arrayOf(spaceId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DeletionWatermark(
                        spaceId = spaceId,
                        objectType = cursor.getString(0),
                        objectId = cursor.getString(1),
                        terminalRevision = cursor.getInt(2),
                        deletedAtEpochMillis = cursor.getLong(3),
                        reason = cursor.getString(4),
                        tombstoneDigest = cursor.getString(5),
                    ),
                )
            }
        }
    }

    fun isLocalSpaceDeleted(): Boolean =
        deletionWatermark(DELETION_OBJECT_SPACE, spaceId) != null

    fun deleteLocalSpace(requestedAt: Instant): LocalSpaceDeletionResult =
        inTransaction(allowDeletedSpace = true) {
            deletionWatermark(DELETION_OBJECT_SPACE, spaceId)?.let { existing ->
                return@inTransaction LocalSpaceDeletionResult(
                    spaceId = spaceId,
                    status = LocalSpaceDeletionStatus.AlreadyDeleted,
                    affectedEventCount = 0,
                    affectedSourceCount = 0,
                    pendingExternalCleanupCount = pendingSourceLocatorReleaseCount(),
                    deletedAt = Instant.ofEpochMilli(existing.deletedAtEpochMillis),
                    externalOriginalsRetained = true,
                )
            }
            val activeEventIds = database.rawQuery(
                "SELECT event_id FROM events_current WHERE space_id = ? AND state = ? ORDER BY event_id",
                arrayOf(spaceId, STATE_ACTIVE),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val activeSources = database.rawQuery(
                """
                    SELECT source_object_id, raw_ownership
                    FROM source_objects
                    WHERE space_id = ? AND state = ?
                    ORDER BY source_object_id
                """.trimIndent(),
                arrayOf(spaceId, LocalSourceState.Active.name),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to LocalRawOwnership.valueOf(cursor.getString(1)))
                    }
                }
            }
            activeEventIds.forEach { eventId ->
                check(tombstoneEvent(eventId, reason = LOCAL_SPACE_DELETE_REASON, deletedAt = requestedAt))
            }
            coveragePersistence.terminalizeAll(requestedAt)
            val deletedAtEpochMillis = requestedAt.toEpochMilli()
            activeSources.forEach { (sourceObjectId, _) ->
                database.execSQL(
                    """
                        UPDATE source_objects
                        SET state = ?, deleted_at = ?
                        WHERE space_id = ? AND source_object_id = ? AND state = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        LocalSourceState.Deleted.name,
                        deletedAtEpochMillis,
                        spaceId,
                        sourceObjectId,
                        LocalSourceState.Active.name,
                    ),
                )
                writeDeletionWatermark(
                    objectType = DELETION_OBJECT_SOURCE,
                    objectId = sourceObjectId,
                    terminalRevision = 1,
                    deletedAtEpochMillis = deletedAtEpochMillis,
                    reason = LOCAL_SPACE_DELETE_REASON,
                )
            }
            writeDeletionWatermark(
                objectType = DELETION_OBJECT_SPACE,
                objectId = spaceId,
                terminalRevision = 1,
                deletedAtEpochMillis = deletedAtEpochMillis,
                reason = LOCAL_SPACE_DELETE_REASON,
            )
            val pendingCleanupCount = pendingSourceLocatorReleaseCount()
            LocalSpaceDeletionResult(
                spaceId = spaceId,
                status = if (pendingCleanupCount == 0) {
                    LocalSpaceDeletionStatus.CompletedLocalOnly
                } else {
                    LocalSpaceDeletionStatus.PendingExternalCleanup
                },
                affectedEventCount = activeEventIds.size,
                affectedSourceCount = activeSources.size,
                pendingExternalCleanupCount = pendingCleanupCount,
                deletedAt = requestedAt,
                externalOriginalsRetained = activeSources.any {
                    it.second == LocalRawOwnership.ExternalNotOwned
                },
            )
        }

    fun sourceObjectsForEvent(eventId: String): List<LocalSourceObject> = database.rawQuery(
        """
            SELECT s.source_object_id, s.source_kind, s.raw_ownership, s.state,
                   s.created_at, s.deleted_at
            FROM source_objects s
            JOIN event_source_links l
              ON l.space_id = s.space_id AND l.source_object_id = s.source_object_id
            WHERE s.space_id = ? AND l.event_id = ?
            ORDER BY s.source_object_id
        """.trimIndent(),
        arrayOf(spaceId, eventId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    LocalSourceObject(
                        sourceObjectId = cursor.getString(0),
                        spaceId = spaceId,
                        kind = LocalSourceKind.valueOf(cursor.getString(1)),
                        rawOwnership = LocalRawOwnership.valueOf(cursor.getString(2)),
                        state = LocalSourceState.valueOf(cursor.getString(3)),
                        createdAt = Instant.ofEpochMilli(cursor.getLong(4)),
                        deletedAt = if (cursor.isNull(5)) null else Instant.ofEpochMilli(cursor.getLong(5)),
                    ),
                )
            }
        }
    }

    fun userConfirmationsForEvent(eventId: String): List<LocalEventUserConfirmation> =
        database.rawQuery(
            """
                SELECT event_revision, confirmation_id, confirmation_kind, confirmed_fields_json,
                       complete_field_set, confirmed_at, state, deleted_at
                FROM event_user_confirmations
                WHERE space_id = ? AND event_id = ?
                ORDER BY event_revision, confirmed_at, confirmation_id
            """.trimIndent(),
            arrayOf(spaceId, eventId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalEventUserConfirmation(
                            confirmationId = cursor.getString(1),
                            eventId = eventId,
                            eventRevision = cursor.getInt(0),
                            kind = UserConfirmationKind.valueOf(cursor.getString(2)),
                            confirmedFields = decodeEvidenceFields(cursor.getString(3)),
                            completeFieldSet = cursor.getInt(4) == 1,
                            confirmedAt = Instant.ofEpochMilli(cursor.getLong(5)),
                            state = UserConfirmationState.valueOf(cursor.getString(6)),
                            deletedAt = if (cursor.isNull(7)) {
                                null
                            } else {
                                Instant.ofEpochMilli(cursor.getLong(7))
                            },
                        ),
                    )
                }
            }
        }

    fun deleteRawOnly(sourceObjectId: String, requestedAt: Instant): SourceDeletionResult =
        inTransaction {
            val source = readSourceObject(sourceObjectId)
            val status = when {
                source == null -> SourceDeletionStatus.NotFound
                source.rawOwnership == LocalRawOwnership.ExternalNotOwned ->
                    SourceDeletionStatus.ExternalNotOwned
                else -> SourceDeletionStatus.NoRaw
            }
            writeDeletionJob(
                sourceObjectId = sourceObjectId,
                operation = SourceDeletionOperation.RawOnly,
                status = status,
                affectedEventCount = 0,
                requestedAt = requestedAt,
            )
        }

    fun deleteSourceCascade(sourceObjectId: String, requestedAt: Instant): SourceDeletionResult =
        inTransaction {
            val source = readSourceObject(sourceObjectId)
                ?: return@inTransaction writeDeletionJob(
                    sourceObjectId = sourceObjectId,
                    operation = SourceDeletionOperation.SourceCascade,
                    status = SourceDeletionStatus.NotFound,
                    affectedEventCount = 0,
                    requestedAt = requestedAt,
                )
            if (source.state == LocalSourceState.Deleted) {
                return@inTransaction writeDeletionJob(
                    sourceObjectId = sourceObjectId,
                    operation = SourceDeletionOperation.SourceCascade,
                    status = if (source.rawOwnership == LocalRawOwnership.ExternalNotOwned) {
                        SourceDeletionStatus.CompletedLocalOnly
                    } else {
                        SourceDeletionStatus.Completed
                    },
                    affectedEventCount = 0,
                    requestedAt = requestedAt,
                )
            }
            val eventIds = activeEventIdsForSource(sourceObjectId)
            val actions = eventIds.associateWith { eventId ->
                sourceCascadeAction(eventId, sourceObjectId)
            }
            if (actions.values.any { it == null }) {
                return@inTransaction writeDeletionJob(
                    sourceObjectId = sourceObjectId,
                    operation = SourceDeletionOperation.SourceCascade,
                    status = SourceDeletionStatus.LineageUnavailable,
                    affectedEventCount = 0,
                    requestedAt = requestedAt,
                )
            }
            var recomputedEventCount = 0
            var deletedEventCount = 0
            actions.forEach { (eventId, action) ->
                when (checkNotNull(action)) {
                    SourceCascadeAction.Delete -> {
                        check(
                            tombstoneEvent(
                                eventId,
                                reason = "source_cascade_delete",
                                deletedAt = requestedAt,
                            ),
                        )
                        deletedEventCount += 1
                    }
                    SourceCascadeAction.Recompute -> {
                        recomputeEventAfterSourceDeletion(eventId, sourceObjectId, requestedAt)
                        recomputedEventCount += 1
                    }
                }
            }
            coveragePersistence.terminalizeSource(sourceObjectId, requestedAt)
            database.execSQL(
                """
                    UPDATE source_objects
                    SET state = ?, deleted_at = ?
                    WHERE space_id = ? AND source_object_id = ? AND state = ?
                """.trimIndent(),
                arrayOf<Any>(
                    LocalSourceState.Deleted.name,
                    requestedAt.toEpochMilli(),
                    spaceId,
                    sourceObjectId,
                    LocalSourceState.Active.name,
                ),
            )
            writeDeletionWatermark(
                objectType = DELETION_OBJECT_SOURCE,
                objectId = sourceObjectId,
                terminalRevision = 1,
                deletedAtEpochMillis = requestedAt.toEpochMilli(),
                reason = "source_cascade_delete",
            )
            writeDeletionJob(
                sourceObjectId = sourceObjectId,
                operation = SourceDeletionOperation.SourceCascade,
                status = if (source.rawOwnership == LocalRawOwnership.ExternalNotOwned) {
                    SourceDeletionStatus.CompletedLocalOnly
                } else {
                    SourceDeletionStatus.Completed
                },
                affectedEventCount = eventIds.size,
                recomputedEventCount = recomputedEventCount,
                deletedEventCount = deletedEventCount,
                requestedAt = requestedAt,
            )
        }

    fun createLocalRecoveryBackup(
        destinationDirectory: File,
        createdAt: Instant = Instant.now(),
    ): RecoveryBackupManifest {
        check(!isLocalSpaceDeleted()) { "deleted local space cannot create a recovery backup" }
        val manifest = LocalRecoveryBackup.create(
            database = database,
            databaseFile = databaseFile,
            activeKey = activeKey,
            destinationDirectory = destinationDirectory,
            createdAt = createdAt,
        )
        try {
            inTransaction {
                database.execSQL(
                    """
                        INSERT INTO backup_checkpoints (
                            backup_id, space_id, source_schema_version, created_at,
                            snapshot_digest, snapshot_size, manifest_mac, key_material_state,
                            health_state, verified_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        manifest.backupId,
                        spaceId,
                        manifest.localStoreSchemaVersion,
                        manifest.createdAt,
                        manifest.snapshotSha256,
                        manifest.snapshotSize,
                        manifest.manifestMac,
                        manifest.keyMaterialState,
                        BACKUP_HEALTH_READY,
                        Instant.now().toString(),
                    ),
                )
            }
        } catch (error: Throwable) {
            destinationDirectory.deleteRecursively()
            throw error
        }
        return manifest
    }

    fun verifyLocalRecoveryBackup(backupDirectory: File): RecoveryBackupManifest =
        LocalRecoveryBackup.verify(
            backupDirectory = backupDirectory,
            key = activeKey,
            authoritativeWatermarks = deletionWatermarks(),
        )

    fun restoreLocalRecoveryCandidate(
        backupDirectory: File,
        destinationDirectory: File,
    ): RecoveryCandidate = LocalRecoveryBackup.restoreCandidateWithKey(
        backupDirectory = backupDirectory,
        destinationDirectory = destinationDirectory,
        key = activeKey,
        authoritativeWatermarks = deletionWatermarks(),
    )

    fun persistCoverageDay(day: PersistedCoverageDay): PersistedCoverageDay = inTransaction {
        val sourceIds = buildSet {
            day.compilation.observations.forEach { addAll(it.sourceObjectIds) }
            day.compilation.candidateEvents.forEach { addAll(it.sourceObjectIds) }
        }
        sourceIds.forEach { sourceId ->
            require(
                deletionWatermark(DELETION_OBJECT_SOURCE, sourceId) == null &&
                    readSourceObject(sourceId)?.state != LocalSourceState.Deleted,
            ) { "coverage source has a terminal deletion watermark" }
            registerSourceObject(
                sourceObjectId = sourceId,
                kind = LocalSourceKind.CoverageEvidence,
                rawOwnership = LocalRawOwnership.NoRaw,
            )
        }
        coveragePersistence.persist(day)
    }

    fun readCoverageDay(localDate: LocalDate): PersistedCoverageDay? =
        if (isLocalSpaceDeleted()) null else coveragePersistence.load(localDate)

    fun readCoverageEventLink(dayId: String, candidateId: String): CoverageEventLink? =
        if (isLocalSpaceDeleted()) null else coveragePersistence.existingLink(dayId, candidateId)

    fun proposeLongTermMemory(proposal: LongTermMemoryProposal): LongTermMemoryRecord =
        inTransaction { longTermMemoryPersistence.propose(proposal) }

    fun confirmLongTermMemory(confirmation: LongTermMemoryConfirmation): LongTermMemoryRecord =
        inTransaction { longTermMemoryPersistence.confirm(confirmation) }

    fun readLongTermMemory(memoryId: String): LongTermMemoryRecord? =
        if (isLocalSpaceDeleted()) null else longTermMemoryPersistence.load(memoryId)

    fun readVisibleLongTermMemories(at: Instant): List<LongTermMemoryRecord> =
        if (isLocalSpaceDeleted()) emptyList() else longTermMemoryPersistence.visible(at)

    fun buildReuseContext(request: ReuseRequest): ReuseContext = inTransaction {
        require(request.spaceId == spaceId) { "reuse request space does not match the local store" }
        val effectiveStart = request.startDate ?: request.meetingAnchorDate
            ?.takeIf { request.intent == ReuseIntent.PreMeetingContext && request.endDate == null }
        val effectiveEnd = request.endDate ?: request.meetingAnchorDate
            ?.takeIf { request.intent == ReuseIntent.PreMeetingContext && request.startDate == null }
        val terms = searchTerms(request.query)
        val candidateEventPage = readPage(
            request.query,
            effectiveStart,
            effectiveEnd,
            cursor = null,
            pageSize = (request.limit * 3).coerceAtMost(100),
        )
        val candidateEvents = candidateEventPage.events
        val candidateMemories = longTermMemoryPersistence.visible(request.requestedAt)
            .filter { memory ->
                when (request.intent) {
                    ReuseIntent.PreMeetingContext,
                    ReuseIntent.DecisionCommitmentRecall,
                    -> memory.type in setOf(
                        com.ameme.android.data.LongTermMemoryType.Decision,
                        com.ameme.android.data.LongTermMemoryType.Commitment,
                    )
                    ReuseIntent.HistoricalSearch,
                    ReuseIntent.ProjectResume,
                    -> true
                }
            }
            .filter { memory ->
                terms.isEmpty() || terms.all { term ->
                    memory.valueSummary.contains(term, ignoreCase = true)
                }
            }

        val exclusions = mutableSetOf<ReuseExclusion>()
        if (candidateEvents.any { it.sensitivity == Sensitivity.Restricted } ||
            candidateMemories.any { it.sensitivity == Sensitivity.Restricted }
        ) {
            exclusions += ReuseExclusion.Restricted
        }
        val eventReferences = candidateEvents
            .asSequence()
            .filter { it.sensitivity != Sensitivity.Restricted }
            .filter {
                request.intent != ReuseIntent.DecisionCommitmentRecall ||
                    it.eventType == EventType.Decision
            }
            .map { event ->
                ReuseReference(
                    objectType = ReuseObjectType.Event,
                    objectId = event.id,
                    revision = event.revision,
                    localDate = event.localDate,
                    sensitivity = event.sensitivity,
                    selectionReason = when {
                        request.intent == ReuseIntent.PreMeetingContext &&
                            request.meetingAnchorDate != null -> ReuseSelectionReason.MeetingAnchor
                        terms.isNotEmpty() -> ReuseSelectionReason.KeywordMatch
                        else -> ReuseSelectionReason.DateMatch
                    },
                )
            }
            .toList()
        val memoryReferences = candidateMemories
            .asSequence()
            .filter { it.sensitivity != Sensitivity.Restricted }
            .mapNotNull { memory ->
                val source = findCurrent(memory.sourceEventId, includeDeleted = false)
                    ?: return@mapNotNull null
                if (source.revision != memory.sourceEventRevision ||
                    source.event.sensitivity == Sensitivity.Restricted
                ) {
                    exclusions += ReuseExclusion.Invalidated
                    return@mapNotNull null
                }
                ReuseReference(
                    objectType = ReuseObjectType.LongTermMemory,
                    objectId = memory.memoryId,
                    revision = memory.revision,
                    localDate = source.event.localDate,
                    sensitivity = memory.sensitivity,
                    selectionReason = when (memory.type) {
                        com.ameme.android.data.LongTermMemoryType.Decision ->
                            ReuseSelectionReason.ActiveDecision
                        com.ameme.android.data.LongTermMemoryType.Commitment ->
                            ReuseSelectionReason.ActiveCommitment
                        else -> if (terms.isEmpty()) {
                            ReuseSelectionReason.DateMatch
                        } else {
                            ReuseSelectionReason.KeywordMatch
                        }
                    },
                    sourceEventId = memory.sourceEventId,
                    sourceEventRevision = memory.sourceEventRevision,
                )
            }
            .toList()
        val ordered = when (request.intent) {
            ReuseIntent.HistoricalSearch -> eventReferences + memoryReferences
            ReuseIntent.ProjectResume,
            ReuseIntent.PreMeetingContext,
            ReuseIntent.DecisionCommitmentRecall,
            -> memoryReferences + eventReferences
        }
        val uniqueReferences = ordered
            .distinctBy { "${it.objectType.wireValue}\u001f${it.objectId}" }
        val references = uniqueReferences.take(request.limit)
        val isPartial = candidateEventPage.nextCursor != null ||
            uniqueReferences.size > request.limit
        val context = ReuseContext(
            attemptId = "reuse_${UUID.randomUUID()}",
            intent = request.intent,
            rangeState = when {
                references.isEmpty() -> ReuseRangeState.Empty
                isPartial -> ReuseRangeState.PartialForLocalScope
                else -> ReuseRangeState.CompleteForLocalScope
            },
            references = references,
            exclusions = exclusions,
            createdAt = request.requestedAt,
            expiresAt = request.requestedAt.plusSeconds(REUSE_CONTEXT_TTL_SECONDS),
        )
        reusePersistence.recordAttempt(context)
        context
    }

    fun revalidateReuseContext(context: ReuseContext, at: Instant): ReuseContext {
        if (!context.expiresAt.isAfter(at)) {
            return context.copy(
                rangeState = ReuseRangeState.Empty,
                references = emptyList(),
                exclusions = context.exclusions + ReuseExclusion.Expired,
            )
        }
        val exclusions = context.exclusions.toMutableSet()
        val valid = context.references.filter { reference ->
            when (reference.objectType) {
                ReuseObjectType.Event -> {
                    val current = findCurrent(reference.objectId, includeDeleted = false)
                    when {
                        current == null -> {
                            exclusions += ReuseExclusion.Deleted
                            false
                        }
                        current.revision != reference.revision -> {
                            exclusions += ReuseExclusion.Invalidated
                            false
                        }
                        current.event.sensitivity == Sensitivity.Restricted -> {
                            exclusions += ReuseExclusion.Restricted
                            false
                        }
                        else -> true
                    }
                }
                ReuseObjectType.LongTermMemory -> {
                    val memory = longTermMemoryPersistence.load(reference.objectId)
                    val source = memory?.let {
                        findCurrent(it.sourceEventId, includeDeleted = false)
                    }
                    when {
                        memory == null || source == null -> {
                            exclusions += ReuseExclusion.Deleted
                            false
                        }
                        memory.revision != reference.revision ||
                            !memory.isVisible(at) ||
                            source.revision != memory.sourceEventRevision -> {
                            exclusions += ReuseExclusion.Invalidated
                            false
                        }
                        memory.sensitivity == Sensitivity.Restricted ||
                            source.event.sensitivity == Sensitivity.Restricted -> {
                            exclusions += ReuseExclusion.Restricted
                            false
                        }
                        else -> true
                    }
                }
            }
        }
        return context.copy(
            rangeState = when {
                valid.isEmpty() -> ReuseRangeState.Empty
                context.rangeState == ReuseRangeState.PartialForLocalScope ->
                    ReuseRangeState.PartialForLocalScope
                else -> ReuseRangeState.CompleteForLocalScope
            },
            references = valid,
            exclusions = exclusions,
        )
    }

    fun resolveReuseContext(context: ReuseContext, at: Instant): ResolvedReuseContext =
        inTransaction {
            val valid = revalidateReuseContext(context, at)
            val items = valid.references.map { reference ->
                when (reference.objectType) {
                    ReuseObjectType.Event -> {
                        val event = checkNotNull(
                            findCurrent(reference.objectId, includeDeleted = false),
                        ).event
                        check(event.revision == reference.revision)
                        ResolvedReuseItem(reference = reference, sourceEvent = event)
                    }
                    ReuseObjectType.LongTermMemory -> {
                        val memory = checkNotNull(
                            longTermMemoryPersistence.load(reference.objectId),
                        )
                        val source = checkNotNull(
                            findCurrent(memory.sourceEventId, includeDeleted = false),
                        ).event
                        check(memory.revision == reference.revision)
                        check(source.revision == reference.sourceEventRevision)
                        ResolvedReuseItem(
                            reference = reference,
                            sourceEvent = source,
                            memorySummary = memory.valueSummary,
                            memoryType = memory.type,
                        )
                    }
                }
            }
            ResolvedReuseContext(context = valid, items = items)
        }

    fun recordReuseOutcome(submission: ReuseOutcomeSubmission): Boolean =
        inTransaction { reusePersistence.recordOutcome(submission) }

    fun reuseTelemetryAggregates(since: Instant): List<ReuseTelemetryAggregate> =
        if (isLocalSpaceDeleted()) emptyList() else reusePersistence.aggregates(since)

    fun helpfulReuseCount(since: Instant): Int =
        if (isLocalSpaceDeleted()) 0 else reusePersistence.helpfulCount(since)

    fun dismissCoverageCandidate(
        dayId: String,
        candidateId: String,
        updatedAt: Instant,
    ): Boolean = inTransaction {
        coveragePersistence.dismiss(dayId, candidateId, updatedAt)
    }

    fun acceptCoverageCandidate(acceptance: CoverageCandidateAcceptance): CoverageEventLink = inTransaction {
        coveragePersistence.existingLink(acceptance.dayId, acceptance.candidateId)?.let { existing ->
            check(existing.lifecycle == CoverageEventLinkLifecycle.Active) {
                "detached coverage candidate cannot be accepted again"
            }
            return@inTransaction existing
        }
        val candidate = coveragePersistence.requireOpenCandidate(
            acceptance.dayId,
            acceptance.candidateId,
        )
        val fieldProvenance = validatedFieldProvenance(candidate, acceptance)
        val (factStatus, evidenceState) = if (acceptance.mode == CoverageAcceptanceMode.UserConfirmed) {
            FactStatus.Confirmed to EvidenceState.UserAsserted
        } else {
            when (candidate.factStatus) {
                CoverageFactStatus.Observed -> FactStatus.NeedsReview to EvidenceState.Observed
                CoverageFactStatus.UserAsserted -> FactStatus.UserAsserted to EvidenceState.UserAsserted
                CoverageFactStatus.Planned -> FactStatus.Planned to EvidenceState.Observed
                CoverageFactStatus.Inferred -> FactStatus.Inferred to EvidenceState.Inferred
            }
        }
        val event = MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = acceptance.localDate,
            time = acceptance.time,
            title = candidate.title,
            detail = acceptance.detail.trim(),
            factStatus = factStatus,
            sourceLabel = acceptance.sourceLabel.trim(),
            isLocalOnly = true,
            userWords = acceptance.userWords?.trim()?.ifEmpty { null },
            revision = 1,
            eventType = when (candidate.eventType) {
                CandidateEventType.Activity -> EventType.Activity
                CandidateEventType.Communication -> EventType.Communication
                CandidateEventType.Decision -> EventType.Decision
                CandidateEventType.Result -> EventType.Result
                CandidateEventType.StateChange -> EventType.StateChange
                CandidateEventType.Milestone -> EventType.Milestone
                CandidateEventType.Experience -> EventType.Experience
            },
            evidenceState = evidenceState,
            sensitivity = acceptance.sensitivity,
            importance = acceptance.importance,
        )
        appendRevision(event, reason = "coverage_candidate_accept", state = STATE_ACTIVE)
        refreshSearchIndex(event, STATE_ACTIVE)
        touchDayLedger(event.localDate)
        coveragePersistence.consumeAndLink(
            dayId = acceptance.dayId,
            candidate = candidate,
            eventId = event.id,
            revision = 1,
            acceptedAt = acceptance.acceptedAt,
        ).also { link ->
            link.sourceObjectIds.forEach { sourceObjectId ->
                registerSourceObject(
                    sourceObjectId = sourceObjectId,
                    kind = LocalSourceKind.CoverageEvidence,
                    rawOwnership = LocalRawOwnership.NoRaw,
                )
                linkEventToSource(event.id, sourceObjectId, createdRevision = event.revision)
            }
            fieldProvenance.forEach { (field, sourceObjectIds) ->
                sourceObjectIds.forEach { sourceObjectId ->
                    registerEventFieldEvidence(
                        eventId = event.id,
                        eventRevision = event.revision,
                        field = field,
                        sourceObjectId = sourceObjectId,
                        createdAt = acceptance.acceptedAt,
                    )
                }
            }
            if (acceptance.mode == CoverageAcceptanceMode.UserConfirmed) {
                registerUserConfirmation(
                    eventId = event.id,
                    eventRevision = event.revision,
                    kind = UserConfirmationKind.CoverageAcceptance,
                    confirmedFields = candidate.observedFields,
                    completeFieldSet = true,
                    confirmedAt = acceptance.acceptedAt,
                )
            }
        }
    }

    fun updateEvent(
        eventId: String,
        factStatus: FactStatus? = null,
        userWords: String? = null,
    ): MemoryEvent? = inTransaction {
        val current = findCurrent(eventId, includeDeleted = false) ?: return@inTransaction null
        val changedAt = Instant.now()
        val completeBefore = completeUserConfirmationFields(eventId, current.revision)
        val sourceFields = completeSourceEvidenceFields(eventId, current.revision)
        val nextCompleteFields = when {
            factStatus == FactStatus.Confirmed -> sourceFields ?: completeBefore
            factStatus == null && completeBefore != null -> completeBefore
            else -> null
        }
        val updated = current.event.copy(
            factStatus = factStatus ?: current.event.factStatus,
            userWords = userWords?.trim()?.take(16_384)?.ifEmpty { null } ?: current.event.userWords,
            revision = current.revision + 1,
        )
        terminalizeEventFieldEvidence(
            eventId = eventId,
            deletedAtEpochMillis = changedAt.toEpochMilli(),
            eventRevision = current.revision,
        )
        terminalizeUserConfirmations(
            eventId = eventId,
            deletedAtEpochMillis = changedAt.toEpochMilli(),
            eventRevision = current.revision,
        )
        appendRevision(updated, reason = "user_revision", state = STATE_ACTIVE)
        if (nextCompleteFields != null) {
            registerUserConfirmation(
                eventId = eventId,
                eventRevision = updated.revision,
                kind = if (factStatus == FactStatus.Confirmed) {
                    UserConfirmationKind.FactStatusConfirmation
                } else {
                    UserConfirmationKind.UserRevision
                },
                confirmedFields = nextCompleteFields,
                completeFieldSet = true,
                confirmedAt = changedAt,
            )
        } else if (factStatus == FactStatus.Confirmed) {
            registerUserConfirmation(
                eventId = eventId,
                eventRevision = updated.revision,
                kind = UserConfirmationKind.FactStatusConfirmation,
                confirmedFields = setOf(
                    EvidenceField.Time,
                    EvidenceField.Action,
                    EvidenceField.Description,
                ),
                completeFieldSet = false,
                confirmedAt = changedAt,
            )
        }
        if (userWords != null && EvidenceField.Description !in nextCompleteFields.orEmpty()) {
            registerUserConfirmation(
                eventId = eventId,
                eventRevision = updated.revision,
                kind = UserConfirmationKind.UserRevision,
                confirmedFields = setOf(EvidenceField.Description),
                completeFieldSet = false,
                confirmedAt = changedAt,
            )
        }
        longTermMemoryPersistence.invalidateForEvent(
            eventId = eventId,
            currentEventRevision = updated.revision,
            reason = LongTermMemoryInvalidationReason.EventRevisionChanged,
            invalidatedAt = changedAt,
        )
        refreshSearchIndex(updated, STATE_ACTIVE)
        touchDayLedger(updated.localDate)
        updated
    }

    fun appendAgentRevision(
        eventId: String,
        content: String,
        evidenceState: EvidenceState,
        factStatus: FactStatus,
        allowedSensitivities: Set<Sensitivity>,
    ): AgentRevisionAppendResult? = inTransaction {
        require(allowedSensitivities.isNotEmpty()) { "Agent sensitivity scope is empty" }
        require(content.isNotBlank() && content.codePointCount(0, content.length) <= 4_000) {
            "Agent revision content is invalid"
        }
        require('\u0000' !in content) { "Agent revision content contains NUL" }
        val current = findCurrent(eventId, includeDeleted = false) ?: return@inTransaction null
        if (current.event.sensitivity !in allowedSensitivities) return@inTransaction null
        val updated = current.event.copy(
            detail = content,
            factStatus = factStatus,
            evidenceState = evidenceState,
            revision = current.revision + 1,
        )
        terminalizeEventFieldEvidence(
            eventId = eventId,
            deletedAtEpochMillis = System.currentTimeMillis(),
            eventRevision = current.revision,
        )
        terminalizeUserConfirmations(
            eventId = eventId,
            deletedAtEpochMillis = System.currentTimeMillis(),
            eventRevision = current.revision,
        )
        val revisionId = appendRevision(updated, reason = "agent_revision", state = STATE_ACTIVE)
        longTermMemoryPersistence.invalidateForEvent(
            eventId = eventId,
            currentEventRevision = updated.revision,
            reason = LongTermMemoryInvalidationReason.EventRevisionChanged,
            invalidatedAt = Instant.now(),
        )
        refreshSearchIndex(updated, STATE_ACTIVE)
        touchDayLedger(updated.localDate)
        AgentRevisionAppendResult(updated, revisionId)
    }

    fun undoAgentCapture(
        target: AgentCaptureUndoTarget,
        allowedSensitivities: Set<Sensitivity>,
        undoneAt: Instant,
    ): AgentCaptureUndoResult? = inTransaction {
        require(allowedSensitivities.isNotEmpty()) { "Agent sensitivity scope is empty" }
        val current = findCurrent(target.eventId, includeDeleted = false) ?: return@inTransaction null
        if (current.event.sensitivity !in allowedSensitivities) return@inTransaction null
        when (target.objectType) {
            "event" -> {
                if (
                    target.objectId != target.eventId ||
                    target.createdRevision != FIRST_EVENT_REVISION
                ) {
                    return@inTransaction null
                }
                if (
                    current.revision != target.createdRevision ||
                    current.revisionId != firstRevisionId(target.eventId)
                ) {
                    throw AgentCaptureUndoConflictException()
                }
                check(
                    tombstoneEvent(
                        target.eventId,
                        reason = AGENT_CAPTURE_UNDO_REASON,
                        deletedAt = undoneAt,
                    ),
                )
                AgentCaptureUndoResult(
                    eventId = target.eventId,
                    objectType = "event",
                    objectId = target.objectId,
                    terminalRevision = target.createdRevision + 1,
                )
            }
            "revision" -> {
                if (
                    target.createdRevision < 2 ||
                    current.revision != target.createdRevision ||
                    current.revisionId != target.objectId
                ) {
                    throw AgentCaptureUndoConflictException()
                }
                val previous = findRevision(target.eventId, target.createdRevision - 1)
                    ?: return@inTransaction null
                val restored = previous.event.copy(revision = current.revision + 1)
                terminalizeEventFieldEvidence(
                    eventId = target.eventId,
                    deletedAtEpochMillis = undoneAt.toEpochMilli(),
                    eventRevision = current.revision,
                )
                terminalizeUserConfirmations(
                    eventId = target.eventId,
                    deletedAtEpochMillis = undoneAt.toEpochMilli(),
                    eventRevision = current.revision,
                )
                val compensationRevisionId = appendRevision(
                    restored,
                    reason = AGENT_REVISION_UNDO_REASON,
                    state = STATE_ACTIVE,
                )
                longTermMemoryPersistence.invalidateForEvent(
                    eventId = target.eventId,
                    currentEventRevision = restored.revision,
                    reason = LongTermMemoryInvalidationReason.EventRevisionChanged,
                    invalidatedAt = undoneAt,
                )
                refreshSearchIndex(restored, STATE_ACTIVE)
                touchDayLedger(restored.localDate)
                AgentCaptureUndoResult(
                    eventId = target.eventId,
                    objectType = "revision",
                    objectId = target.objectId,
                    terminalRevision = restored.revision,
                    compensationRevisionId = compensationRevisionId,
                )
            }
            else -> null
        }
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

    fun markSourceLocatorReleased(eventId: String): Boolean = inTransaction(allowDeletedSpace = true) {
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

    private fun appendRevision(event: MemoryEvent, reason: String, state: String): String {
        val current = findCurrent(event.id, includeDeleted = true)
        return writeRevision(event, reason, state, current)
    }

    private fun writeDeletionWatermark(
        objectType: String,
        objectId: String,
        terminalRevision: Int,
        deletedAtEpochMillis: Long,
        reason: String,
    ) {
        require(objectType.isNotBlank() && objectId.isNotBlank() && reason.isNotBlank())
        require(terminalRevision >= 1)
        val digest = LocalRecoveryBackup.tombstoneDigest(
            spaceId = spaceId,
            objectType = objectType,
            objectId = objectId,
            terminalRevision = terminalRevision,
            deletedAtEpochMillis = deletedAtEpochMillis,
            reason = reason,
        )
        database.execSQL(
            """
                INSERT INTO deletion_watermarks (
                    space_id, object_type, object_id, terminal_revision,
                    deleted_at, reason, tombstone_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(space_id, object_type, object_id) DO UPDATE SET
                    terminal_revision = excluded.terminal_revision,
                    deleted_at = excluded.deleted_at,
                    reason = excluded.reason,
                    tombstone_digest = excluded.tombstone_digest
                WHERE excluded.terminal_revision > deletion_watermarks.terminal_revision
            """.trimIndent(),
            arrayOf<Any>(
                spaceId,
                objectType,
                objectId,
                terminalRevision,
                deletedAtEpochMillis,
                reason,
                digest,
            ),
        )
    }

    private fun appendNewRevision(event: MemoryEvent, reason: String, state: String) {
        writeRevision(event, reason, state, current = null)
    }

    private fun writeRevision(
        event: MemoryEvent,
        reason: String,
        state: String,
        current: CurrentEvent?,
    ): String {
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
        return revisionId
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
                val revisionId = if (
                    binding.operation ==
                    com.ameme.android.data.transport.MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_APPEND_REVISION
                ) {
                    database.rawQuery(
                        """
                            SELECT revision_id
                            FROM event_revisions
                            WHERE space_id = ? AND event_id = ? AND revision = ?
                        """.trimIndent(),
                        arrayOf(binding.spaceId, existing.second, existing.third.toString()),
                    ).use { cursor ->
                        check(cursor.moveToFirst()) { "Agent revision idempotency result is unavailable" }
                        cursor.getString(0)
                    }
                } else {
                    null
                }
                AgentLocalNodeIdempotencyResult.Applied(
                    AgentLocalNodeCaptureOutcome(existing.second, existing.third, revisionId),
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

    internal fun resolveAgentUndoIdempotency(
        binding: AgentLocalNodeIdempotencyBinding,
        payloadDigest: String,
        undoToken: String,
        at: Instant,
        capture: (AgentCaptureUndoTarget) -> AgentCaptureUndoResult?,
    ): AgentLocalNodeUndoIdempotencyResult = inTransaction {
        require(binding.operation == "undo_capture") { "Agent undo operation is invalid" }
        require(binding.memoryType in setOf("event", "revision")) { "Agent undo type is invalid" }
        require(payloadDigest.matches(Regex("sha256_[0-9a-f]{64}"))) { "payload digest is invalid" }
        if (!undoToken.matches(Regex("idem_[0-9a-f]{64}"))) {
            return@inTransaction AgentLocalNodeUndoIdempotencyResult.NotVisible
        }
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
            if (!cursor.moveToFirst()) null else Triple(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getInt(2),
            )
        }
        if (existing != null) {
            if (existing.first != payloadDigest) {
                return@inTransaction AgentLocalNodeUndoIdempotencyResult.Conflict
            }
            val original = findAgentUndoSource(binding, undoToken)
                ?: return@inTransaction AgentLocalNodeUndoIdempotencyResult.NotVisible
            return@inTransaction AgentLocalNodeUndoIdempotencyResult.Applied(
                undoOutcomeFrom(
                    original = original,
                    objectType = binding.memoryType,
                    terminalRevision = existing.third,
                ),
            )
        }

        val original = findAgentUndoSource(binding, undoToken)
            ?: return@inTransaction AgentLocalNodeUndoIdempotencyResult.NotVisible
        val expiresAtMillis = original.createdAtEpochMillis +
            AGENT_CAPTURE_UNDO_TTL_SECONDS * 1_000L
        if (at.toEpochMilli() >= expiresAtMillis) {
            return@inTransaction AgentLocalNodeUndoIdempotencyResult.NotVisible
        }
        val outcome = capture(
            AgentCaptureUndoTarget(
                eventId = original.eventId,
                objectType = binding.memoryType,
                objectId = original.objectId,
                createdRevision = original.revision,
            ),
        ) ?: return@inTransaction AgentLocalNodeUndoIdempotencyResult.NotVisible
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
                put("revision", outcome.terminalRevision)
                put("created_at", at.toEpochMilli())
            },
        )
        AgentLocalNodeUndoIdempotencyResult.Applied(outcome)
    }

    private fun findAgentUndoSource(
        binding: AgentLocalNodeIdempotencyBinding,
        undoToken: String,
    ): AgentUndoSource? {
        val originalOperation = when (binding.memoryType) {
            "event" -> "create_event"
            "revision" -> "append_revision"
            else -> return null
        }
        return database.rawQuery(
            """
                SELECT event_id, revision, created_at
                FROM agent_idempotency
                WHERE caller_id = ? AND grant_id = ? AND purpose = ? AND space_id = ?
                  AND memory_type = ? AND operation = ? AND slot = ?
            """.trimIndent(),
            arrayOf(
                binding.callerId,
                binding.grantId,
                binding.purpose,
                binding.spaceId,
                binding.memoryType,
                originalOperation,
                undoToken,
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val eventId = cursor.getString(0)
                val revision = cursor.getInt(1)
                val objectId = if (binding.memoryType == "event") {
                    eventId
                } else {
                    revisionId(eventId, revision) ?: return@use null
                }
                AgentUndoSource(
                    eventId = eventId,
                    objectId = objectId,
                    revision = revision,
                    createdAtEpochMillis = cursor.getLong(2),
                )
            }
        }
    }

    private fun undoOutcomeFrom(
        original: AgentUndoSource,
        objectType: String,
        terminalRevision: Int,
    ): AgentCaptureUndoResult {
        require(objectType in setOf("event", "revision")) { "Agent undo type is invalid" }
        val compensationRevisionId = if (objectType == "revision") {
            revisionId(original.eventId, terminalRevision)
                ?: error("Agent undo compensation revision is unavailable")
        } else {
            null
        }
        return AgentCaptureUndoResult(
            eventId = original.eventId,
            objectType = objectType,
            objectId = original.objectId,
            terminalRevision = terminalRevision,
            compensationRevisionId = compensationRevisionId,
        )
    }

    private fun revisionId(eventId: String, revision: Int): String? = database.rawQuery(
        """
            SELECT revision_id FROM event_revisions
            WHERE space_id = ? AND event_id = ? AND revision = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId, revision.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

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

    private fun findRevision(eventId: String, revision: Int): CurrentEvent? = database.rawQuery(
        """
            SELECT revision_id, revision, event_id, local_date, local_time, title, detail,
                   fact_status, source_label, is_local_only, user_words, event_type,
                   evidence_state, sensitivity, importance
            FROM event_revisions
            WHERE event_id = ? AND space_id = ? AND revision = ? AND state = ?
        """.trimIndent(),
        arrayOf(eventId, spaceId, revision.toString(), STATE_ACTIVE),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
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

    private fun firstRevisionId(eventId: String): String? = database.rawQuery(
        """
            SELECT revision_id FROM event_revisions
            WHERE space_id = ? AND event_id = ? AND revision = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId, FIRST_EVENT_REVISION.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

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

    private fun registerCapturedSource(eventId: String, source: SourceCaptureRequest) {
        val sourceObjectId = "source_${UUID.randomUUID()}"
        registerSourceObject(
            sourceObjectId = sourceObjectId,
            kind = LocalSourceKind.CapturedLocator,
            rawOwnership = if (source.locatorUri == null) {
                LocalRawOwnership.NoRaw
            } else {
                // Android currently stores only a provider/content locator. It does not own Raw bytes.
                LocalRawOwnership.ExternalNotOwned
            },
        )
        linkEventToSource(eventId, sourceObjectId, createdRevision = 1)
    }

    private fun registerSourceObject(
        sourceObjectId: String,
        kind: LocalSourceKind,
        rawOwnership: LocalRawOwnership,
    ) {
        require(sourceObjectId.isNotBlank())
        check(deletionWatermark(DELETION_OBJECT_SOURCE, sourceObjectId) == null) {
            "deleted source object cannot be registered again"
        }
        database.insertWithOnConflict(
            "source_objects",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("source_object_id", sourceObjectId)
                put("source_kind", kind.name)
                put("raw_ownership", rawOwnership.name)
                put("state", LocalSourceState.Active.name)
                put("created_at", System.currentTimeMillis())
                putNull("deleted_at")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val existing = readSourceObject(sourceObjectId) ?: error("source object was not persisted")
        check(existing.kind == kind && existing.rawOwnership == rawOwnership) {
            "source object identity changed"
        }
        check(existing.state == LocalSourceState.Active) { "source object is terminal" }
    }

    private fun linkEventToSource(eventId: String, sourceObjectId: String, createdRevision: Int) {
        database.insertWithOnConflict(
            "event_source_links",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("event_id", eventId)
                put("source_object_id", sourceObjectId)
                put("created_revision", createdRevision)
                put("state", SOURCE_LINK_STATE_ACTIVE)
                put("created_at", System.currentTimeMillis())
                putNull("deleted_at")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun validatedFieldProvenance(
        candidate: com.ameme.android.coverage.CandidateEvent,
        acceptance: CoverageCandidateAcceptance,
    ): Map<EvidenceField, Set<String>> {
        if (acceptance.fieldSourceObjectIds.isEmpty()) {
            return if (candidate.sourceObjectIds.size == 1) {
                candidate.observedFields.associateWith { candidate.sourceObjectIds }
            } else {
                emptyMap()
            }
        }
        require(acceptance.fieldSourceObjectIds.keys == candidate.observedFields) {
            "field provenance must cover every accepted candidate field"
        }
        require(acceptance.fieldSourceObjectIds.values.all {
            it.isNotEmpty() && candidate.sourceObjectIds.containsAll(it)
        }) {
            "field provenance references an unavailable candidate source"
        }
        require(
            acceptance.fieldSourceObjectIds.values.flatten().toSet() == candidate.sourceObjectIds,
        ) {
            "every linked source must support at least one accepted field"
        }
        return acceptance.fieldSourceObjectIds
    }

    private fun registerUserConfirmation(
        eventId: String,
        eventRevision: Int,
        kind: UserConfirmationKind,
        confirmedFields: Set<EvidenceField>,
        completeFieldSet: Boolean,
        confirmedAt: Instant,
        confirmationId: String = "confirm_${UUID.randomUUID()}",
    ) {
        require(confirmedFields.isNotEmpty()) { "user confirmation fields must not be empty" }
        database.insertOrThrow(
            "event_user_confirmations",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("event_id", eventId)
                put("event_revision", eventRevision)
                put("confirmation_id", confirmationId)
                put("confirmation_kind", kind.name)
                put("confirmed_fields_json", encodeEvidenceFields(confirmedFields))
                put("complete_field_set", if (completeFieldSet) 1 else 0)
                put("confirmed_at", confirmedAt.toEpochMilli())
                put("state", UserConfirmationState.Active.name)
                putNull("deleted_at")
            },
        )
    }

    private fun activeUserConfirmations(
        eventId: String,
        eventRevision: Int,
    ): List<LocalEventUserConfirmation> = userConfirmationsForEvent(eventId).filter {
        it.eventRevision == eventRevision && it.state == UserConfirmationState.Active
    }

    private fun completeUserConfirmationFields(
        eventId: String,
        eventRevision: Int,
    ): Set<EvidenceField>? {
        val complete = activeUserConfirmations(eventId, eventRevision).filter {
            it.completeFieldSet
        }
        if (complete.isEmpty()) return null
        val fieldSets = complete.map(LocalEventUserConfirmation::confirmedFields).distinct()
        return fieldSets.singleOrNull()
    }

    private fun carryUserConfirmations(
        confirmations: List<LocalEventUserConfirmation>,
        eventRevision: Int,
    ) {
        confirmations.forEach { confirmation ->
            registerUserConfirmation(
                eventId = confirmation.eventId,
                eventRevision = eventRevision,
                kind = confirmation.kind,
                confirmedFields = confirmation.confirmedFields,
                completeFieldSet = confirmation.completeFieldSet,
                confirmedAt = confirmation.confirmedAt,
                confirmationId = confirmation.confirmationId,
            )
        }
    }

    private fun terminalizeUserConfirmations(
        eventId: String,
        deletedAtEpochMillis: Long,
        eventRevision: Int? = null,
    ) {
        val revisionClause = if (eventRevision == null) "" else " AND event_revision = ?"
        val args = buildList<Any> {
            add(UserConfirmationState.Deleted.name)
            add(deletedAtEpochMillis)
            add(spaceId)
            add(eventId)
            add(UserConfirmationState.Active.name)
            if (eventRevision != null) add(eventRevision)
        }.toTypedArray()
        database.execSQL(
            """
                UPDATE event_user_confirmations
                SET state = ?, deleted_at = ?
                WHERE space_id = ? AND event_id = ? AND state = ?$revisionClause
            """.trimIndent(),
            args,
        )
    }

    private fun encodeEvidenceFields(fields: Set<EvidenceField>): String =
        JSONArray(fields.sortedBy { it.wireValue }.map { it.name }).toString()

    private fun decodeEvidenceFields(encoded: String): Set<EvidenceField> {
        val array = JSONArray(encoded)
        require(array.length() > 0) { "user confirmation field set is empty" }
        return buildSet {
            repeat(array.length()) { index ->
                add(EvidenceField.valueOf(array.getString(index)))
            }
        }.also { fields ->
            require(fields.size == array.length()) { "user confirmation fields are duplicated" }
        }
    }

    private fun registerEventFieldEvidence(
        eventId: String,
        eventRevision: Int,
        field: EvidenceField,
        sourceObjectId: String,
        createdAt: Instant,
    ) {
        database.insertOrThrow(
            "event_field_evidence",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("event_id", eventId)
                put("event_revision", eventRevision)
                put("evidence_field", field.name)
                put("source_object_id", sourceObjectId)
                put("state", EventFieldEvidenceState.Active.name)
                put("created_at", createdAt.toEpochMilli())
                putNull("deleted_at")
            },
        )
    }

    private fun activeFieldEvidence(
        eventId: String,
        eventRevision: Int,
    ): List<Pair<EvidenceField, String>> = database.rawQuery(
        """
            SELECT evidence_field, source_object_id
            FROM event_field_evidence
            WHERE space_id = ? AND event_id = ? AND event_revision = ? AND state = ?
            ORDER BY evidence_field, source_object_id
        """.trimIndent(),
        arrayOf(
            spaceId,
            eventId,
            eventRevision.toString(),
            EventFieldEvidenceState.Active.name,
        ),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(EvidenceField.valueOf(cursor.getString(0)) to cursor.getString(1))
            }
        }
    }

    private fun completeSourceEvidenceFields(
        eventId: String,
        eventRevision: Int,
    ): Set<EvidenceField>? {
        val activeSources = activeSourceIdsForEvent(eventId)
        val evidence = activeFieldEvidence(eventId, eventRevision)
        if (
            activeSources.isEmpty() ||
            evidence.isEmpty() ||
            evidence.any { it.second !in activeSources } ||
            activeSources.any { source -> evidence.none { it.second == source } }
        ) {
            return null
        }
        return evidence.map { it.first }.toSet()
    }

    private fun activeSourceIdsForEvent(eventId: String): Set<String> = database.rawQuery(
        """
            SELECT source_object_id
            FROM event_source_links
            WHERE space_id = ? AND event_id = ? AND state = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId, SOURCE_LINK_STATE_ACTIVE),
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun sourceCascadeAction(
        eventId: String,
        deletedSourceObjectId: String,
    ): SourceCascadeAction? {
        val activeSources = activeSourceIdsForEvent(eventId)
        val current = findCurrent(eventId, includeDeleted = false) ?: return null
        val evidence = activeFieldEvidence(eventId, current.revision)
        val userConfirmedFields = completeUserConfirmationFields(eventId, current.revision)
        if (activeSources.size == 1 && userConfirmedFields == null) {
            return SourceCascadeAction.Delete
        }
        val sourceFields = completeSourceEvidenceFields(eventId, current.revision)
        if (userConfirmedFields == null && sourceFields == null) return null
        if (
            userConfirmedFields != null &&
            evidence.any { it.first !in userConfirmedFields || it.second !in activeSources }
        ) {
            return null
        }
        val fields = userConfirmedFields ?: sourceFields ?: return null
        val remaining = evidence.filter { it.second != deletedSourceObjectId }
        val supportedFields = remaining.map { it.first }.toSet() + userConfirmedFields.orEmpty()
        return if (fields.all(supportedFields::contains)) {
            SourceCascadeAction.Recompute
        } else {
            SourceCascadeAction.Delete
        }
    }

    private fun recomputeEventAfterSourceDeletion(
        eventId: String,
        deletedSourceObjectId: String,
        requestedAt: Instant,
    ) {
        val current = checkNotNull(findCurrent(eventId, includeDeleted = false))
        val activeSourceCount = activeSourceIdsForEvent(eventId).size
        val confirmations = activeUserConfirmations(eventId, current.revision)
        val remainingEvidence = activeFieldEvidence(eventId, current.revision).filter {
            it.second != deletedSourceObjectId
        }
        val updated = current.event.copy(
            sourceLabel = if (activeSourceCount == 1) {
                USER_CONFIRMED_SOURCE_DELETED_LABEL
            } else {
                RECOMPUTED_SOURCE_LABEL
            },
            revision = current.revision + 1,
        )
        terminalizeEventFieldEvidence(
            eventId = eventId,
            deletedAtEpochMillis = requestedAt.toEpochMilli(),
            eventRevision = current.revision,
        )
        terminalizeUserConfirmations(
            eventId = eventId,
            deletedAtEpochMillis = requestedAt.toEpochMilli(),
            eventRevision = current.revision,
        )
        appendRevision(updated, reason = "source_deletion_recompute", state = STATE_ACTIVE)
        remainingEvidence.forEach { (field, sourceObjectId) ->
            registerEventFieldEvidence(
                eventId = eventId,
                eventRevision = updated.revision,
                field = field,
                sourceObjectId = sourceObjectId,
                createdAt = requestedAt,
            )
        }
        carryUserConfirmations(confirmations, updated.revision)
        database.execSQL(
            """
                UPDATE event_source_links
                SET state = ?, deleted_at = ?
                WHERE space_id = ? AND event_id = ? AND source_object_id = ? AND state = ?
            """.trimIndent(),
            arrayOf<Any>(
                SOURCE_LINK_STATE_DELETED,
                requestedAt.toEpochMilli(),
                spaceId,
                eventId,
                deletedSourceObjectId,
                SOURCE_LINK_STATE_ACTIVE,
            ),
        )
        coveragePersistence.detachEvent(eventId, requestedAt)
        longTermMemoryPersistence.invalidateForEvent(
            eventId = eventId,
            currentEventRevision = updated.revision,
            reason = LongTermMemoryInvalidationReason.EventRevisionChanged,
            invalidatedAt = requestedAt,
        )
        refreshSearchIndex(updated, STATE_ACTIVE)
        touchDayLedger(updated.localDate)
    }

    private fun terminalizeEventFieldEvidence(
        eventId: String,
        deletedAtEpochMillis: Long,
        eventRevision: Int? = null,
    ) {
        val revisionClause = if (eventRevision == null) "" else " AND event_revision = ?"
        val args = buildList<Any> {
            add(EventFieldEvidenceState.Deleted.name)
            add(deletedAtEpochMillis)
            add(spaceId)
            add(eventId)
            add(EventFieldEvidenceState.Active.name)
            if (eventRevision != null) add(eventRevision)
        }.toTypedArray()
        database.execSQL(
            """
                UPDATE event_field_evidence
                SET state = ?, deleted_at = ?
                WHERE space_id = ? AND event_id = ? AND state = ?$revisionClause
            """.trimIndent(),
            args,
        )
    }

    private fun readSourceObject(sourceObjectId: String): LocalSourceObject? = database.rawQuery(
        """
            SELECT source_kind, raw_ownership, state, created_at, deleted_at
            FROM source_objects
            WHERE space_id = ? AND source_object_id = ?
        """.trimIndent(),
        arrayOf(spaceId, sourceObjectId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            LocalSourceObject(
                sourceObjectId = sourceObjectId,
                spaceId = spaceId,
                kind = LocalSourceKind.valueOf(cursor.getString(0)),
                rawOwnership = LocalRawOwnership.valueOf(cursor.getString(1)),
                state = LocalSourceState.valueOf(cursor.getString(2)),
                createdAt = Instant.ofEpochMilli(cursor.getLong(3)),
                deletedAt = if (cursor.isNull(4)) null else Instant.ofEpochMilli(cursor.getLong(4)),
            )
        }
    }

    private fun activeEventIdsForSource(sourceObjectId: String): List<String> = database.rawQuery(
        """
            SELECT l.event_id
            FROM event_source_links l
            JOIN events_current e
              ON e.space_id = l.space_id AND e.event_id = l.event_id
            WHERE l.space_id = ? AND l.source_object_id = ? AND l.state = ? AND e.state = ?
            ORDER BY l.event_id
        """.trimIndent(),
        arrayOf(spaceId, sourceObjectId, SOURCE_LINK_STATE_ACTIVE, STATE_ACTIVE),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun activeSourceCountForEvent(eventId: String): Int = database.rawQuery(
        """
            SELECT COUNT(*)
            FROM event_source_links
            WHERE space_id = ? AND event_id = ? AND state = ?
        """.trimIndent(),
        arrayOf(spaceId, eventId, SOURCE_LINK_STATE_ACTIVE),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun writeDeletionJob(
        sourceObjectId: String,
        operation: SourceDeletionOperation,
        status: SourceDeletionStatus,
        affectedEventCount: Int,
        recomputedEventCount: Int = 0,
        deletedEventCount: Int = 0,
        requestedAt: Instant,
    ): SourceDeletionResult {
        val jobId = "del_${UUID.randomUUID()}"
        val completedAt = Instant.now().let { if (it.isBefore(requestedAt)) requestedAt else it }
        database.insertOrThrow(
            "deletion_jobs",
            null,
            ContentValues().apply {
                put("job_id", jobId)
                put("space_id", spaceId)
                put("source_object_id", sourceObjectId)
                put("operation", operation.name)
                put("status", status.name)
                put("affected_event_count", affectedEventCount)
                put("recomputed_event_count", recomputedEventCount)
                put("deleted_event_count", deletedEventCount)
                put("created_at", requestedAt.toEpochMilli())
                put("completed_at", completedAt.toEpochMilli())
            },
        )
        return SourceDeletionResult(
            jobId = jobId,
            sourceObjectId = sourceObjectId,
            operation = operation,
            status = status,
            affectedEventCount = affectedEventCount,
            recomputedEventCount = recomputedEventCount,
            deletedEventCount = deletedEventCount,
            createdAt = requestedAt,
            completedAt = completedAt,
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

    private inline fun <T> inTransaction(
        allowDeletedSpace: Boolean = false,
        block: () -> T,
    ): T {
        if (database.inTransaction()) {
            if (!allowDeletedSpace) requireLocalSpaceActive()
            return block()
        }
        database.beginTransaction()
        return try {
            if (!allowDeletedSpace) requireLocalSpaceActive()
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    private fun requireLocalSpaceActive() {
        check(!isLocalSpaceDeleted()) { "local space is deleted and frozen" }
    }

    private fun pendingSourceLocatorReleaseCount(): Int = database.rawQuery(
        """
            SELECT COUNT(*) FROM source_locators
            WHERE space_id = ? AND state = ?
        """.trimIndent(),
        arrayOf(spaceId, LOCATOR_STATE_RELEASE_PENDING),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private data class CurrentEvent(
        val revisionId: String,
        val revision: Int,
        val event: MemoryEvent,
    )

    private data class AgentUndoSource(
        val eventId: String,
        val objectId: String,
        val revision: Int,
        val createdAtEpochMillis: Long,
    )

    private data class EventRow(val event: MemoryEvent, val updatedAt: Long)

    private data class CursorPosition(
        val date: String,
        val time: String,
        val updatedAt: Long,
        val eventId: String,
    )

    private enum class SourceCascadeAction {
        Delete,
        Recompute,
    }

    companion object {
        const val SCHEMA_VERSION = 13
        const val FIELD_EVIDENCE_SCHEMA_VERSION = 12
        const val SOURCE_DELETION_SCHEMA_VERSION = 11
        const val REUSE_SCHEMA_VERSION = 10
        const val RECOVERY_SCHEMA_VERSION = 9
        const val LONG_TERM_MEMORY_SCHEMA_VERSION = 8
        const val COVERAGE_SCHEMA_VERSION = 7
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_DELETED = "DELETED"
        const val DEFAULT_SPACE_ID = "space_personal"
        const val LEGACY_SPACE_ID = "space_legacy"
        const val LOCATOR_STATE_RELEASE_PENDING = "RELEASE_PENDING"
        const val LOCATOR_STATE_RELEASED = "RELEASED"
        const val LOCATOR_STATE_DELETED = "DELETED"
        const val BATCH_EXISTENCE_CHECK_SIZE = 500
        const val DELETION_OBJECT_EVENT = "EVENT"
        const val DELETION_OBJECT_SOURCE = "SOURCE_OBJECT"
        const val DELETION_OBJECT_SPACE = "SPACE"
        const val LOCAL_SPACE_DELETE_REASON = "local_space_delete"
        const val RECOMPUTED_SOURCE_LABEL = "多来源（已重算）"
        const val USER_CONFIRMED_SOURCE_DELETED_LABEL = "用户确认（来源已删除）"
        const val SOURCE_LINK_STATE_ACTIVE = "ACTIVE"
        const val SOURCE_LINK_STATE_DELETED = "DELETED"
        const val BACKUP_HEALTH_READY = "READY"
        const val REUSE_CONTEXT_TTL_SECONDS = 15 * 60L
        const val AGENT_CAPTURE_UNDO_TTL_SECONDS = 10 * 60L
        private const val FIRST_EVENT_REVISION = 1
        private const val AGENT_CAPTURE_UNDO_REASON = "agent_capture_undo"
        private const val AGENT_REVISION_UNDO_REASON = "agent_revision_undo"

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
                LocalEventDatabase(database, databaseFile, key, spaceId, backend)
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
                    recordMigration(database, 6, "create_v6_event_policy_and_day_summary")
                    createCoverageTables(database)
                    recordMigration(database, COVERAGE_SCHEMA_VERSION, "create_v7_coverage_projection")
                    createLongTermMemoryTables(database)
                    recordMigration(database, LONG_TERM_MEMORY_SCHEMA_VERSION, "create_v8_long_term_memory")
                    createRecoveryTables(database)
                    recordMigration(database, RECOVERY_SCHEMA_VERSION, "create_v9_recovery_safety")
                    createReuseTables(database)
                    recordMigration(database, REUSE_SCHEMA_VERSION, "create_v10_reuse_telemetry")
                    createSourceDeletionTables(database)
                    recordMigration(
                        database,
                        SOURCE_DELETION_SCHEMA_VERSION,
                        "create_v11_source_deletion",
                    )
                    createFieldEvidenceTables(database)
                    recordMigration(
                        database,
                        FIELD_EVIDENCE_SCHEMA_VERSION,
                        "create_v12_field_evidence",
                    )
                    createUserConfirmationTables(database)
                    recordMigration(database, SCHEMA_VERSION, "create_v13_user_confirmation_provenance")
                    database.version = SCHEMA_VERSION
                }
                1 -> {
                    migrateV1ToV2(database)
                    migrateV2ToV3(database)
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                2 -> {
                    migrateV2ToV3(database)
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                3 -> {
                    migrateV3ToV4(database)
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                4 -> {
                    migrateV4ToV5(database)
                    migrateV5ToV6(database)
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                5 -> {
                    migrateV5ToV6(database)
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                6 -> {
                    migrateV6ToV7(database)
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                7 -> {
                    migrateV7ToV8(database)
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                LONG_TERM_MEMORY_SCHEMA_VERSION -> {
                    migrateV8ToV9(database)
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                RECOVERY_SCHEMA_VERSION -> {
                    migrateV9ToV10(database)
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                REUSE_SCHEMA_VERSION -> {
                    migrateV10ToV11(database)
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                SOURCE_DELETION_SCHEMA_VERSION -> {
                    migrateV11ToV12(database)
                    migrateV12ToV13(database)
                }
                FIELD_EVIDENCE_SCHEMA_VERSION -> migrateV12ToV13(database)
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
            recordMigration(database, 6, "migrate_v5_to_v6_event_policy_and_day_summary")
            database.version = 6
        }

        private fun migrateV6ToV7(database: SQLiteDatabase) = inMigration(database) {
            createCoverageTables(database)
            recordMigration(database, COVERAGE_SCHEMA_VERSION, "migrate_v6_to_v7_coverage_projection")
            database.version = COVERAGE_SCHEMA_VERSION
        }

        private fun migrateV7ToV8(database: SQLiteDatabase) = inMigration(database) {
            createLongTermMemoryTables(database)
            recordMigration(database, LONG_TERM_MEMORY_SCHEMA_VERSION, "migrate_v7_to_v8_long_term_memory")
            database.version = LONG_TERM_MEMORY_SCHEMA_VERSION
        }

        private fun migrateV8ToV9(database: SQLiteDatabase) = inMigration(database) {
            createRecoveryTables(database)
            recordMigration(database, RECOVERY_SCHEMA_VERSION, "migrate_v8_to_v9_recovery_safety")
            database.version = RECOVERY_SCHEMA_VERSION
        }

        private fun migrateV9ToV10(database: SQLiteDatabase) = inMigration(database) {
            createReuseTables(database)
            recordMigration(database, REUSE_SCHEMA_VERSION, "migrate_v9_to_v10_reuse_telemetry")
            database.version = REUSE_SCHEMA_VERSION
        }

        private fun migrateV10ToV11(database: SQLiteDatabase) = inMigration(database) {
            createSourceDeletionTables(database)
            recordMigration(
                database,
                SOURCE_DELETION_SCHEMA_VERSION,
                "migrate_v10_to_v11_source_deletion",
            )
            database.version = SOURCE_DELETION_SCHEMA_VERSION
        }

        private fun migrateV11ToV12(database: SQLiteDatabase) = inMigration(database) {
            createFieldEvidenceTables(database)
            recordMigration(
                database,
                FIELD_EVIDENCE_SCHEMA_VERSION,
                "migrate_v11_to_v12_field_evidence",
            )
            database.version = FIELD_EVIDENCE_SCHEMA_VERSION
        }

        private fun migrateV12ToV13(database: SQLiteDatabase) = inMigration(database) {
            createUserConfirmationTables(database)
            recordMigration(database, SCHEMA_VERSION, "migrate_v12_to_v13_user_confirmation_provenance")
            database.version = SCHEMA_VERSION
        }

        private fun createRecoveryTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS deletion_watermarks (
                        space_id TEXT NOT NULL,
                        object_type TEXT NOT NULL,
                        object_id TEXT NOT NULL,
                        terminal_revision INTEGER NOT NULL,
                        deleted_at INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        tombstone_digest TEXT NOT NULL,
                        PRIMARY KEY(space_id, object_type, object_id),
                        CHECK(terminal_revision >= 1),
                        CHECK(length(tombstone_digest) = 64)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS backup_checkpoints (
                        backup_id TEXT PRIMARY KEY NOT NULL,
                        space_id TEXT NOT NULL,
                        source_schema_version INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        snapshot_digest TEXT NOT NULL,
                        snapshot_size INTEGER NOT NULL,
                        manifest_mac TEXT NOT NULL,
                        key_material_state TEXT NOT NULL,
                        health_state TEXT NOT NULL,
                        verified_at TEXT NOT NULL,
                        CHECK(snapshot_size > 0),
                        CHECK(length(snapshot_digest) = 64),
                        CHECK(length(manifest_mac) = 64)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS deletion_watermarks_no_delete
                    BEFORE DELETE ON deletion_watermarks
                    BEGIN
                        SELECT RAISE(ABORT, 'deletion watermarks are append-only');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS deletion_watermarks_no_regression
                    BEFORE UPDATE ON deletion_watermarks
                    WHEN NEW.terminal_revision <= OLD.terminal_revision
                    BEGIN
                        SELECT RAISE(ABORT, 'deletion watermark revisions must advance');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_deletion_watermarks_space_deleted ON deletion_watermarks(space_id, deleted_at)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_backup_checkpoints_space_created ON backup_checkpoints(space_id, created_at)",
            )
        }

        private fun createReuseTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS reuse_attempts (
                        attempt_id TEXT NOT NULL,
                        space_id TEXT NOT NULL,
                        intent TEXT NOT NULL,
                        range_state TEXT NOT NULL,
                        result_count_bucket TEXT NOT NULL,
                        event_ref_digests TEXT NOT NULL,
                        memory_ref_digests TEXT NOT NULL,
                        lineage_digests TEXT NOT NULL,
                        exclusions TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, attempt_id),
                        CHECK(length(attempt_id) > 20),
                        CHECK(expires_at > created_at)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS reuse_outcomes (
                        attempt_id TEXT NOT NULL,
                        space_id TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        user_action TEXT NOT NULL,
                        submitted_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, attempt_id),
                        FOREIGN KEY(space_id, attempt_id)
                            REFERENCES reuse_attempts(space_id, attempt_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_reuse_attempts_weekly
                    ON reuse_attempts(space_id, created_at, intent)
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_reuse_outcomes_weekly
                    ON reuse_outcomes(space_id, submitted_at, outcome)
                """.trimIndent(),
            )
            listOf("reuse_attempts", "reuse_outcomes").forEach { table ->
                database.execSQL(
                    """
                        CREATE TRIGGER IF NOT EXISTS ${table}_no_update
                        BEFORE UPDATE ON $table
                        BEGIN
                            SELECT RAISE(ABORT, '$table is append-only');
                        END
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                        CREATE TRIGGER IF NOT EXISTS ${table}_no_delete
                        BEFORE DELETE ON $table
                        BEGIN
                            SELECT RAISE(ABORT, '$table is append-only');
                        END
                    """.trimIndent(),
                )
            }
        }

        private fun createSourceDeletionTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS source_objects (
                        space_id TEXT NOT NULL,
                        source_object_id TEXT NOT NULL,
                        source_kind TEXT NOT NULL,
                        raw_ownership TEXT NOT NULL,
                        state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        PRIMARY KEY(space_id, source_object_id),
                        CHECK(
                            (state = '${LocalSourceState.Active.name}' AND deleted_at IS NULL) OR
                            (state = '${LocalSourceState.Deleted.name}' AND deleted_at IS NOT NULL)
                        )
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS event_source_links (
                        space_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        source_object_id TEXT NOT NULL,
                        created_revision INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        PRIMARY KEY(space_id, event_id, source_object_id),
                        FOREIGN KEY(space_id, event_id)
                            REFERENCES events_current(space_id, event_id),
                        FOREIGN KEY(space_id, source_object_id)
                            REFERENCES source_objects(space_id, source_object_id),
                        CHECK(created_revision >= 1),
                        CHECK(
                            (state = '$SOURCE_LINK_STATE_ACTIVE' AND deleted_at IS NULL) OR
                            (state = '$SOURCE_LINK_STATE_DELETED' AND deleted_at IS NOT NULL)
                        )
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS deletion_jobs (
                        job_id TEXT PRIMARY KEY NOT NULL,
                        space_id TEXT NOT NULL,
                        source_object_id TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        status TEXT NOT NULL,
                        affected_event_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        completed_at INTEGER NOT NULL,
                        CHECK(affected_event_count >= 0),
                        CHECK(completed_at >= created_at)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_source_objects_space_state ON source_objects(space_id, state)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_event_source_links_source ON event_source_links(space_id, source_object_id, state)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_deletion_jobs_space_created ON deletion_jobs(space_id, created_at)",
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS source_objects_no_delete
                    BEFORE DELETE ON source_objects
                    BEGIN
                        SELECT RAISE(ABORT, 'source objects cannot be deleted');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS source_objects_terminal_only
                    BEFORE UPDATE ON source_objects
                    WHEN NEW.space_id != OLD.space_id OR
                         NEW.source_object_id != OLD.source_object_id OR
                         OLD.state != '${LocalSourceState.Active.name}' OR
                         NEW.state != '${LocalSourceState.Deleted.name}' OR
                         NEW.deleted_at IS NULL OR
                         NEW.source_kind != OLD.source_kind OR
                         NEW.raw_ownership != OLD.raw_ownership OR
                         NEW.created_at != OLD.created_at
                    BEGIN
                        SELECT RAISE(ABORT, 'source object update must be terminal');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_source_links_no_delete
                    BEFORE DELETE ON event_source_links
                    BEGIN
                        SELECT RAISE(ABORT, 'event source links cannot be deleted');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_source_links_terminal_only
                    BEFORE UPDATE ON event_source_links
                    WHEN NEW.space_id != OLD.space_id OR
                         NEW.event_id != OLD.event_id OR
                         NEW.source_object_id != OLD.source_object_id OR
                         OLD.state != '$SOURCE_LINK_STATE_ACTIVE' OR
                         NEW.state != '$SOURCE_LINK_STATE_DELETED' OR
                         NEW.deleted_at IS NULL OR
                         NEW.created_revision != OLD.created_revision OR
                         NEW.created_at != OLD.created_at
                    BEGIN
                        SELECT RAISE(ABORT, 'event source link update must be terminal');
                    END
                """.trimIndent(),
            )
            listOf("deletion_jobs").forEach { table ->
                database.execSQL(
                    """
                        CREATE TRIGGER IF NOT EXISTS ${table}_no_update
                        BEFORE UPDATE ON $table
                        BEGIN
                            SELECT RAISE(ABORT, '$table is append-only');
                        END
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                        CREATE TRIGGER IF NOT EXISTS ${table}_no_delete
                        BEFORE DELETE ON $table
                        BEGIN
                            SELECT RAISE(ABORT, '$table is append-only');
                        END
                    """.trimIndent(),
                )
            }
        }

        private fun createFieldEvidenceTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS event_field_evidence (
                        space_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        event_revision INTEGER NOT NULL,
                        evidence_field TEXT NOT NULL,
                        source_object_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        PRIMARY KEY(
                            space_id, event_id, event_revision, evidence_field, source_object_id
                        ),
                        FOREIGN KEY(space_id, event_id)
                            REFERENCES events_current(space_id, event_id),
                        FOREIGN KEY(space_id, source_object_id)
                            REFERENCES source_objects(space_id, source_object_id),
                        CHECK(event_revision >= 1),
                        CHECK(
                            (state = '${EventFieldEvidenceState.Active.name}' AND deleted_at IS NULL) OR
                            (state = '${EventFieldEvidenceState.Deleted.name}' AND deleted_at IS NOT NULL)
                        )
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_event_field_evidence_source
                    ON event_field_evidence(
                        space_id, source_object_id, state, event_id, event_revision
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_field_evidence_no_delete
                    BEFORE DELETE ON event_field_evidence
                    BEGIN
                        SELECT RAISE(ABORT, 'event field evidence cannot be deleted');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_field_evidence_terminal_only
                    BEFORE UPDATE ON event_field_evidence
                    WHEN NEW.space_id != OLD.space_id OR
                         NEW.event_id != OLD.event_id OR
                         NEW.event_revision != OLD.event_revision OR
                         NEW.evidence_field != OLD.evidence_field OR
                         NEW.source_object_id != OLD.source_object_id OR
                         OLD.state != '${EventFieldEvidenceState.Active.name}' OR
                         NEW.state != '${EventFieldEvidenceState.Deleted.name}' OR
                         NEW.deleted_at IS NULL OR
                         NEW.created_at != OLD.created_at
                    BEGIN
                        SELECT RAISE(ABORT, 'event field evidence update must be terminal');
                    END
                """.trimIndent(),
            )
            if (!hasColumn(database, "deletion_jobs", "recomputed_event_count")) {
                database.execSQL(
                    "ALTER TABLE deletion_jobs ADD COLUMN recomputed_event_count INTEGER NOT NULL DEFAULT 0",
                )
            }
            if (!hasColumn(database, "deletion_jobs", "deleted_event_count")) {
                database.execSQL(
                    "ALTER TABLE deletion_jobs ADD COLUMN deleted_event_count INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private fun createUserConfirmationTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS event_user_confirmations (
                        space_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        event_revision INTEGER NOT NULL,
                        confirmation_id TEXT NOT NULL,
                        confirmation_kind TEXT NOT NULL,
                        confirmed_fields_json TEXT NOT NULL,
                        complete_field_set INTEGER NOT NULL,
                        confirmed_at INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        deleted_at INTEGER,
                        PRIMARY KEY(
                            space_id, event_id, event_revision, confirmation_id
                        ),
                        FOREIGN KEY(space_id, event_id)
                            REFERENCES events_current(space_id, event_id),
                        CHECK(event_revision >= 1),
                        CHECK(length(confirmation_id) > 0),
                        CHECK(length(confirmed_fields_json) > 2),
                        CHECK(complete_field_set IN (0, 1)),
                        CHECK(
                            (state = '${UserConfirmationState.Active.name}' AND deleted_at IS NULL) OR
                            (state = '${UserConfirmationState.Deleted.name}' AND deleted_at IS NOT NULL)
                        )
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_event_user_confirmations_current
                    ON event_user_confirmations(
                        space_id, event_id, event_revision, state, complete_field_set
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_user_confirmations_no_delete
                    BEFORE DELETE ON event_user_confirmations
                    BEGIN
                        SELECT RAISE(ABORT, 'event user confirmation cannot be deleted');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS event_user_confirmations_terminal_only
                    BEFORE UPDATE ON event_user_confirmations
                    WHEN NEW.space_id != OLD.space_id OR
                         NEW.event_id != OLD.event_id OR
                         NEW.event_revision != OLD.event_revision OR
                         NEW.confirmation_id != OLD.confirmation_id OR
                         NEW.confirmation_kind != OLD.confirmation_kind OR
                         NEW.confirmed_fields_json != OLD.confirmed_fields_json OR
                         NEW.complete_field_set != OLD.complete_field_set OR
                         NEW.confirmed_at != OLD.confirmed_at OR
                         OLD.state != '${UserConfirmationState.Active.name}' OR
                         NEW.state != '${UserConfirmationState.Deleted.name}' OR
                         NEW.deleted_at IS NULL
                    BEGIN
                        SELECT RAISE(ABORT, 'event user confirmation update must be terminal');
                    END
                """.trimIndent(),
            )
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

        private fun createCoverageTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS coverage_days (
                        space_id TEXT NOT NULL,
                        local_date TEXT NOT NULL,
                        day_id TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        registry_version TEXT NOT NULL,
                        compiled_at TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, day_id),
                        UNIQUE(space_id, local_date)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS coverage_candidate_states (
                        space_id TEXT NOT NULL,
                        day_id TEXT NOT NULL,
                        candidate_id TEXT NOT NULL,
                        lifecycle TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(space_id, day_id, candidate_id),
                        FOREIGN KEY(space_id, day_id) REFERENCES coverage_days(space_id, day_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS coverage_event_links (
                        space_id TEXT NOT NULL,
                        day_id TEXT NOT NULL,
                        candidate_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        created_revision INTEGER NOT NULL,
                        source_object_ids_json TEXT NOT NULL,
                        accepted_at TEXT NOT NULL,
                        lifecycle TEXT NOT NULL,
                        detached_at TEXT,
                        PRIMARY KEY(space_id, day_id, candidate_id),
                        FOREIGN KEY(space_id, day_id, candidate_id)
                            REFERENCES coverage_candidate_states(space_id, day_id, candidate_id),
                        FOREIGN KEY(space_id, event_id)
                            REFERENCES events_current(space_id, event_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS coverage_source_index (
                        space_id TEXT NOT NULL,
                        day_id TEXT NOT NULL,
                        source_object_id TEXT NOT NULL,
                        object_kind TEXT NOT NULL,
                        object_id TEXT NOT NULL,
                        PRIMARY KEY(space_id, day_id, source_object_id, object_kind, object_id),
                        FOREIGN KEY(space_id, day_id) REFERENCES coverage_days(space_id, day_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_coverage_days_space_date ON coverage_days(space_id, local_date)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_coverage_sources_space_source ON coverage_source_index(space_id, source_object_id)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_coverage_links_space_event ON coverage_event_links(space_id, event_id, lifecycle)",
            )
        }

        private fun createLongTermMemoryTables(database: SQLiteDatabase) {
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS long_term_memories_current (
                        space_id TEXT NOT NULL,
                        memory_id TEXT NOT NULL,
                        memory_type TEXT NOT NULL,
                        value_summary TEXT NOT NULL,
                        source_event_id TEXT NOT NULL,
                        source_event_revision INTEGER NOT NULL,
                        evidence_state TEXT NOT NULL,
                        sensitivity TEXT NOT NULL,
                        state TEXT NOT NULL,
                        valid_from INTEGER NOT NULL,
                        valid_until INTEGER,
                        confirmed_at INTEGER,
                        supersedes_memory_id TEXT,
                        superseded_by_memory_id TEXT,
                        invalidated_at INTEGER,
                        invalidation_reason TEXT,
                        revision INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, memory_id),
                        FOREIGN KEY(space_id, source_event_id)
                            REFERENCES events_current(space_id, event_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS long_term_memory_revisions (
                        revision_id TEXT PRIMARY KEY,
                        space_id TEXT NOT NULL,
                        memory_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        base_revision_id TEXT,
                        reason TEXT NOT NULL,
                        snapshot_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE(space_id, memory_id, revision),
                        FOREIGN KEY(space_id, memory_id)
                            REFERENCES long_term_memories_current(space_id, memory_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS long_term_memory_event_dependencies (
                        space_id TEXT NOT NULL,
                        memory_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        event_revision INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(space_id, memory_id, event_id),
                        FOREIGN KEY(space_id, memory_id)
                            REFERENCES long_term_memories_current(space_id, memory_id),
                        FOREIGN KEY(space_id, event_id)
                            REFERENCES events_current(space_id, event_id)
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_long_term_memory_source_version_type
                    ON long_term_memories_current(
                        space_id, source_event_id, source_event_revision, memory_type
                    )
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_long_term_memory_visible
                    ON long_term_memories_current(space_id, state, valid_from, valid_until)
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS idx_long_term_memory_dependency_event
                    ON long_term_memory_event_dependencies(space_id, event_id, state)
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS prevent_long_term_memory_revisions_update
                    BEFORE UPDATE ON long_term_memory_revisions
                    BEGIN
                        SELECT RAISE(ABORT, 'long_term_memory_revisions is append-only');
                    END
                """.trimIndent(),
            )
            database.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS prevent_long_term_memory_revisions_delete
                    BEFORE DELETE ON long_term_memory_revisions
                    BEGIN
                        SELECT RAISE(ABORT, 'long_term_memory_revisions is append-only');
                    END
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

        private fun toAgentFtsExpression(terms: List<String>): String = terms
            .joinToString(" AND ") { token ->
                val phrase = "\"${token.replace("\"", "\"\"")}\""
                "(title:$phrase OR detail:$phrase)"
            }

        private val AGENT_READ_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private const val MAX_SEARCH_TERMS = 16
        private const val MIN_SUMMARY_EVENTS = 2

    }
}
