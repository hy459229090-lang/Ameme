package com.ameme.android.data.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.data.local.SyntheticDatabaseKeyProvider
import com.ameme.android.data.AgentAccessAuditObjectCountBucket
import com.ameme.android.data.AgentAccessAuditPhase
import com.ameme.android.data.AgentAccessAuditPolicy
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLocalNodeEndpointInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile = File(context.cacheDir, "agent-local-node-endpoint-${System.nanoTime()}.db")
    private val key = ByteArray(32) { index -> (index + 17).toByte() }
    private val clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC)

    @After
    fun cleanUp() {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .forEach(File::delete)
        key.fill(0)
    }

    @Test
    fun verifiedCreateAndRevisionPersistAndReplayAcrossSqlCipherRepositoryReopen() = runBlocking {
        val repository = openRepository()
        val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
            repository = repository,
            repositorySpaceId = SPACE_ID,
            verifiedSession = VerifiedAgentLocalNodeSession(
                callerId = "agent_synthetic",
                grantId = "grant_synthetic",
                purpose = "autonomous_memory",
                allowedSpaces = setOf("space_personal", SPACE_ID),
                allowedMemoryTypes = setOf("event", "revision"),
                allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
                allowedSensitivities = setOf("personal"),
                allowedDataClasses = setOf("structured"),
                expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
            ),
            idempotencyRegistry = repository.durableAgentIdempotencyRegistry(),
            accessAuditSink = repository.durableAgentAccessAuditSink(),
            clock = clock,
        )
        val payload = AgentLocalNodeCreateEventPayload(
            space = SPACE_ID,
            memoryType = "event",
            content = CONTENT,
            eventType = "result",
            evidenceState = "observed",
            factStatus = "confirmed",
            sensitivity = "personal",
            dataClass = "structured",
            now = NOW,
            eventTime = NOW,
        )
        val bytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
        val request = try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = "req_android_sqlcipher",
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = setOf(SPACE_ID),
                    memoryTypes = setOf("event"),
                    operation = "create_event",
                    idempotencySlot = GOLDEN_SLOT,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(bytes),
                ),
                payload = bytes,
            )
        } finally {
            bytes.fill(0)
        }

        val response = endpoint.exchange(request)
        assertEquals(AgentLocalNodeStatus.Ok, response.status)
        val resultBytes = response.payloadCopy()
        val result = try {
            AgentLocalNodeApplicationCodec.decodeCreateEventResult(resultBytes)
        } finally {
            resultBytes.fill(0)
            response.close()
        }
        val appendPayload = AgentLocalNodeAppendRevisionPayload(
            eventId = result.eventId,
            space = SPACE_ID,
            memoryType = "revision",
            content = REVISION_CONTENT,
            evidenceState = "user_asserted",
            factStatus = "user_asserted",
            now = NOW,
        )
        val appendBytes = AgentLocalNodeApplicationCodec.encodeAppendRevision(appendPayload)
        val appendRequest = try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = "req_android_sqlcipher_append",
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = setOf(SPACE_ID),
                    memoryTypes = setOf("revision"),
                    operation = "append_revision",
                    idempotencySlot = APPEND_SLOT,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(appendBytes),
                ),
                payload = appendBytes,
            )
        } finally {
            appendBytes.fill(0)
        }
        val appendResponse = endpoint.exchange(appendRequest)
        val appendResultBytes = appendResponse.payloadCopy()
        val appendResult = try {
            AgentLocalNodeApplicationCodec.decodeAppendRevisionResult(appendResultBytes)
        } finally {
            appendResultBytes.fill(0)
            appendResponse.close()
        }
        assertEquals(result.eventId, appendResult.targetEventId)
        assertEquals(2, appendResult.revision)
        repository.close()

        var compensationRevisionId = ""
        openRepository().use { reopened ->
            val stored = reopened.loadActiveEvents().single { it.id == result.eventId }
            assertEquals(REVISION_CONTENT, stored.detail)
            assertEquals("AgentAutonomous", stored.sourceLabel)
            assertEquals(FactStatus.UserAsserted, stored.factStatus)
            assertEquals(EvidenceState.UserAsserted, stored.evidenceState)
            assertEquals(2, stored.revision)
            assertTrue(databaseFile.length() > 0L)
            val audit = reopened.recentAgentAccessAudit(limit = 10, at = clock.instant())
            assertEquals(4, audit.size)
            assertEquals(2, audit.groupBy { it.traceId }.size)
            audit.groupBy { it.traceId }.values.forEach { trace ->
                assertEquals(
                    setOf(AgentAccessAuditPhase.Started, AgentAccessAuditPhase.Completed),
                    trace.mapTo(mutableSetOf()) { it.phase },
                )
            }
            val appendAudit = audit.single {
                it.phase == AgentAccessAuditPhase.Completed &&
                    it.operation == "append_revision"
            }
            assertEquals(AgentAccessAuditPolicy.RESULT_OK, appendAudit.resultCode)
            assertEquals(AgentAccessAuditObjectCountBucket.One, appendAudit.objectCountBucket)
            assertFalse(audit.joinToString().contains(CONTENT))
            assertFalse(audit.joinToString().contains(REVISION_CONTENT))
            assertFalse(audit.joinToString().contains(result.eventId))

            val replayEndpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = reopened,
                repositorySpaceId = SPACE_ID,
                verifiedSession = VerifiedAgentLocalNodeSession(
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    allowedSpaces = setOf(SPACE_ID),
                    allowedMemoryTypes = setOf("event", "revision"),
                    allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
                    allowedSensitivities = setOf("personal"),
                    allowedDataClasses = setOf("structured"),
                    expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
                ),
                idempotencyRegistry = reopened.durableAgentIdempotencyRegistry(),
                accessAuditSink = reopened.durableAgentAccessAuditSink(),
                clock = clock,
            )
            val replayBytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
            val replayRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_replay",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("event"),
                        operation = "create_event",
                        idempotencySlot = GOLDEN_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(replayBytes),
                    ),
                    payload = replayBytes,
                )
            } finally {
                replayBytes.fill(0)
            }
            replayEndpoint.exchange(replayRequest).use { replayResponse ->
                assertEquals(AgentLocalNodeStatus.Ok, replayResponse.status)
                val replayResultBytes = replayResponse.payloadCopy()
                try {
                    assertEquals(result.eventId, AgentLocalNodeApplicationCodec.decodeCreateEventResult(replayResultBytes).eventId)
                } finally {
                    replayResultBytes.fill(0)
                }
            }
            val appendReplayBytes = AgentLocalNodeApplicationCodec.encodeAppendRevision(appendPayload)
            val appendReplayRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_append_replay",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("revision"),
                        operation = "append_revision",
                        idempotencySlot = APPEND_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(appendReplayBytes),
                    ),
                    payload = appendReplayBytes,
                )
            } finally {
                appendReplayBytes.fill(0)
            }
            replayEndpoint.exchange(appendReplayRequest).use { replayResponse ->
                assertEquals(AgentLocalNodeStatus.Ok, replayResponse.status)
                val replayResultBytes = replayResponse.payloadCopy()
                try {
                    val replayResult = AgentLocalNodeApplicationCodec.decodeAppendRevisionResult(replayResultBytes)
                    assertEquals(appendResult.eventRevisionId, replayResult.eventRevisionId)
                    assertEquals(2, replayResult.revision)
                } finally {
                    replayResultBytes.fill(0)
                }
            }
            val undoPayload = AgentLocalNodeUndoCapturePayload(
                undoToken = APPEND_SLOT,
                space = SPACE_ID,
                memoryType = "revision",
                now = NOW,
            )
            val undoBytes = AgentLocalNodeApplicationCodec.encodeUndoCapture(undoPayload)
            val undoRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_revision_undo",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("revision"),
                        operation = "undo_capture",
                        idempotencySlot = UNDO_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(undoBytes),
                    ),
                    payload = undoBytes,
                )
            } finally {
                undoBytes.fill(0)
            }
            replayEndpoint.exchange(undoRequest).use { undoResponse ->
                assertEquals(AgentLocalNodeStatus.Ok, undoResponse.status)
                val undoResultBytes = undoResponse.payloadCopy()
                try {
                    val undoResult =
                        AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(undoResultBytes)
                    assertEquals("revision", undoResult.undoneObjectType)
                    assertEquals(appendResult.eventRevisionId, undoResult.undoneObjectId)
                    compensationRevisionId = undoResult.compensationRevisionId.orEmpty()
                    assertTrue(compensationRevisionId.startsWith("rev_"))
                } finally {
                    undoResultBytes.fill(0)
                }
            }
            assertEquals(1, reopened.loadActiveEvents().count { it.id == result.eventId })
            val restored = reopened.loadActiveEvents().single { it.id == result.eventId }
            assertEquals(3, restored.revision)
            assertEquals(CONTENT, restored.detail)
            assertEquals(FactStatus.Confirmed, restored.factStatus)
            assertEquals(EvidenceState.Observed, restored.evidenceState)
        }

        openRepository().use { reopened ->
            val replayEndpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = reopened,
                repositorySpaceId = SPACE_ID,
                verifiedSession = VerifiedAgentLocalNodeSession(
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    allowedSpaces = setOf(SPACE_ID),
                    allowedMemoryTypes = setOf("event", "revision"),
                    allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
                    allowedSensitivities = setOf("personal"),
                    allowedDataClasses = setOf("structured"),
                    expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
                ),
                idempotencyRegistry = reopened.durableAgentIdempotencyRegistry(),
                accessAuditSink = reopened.durableAgentAccessAuditSink(),
                clock = clock,
            )
            val undoPayload = AgentLocalNodeUndoCapturePayload(
                undoToken = APPEND_SLOT,
                space = SPACE_ID,
                memoryType = "revision",
                now = NOW,
            )
            val replayBytes = AgentLocalNodeApplicationCodec.encodeUndoCapture(undoPayload)
            val replayRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_revision_undo_replay",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("revision"),
                        operation = "undo_capture",
                        idempotencySlot = UNDO_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(replayBytes),
                    ),
                    payload = replayBytes,
                )
            } finally {
                replayBytes.fill(0)
            }
            replayEndpoint.exchange(replayRequest).use { replayResponse ->
                assertEquals(AgentLocalNodeStatus.Ok, replayResponse.status)
                val resultBytes = replayResponse.payloadCopy()
                try {
                    assertEquals(
                        compensationRevisionId,
                        AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(resultBytes)
                            .compensationRevisionId,
                    )
                } finally {
                    resultBytes.fill(0)
                }
            }
            assertEquals(3, reopened.loadActiveEvents().single { it.id == result.eventId }.revision)
        }
    }

    @Test
    fun exactEventUndoTombstoneAndResultReplayAcrossSqlCipherRepositoryReopen() = runBlocking {
        val payload = AgentLocalNodeCreateEventPayload(
            space = SPACE_ID,
            memoryType = "event",
            content = "Synthetic SQLCipher Agent capture to undo.",
            eventType = "result",
            evidenceState = "observed",
            factStatus = "confirmed",
            sensitivity = "personal",
            dataClass = "structured",
            now = NOW,
            eventTime = NOW,
        )
        var eventId = ""
        openRepository().use { repository ->
            val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = repository,
                repositorySpaceId = SPACE_ID,
                verifiedSession = VerifiedAgentLocalNodeSession(
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    allowedSpaces = setOf(SPACE_ID),
                    allowedMemoryTypes = setOf("event", "revision"),
                    allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
                    allowedSensitivities = setOf("personal"),
                    allowedDataClasses = setOf("structured"),
                    expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
                ),
                idempotencyRegistry = repository.durableAgentIdempotencyRegistry(),
                accessAuditSink = repository.durableAgentAccessAuditSink(),
                clock = clock,
            )
            val createBytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
            val createRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_event_undo_seed",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("event"),
                        operation = "create_event",
                        idempotencySlot = GOLDEN_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(createBytes),
                    ),
                    payload = createBytes,
                )
            } finally {
                createBytes.fill(0)
            }
            endpoint.exchange(createRequest).use { createResponse ->
                val resultBytes = createResponse.payloadCopy()
                try {
                    eventId =
                        AgentLocalNodeApplicationCodec.decodeCreateEventResult(resultBytes).eventId
                } finally {
                    resultBytes.fill(0)
                }
            }
            val undoPayload = AgentLocalNodeUndoCapturePayload(
                undoToken = GOLDEN_SLOT,
                space = SPACE_ID,
                memoryType = "event",
                now = NOW,
            )
            val undoBytes = AgentLocalNodeApplicationCodec.encodeUndoCapture(undoPayload)
            val undoRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_event_undo",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("event"),
                        operation = "undo_capture",
                        idempotencySlot = UNDO_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(undoBytes),
                    ),
                    payload = undoBytes,
                )
            } finally {
                undoBytes.fill(0)
            }
            endpoint.exchange(undoRequest).use { undoResponse ->
                val resultBytes = undoResponse.payloadCopy()
                try {
                    val result = AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(resultBytes)
                    assertEquals(eventId, result.targetEventId)
                    assertEquals("event", result.undoneObjectType)
                    assertEquals(eventId, result.undoneObjectId)
                } finally {
                    resultBytes.fill(0)
                }
            }
            assertTrue(repository.loadActiveEvents().none { it.id == eventId })
        }

        openRepository().use { reopened ->
            val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = reopened,
                repositorySpaceId = SPACE_ID,
                verifiedSession = VerifiedAgentLocalNodeSession(
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    allowedSpaces = setOf(SPACE_ID),
                    allowedMemoryTypes = setOf("event", "revision"),
                    allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
                    allowedSensitivities = setOf("personal"),
                    allowedDataClasses = setOf("structured"),
                    expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
                ),
                idempotencyRegistry = reopened.durableAgentIdempotencyRegistry(),
                accessAuditSink = reopened.durableAgentAccessAuditSink(),
                clock = clock,
            )
            val undoPayload = AgentLocalNodeUndoCapturePayload(
                undoToken = GOLDEN_SLOT,
                space = SPACE_ID,
                memoryType = "event",
                now = NOW,
            )
            val replayBytes = AgentLocalNodeApplicationCodec.encodeUndoCapture(undoPayload)
            val replayRequest = try {
                AgentLocalNodeRequest(
                    control = AgentLocalNodeControl(
                        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                        requestId = "req_android_sqlcipher_event_undo_replay",
                        callerId = "agent_synthetic",
                        grantId = "grant_synthetic",
                        purpose = "autonomous_memory",
                        spaces = setOf(SPACE_ID),
                        memoryTypes = setOf("event"),
                        operation = "undo_capture",
                        idempotencySlot = UNDO_SLOT,
                        payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(replayBytes),
                    ),
                    payload = replayBytes,
                )
            } finally {
                replayBytes.fill(0)
            }
            endpoint.exchange(replayRequest).use { replayResponse ->
                val resultBytes = replayResponse.payloadCopy()
                try {
                    assertEquals(
                        eventId,
                        AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(resultBytes)
                            .undoneObjectId,
                    )
                } finally {
                    resultBytes.fill(0)
                }
            }
            assertTrue(reopened.loadActiveEvents().none { it.id == eventId })
        }
    }

    @Test
    fun boundedVisibleEventsPersistsSensitivityAndDeletionBoundariesAcrossReopen() = runBlocking {
        var confidentialEventId = ""
        openRepository().use { repository ->
            val session = VerifiedAgentLocalNodeSession(
                callerId = "agent_synthetic",
                grantId = "grant_synthetic",
                purpose = "autonomous_memory",
                allowedSpaces = setOf(SPACE_ID),
                allowedMemoryTypes = setOf("event"),
                allowedOperations = setOf("create_event", "visible_events"),
                allowedSensitivities = setOf(
                    "personal",
                    "confidential",
                    "restricted",
                ),
                allowedDataClasses = setOf("structured"),
                expiresAt = Instant.parse("2026-07-14T06:00:00Z"),
            )
            val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = repository,
                repositorySpaceId = SPACE_ID,
                verifiedSession = session,
                idempotencyRegistry = repository.durableAgentIdempotencyRegistry(),
                accessAuditSink = repository.durableAgentAccessAuditSink(),
                clock = clock,
            )
            suspend fun create(
                content: String,
                sensitivity: String,
                eventTime: String,
                slotCharacter: Char,
            ): String {
                val payload = AgentLocalNodeCreateEventPayload(
                    space = SPACE_ID,
                    memoryType = "event",
                    content = content,
                    eventType = "result",
                    evidenceState = "observed",
                    factStatus = "confirmed",
                    sensitivity = sensitivity,
                    dataClass = "structured",
                    now = NOW,
                    eventTime = eventTime,
                )
                val bytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
                val request = try {
                    AgentLocalNodeRequest(
                        control = AgentLocalNodeControl(
                            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                            requestId = "req_visible_create_$sensitivity",
                            callerId = "agent_synthetic",
                            grantId = "grant_synthetic",
                            purpose = "autonomous_memory",
                            spaces = setOf(SPACE_ID),
                            memoryTypes = setOf("event"),
                            operation = "create_event",
                            idempotencySlot = "idem_${slotCharacter.toString().repeat(64)}",
                            payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(bytes),
                        ),
                        payload = bytes,
                    )
                } finally {
                    bytes.fill(0)
                }
                return endpoint.exchange(request).use { response ->
                    val resultBytes = response.payloadCopy()
                    try {
                        AgentLocalNodeApplicationCodec.decodeCreateEventResult(resultBytes).eventId
                    } finally {
                        resultBytes.fill(0)
                    }
                }
            }
            suspend fun visible(
                requestId: String,
                sessionEndpoint: MemoryRepositoryAgentLocalNodeEndpoint = endpoint,
                query: String = "SQLCIPHER_VISIBLE",
                allowHighRisk: Boolean = false,
                startAt: String = "2026-07-14T02:30:00Z",
                endAt: String = "2026-07-14T06:00:00Z",
                limit: Int = 10,
            ): AgentLocalNodeVisibleEventsResult {
                val payload = AgentLocalNodeVisibleEventsPayload(
                    spaces = listOf(SPACE_ID),
                    memoryTypes = listOf("event"),
                    query = query,
                    allowHighRisk = allowHighRisk,
                    startAt = startAt,
                    endAt = endAt,
                    limit = limit,
                )
                val bytes = AgentLocalNodeApplicationCodec.encodeVisibleEvents(payload)
                val request = try {
                    AgentLocalNodeRequest(
                        control = AgentLocalNodeControl(
                            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                            requestId = requestId,
                            callerId = "agent_synthetic",
                            grantId = "grant_synthetic",
                            purpose = "autonomous_memory",
                            spaces = setOf(SPACE_ID),
                            memoryTypes = setOf("event"),
                            operation = "visible_events",
                            idempotencySlot = "idem_${"9".repeat(64)}",
                            payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(bytes),
                        ),
                        payload = bytes,
                    )
                } finally {
                    bytes.fill(0)
                }
                return sessionEndpoint.exchange(request).use { response ->
                    assertEquals(AgentLocalNodeStatus.Ok, response.status)
                    val resultBytes = response.payloadCopy()
                    try {
                        AgentLocalNodeApplicationCodec.decodeVisibleEventsResult(resultBytes)
                    } finally {
                        resultBytes.fill(0)
                    }
                }
            }

            create(
                "SQLCIPHER_VISIBLE PERSONAL",
                "personal",
                "2026-07-14T03:00:00Z",
                '1',
            )
            confidentialEventId = create(
                "SQLCIPHER_VISIBLE CONFIDENTIAL",
                "confidential",
                "2026-07-14T04:00:00Z",
                '2',
            )
            create(
                "SQLCIPHER_VISIBLE RESTRICTED",
                "restricted",
                "2026-07-14T05:00:00Z",
                '3',
            )

            val bounded = visible(
                requestId = "req_visible_bounded",
                endAt = "2026-07-14T04:30:00Z",
                limit = 1,
            )
            assertEquals(listOf(confidentialEventId), bounded.events.map { it.eventId })
            assertFalse(bounded.riskFiltered)
            assertTrue(bounded.events.all { it.dataClass == "structured" })

            val policyFiltered = visible(requestId = "req_visible_policy_filtered")
            assertEquals(2, policyFiltered.events.size)
            assertTrue(policyFiltered.riskFiltered)
            assertTrue(policyFiltered.events.none { it.sensitivity == "restricted" })

            val personalEndpoint =
                MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                    repository = repository,
                    repositorySpaceId = SPACE_ID,
                    verifiedSession = session.copy(allowedSensitivities = setOf("personal")),
                    idempotencyRegistry = repository.durableAgentIdempotencyRegistry(),
                    accessAuditSink = repository.durableAgentAccessAuditSink(),
                    clock = clock,
                )
            val unauthorized = visible(
                requestId = "req_visible_unauthorized",
                sessionEndpoint = personalEndpoint,
            )
            assertEquals(1, unauthorized.events.size)
            assertFalse(unauthorized.riskFiltered)

            val allowed = visible(
                requestId = "req_visible_high_risk_allowed",
                allowHighRisk = true,
            )
            assertEquals(3, allowed.events.size)
            assertTrue(allowed.events.any { it.sensitivity == "restricted" })
            val hiddenSourceLabel = visible(
                requestId = "req_visible_hidden_source_label",
                query = "AgentAutonomous",
                allowHighRisk = true,
            )
            assertTrue(hiddenSourceLabel.events.isEmpty())
            assertTrue(repository.deleteEvent(confidentialEventId))
        }

        openRepository().use { reopened ->
            val result = reopened.readAgentVisibleEvents(
                query = "CONFIDENTIAL",
                startAt = Instant.parse("2026-07-14T00:00:00Z"),
                endAt = Instant.parse("2026-07-14T23:59:59Z"),
                timeZone = ZoneOffset.UTC,
                allowedSensitivities = setOf(com.ameme.android.domain.Sensitivity.Confidential),
                allowHighRisk = false,
                limit = 10,
            )
            assertTrue(result.events.none { it.id == confidentialEventId })
            assertFalse(result.riskFiltered)
        }
    }

    private fun openRepository(): LocalMemoryRepository = LocalMemoryRepository.open(
        context = context,
        spaceId = SPACE_ID,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = databaseFile,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private companion object {
        const val SPACE_ID = "space_work"
        const val NOW = "2026-07-14T04:00:00Z"
        const val CONTENT = "Synthetic SQLCipher Agent capture."
        const val REVISION_CONTENT = "Synthetic SQLCipher Agent revision."
        const val GOLDEN_SLOT = "idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13"
        const val APPEND_SLOT = "idem_9bf77d38ad5078308ea7f36bf05ffbcc126f402d019652b34435258b800cf1c9"
        const val UNDO_SLOT = "idem_0da22aed7cfd32171578109197a5c3a9303467e0eb31b49af6d1bd841b77e6ca"
    }
}
