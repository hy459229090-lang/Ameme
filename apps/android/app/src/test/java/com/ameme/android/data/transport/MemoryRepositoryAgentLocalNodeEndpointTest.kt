package com.ameme.android.data.transport

import com.ameme.android.data.FakeMemoryRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryAgentLocalNodeEndpointTest {
    private val clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC)

    @Test
    fun goldenCreatePayloadAndResultMatchCanonicalV1Digests() {
        val payload = goldenPayload()
        val encoded = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)

        assertEquals(GOLDEN_PAYLOAD_JSON, encoded.decodeToString())
        assertEquals(GOLDEN_PAYLOAD_DIGEST, AgentLocalNodeApplicationCodec.payloadDigest(payload))
        assertEquals(payload, AgentLocalNodeApplicationCodec.decodeCreateEvent(encoded))

        val control = control(payload = payload)
        val encodedRequest = AgentLocalNodeApplicationCodec.encodeCreateEventRequestEnvelope(control, payload)
        assertEquals(GOLDEN_REQUEST_JSON, encodedRequest.decodeToString())
        assertEquals(GOLDEN_REQUEST_DIGEST, AgentLocalNodeApplicationCodec.canonicalDigest(encodedRequest))

        val result = AgentLocalNodeCreateEventResult(eventId = "evt_synthetic_001", revision = 1)
        val encodedResult = AgentLocalNodeApplicationCodec.encodeCreateEventResult(result)
        assertEquals(GOLDEN_RESULT_JSON, encodedResult.decodeToString())
        assertEquals(GOLDEN_RESULT_DIGEST, AgentLocalNodeApplicationCodec.canonicalDigest(encodedResult))
        assertEquals(result, AgentLocalNodeApplicationCodec.decodeCreateEventResult(encodedResult))
        val request = AgentLocalNodeRequest(control, encoded)
        val response = AgentLocalNodeResponse.validatedFor(
            request = request,
            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
            requestId = control.requestId,
            status = AgentLocalNodeStatus.Ok,
            resultDigest = GOLDEN_RESULT_DIGEST,
            payload = encodedResult,
        )
        assertEquals(
            GOLDEN_RESPONSE_JSON,
            AgentLocalNodeApplicationCodec.encodeResponseEnvelope(response).decodeToString(),
        )
    }

    @Test
    fun verifiedGrantSupersetCapturesIntoRepositoryAndReturnsRevisionOne() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val initialCount = repository.loadActiveEvents().size
        val endpoint = enabledEndpoint(repository)
        val request = request(goldenPayload())

        val response = endpoint.exchange(request)

        assertEquals(AgentLocalNodeStatus.Ok, response.status)
        assertNull(response.error)
        val resultBytes = response.payloadCopy()
        val result = AgentLocalNodeApplicationCodec.decodeCreateEventResult(resultBytes)
        assertEquals(1, result.revision)
        assertEquals("event", result.objectType)
        assertEquals(
            AgentLocalNodeApplicationCodec.canonicalDigest(resultBytes),
            response.resultDigest,
        )
        assertEquals(initialCount + 1, repository.loadActiveEvents().size)
        val captured = repository.loadActiveEvents().single { it.id == result.eventId }
        assertEquals("Synthetic milestone was verified by a tool.", captured.detail)
        assertEquals("AgentAutonomous", captured.sourceLabel)
        assertFailsState { request.payloadCopy() }
        resultBytes.fill(0)
        response.close()
        assertFailsState { response.payloadCopy() }
    }

    @Test
    fun sameSlotAndDigestReplaysWhileChangedSemanticsConflict() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val initialCount = repository.loadActiveEvents().size
        val registry = InMemoryTestIdempotencyRegistry()
        val endpoint = enabledEndpoint(repository, registry)

        val first = endpoint.exchange(request(goldenPayload(), requestId = "req_first"))
        val replay = endpoint.exchange(request(goldenPayload(), requestId = "req_replay"))
        val changed = goldenPayload().copy(content = "Changed synthetic semantics.")
        val conflict = endpoint.exchange(
            request(changed, requestId = "req_changed", idempotencySlot = GOLDEN_SLOT),
        )

        assertEquals(
            resultOf(first).eventId,
            resultOf(replay).eventId,
        )
        assertEquals(initialCount + 1, repository.loadActiveEvents().size)
        assertError(conflict, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
        assertArrayEquals(byteArrayOf(), conflict.payloadCopy())
    }

    @Test
    fun scopeMismatchAndGrantDenialsDoNotWriteOrReturnContent() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val initialCount = repository.loadActiveEvents().size
        val endpoint = enabledEndpoint(repository)
        val payload = goldenPayload()

        val crossSpace = endpoint.exchange(
            request(payload, spaces = setOf("space_personal"), requestId = "req_cross_space"),
        )
        val crossType = endpoint.exchange(
            request(payload, memoryTypes = setOf("revision"), requestId = "req_cross_type"),
        )
        val wrongCaller = endpoint.exchange(
            request(payload, callerId = "agent_other", requestId = "req_wrong_caller"),
        )

        assertError(crossSpace, AgentLocalNodeErrorCode.SCOPE_MISMATCH)
        assertError(crossType, AgentLocalNodeErrorCode.SCOPE_MISMATCH)
        assertError(wrongCaller, AgentLocalNodeErrorCode.AUTH_REQUIRED)
        listOf(crossSpace, crossType, wrongCaller).forEach { response ->
            assertArrayEquals(byteArrayOf(), response.payloadCopy())
            assertFalse(response.toString().contains(payload.content))
        }
        assertEquals(initialCount, repository.loadActiveEvents().size)
    }

    @Test
    fun localAccessGrantPolicyIsEnforcedBeforePayloadScopeChecks() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val grant = AgentAccessGrant(
            schemaVersion = AgentAccessGrant.CURRENT_SCHEMA_VERSION,
            grantId = "grant_synthetic",
            ownerId = "user_synthetic",
            callerId = "agent_synthetic",
            purposes = setOf("autonomous_memory"),
            spaces = setOf("space_work"),
            dataTypes = setOf("event"),
            notBefore = Instant.parse("2026-07-14T03:00:00Z"),
            expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
            status = AgentAccessGrantStatus.Active,
            createdAt = Instant.parse("2026-07-14T03:00:00Z"),
        )
        val endpoint = enabledEndpoint(
            repository,
            session = verifiedSession().copy(accessGrant = grant),
        )

        val approved = endpoint.exchange(request(goldenPayload(), requestId = "req_grant_allowed"))
        val denied = endpoint.exchange(
            request(
                goldenPayload(),
                spaces = setOf("space_personal"),
                requestId = "req_grant_expansion",
            ),
        )

        assertEquals(AgentLocalNodeStatus.Ok, approved.status)
        assertError(denied, AgentLocalNodeErrorCode.SPACE_DENIED)
    }

    @Test
    fun tamperedDigestFailsBeforeRepositoryAndUnsupportedOperationIsStable() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val initialCount = repository.loadActiveEvents().size
        val endpoint = enabledEndpoint(repository)
        val payload = goldenPayload().copy(content = "Changed without digest update.")

        val tampered = endpoint.exchange(
            request(payload, requestId = "req_tampered", payloadDigest = GOLDEN_PAYLOAD_DIGEST),
        )
        val unsupported = enabledEndpoint(repository).exchange(
            request(goldenPayload(), requestId = "req_get", operation = "get_event"),
        )
        val unauthenticatedProbe = MemoryRepositoryAgentLocalNodeEndpoint.closed(clock).exchange(
            request(goldenPayload(), requestId = "req_closed_get", operation = "get_event"),
        )

        assertError(tampered, AgentLocalNodeErrorCode.PAYLOAD_DIGEST_MISMATCH)
        assertError(unsupported, AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED)
        assertError(unauthenticatedProbe, AgentLocalNodeErrorCode.AUTH_REQUIRED)
        assertEquals(initialCount, repository.loadActiveEvents().size)
    }

    @Test
    fun closedEndpointAndExpiredOrRevokedGrantFailWithoutExistenceDetail() = runBlocking {
        val payload = goldenPayload()
        val closed = MemoryRepositoryAgentLocalNodeEndpoint.closed(clock).exchange(
            request(payload, requestId = "req_closed"),
        )
        val expiredRepository = FakeMemoryRepository(clock)
        val expired = enabledEndpoint(
            expiredRepository,
            session = verifiedSession(expiresAt = Instant.parse(NOW)),
        ).exchange(request(payload, requestId = "req_expired"))
        val revokedRepository = FakeMemoryRepository(clock)
        val revoked = enabledEndpoint(
            revokedRepository,
            session = verifiedSession(grantState = VerifiedAgentLocalNodeGrantState.Revoked),
        ).exchange(request(payload, requestId = "req_revoked"))

        assertError(closed, AgentLocalNodeErrorCode.AUTH_REQUIRED)
        assertError(expired, AgentLocalNodeErrorCode.GRANT_EXPIRED)
        assertError(revoked, AgentLocalNodeErrorCode.GRANT_REVOKED)
        listOf(closed, expired, revoked).forEach { response ->
            assertArrayEquals(byteArrayOf(), response.payloadCopy())
            assertFalse(response.toString().contains(payload.content))
        }
    }

    @Test
    fun sensitivityAndDataClassMustComeFromVerifiedSession() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val initialCount = repository.loadActiveEvents().size
        val endpoint = enabledEndpoint(repository)
        val confidential = endpoint.exchange(
            request(goldenPayload().copy(sensitivity = "confidential"), requestId = "req_confidential"),
        )
        val noStructuredGrant = enabledEndpoint(
            repository,
            session = verifiedSession().copy(allowedDataClasses = emptySet()),
        ).exchange(request(goldenPayload(), requestId = "req_no_structured_grant"))

        assertError(confidential, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        assertError(noStructuredGrant, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        assertEquals(initialCount, repository.loadActiveEvents().size)
    }

    @Test
    fun strictCanonicalJsonRejectsDuplicateKeysEscapedNulAndLoneSurrogate() {
        val canonical = GOLDEN_PAYLOAD_JSON
        val duplicate = canonical.replaceFirst(
            "{",
            "{\"space\":\"space_work\",",
        ).encodeToByteArray()
        val escapedNul = canonical.replace("Synthetic milestone", "Synthetic\\u0000milestone")
            .encodeToByteArray()
        val loneSurrogate = canonical.replace("Synthetic milestone", "Synthetic\\ud800milestone")
            .encodeToByteArray()

        assertFailsArgument { AgentLocalNodeApplicationCodec.decodeCreateEvent(duplicate) }
        assertFailsArgument { AgentLocalNodeApplicationCodec.decodeCreateEvent(escapedNul) }
        assertFailsArgument { AgentLocalNodeApplicationCodec.decodeCreateEvent(loneSurrogate) }
    }

    private fun enabledEndpoint(
        repository: FakeMemoryRepository,
        registry: AgentLocalNodeIdempotencyRegistry = InMemoryTestIdempotencyRegistry(),
        session: VerifiedAgentLocalNodeSession = verifiedSession(),
    ): MemoryRepositoryAgentLocalNodeEndpoint =
        MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
            repository = repository,
            repositorySpaceId = "space_work",
            verifiedSession = session,
            idempotencyRegistry = registry,
            clock = clock,
        )

    private fun verifiedSession(
        expiresAt: Instant = Instant.parse("2026-07-14T05:00:00Z"),
        grantState: VerifiedAgentLocalNodeGrantState = VerifiedAgentLocalNodeGrantState.Active,
    ) = VerifiedAgentLocalNodeSession(
        callerId = "agent_synthetic",
        grantId = "grant_synthetic",
        purpose = "autonomous_memory",
        allowedSpaces = setOf("space_personal", "space_work"),
        allowedMemoryTypes = setOf("event", "revision"),
        allowedOperations = setOf("create_event"),
        allowedSensitivities = setOf("personal"),
        allowedDataClasses = setOf("structured"),
        expiresAt = expiresAt,
        grantState = grantState,
    )

    private fun request(
        payload: AgentLocalNodeCreateEventPayload,
        requestId: String = "req_create_event",
        callerId: String = "agent_synthetic",
        spaces: Set<String> = setOf("space_work"),
        memoryTypes: Set<String> = setOf("event"),
        operation: String = "create_event",
        idempotencySlot: String = GOLDEN_SLOT,
        payloadDigest: String? = null,
    ): AgentLocalNodeRequest {
        val encoded = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
        val digest = payloadDigest ?: AgentLocalNodeApplicationCodec.canonicalDigest(encoded)
        return try {
            AgentLocalNodeRequest(
                control = control(
                    payload = payload,
                    requestId = requestId,
                    callerId = callerId,
                    spaces = spaces,
                    memoryTypes = memoryTypes,
                    operation = operation,
                    idempotencySlot = idempotencySlot,
                    payloadDigest = digest,
                ),
                payload = encoded,
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun control(
        payload: AgentLocalNodeCreateEventPayload,
        requestId: String = "req_create_event",
        callerId: String = "agent_synthetic",
        spaces: Set<String> = setOf("space_work"),
        memoryTypes: Set<String> = setOf("event"),
        operation: String = "create_event",
        idempotencySlot: String = GOLDEN_SLOT,
        payloadDigest: String = AgentLocalNodeApplicationCodec.payloadDigest(payload),
    ) = AgentLocalNodeControl(
        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
        requestId = requestId,
        callerId = callerId,
        grantId = "grant_synthetic",
        purpose = "autonomous_memory",
        spaces = spaces,
        memoryTypes = memoryTypes,
        operation = operation,
        idempotencySlot = idempotencySlot,
        payloadDigest = payloadDigest,
    )

    private fun goldenPayload() = AgentLocalNodeCreateEventPayload(
        space = "space_work",
        memoryType = "event",
        content = "Synthetic milestone was verified by a tool.",
        eventType = "result",
        evidenceState = "observed",
        factStatus = "confirmed",
        sensitivity = "personal",
        dataClass = "structured",
        now = NOW,
        eventTime = NOW,
    )

    private fun resultOf(response: AgentLocalNodeResponse): AgentLocalNodeCreateEventResult {
        val bytes = response.payloadCopy()
        return try {
            AgentLocalNodeApplicationCodec.decodeCreateEventResult(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun assertError(response: AgentLocalNodeResponse, code: AgentLocalNodeErrorCode) {
        assertEquals(AgentLocalNodeStatus.Error, response.status)
        assertEquals(code, response.error?.code)
        assertEquals(
            code == AgentLocalNodeErrorCode.TEMPORARILY_UNAVAILABLE ||
                code == AgentLocalNodeErrorCode.INTERNAL_ERROR,
            response.error?.retryable,
        )
        assertNull(response.resultDigest)
    }

    private fun assertFailsArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected strict payload rejection.
        }
    }

    private fun assertFailsState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected after payload clearing.
        }
    }

    private class InMemoryTestIdempotencyRegistry : AgentLocalNodeIdempotencyRegistry {
        private data class Stored(
            val digest: String,
            val outcome: AgentLocalNodeCaptureOutcome,
        )

        private val stored = mutableMapOf<AgentLocalNodeIdempotencyBinding, Stored>()

        @Synchronized
        override fun resolveOrCapture(
            binding: AgentLocalNodeIdempotencyBinding,
            payloadDigest: String,
            capture: () -> AgentLocalNodeCaptureOutcome,
        ): AgentLocalNodeIdempotencyResult {
            val existing = stored[binding]
            if (existing != null) {
                return if (existing.digest == payloadDigest) {
                    AgentLocalNodeIdempotencyResult.Applied(existing.outcome)
                } else {
                    AgentLocalNodeIdempotencyResult.Conflict
                }
            }
            val outcome = capture()
            stored[binding] = Stored(payloadDigest, outcome)
            return AgentLocalNodeIdempotencyResult.Applied(outcome)
        }
    }

    private companion object {
        const val NOW = "2026-07-14T04:00:00Z"
        const val GOLDEN_SLOT = "idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13"
        const val GOLDEN_PAYLOAD_DIGEST =
            "sha256_81d4aac0b01c90e3ea84ce7a5c912ed26adb797ffc34e70ffa0b6641b3015d48"
        const val GOLDEN_RESULT_DIGEST =
            "sha256_b45b65aff991f6fb523b054b9e7382710b450ac841cf1692b7f50acdba5c501c"
        const val GOLDEN_REQUEST_DIGEST =
            "sha256_1458da7b922e66244d08e89af233bf73fd6bfac383152f79a9aa6726fce18b71"
        const val GOLDEN_PAYLOAD_JSON =
            "{\"content\":\"Synthetic milestone was verified by a tool.\",\"data_class\":\"structured\"," +
                "\"event_time\":\"2026-07-14T04:00:00Z\",\"event_type\":\"result\"," +
                "\"evidence_state\":\"observed\",\"fact_status\":\"confirmed\"," +
                "\"memory_type\":\"event\",\"now\":\"2026-07-14T04:00:00Z\"," +
                "\"sensitivity\":\"personal\",\"space\":\"space_work\"}"
        const val GOLDEN_RESULT_JSON =
            "{\"event_id\":\"evt_synthetic_001\",\"object_type\":\"event\",\"revision\":1}"
        const val GOLDEN_REQUEST_JSON =
            "{\"control\":{\"caller_id\":\"agent_synthetic\",\"grant_id\":\"grant_synthetic\"," +
                "\"idempotency_slot\":\"$GOLDEN_SLOT\",\"memory_types\":[\"event\"]," +
                "\"operation\":\"create_event\",\"payload_digest\":\"$GOLDEN_PAYLOAD_DIGEST\"," +
                "\"purpose\":\"autonomous_memory\",\"spaces\":[\"space_work\"]}," +
                "\"payload\":$GOLDEN_PAYLOAD_JSON,\"protocol_version\":\"ameme.agent-local-node.v1\"," +
                "\"request_id\":\"req_create_event\"}"
        const val GOLDEN_RESPONSE_JSON =
            "{\"error\":null,\"protocol_version\":\"ameme.agent-local-node.v1\"," +
                "\"request_id\":\"req_create_event\",\"result\":$GOLDEN_RESULT_JSON," +
                "\"result_digest\":\"$GOLDEN_RESULT_DIGEST\",\"status\":\"ok\"}"
    }
}
