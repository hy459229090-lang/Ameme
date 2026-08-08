package com.ameme.android.data.local

import android.content.ContentValues
import com.ameme.android.coverage.CandidateEvent
import com.ameme.android.coverage.CandidateEventType
import com.ameme.android.coverage.ContextGap
import com.ameme.android.coverage.ContextGapState
import com.ameme.android.coverage.ContextType
import com.ameme.android.coverage.CoverageCompileResult
import com.ameme.android.coverage.CoverageFactStatus
import com.ameme.android.coverage.CoverageObservation
import com.ameme.android.coverage.CoverageState
import com.ameme.android.coverage.EvidenceField
import com.ameme.android.coverage.GapReason
import com.ameme.android.coverage.ObservationState
import com.ameme.android.coverage.PromptPolicy
import com.ameme.android.coverage.TimePrecision
import com.ameme.android.coverage.TimeRange
import com.ameme.android.coverage.ValueLevel
import com.ameme.android.data.CoverageCandidateLifecycle
import com.ameme.android.data.CoverageEventLink
import com.ameme.android.data.CoverageEventLinkLifecycle
import com.ameme.android.data.PersistedCoverageDay
import java.time.Instant
import java.time.LocalDate
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLCipher-local Coverage projection. Event creation remains in [LocalEventDatabase] so the
 * candidate lifecycle, append-only Revision, current projection, FTS and DayLedger share one
 * transaction. The JSON payload is encrypted by SQLCipher and uses only cross-platform wire values.
 */
