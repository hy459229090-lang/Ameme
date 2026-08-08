package com.ameme.android.data.local

import android.content.ContentValues
import com.ameme.android.data.ReuseContext
import com.ameme.android.data.ReuseOutcome
import com.ameme.android.data.ReuseOutcomeSubmission
import com.ameme.android.data.ReuseResultCountBucket
import com.ameme.android.data.ReuseTelemetryAggregate
import com.ameme.android.data.ReuseUserAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

/**
 * Content-free local reuse telemetry.
 *
 * This table never receives query text, Event/Memory content, source locators, raw object IDs, or
 * raw lineage IDs. Per-attempt salted SHA-256 digests retain auditable "what kind of lineage was
 * used" evidence without creating a second content/index surface.
 */
internal class LocalReusePersistence(
    private val database: SQLiteDatabase,
    private val spaceId: String,
) {
    fun recordAttempt(context: ReuseContext) {
        val eventDigests = context.references
            .filter { it.objectType.wireValue == "event" }
            .map { digest(context.attemptId, "event", it.objectId, it.revision.toString()) }
        val memoryDigests = context.references
            .filter { it.objectType.wireValue == "long_term_memory" }
            .map { digest(context.attemptId, "memory", it.objectId, it.revision.toString()) }
        val lineageDigests = context.references
            .filter { it.sourceEventId != null && it.sourceEventRevision != null }
            .map {
                digest(
                    context.attemptId,
                    "lineage",
                    it.sourceEventId!!,
                    it.sourceEventRevision!!.toString(),
                )
            }
        database.insertOrThrow(
            "reuse_attempts",
            null,
            ContentValues().apply {
                put("attempt_id", context.attemptId)
                put("space_id", spaceId)
                put("intent", context.intent.wireValue)
                put("range_state", context.rangeState.wireValue)
                put("result_count_bucket", ReuseResultCountBucket.from(context.references.size).wireValue)
                put("event_ref_digests", JSONArray(eventDigests).toString())
                put("memory_ref_digests", JSONArray(memoryDigests).toString())
                put("lineage_digests", JSONArray(lineageDigests).toString())
                put("exclusions", JSONArray(context.exclusions.map { it.wireValue }.sorted()).toString())
                put("created_at", context.createdAt.toEpochMilli())
                put("expires_at", context.expiresAt.toEpochMilli())
            },
        )
    }

    fun recordOutcome(submission: ReuseOutcomeSubmission): Boolean {
        val attemptCreatedAt = database.rawQuery(
            """
                SELECT created_at FROM reuse_attempts
                WHERE space_id = ? AND attempt_id = ?
            """.trimIndent(),
            arrayOf(spaceId, submission.attemptId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: return false
        if (submission.submittedAt.toEpochMilli() < attemptCreatedAt) return false
        val values = ContentValues().apply {
            put("attempt_id", submission.attemptId)
            put("space_id", spaceId)
            put("outcome", submission.outcome.wireValue)
            put("user_action", submission.userAction.wireValue)
            put("submitted_at", submission.submittedAt.toEpochMilli())
        }
        return runCatching {
            database.insertOrThrow("reuse_outcomes", null, values)
            true
        }.getOrElse { false }
    }

    fun aggregates(since: Instant): List<ReuseTelemetryAggregate> = database.rawQuery(
        """
            SELECT a.intent, o.outcome, o.user_action, a.result_count_bucket, COUNT(*)
            FROM reuse_attempts a
            LEFT JOIN reuse_outcomes o
              ON o.space_id = a.space_id AND o.attempt_id = a.attempt_id
            WHERE a.space_id = ? AND a.created_at >= ?
            GROUP BY a.intent, o.outcome, o.user_action, a.result_count_bucket
            ORDER BY a.intent, o.outcome, o.user_action, a.result_count_bucket
        """.trimIndent(),
        arrayOf(spaceId, since.toEpochMilli().toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ReuseTelemetryAggregate(
                        intent = com.ameme.android.data.ReuseIntent.entries.first {
                            it.wireValue == cursor.getString(0)
                        },
                        outcome = cursor.getString(1)?.let { value ->
                            ReuseOutcome.entries.first { it.wireValue == value }
                        },
                        userAction = cursor.getString(2)?.let { value ->
                            ReuseUserAction.entries.first { it.wireValue == value }
                        },
                        resultCountBucket = ReuseResultCountBucket.entries.first {
                            it.wireValue == cursor.getString(3)
                        },
                        attemptCount = cursor.getInt(4),
                    ),
                )
            }
        }
    }

    fun helpfulCount(since: Instant): Int = database.rawQuery(
        """
            SELECT COUNT(*)
            FROM reuse_outcomes
            WHERE space_id = ? AND outcome = ? AND submitted_at >= ?
        """.trimIndent(),
        arrayOf(spaceId, ReuseOutcome.Useful.wireValue, since.toEpochMilli().toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun digest(vararg values: String): String {
        val payload = values.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
    }
}
