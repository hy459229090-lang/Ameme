package com.ameme.android.data

import java.time.Duration
import java.time.Instant

/**
 * Content-free security evidence for one authenticated Agent Local Node request phase.
 *
 * Request bodies, search terms, payload/idempotency digests, object identifiers, source
 * locators, raw paths, secrets, and unrestricted exception text are deliberately absent.
 */
data class AgentAccessAuditRecord(
    val auditId: String,
    val traceId: String,
    val phase: AgentAccessAuditPhase,
    val callerId: String,
    val purpose: String,
    val spaces: List<String>,
    val dataTypes: List<String>,
    val operation: String,
    val resultCode: String,
    val objectCountBucket: AgentAccessAuditObjectCountBucket,
    val occurredAt: Instant,
    val retentionUntil: Instant,
) {
    init {
        require(AUDIT_ID.matches(auditId)) { "Agent access audit id is invalid" }
        require(TRACE_ID.matches(traceId)) { "Agent access audit trace id is invalid" }
        require(IDENTIFIER.matches(callerId)) { "Agent access audit caller is invalid" }
        require(IDENTIFIER.matches(purpose)) { "Agent access audit purpose is invalid" }
        require(spaces.isNotEmpty() && spaces.size <= MAX_SCOPE_ITEMS) {
            "Agent access audit spaces are invalid"
        }
        require(spaces == spaces.distinct().sorted() && spaces.all(IDENTIFIER::matches)) {
            "Agent access audit spaces must be canonical"
        }
        require(dataTypes.isNotEmpty() && dataTypes.size <= MAX_SCOPE_ITEMS) {
            "Agent access audit data types are invalid"
        }
        require(dataTypes == dataTypes.distinct().sorted() && dataTypes.all(IDENTIFIER::matches)) {
            "Agent access audit data types must be canonical"
        }
        require(IDENTIFIER.matches(operation)) { "Agent access audit operation is invalid" }
        require(RESULT_CODE.matches(resultCode)) { "Agent access audit result code is invalid" }
        require(retentionUntil == occurredAt.plus(AgentAccessAuditPolicy.retention)) {
            "Agent access audit retention is invalid"
        }
        require(
            (phase == AgentAccessAuditPhase.Started &&
                resultCode == AgentAccessAuditPolicy.RESULT_STARTED &&
                objectCountBucket == AgentAccessAuditObjectCountBucket.Unknown) ||
                (phase == AgentAccessAuditPhase.Completed &&
                    resultCode != AgentAccessAuditPolicy.RESULT_STARTED),
        ) { "Agent access audit phase/result pairing is invalid" }
    }

    companion object {
        private const val MAX_SCOPE_ITEMS = 8
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val RESULT_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
        private val AUDIT_ID =
            Regex("audit_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val TRACE_ID =
            Regex("trace_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

enum class AgentAccessAuditPhase {
    Started,
    Completed,
}

enum class AgentAccessAuditObjectCountBucket(val wireValue: String) {
    Zero("0"),
    One("1"),
    TwoToTen("2_10"),
    ElevenToOneHundred("11_100"),
    MoreThanOneHundred("101_plus"),
    Unknown("unknown");

    companion object {
        fun fromCount(count: Int?): AgentAccessAuditObjectCountBucket = when {
            count == null -> Unknown
            count < 0 -> throw IllegalArgumentException("Agent access audit count is invalid")
            count == 0 -> Zero
            count == 1 -> One
            count <= 10 -> TwoToTen
            count <= 100 -> ElevenToOneHundred
            else -> MoreThanOneHundred
        }
    }
}

object AgentAccessAuditPolicy {
    val retention: Duration = Duration.ofDays(180)
    const val RESULT_STARTED = "ATTEMPT_STARTED"
    const val RESULT_OK = "OK"
    const val MAX_RECENT_RECORDS = 100
    const val MAX_RETAINED_RECORDS = 50_000
}

interface AgentAccessAuditRepository {
    fun recentAgentAccessAudit(
        limit: Int = 20,
        at: Instant = Instant.now(),
    ): List<AgentAccessAuditRecord>

    fun pruneExpiredAgentAccessAudit(at: Instant = Instant.now()): Int
}