internal class LocalCoveragePersistence(
    private val database: SQLiteDatabase,
    private val spaceId: String,
) {
    fun persist(day: PersistedCoverageDay): PersistedCoverageDay {
        val compilation = day.compilation
        require(compilation.schemaVersion == 1) { "unsupported coverage schema" }
        require(compilation.spaceId == spaceId) { "coverage space mismatch" }
        require(day.candidateStates.keys.containsAll(compilation.candidateEvents.map(CandidateEvent::candidateId))) {
            "candidate state is missing"
        }
        require(compilation.candidateEvents.all {
            day.candidateStates[it.candidateId] == CoverageCandidateLifecycle.Open
        }) { "compiler persistence cannot forge a terminal candidate state" }

        val existingIdentity = database.rawQuery(
            "SELECT day_id, owner_id FROM coverage_days WHERE space_id = ? AND local_date = ?",
            arrayOf(spaceId, compilation.localDate.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getString(1)
        }
        require(
            existingIdentity == null ||
                existingIdentity == (compilation.dayId to compilation.ownerId),
        ) { "coverage day identity changed for an existing local date" }

        val dayValues = ContentValues().apply {
            put("space_id", spaceId)
            put("local_date", compilation.localDate.toString())
            put("day_id", compilation.dayId)
            put("owner_id", compilation.ownerId)
            put("registry_version", day.registryVersion)
            put("compiled_at", day.compiledAt.toString())
            put("payload_json", CoverageJsonCodec.encode(compilation))
            put("updated_at", System.currentTimeMillis())
        }
        if (
            database.update(
                "coverage_days",
                dayValues,
                "space_id = ? AND day_id = ?",
                arrayOf(spaceId, compilation.dayId),
            ) == 0
        ) {
            database.insertOrThrow("coverage_days", null, dayValues)
        }

        val incomingCandidateIds = compilation.candidateEvents.map(CandidateEvent::candidateId).toSet()
        incomingCandidateIds.forEach { candidateId ->
            database.insertWithOnConflict(
                "coverage_candidate_states",
                null,
                ContentValues().apply {
                    put("space_id", spaceId)
                    put("day_id", compilation.dayId)
                    put("candidate_id", candidateId)
                    put("lifecycle", CoverageCandidateLifecycle.Open.name)
                    put("updated_at", day.compiledAt.toString())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        if (incomingCandidateIds.isEmpty()) {
            database.execSQL(
                """
                    UPDATE coverage_candidate_states
                    SET lifecycle = ?, updated_at = ?
                    WHERE space_id = ? AND day_id = ? AND lifecycle = ?
                """.trimIndent(),
                arrayOf(
                    CoverageCandidateLifecycle.Deleted.name,
                    day.compiledAt.toString(),
                    spaceId,
                    compilation.dayId,
                    CoverageCandidateLifecycle.Open.name,
                ),
            )
        } else {
            val placeholders = incomingCandidateIds.joinToString(",") { "?" }
            database.execSQL(
                """
                    UPDATE coverage_candidate_states
                    SET lifecycle = ?, updated_at = ?
                    WHERE space_id = ? AND day_id = ? AND lifecycle = ?
                      AND candidate_id NOT IN ($placeholders)
                """.trimIndent(),
                arrayOf<Any>(
                    CoverageCandidateLifecycle.Deleted.name,
                    day.compiledAt.toString(),
                    spaceId,
                    compilation.dayId,
                    CoverageCandidateLifecycle.Open.name,
                    *incomingCandidateIds.toTypedArray(),
                ),
            )
        }
        rebuildSourceIndex(compilation)
        return load(compilation.localDate) ?: error("persisted coverage day is unreadable")
    }

    fun load(localDate: LocalDate): PersistedCoverageDay? {
        val row = database.rawQuery(
            """
                SELECT day_id, owner_id, registry_version, compiled_at, payload_json
                FROM coverage_days WHERE space_id = ? AND local_date = ?
            """.trimIndent(),
            arrayOf(spaceId, localDate.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                CoverageDayRow(
                    dayId = cursor.getString(0),
                    ownerId = cursor.getString(1),
                    registryVersion = cursor.getString(2),
                    compiledAt = Instant.parse(cursor.getString(3)),
                    payload = cursor.getString(4),
                )
            }
        } ?: return null
        val compilation = CoverageJsonCodec.decode(row.payload)
        check(
            compilation.dayId == row.dayId &&
                compilation.ownerId == row.ownerId &&
                compilation.spaceId == spaceId &&
                compilation.localDate == localDate,
        ) { "coverage payload identity mismatch" }
        val states = database.rawQuery(
            """
                SELECT candidate_id, lifecycle FROM coverage_candidate_states
                WHERE space_id = ? AND day_id = ?
            """.trimIndent(),
            arrayOf(spaceId, row.dayId),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), CoverageCandidateLifecycle.valueOf(cursor.getString(1)))
                }
            }
        }
        check(compilation.candidateEvents.all { states.containsKey(it.candidateId) }) {
            "coverage candidate lifecycle is missing"
        }
        check(states.filterKeys { id -> compilation.candidateEvents.none { it.candidateId == id } }
            .values.none { it == CoverageCandidateLifecycle.Open }) {
            "removed coverage candidate remained open"
        }
        return PersistedCoverageDay(
            compilation = compilation,
            registryVersion = row.registryVersion,
            compiledAt = row.compiledAt,
            candidateStates = states,
        )
    }

    fun requireOpenCandidate(dayId: String, candidateId: String): CandidateEvent {
        val localDate = database.rawQuery(
            "SELECT local_date FROM coverage_days WHERE space_id = ? AND day_id = ?",
            arrayOf(spaceId, dayId),
        ).use { cursor -> if (cursor.moveToFirst()) LocalDate.parse(cursor.getString(0)) else null }
            ?: throw IllegalArgumentException("coverage day not found")
        val persisted = load(localDate) ?: error("coverage day is unreadable")
        require(persisted.candidateStates[candidateId] == CoverageCandidateLifecycle.Open) {
            "coverage candidate is not open"
        }
        return persisted.compilation.candidateEvents.firstOrNull { it.candidateId == candidateId }
            ?: throw IllegalArgumentException("coverage candidate not found")
    }

    fun existingLink(dayId: String, candidateId: String): CoverageEventLink? = database.rawQuery(
        """
            SELECT event_id, created_revision, source_object_ids_json, accepted_at, lifecycle
            FROM coverage_event_links
            WHERE space_id = ? AND day_id = ? AND candidate_id = ?
        """.trimIndent(),
        arrayOf(spaceId, dayId, candidateId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else CoverageEventLink(
            dayId = dayId,
            spaceId = spaceId,
            candidateId = candidateId,
            eventId = cursor.getString(0),
            createdRevision = cursor.getInt(1),
            sourceObjectIds = jsonStringSet(cursor.getString(2)),
            acceptedAt = Instant.parse(cursor.getString(3)),
            lifecycle = CoverageEventLinkLifecycle.valueOf(cursor.getString(4)),
        )
    }

    fun consumeAndLink(
        dayId: String,
        candidate: CandidateEvent,
        eventId: String,
        revision: Int,
        acceptedAt: Instant,
    ): CoverageEventLink {
        check(
            database.update(
                "coverage_candidate_states",
                ContentValues().apply {
                    put("lifecycle", CoverageCandidateLifecycle.Consumed.name)
                    put("updated_at", acceptedAt.toString())
                },
                "space_id = ? AND day_id = ? AND candidate_id = ? AND lifecycle = ?",
                arrayOf(
                    spaceId,
                    dayId,
                    candidate.candidateId,
                    CoverageCandidateLifecycle.Open.name,
                ),
            ) == 1,
        ) { "coverage candidate state changed" }
        val link = CoverageEventLink(
            dayId = dayId,
            spaceId = spaceId,
            candidateId = candidate.candidateId,
            eventId = eventId,
            createdRevision = revision,
            sourceObjectIds = candidate.sourceObjectIds,
            lifecycle = CoverageEventLinkLifecycle.Active,
            acceptedAt = acceptedAt,
        )
        database.insertOrThrow(
            "coverage_event_links",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("day_id", dayId)
                put("candidate_id", candidate.candidateId)
                put("event_id", eventId)
                put("created_revision", revision)
                put("source_object_ids_json", jsonStringArray(candidate.sourceObjectIds))
                put("accepted_at", acceptedAt.toString())
                put("lifecycle", CoverageEventLinkLifecycle.Active.name)
            },
        )
        return link
    }

    fun dismiss(dayId: String, candidateId: String, updatedAt: Instant): Boolean =
        database.update(
            "coverage_candidate_states",
            ContentValues().apply {
                put("lifecycle", CoverageCandidateLifecycle.Dismissed.name)
                put("updated_at", updatedAt.toString())
            },
            "space_id = ? AND day_id = ? AND candidate_id = ? AND lifecycle = ?",
            arrayOf(spaceId, dayId, candidateId, CoverageCandidateLifecycle.Open.name),
        ) == 1

    fun detachEvent(eventId: String, detachedAt: Instant) {
        database.execSQL(
            """
                UPDATE coverage_event_links
                SET lifecycle = ?, detached_at = ?
                WHERE space_id = ? AND event_id = ? AND lifecycle = ?
            """.trimIndent(),
            arrayOf(
                CoverageEventLinkLifecycle.Detached.name,
                detachedAt.toString(),
                spaceId,
                eventId,
                CoverageEventLinkLifecycle.Active.name,
            ),
        )
    }

    /**
     * Makes candidates backed by a terminal source non-actionable. The encrypted day payload and
     * source index remain as audit history; future compiles containing the watermark are rejected
     * by LocalEventDatabase before this projection is replaced.
     */
    fun terminalizeSource(sourceObjectId: String, deletedAt: Instant) {
        database.execSQL(
            """
                UPDATE coverage_candidate_states
                SET lifecycle = ?, updated_at = ?
                WHERE space_id = ? AND lifecycle = ? AND EXISTS (
                    SELECT 1 FROM coverage_source_index source
                    WHERE source.space_id = coverage_candidate_states.space_id
                      AND source.day_id = coverage_candidate_states.day_id
                      AND source.object_kind = 'candidate'
                      AND source.object_id = coverage_candidate_states.candidate_id
                      AND source.source_object_id = ?
                )
            """.trimIndent(),
            arrayOf(
                CoverageCandidateLifecycle.Deleted.name,
                deletedAt.toString(),
                spaceId,
                CoverageCandidateLifecycle.Open.name,
                sourceObjectId,
            ),
        )
    }

    fun terminalizeAll(deletedAt: Instant) {
        database.execSQL(
            """
                UPDATE coverage_candidate_states
                SET lifecycle = ?, updated_at = ?
                WHERE space_id = ? AND lifecycle = ?
            """.trimIndent(),
            arrayOf(
                CoverageCandidateLifecycle.Deleted.name,
                deletedAt.toString(),
                spaceId,
                CoverageCandidateLifecycle.Open.name,
            ),
        )
        database.execSQL(
            """
                UPDATE coverage_event_links
                SET lifecycle = ?, detached_at = ?
                WHERE space_id = ? AND lifecycle = ?
            """.trimIndent(),
            arrayOf(
                CoverageEventLinkLifecycle.Detached.name,
                deletedAt.toString(),
                spaceId,
                CoverageEventLinkLifecycle.Active.name,
            ),
        )
    }

    private fun rebuildSourceIndex(compilation: CoverageCompileResult) {
        database.delete(
            "coverage_source_index",
            "space_id = ? AND day_id = ?",
            arrayOf(spaceId, compilation.dayId),
        )
        compilation.observations.forEach { observation ->
            observation.sourceObjectIds.forEach { sourceId ->
                insertSourceIndex(compilation.dayId, sourceId, "observation", observation.coverageObservationId)
            }
        }
        compilation.candidateEvents.forEach { candidate ->
            candidate.sourceObjectIds.forEach { sourceId ->
                insertSourceIndex(compilation.dayId, sourceId, "candidate", candidate.candidateId)
            }
        }
    }

    private fun insertSourceIndex(dayId: String, sourceId: String, kind: String, objectId: String) {
        database.insertOrThrow(
            "coverage_source_index",
            null,
            ContentValues().apply {
                put("space_id", spaceId)
                put("day_id", dayId)
                put("source_object_id", sourceId)
                put("object_kind", kind)
                put("object_id", objectId)
            },
        )
    }

    private data class CoverageDayRow(
        val dayId: String,
        val ownerId: String,
        val registryVersion: String,
        val compiledAt: Instant,
        val payload: String,
    )
}

