package com.ameme.android.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentAccessAuditRepositoryTest {
    @Test
    fun objectCountsUseBoundedNonIdentifyingBuckets() {
        assertEquals(
            AgentAccessAuditObjectCountBucket.Unknown,
            AgentAccessAuditObjectCountBucket.fromCount(null),
        )
        assertEquals(
            AgentAccessAuditObjectCountBucket.Zero,
            AgentAccessAuditObjectCountBucket.fromCount(0),
        )
        assertEquals(
            AgentAccessAuditObjectCountBucket.One,
            AgentAccessAuditObjectCountBucket.fromCount(1),
        )
        assertEquals(
            AgentAccessAuditObjectCountBucket.TwoToTen,
            AgentAccessAuditObjectCountBucket.fromCount(10),
        )
        assertEquals(
            AgentAccessAuditObjectCountBucket.ElevenToOneHundred,
            AgentAccessAuditObjectCountBucket.fromCount(100),
        )
        assertEquals(
            AgentAccessAuditObjectCountBucket.MoreThanOneHundred,
            AgentAccessAuditObjectCountBucket.fromCount(101),
        )
    }

    @Test
    fun recordRequiresCanonicalScopeExactRetentionAndValidPhasePair() {
        val at = Instant.parse("2026-07-29T08:00:00Z")
        val valid = record(at = at)
        assertEquals(at.plus(AgentAccessAuditPolicy.retention), valid.retentionUntil)

        assertFailsArgument {
            record(at = at, spaces = listOf("space_work", "space_personal"))
        }
        assertFailsArgument {
            record(
                at = at,
                retentionUntil = at.plus(AgentAccessAuditPolicy.retention).minusMillis(1),
            )
        }
        assertFailsArgument {
            record(
                at = at,
                phase = AgentAccessAuditPhase.Started,
                resultCode = AgentAccessAuditPolicy.RESULT_OK,
            )
        }
    }

    @Test
    fun recoveryPlannerKeepsCandidateEvidenceAndAddsPostBackupCompletion() {
        val retainedAt = Instant.parse("2026-07-29T08:00:00Z")
        val traceId = "trace_00000000-0000-4000-8000-000000000020"
        val started = record(
            auditId = "audit_00000000-0000-4000-8000-000000000021",
            traceId = traceId,
            at = retainedAt.minusSeconds(10),
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
        )
        val completed = record(
            auditId = "audit_00000000-0000-4000-8000-000000000022",
            traceId = traceId,
            at = retainedAt.minusSeconds(5),
        )

        val plan = AgentAccessAuditRecoveryPlanner.plan(
            candidateRecords = listOf(started),
            liveRetainedRecords = listOf(completed, started),
            retainedAt = retainedAt,
        )

        assertEquals(listOf(completed), plan.insertions)
        assertEquals(2, plan.retainedRecordCount)
        assertEquals(
            AgentAccessAuditRecoveryPlanner.digest(listOf(started, completed)),
            plan.retainedDigest,
        )
        assertEquals(
            plan.retainedDigest,
            AgentAccessAuditRecoveryPlanner.digest(listOf(completed, started)),
        )
    }

    @Test
    fun recoveryPlannerFailsClosedOnTracePhaseConflictOrExpiredLiveEvidence() {
        val retainedAt = Instant.parse("2026-07-29T08:00:00Z")
        val candidate = record(
            auditId = "audit_00000000-0000-4000-8000-000000000031",
            traceId = "trace_00000000-0000-4000-8000-000000000030",
            at = retainedAt.minusSeconds(10),
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
        )
        val conflicting = candidate.copy(
            auditId = "audit_00000000-0000-4000-8000-000000000032",
        )
        assertFailsArgument {
            AgentAccessAuditRecoveryPlanner.plan(
                candidateRecords = listOf(candidate),
                liveRetainedRecords = listOf(conflicting),
                retainedAt = retainedAt,
            )
        }
        val expired = record(
            auditId = "audit_00000000-0000-4000-8000-000000000033",
            traceId = "trace_00000000-0000-4000-8000-000000000034",
            at = retainedAt.minus(AgentAccessAuditPolicy.retention),
        )
        assertFailsArgument {
            AgentAccessAuditRecoveryPlanner.plan(
                candidateRecords = emptyList(),
                liveRetainedRecords = listOf(expired),
                retainedAt = retainedAt,
            )
        }
    }

    @Test
    fun recoveryPlannerIgnoresExpiredCandidateEvidenceForConflictsAndCapacity() {
        val retainedAt = Instant.parse("2026-07-29T08:00:00Z")
        val traceId = "trace_00000000-0000-4000-8000-000000000040"
        val expiredCandidate = record(
            auditId = "audit_00000000-0000-4000-8000-000000000041",
            traceId = traceId,
            at = retainedAt.minus(AgentAccessAuditPolicy.retention),
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
        )
        val retainedLive = record(
            auditId = "audit_00000000-0000-4000-8000-000000000042",
            traceId = traceId,
            at = retainedAt.minusSeconds(10),
            phase = AgentAccessAuditPhase.Started,
            resultCode = AgentAccessAuditPolicy.RESULT_STARTED,
        )

        val plan = AgentAccessAuditRecoveryPlanner.plan(
            candidateRecords = buildList {
                add(expiredCandidate)
                repeat(AgentAccessAuditPolicy.MAX_RETAINED_RECORDS) { index ->
                    val suffix = "%012x".format(index + 0x1000)
                    add(
                    expiredCandidate.copy(
                            auditId = "audit_00000000-0000-4000-8000-$suffix",
                            traceId = "trace_00000000-0000-4000-8000-$suffix",
                        ),
                    )
                }
            },
            liveRetainedRecords = listOf(retainedLive),
            retainedAt = retainedAt,
        )

        assertEquals(listOf(retainedLive), plan.insertions)
        assertEquals(1, plan.retainedRecordCount)
        assertEquals(
            AgentAccessAuditRecoveryPlanner.digest(listOf(retainedLive)),
            plan.retainedDigest,
        )
    }

    private fun record(
        auditId: String = "audit_00000000-0000-4000-8000-000000000001",
        traceId: String = "trace_00000000-0000-4000-8000-000000000002",
        at: Instant,
        spaces: List<String> = listOf("space_personal", "space_work"),
        phase: AgentAccessAuditPhase = AgentAccessAuditPhase.Completed,
        resultCode: String = AgentAccessAuditPolicy.RESULT_OK,
        retentionUntil: Instant = at.plus(AgentAccessAuditPolicy.retention),
    ) = AgentAccessAuditRecord(
        auditId = auditId,
        traceId = traceId,
        phase = phase,
        callerId = "agent_synthetic",
        purpose = "autonomous_memory",
        spaces = spaces,
        dataTypes = listOf("event", "revision"),
        operation = "visible_events",
        resultCode = resultCode,
        objectCountBucket = if (phase == AgentAccessAuditPhase.Started) {
            AgentAccessAuditObjectCountBucket.Unknown
        } else {
            AgentAccessAuditObjectCountBucket.One
        },
        occurredAt = at,
        retentionUntil = retentionUntil,
    )

    private fun assertFailsArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected invariant rejection.
        }
    }
}