private object CoverageJsonCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(result: CoverageCompileResult): String = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("day_id", result.dayId)
        put("owner_id", result.ownerId)
        put("space_id", result.spaceId)
        put("local_date", result.localDate.toString())
        put("timezone", result.timezone)
        put("coverage_state", result.coverageState.wireValue)
        put("covered_context_types", jsonArray(result.coveredContextTypes.map(ContextType::wireValue)))
        put("unknown_context_types", jsonArray(result.unknownContextTypes.map(ContextType::wireValue)))
        put("observations", JSONArray().apply {
            result.observations.forEach { observation -> put(observationJson(observation)) }
        })
        put("candidate_events", JSONArray().apply {
            result.candidateEvents.forEach { candidate -> put(candidateJson(candidate)) }
        })
        put("context_gaps", JSONArray().apply {
            result.contextGaps.forEach { gap -> put(gapJson(gap)) }
        })
    }.toString()

    fun decode(value: String): CoverageCompileResult {
        val json = JSONObject(value)
        check(json.getInt("schema_version") == SCHEMA_VERSION) { "unsupported coverage payload" }
        return CoverageCompileResult(
            dayId = json.getString("day_id"),
            ownerId = json.getString("owner_id"),
            spaceId = json.getString("space_id"),
            localDate = LocalDate.parse(json.getString("local_date")),
            timezone = json.getString("timezone"),
            coverageState = wireEnum(json.getString("coverage_state"), CoverageState.entries) { it.wireValue },
            coveredContextTypes = jsonStringSet(json.getJSONArray("covered_context_types"))
                .mapTo(mutableSetOf()) { wireEnum(it, ContextType.entries) { item -> item.wireValue } },
            unknownContextTypes = jsonStringSet(json.getJSONArray("unknown_context_types"))
                .mapTo(mutableSetOf()) { wireEnum(it, ContextType.entries) { item -> item.wireValue } },
            observations = json.getJSONArray("observations").objects(::observation),
            candidateEvents = json.getJSONArray("candidate_events").objects(::candidate),
            contextGaps = json.getJSONArray("context_gaps").objects(::gap),
        )
    }

    private fun observationJson(value: CoverageObservation) = JSONObject().apply {
        put("schema_version", value.schemaVersion)
        put("id", value.coverageObservationId)
        put("owner_id", value.ownerId)
        put("space_id", value.spaceId)
        put("local_date", value.localDate.toString())
        put("capability_id", value.capabilityId)
        put("source_ids", jsonArray(value.sourceObjectIds))
        put("contexts", jsonArray(value.contextTypes.map(ContextType::wireValue)))
        put("fields", jsonArray(value.observedFields.map(EvidenceField::wireValue)))
        put("fact_status", value.factStatus.wireValue)
        put("state", value.state.wireValue)
        put("observed_at", value.observedAt.toString())
        value.timeRange?.let { put("time_range", timeRangeJson(it)) }
    }

    private fun observation(json: JSONObject) = CoverageObservation(
        schemaVersion = json.getInt("schema_version"),
        coverageObservationId = json.getString("id"),
        ownerId = json.getString("owner_id"),
        spaceId = json.getString("space_id"),
        localDate = LocalDate.parse(json.getString("local_date")),
        capabilityId = json.getString("capability_id"),
        sourceObjectIds = jsonStringSet(json.getJSONArray("source_ids")),
        contextTypes = jsonStringSet(json.getJSONArray("contexts"))
            .mapTo(mutableSetOf()) { wireEnum(it, ContextType.entries) { item -> item.wireValue } },
        observedFields = jsonStringSet(json.getJSONArray("fields"))
            .mapTo(mutableSetOf()) { wireEnum(it, EvidenceField.entries) { item -> item.wireValue } },
        factStatus = wireEnum(json.getString("fact_status"), CoverageFactStatus.entries) { it.wireValue },
        state = wireEnum(json.getString("state"), ObservationState.entries) { it.wireValue },
        observedAt = Instant.parse(json.getString("observed_at")),
        timeRange = json.optJSONObject("time_range")?.let(::timeRange),
    )

    private fun candidateJson(value: CandidateEvent) = JSONObject().apply {
        put("id", value.candidateId)
        put("source_ids", jsonArray(value.sourceObjectIds))
        put("capability_id", value.capabilityId)
        put("event_type", value.eventType.wireValue)
        put("title", value.title)
        value.timeRange?.let { put("time_range", timeRangeJson(it)) }
        put("fact_status", value.factStatus.wireValue)
        put("confidence", value.confidence)
        put("fields", jsonArray(value.observedFields.map(EvidenceField::wireValue)))
    }

    private fun candidate(json: JSONObject) = CandidateEvent(
        candidateId = json.getString("id"),
        sourceObjectIds = jsonStringSet(json.getJSONArray("source_ids")),
        capabilityId = json.getString("capability_id"),
        eventType = wireEnum(json.getString("event_type"), CandidateEventType.entries) { it.wireValue },
        title = json.getString("title"),
        timeRange = json.optJSONObject("time_range")?.let(::timeRange),
        factStatus = wireEnum(json.getString("fact_status"), CoverageFactStatus.entries) { it.wireValue },
        confidence = json.getDouble("confidence"),
        observedFields = jsonStringSet(json.getJSONArray("fields"))
            .mapTo(mutableSetOf()) { wireEnum(it, EvidenceField.entries) { item -> item.wireValue } },
    )

    private fun gapJson(value: ContextGap) = JSONObject().apply {
        put("schema_version", value.schemaVersion)
        put("id", value.contextGapId)
        put("owner_id", value.ownerId)
        put("space_id", value.spaceId)
        put("local_date", value.localDate.toString())
        put("context_type", value.contextType.wireValue)
        put("reason", value.reason.wireValue)
        put("value_level", value.valueLevel.wireValue)
        put("prompt_policy", value.promptPolicy.wireValue)
        put("capability_ids", jsonArray(value.capabilityIds))
        put("state", value.state.wireValue)
        put("created_at", value.createdAt.toString())
        value.timeRange?.let { put("time_range", timeRangeJson(it)) }
    }

    private fun gap(json: JSONObject) = ContextGap(
        schemaVersion = json.getInt("schema_version"),
        contextGapId = json.getString("id"),
        ownerId = json.getString("owner_id"),
        spaceId = json.getString("space_id"),
        localDate = LocalDate.parse(json.getString("local_date")),
        contextType = wireEnum(json.getString("context_type"), ContextType.entries) { it.wireValue },
        reason = wireEnum(json.getString("reason"), GapReason.entries) { it.wireValue },
        valueLevel = wireEnum(json.getString("value_level"), ValueLevel.entries) { it.wireValue },
        promptPolicy = wireEnum(json.getString("prompt_policy"), PromptPolicy.entries) { it.wireValue },
        capabilityIds = jsonStringSet(json.getJSONArray("capability_ids")),
        state = wireEnum(json.getString("state"), ContextGapState.entries) { it.wireValue },
        createdAt = Instant.parse(json.getString("created_at")),
        timeRange = json.optJSONObject("time_range")?.let(::timeRange),
    )

    private fun timeRangeJson(value: TimeRange) = JSONObject().apply {
        put("start", value.start.toString())
        value.end?.let { put("end", it.toString()) }
        value.timezone?.let { put("timezone", it) }
        put("precision", value.precision.wireValue)
    }

    private fun timeRange(json: JSONObject) = TimeRange(
        start = Instant.parse(json.getString("start")),
        end = json.optString("end").takeIf(String::isNotEmpty)?.let(Instant::parse),
        timezone = json.optString("timezone").takeIf(String::isNotEmpty),
        precision = wireEnum(json.getString("precision"), TimePrecision.entries) { it.wireValue },
    )

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
        buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }
}

private fun jsonArray(values: Iterable<String>) = JSONArray().apply { values.forEach(::put) }
private fun jsonStringArray(values: Iterable<String>) = jsonArray(values).toString()
private fun jsonStringSet(value: String): Set<String> = jsonStringSet(JSONArray(value))
private fun jsonStringSet(value: JSONArray): Set<String> =
    buildSet { for (index in 0 until value.length()) add(value.getString(index)) }
private fun <T> wireEnum(value: String, entries: Iterable<T>, wire: (T) -> String): T =
    entries.firstOrNull { wire(it) == value } ?: error("unknown coverage wire value $value")
