package com.ameme.android.data.transport

import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.AgentCaptureUndoResult
import com.ameme.android.data.AgentCaptureUndoTarget
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
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
    fun appendRevisionEnvelopeAndResultRemainStrictCanonicalV1() {
        val payload = AgentLocalNodeAppendRevisionPayload(
            eventId = "evt_synthetic_001",
            space = "space_work",
            memoryType = "revision",
            content = "Synthetic revision.",
            evidenceState = "user_asserted",
            factStatus = "user_asserted",
            now = NOW,
        )
        val control = AgentLocalNodeControl(
            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
            requestId = "req_append_revision",
            callerId = "agent_synthetic",
            grantId = "grant_synthetic",
            purpose = "autonomous_memory",
            spaces = setOf("space_work"),
            memoryTypes = setOf("revision"),
            operation = "append_revision",
            idempotencySlot = APPEND_SLOT,
            payloadDigest = AgentLocalNodeApplicationCodec.payloadDigest(payload),
        )
        val envelope = AgentLocalNodeApplicationCodec.encodeAppendRevisionRequestEnvelope(control, payload)
        val decoded = AgentLocalNodeApplicationCodec.decodeRequestEnvelope(envelope)
        val payloadBytes = decoded.payloadCopy()
        try {
            assertEquals(payload, AgentLocalNodeApplicationCodec.decodeAppendRevision(payloadBytes))
        } finally {
            payloadBytes.fill(0)
            decoded.close()
        }
        assertFailsArgument {
            AgentLocalNodeApplicationCodec.decodeCreateEventRequestEnvelope(envelope)
        }

        val result = AgentLocalNodeAppendRevisionResult(
            eventRevisionId = "rev_synthetic_002",
            revision = 2,
            targetEventId = payload.eventId,
        )
        val encoded = AgentLocalNodeApplicationCodec.encodeAppendRevisionResult(result)
        assertEquals(result, AgentLocalNodeApplicationCodec.decodeAppendRevisionResult(encoded))
        val responseRequest = AgentLocalNodeRequest(control, AgentLocalNodeApplicationCodec.encodeAppendRevision(payload))
        val response = AgentLocalNodeResponse.validatedFor(
            request = responseRequest,
            protocolVersion = control.protocolVersion,
            requestId = control.requestId,
            status = AgentLocalNodeStatus.Ok,
            resultDigest = AgentLocalNodeApplicationCodec.canonicalDigest(encoded),
            payload = encoded,
        )
        assertTrue(
            AgentLocalNodeApplicationCodec.encodeResponseEnvelope(response)
                .decodeToString()
                .contains("\"object_type\":\"revision\""),
        )
        response.close()
        responseRequest.close()
        encoded.fill(0)
    }

    @Test
    fun undoEnvelopeAndConditionalResultsRemainStrictCanonicalV1() {
        val payload = AgentLocalNodeUndoCapturePayload(
            undoToken = APPEND_SLOT,
            space = "space_work",
            memoryType = "revision",
            now = NOW,
        )
        val control = AgentLocalNodeControl(
            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
            requestId = "req_undo_revision",
            callerId = "agent_synthetic",
            grantId = "grant_synthetic",
            purpose = "autonomous_memory",
            spaces = setOf("space_work"),
            memoryTypes = setOf("revision"),
            operation = "undo_capture",
            idempotencySlot = UNDO_SLOT,
            payloadDigest = AgentLocalNodeApplicationCodec.payloadDigest(payload),
        )
        val envelope = AgentLocalNodeApplicationCodec.encodeUndoCaptureRequestEnvelope(control, payload)
        val decoded = AgentLocalNodeApplicationCodec.decodeRequestEnvelope(envelope)
        val payloadBytes = decoded.payloadCopy()
        try {
            assertEquals(payload, AgentLocalNodeApplicationCodec.decodeUndoCapture(payloadBytes))
        } finally {
            payloadBytes.fill(0)
            decoded.close()
        }

        val revisionResult = AgentLocalNodeUndoCaptureResult(
            targetEventId = "evt_synthetic_001",
            undoneObjectType = "revision",
            undoneObjectId = "rev_synthetic_002",
            compensationRevisionId = "rev_synthetic_003",
        )
        val revisionBytes = AgentLocalNodeApplicationCodec.encodeUndoCaptureResult(revisionResult)
        assertEquals(
            revisionResult,
            AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(revisionBytes),
        )
        val eventResult = AgentLocalNodeUndoCaptureResult(
            targetEventId = "evt_synthetic_001",
            undoneObjectType = "event",
            undoneObjectId = "evt_synthetic_001",
        )
        val eventBytes = AgentLocalNodeApplicationCodec.encodeUndoCaptureResult(eventResult)
        assertFalse(eventBytes.decodeToString().contains("compensation_revision_id"))
        assertEquals(eventResult, AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(eventBytes))
        revisionBytes.fill(0)
        eventBytes.fill(0)
    }

    @Test
    fun visibleEventsEnvelopeAndResultRemainStrictCanonicalV1() {
        val payload = AgentLocalNodeVisibleEventsPayload(
            spaces = listOf("space_work"),
            memoryTypes = listOf("event"),
            query = "synthetic",
            allowHighRisk = false,
            limit = 2,
            startAt = "2026-07-14T00:00:00Z",
            endAt = "2026-07-14T23:59:59Z",
        )
        val encoded = AgentLocalNodeApplicationCodec.encodeVisibleEvents(payload)
        assertEquals(VISIBLE_PAYLOAD_JSON, encoded.decodeToString())
        assertEquals(VISIBLE_PAYLOAD_DIGEST, AgentLocalNodeApplicationCodec.payloadDigest(payload))
        assertEquals(payload, AgentLocalNodeApplicationCodec.decodeVisibleEvents(encoded))
        val control = AgentLocalNodeControl(
            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
            requestId = "req_visible_events",
            callerId = "agent_synthetic",
            grantId = "grant_synthetic",
            purpose = "autonomous_memory",
            spaces = setOf("space_work"),
            memoryTypes = setOf("event"),
            operation = "visible_events",
            idempotencySlot = GOLDEN_SLOT,
            payloadDigest = VISIBLE_PAYLOAD_DIGEST,
        )
        val envelope = AgentLocalNodeApplicationCodec.encodeVisibleEventsRequestEnvelope(
            control,
            payload,
        )
        val decoded = AgentLocalNodeApplicationCodec.decodeRequestEnvelope(envelope)
        val decodedPayload = decoded.payloadCopy()
        try {
            assertEquals(payload, AgentLocalNodeApplicationCodec.decodeVisibleEvents(decodedPayload))
        } finally {
            decodedPayload.fill(0)
            decoded.close()
        }
        val result = AgentLocalNodeVisibleEventsResult(
            events = listOf(
                AgentLocalNodeEventView(
                    eventId = "evt_visible_001",
                    spaceId = "space_work",
                    revision = 2,
                    eventType = "result",
                    title = "Synthetic visible event",
                    description = "Bounded structured memory.",
                    factStatus = "confirmed",
                    evidenceState = "observed",
                    sensitivity = "personal",
                    contentTruncated = false,
                ),
            ),
            riskFiltered = false,
        )
        val resultBytes = AgentLocalNodeApplicationCodec.encodeVisibleEventsResult(result)
        assertEquals(result, AgentLocalNodeApplicationCodec.decodeVisibleEventsResult(resultBytes))
        assertFalse(resultBytes.decodeToString().contains("source"))
        assertFalse(resultBytes.decodeToString().contains("user_words"))
        assertFalse(resultBytes.decodeToString().contains("locator"))
        encoded.fill(0)
        envelope.fill(0)
        resultBytes.fill(0)
    }

    @Test
    fun visibleEventsReturnsBoundedCurrentProjectionAndHidesDeletedEvents() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val endpoint = enabledEndpoint(
            repository,
            session = verifiedSession().copy(
                allowedOperations = setOf("visible_events"),
                allowedSensitivities = setOf("personal", "confidential"),
            ),
        )
        val payload = AgentLocalNodeVisibleEventsPayload(
            spaces = listOf("space_work"),
            memoryTypes = listOf("event"),
            query = "方案",
            allowHighRisk = false,
            limit = 1,
            startAt = "2026-07-14T00:00:00Z",
            endAt = "2026-07-14T23:59:59Z",
        )

        val first = visibleResultOf(
            endpoint.exchange(visibleRequest(payload, "req_visible_bounded")),
        )

        assertFalse(first.riskFiltered)
        assertEquals(1, first.events.size)
        assertEquals("evt_synth_question", first.events.single().eventId)
        assertEquals("space_work", first.events.single().spaceId)
        assertEquals("event", first.events.single().memoryType)
        assertEquals("structured", first.events.single().dataClass)
        assertEquals("confidential", first.events.single().sensitivity)
        assertFalse(first.events.single().contentTruncated)

        assertTrue(repository.deleteEvent("evt_synth_question"))
        val afterDelete = visibleResultOf(
            endpoint.exchange(visibleRequest(payload, "req_visible_after_delete")),
        )
        val hiddenUserWords = visibleResultOf(
            endpoint.exchange(
                visibleRequest(
                    payload.copy(
                        query = "今天把可验证的部分先落下来",
                        limit = 10,
                    ),
                    "req_visible_hidden_user_words",
                ),
            ),
        )
        val hiddenSourceLabel = visibleResultOf(
            endpoint.exchange(
                visibleRequest(
                    payload.copy(query = "合成文字补充", limit = 10),
                    "req_visible_hidden_source_label",
                ),
            ),
        )
        assertTrue(afterDelete.events.isEmpty())
        assertFalse(afterDelete.riskFiltered)
        assertTrue(hiddenUserWords.events.isEmpty())
        assertTrue(hiddenSourceLabel.events.isEmpty())
    }

    @Test
    fun visibleEventsRiskFilterDoesNotLeakUnauthorizedSensitivityAndTruncatesContent() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val marker = "SYNTHETIC_RESTRICTED_READ_MARKER"
        val longContent = "$marker ${"x".repeat(1_200)}"
        val createEndpoint = enabledEndpoint(
            repository,
            session = verifiedSession().copy(
                allowedOperations = setOf("create_event"),
                allowedSensitivities = setOf("restricted"),
            ),
        )
        val created = resultOf(
            createEndpoint.exchange(
                request(
                    goldenPayload().copy(
                        content = longContent,
                        sensitivity = "restricted",
                    ),
                    requestId = "req_visible_restricted_seed",
                    idempotencySlot = SECOND_CREATE_SLOT,
                ),
            ),
        )
        val base = AgentLocalNodeVisibleEventsPayload(
            spaces = listOf("space_work"),
            memoryTypes = listOf("event"),
            query = marker,
            allowHighRisk = false,
            limit = 5,
            startAt = "2026-07-14T00:00:00Z",
            endAt = "2026-07-14T23:59:59Z",
        )
        val unauthorizedEndpoint = enabledEndpoint(
            repository,
            session = verifiedSession().copy(
                allowedOperations = setOf("visible_events"),
                allowedSensitivities = setOf("personal"),
            ),
        )
        val authorizedEndpoint = enabledEndpoint(
            repository,
            session = verifiedSession().copy(
                allowedOperations = setOf("visible_events"),
                allowedSensitivities = setOf("restricted"),
            ),
        )

        val unauthorized = visibleResultOf(
            unauthorizedEndpoint.exchange(visibleRequest(base, "req_visible_unauthorized")),
        )
        val policyFiltered = visibleResultOf(
            authorizedEndpoint.exchange(visibleRequest(base, "req_visible_policy_filtered")),
        )
        val allowed = visibleResultOf(
            authorizedEndpoint.exchange(
                visibleRequest(
                    base.copy(allowHighRisk = true),
                    "req_visible_restricted_allowed",
                ),
            ),
        )

        assertTrue(unauthorized.events.isEmpty())
        assertFalse(unauthorized.riskFiltered)
        assertTrue(policyFiltered.events.isEmpty())
        assertTrue(policyFiltered.riskFiltered)
        assertFalse(allowed.riskFiltered)
        assertEquals(created.eventId, allowed.events.single().eventId)
        val description = allowed.events.single().description
        assertEquals(1_000, description.codePointCount(0, description.length))
        assertTrue(allowed.events.single().contentTruncated)
    }

    @Test
    fun visibleEventsRequiresExplicitOperationAndExactEventSpaceScope() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val payload = AgentLocalNodeVisibleEventsPayload(
            spaces = listOf("space_work"),
            memoryTypes = listOf("event"),
            allowHighRisk = false,
            limit = 5,
        )
        val operationDenied = enabledEndpoint(repository).exchange(
            visibleRequest(payload, "req_visible_operation_denied"),
        )
        val visibleSession = verifiedSession().copy(
            allowedOperations = setOf("visible_events"),
        )
        val spaceDenied = enabledEndpoint(repository, session = visibleSession).exchange(
            visibleRequest(
                payload.copy(spaces = listOf("space_personal")),
                "req_visible_space_denied",
            ),
        )
        val typeDenied = enabledEndpoint(repository, session = visibleSession).exchange(
            visibleRequest(
                payload.copy(memoryTypes = listOf("revision")),
                "req_visible_type_denied",
            ),
        )

        assertError(operationDenied, AgentLocalNodeErrorCode.OPERATION_UNSUPPORTED)
        assertError(spaceDenied, AgentLocalNodeErrorCode.SPACE_DENIED)
        assertError(typeDenied, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        listOf(operationDenied, spaceDenied, typeDenied).forEach {
            assertArrayEquals(byteArrayOf(), it.payloadCopy())
        }
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
    fun appendRevisionUpdatesExactEventAndReplaysWithoutSecondMutation() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val registry = InMemoryTestIdempotencyRegistry()
        val session = verifiedSession().copy(
            allowedOperations = setOf("create_event", "append_revision"),
        )
        val endpoint = enabledEndpoint(repository, registry, session)
        val createResponse = endpoint.exchange(request(goldenPayload(), requestId = "req_revision_seed"))
        val eventId = resultOf(createResponse).eventId
        val append = AgentLocalNodeAppendRevisionPayload(
            eventId = eventId,
            space = "space_work",
            memoryType = "revision",
            content = "Synthetic revision was explicitly asserted.",
            evidenceState = "user_asserted",
            factStatus = "user_asserted",
            now = NOW,
        )

        val first = endpoint.exchange(appendRequest(append, requestId = "req_append_first"))
        val replay = endpoint.exchange(appendRequest(append, requestId = "req_append_replay"))
        val changed = endpoint.exchange(
            appendRequest(
                append.copy(content = "Changed revision semantics."),
                requestId = "req_append_changed",
            ),
        )

        val firstResult = appendResultOf(first)
        val replayResult = appendResultOf(replay)
        assertEquals("revision", firstResult.objectType)
        assertEquals(eventId, firstResult.targetEventId)
        assertEquals(2, firstResult.revision)
        assertEquals(firstResult.eventRevisionId, replayResult.eventRevisionId)
        assertError(changed, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
        val stored = repository.loadActiveEvents().single { it.id == eventId }
        assertEquals(2, stored.revision)
        assertEquals("Synthetic revision was explicitly asserted.", stored.detail)
        assertEquals(FactStatus.UserAsserted, stored.factStatus)
        assertEquals(EvidenceState.UserAsserted, stored.evidenceState)
        assertEquals("AgentAutonomous", stored.sourceLabel)
    }

    @Test
    fun eventUndoTombstonesOnlyExactCaptureAndReplaysWithoutSecondMutation() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val registry = InMemoryTestIdempotencyRegistry()
        val endpoint = enabledEndpoint(
            repository,
            registry,
            verifiedSession().copy(
                allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
            ),
        )
        val create = endpoint.exchange(request(goldenPayload(), requestId = "req_event_undo_seed"))
        val eventId = resultOf(create).eventId
        val undo = AgentLocalNodeUndoCapturePayload(
            undoToken = GOLDEN_SLOT,
            space = "space_work",
            memoryType = "event",
            now = NOW,
        )

        val first = endpoint.exchange(undoRequest(undo, "req_event_undo_first"))
        val replay = endpoint.exchange(undoRequest(undo, "req_event_undo_replay"))
        val changed = endpoint.exchange(
            undoRequest(
                undo.copy(now = "2026-07-14T04:00:01Z"),
                "req_event_undo_changed",
            ),
        )

        val firstResult = undoResultOf(first)
        val replayResult = undoResultOf(replay)
        assertEquals("event", firstResult.undoneObjectType)
        assertEquals(eventId, firstResult.targetEventId)
        assertEquals(eventId, firstResult.undoneObjectId)
        assertNull(firstResult.compensationRevisionId)
        assertEquals(firstResult, replayResult)
        assertError(changed, AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT)
        assertFalse(repository.loadActiveEvents().any { it.id == eventId })
    }

    @Test
    fun revisionUndoAppendsCompensationAndChangedHeadConflicts() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val registry = InMemoryTestIdempotencyRegistry()
        val session = verifiedSession().copy(
            allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
        )
        val endpoint = enabledEndpoint(repository, registry, session)
        val create = endpoint.exchange(
            request(
                goldenPayload(),
                requestId = "req_revision_undo_seed",
                idempotencySlot = SECOND_CREATE_SLOT,
            ),
        )
        val eventId = resultOf(create).eventId
        val append = AgentLocalNodeAppendRevisionPayload(
            eventId = eventId,
            space = "space_work",
            memoryType = "revision",
            content = "Synthetic revision to compensate.",
            evidenceState = "user_asserted",
            factStatus = "user_asserted",
            now = NOW,
        )
        val appended = appendResultOf(
            endpoint.exchange(appendRequest(append, "req_revision_undo_append")),
        )
        val undo = AgentLocalNodeUndoCapturePayload(
            undoToken = APPEND_SLOT,
            space = "space_work",
            memoryType = "revision",
            now = NOW,
        )

        val undone = undoResultOf(
            endpoint.exchange(undoRequest(undo, "req_revision_undo_first")),
        )

        assertEquals("revision", undone.undoneObjectType)
        assertEquals(appended.eventRevisionId, undone.undoneObjectId)
        assertTrue(undone.compensationRevisionId.orEmpty().startsWith("rev_"))
        val restored = repository.loadActiveEvents().single { it.id == eventId }
        assertEquals(goldenPayload().content, restored.detail)
        assertEquals(FactStatus.Confirmed, restored.factStatus)
        assertEquals(EvidenceState.Observed, restored.evidenceState)
        assertEquals(3, restored.revision)

        val conflictCreate = endpoint.exchange(
            request(
                goldenPayload(),
                requestId = "req_revision_conflict_seed",
                idempotencySlot = THIRD_CREATE_SLOT,
            ),
        )
        val conflictEventId = resultOf(conflictCreate).eventId
        val conflictAppend = append.copy(eventId = conflictEventId)
        endpoint.exchange(
            appendRequest(
                conflictAppend,
                "req_revision_conflict_append",
                idempotencySlot = SECOND_APPEND_SLOT,
            ),
        )
        repository.updateEvent(conflictEventId, userWords = "Synthetic later edit.")
        val conflict = endpoint.exchange(
            undoRequest(
                undo.copy(undoToken = SECOND_APPEND_SLOT),
                "req_revision_conflict_undo",
                idempotencySlot = SECOND_UNDO_SLOT,
            ),
        )
        assertError(conflict, AgentLocalNodeErrorCode.REVISION_CONFLICT)
        assertEquals(3, repository.loadActiveEvents().single { it.id == conflictEventId }.revision)
    }

    @Test
    fun undoHidesUnknownTokenAndRequiresExactTypeGrant() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val session = verifiedSession().copy(
            allowedOperations = setOf("create_event", "append_revision", "undo_capture"),
        )
        val payload = AgentLocalNodeUndoCapturePayload(
            undoToken = "idem_" + "0".repeat(64),
            space = "space_work",
            memoryType = "revision",
            now = NOW,
        )
        val missing = enabledEndpoint(repository, session = session).exchange(
            undoRequest(payload, "req_undo_missing"),
        )
        val denied = enabledEndpoint(
            repository,
            session = session.copy(allowedMemoryTypes = setOf("event")),
        ).exchange(undoRequest(payload, "req_undo_denied"))

        assertError(missing, AgentLocalNodeErrorCode.NOT_VISIBLE)
        assertError(denied, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        assertArrayEquals(byteArrayOf(), missing.payloadCopy())
        assertArrayEquals(byteArrayOf(), denied.payloadCopy())
        assertFalse(missing.toString().contains(payload.undoToken))
    }

    @Test
    fun appendRevisionRequiresRevisionGrantAndHidesMissingOrDeletedTargets() = runBlocking {
        val repository = FakeMemoryRepository(clock)
        val appendSession = verifiedSession().copy(
            allowedOperations = setOf("create_event", "append_revision"),
        )
        val append = AgentLocalNodeAppendRevisionPayload(
            eventId = "evt_missing_private",
            space = "space_work",
            memoryType = "revision",
            content = "Synthetic hidden-target probe.",
            evidenceState = "inferred",
            factStatus = "low_confidence_candidate",
            now = NOW,
        )
        val missing = enabledEndpoint(repository, session = appendSession).exchange(
            appendRequest(append, requestId = "req_append_missing"),
        )
        val denied = enabledEndpoint(
            repository,
            session = appendSession.copy(allowedMemoryTypes = setOf("event")),
        ).exchange(appendRequest(append, requestId = "req_append_denied"))
        val restrictedCreate = enabledEndpoint(
            repository,
            session = appendSession.copy(
                allowedOperations = setOf("create_event"),
                allowedSensitivities = setOf("restricted"),
            ),
        ).exchange(
            request(
                goldenPayload().copy(sensitivity = "restricted"),
                requestId = "req_restricted_seed",
                idempotencySlot = "idem_46f3131385d91b67382462d77d16c1b63677200e5550620aaea6fc9c0901e377",
            ),
        )
        val restrictedEventId = resultOf(restrictedCreate).eventId
        val hiddenBySensitivity = enabledEndpoint(
            repository,
            session = appendSession.copy(allowedSensitivities = setOf("personal")),
        ).exchange(
            appendRequest(
                append.copy(eventId = restrictedEventId),
                requestId = "req_append_restricted",
                idempotencySlot = "idem_ad4a8944395a55b8da26af4cd6da434c73081766db23c1e16a4cf57a68e7d589",
            ),
        )
        val deletedSeed = enabledEndpoint(repository, session = appendSession).exchange(
            request(
                goldenPayload(),
                requestId = "req_deleted_seed",
                idempotencySlot = "idem_a40cb857ad261388ac879841a13a9d8cbf61904653257729732978482a51d257",
            ),
        )
        val deletedEventId = resultOf(deletedSeed).eventId
        assertTrue(repository.deleteEvent(deletedEventId))
        val hiddenAfterDelete = enabledEndpoint(repository, session = appendSession).exchange(
            appendRequest(
                append.copy(eventId = deletedEventId),
                requestId = "req_append_deleted",
                idempotencySlot = "idem_c74617a3aa9c6fd75b21094191b9e23fbd400a05366bff9dd6f59bf72566edec",
            ),
        )

        assertError(missing, AgentLocalNodeErrorCode.NOT_VISIBLE)
        assertError(denied, AgentLocalNodeErrorCode.DATA_TYPE_DENIED)
        assertError(hiddenBySensitivity, AgentLocalNodeErrorCode.NOT_VISIBLE)
        assertError(hiddenAfterDelete, AgentLocalNodeErrorCode.NOT_VISIBLE)
        assertArrayEquals(byteArrayOf(), missing.payloadCopy())
        assertArrayEquals(byteArrayOf(), denied.payloadCopy())
        assertArrayEquals(byteArrayOf(), hiddenBySensitivity.payloadCopy())
        assertArrayEquals(byteArrayOf(), hiddenAfterDelete.payloadCopy())
        assertFalse(missing.toString().contains(append.eventId))
        assertFalse(missing.toString().contains(append.content))
        assertEquals(1, repository.loadActiveEvents().single { it.id == restrictedEventId }.revision)
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

    private fun appendRequest(
        payload: AgentLocalNodeAppendRevisionPayload,
        requestId: String,
        idempotencySlot: String = APPEND_SLOT,
    ): AgentLocalNodeRequest {
        val encoded = AgentLocalNodeApplicationCodec.encodeAppendRevision(payload)
        return try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = requestId,
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = setOf(payload.space),
                    memoryTypes = setOf(payload.memoryType),
                    operation = "append_revision",
                    idempotencySlot = idempotencySlot,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(encoded),
                ),
                payload = encoded,
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun undoRequest(
        payload: AgentLocalNodeUndoCapturePayload,
        requestId: String,
        idempotencySlot: String = UNDO_SLOT,
    ): AgentLocalNodeRequest {
        val encoded = AgentLocalNodeApplicationCodec.encodeUndoCapture(payload)
        return try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = requestId,
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = setOf(payload.space),
                    memoryTypes = setOf(payload.memoryType),
                    operation = "undo_capture",
                    idempotencySlot = idempotencySlot,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(encoded),
                ),
                payload = encoded,
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun visibleRequest(
        payload: AgentLocalNodeVisibleEventsPayload,
        requestId: String,
    ): AgentLocalNodeRequest {
        val encoded = AgentLocalNodeApplicationCodec.encodeVisibleEvents(payload)
        return try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = requestId,
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = payload.spaces.toSet(),
                    memoryTypes = payload.memoryTypes.toSet(),
                    operation = "visible_events",
                    idempotencySlot = GOLDEN_SLOT,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(encoded),
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

    private fun appendResultOf(response: AgentLocalNodeResponse): AgentLocalNodeAppendRevisionResult {
        val bytes = response.payloadCopy()
        return try {
            AgentLocalNodeApplicationCodec.decodeAppendRevisionResult(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun undoResultOf(response: AgentLocalNodeResponse): AgentLocalNodeUndoCaptureResult {
        val bytes = response.payloadCopy()
        return try {
            AgentLocalNodeApplicationCodec.decodeUndoCaptureResult(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun visibleResultOf(response: AgentLocalNodeResponse): AgentLocalNodeVisibleEventsResult {
        val bytes = response.payloadCopy()
        return try {
            AgentLocalNodeApplicationCodec.decodeVisibleEventsResult(bytes)
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
        private val undoStored = mutableMapOf<
            AgentLocalNodeIdempotencyBinding,
            Pair<String, AgentCaptureUndoResult>
            >()

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

        @Synchronized
        override fun resolveOrUndo(
            binding: AgentLocalNodeIdempotencyBinding,
            payloadDigest: String,
            undoToken: String,
            at: Instant,
            capture: (AgentCaptureUndoTarget) -> AgentCaptureUndoResult?,
        ): AgentLocalNodeUndoIdempotencyResult {
            val replay = undoStored[binding]
            if (replay != null) {
                return if (replay.first == payloadDigest) {
                    AgentLocalNodeUndoIdempotencyResult.Applied(replay.second)
                } else {
                    AgentLocalNodeUndoIdempotencyResult.Conflict
                }
            }
            val originalOperation = when (binding.memoryType) {
                "event" -> "create_event"
                "revision" -> "append_revision"
                else -> return AgentLocalNodeUndoIdempotencyResult.NotVisible
            }
            val original = stored[
                binding.copy(
                    operation = originalOperation,
                    slot = undoToken,
                ),
            ]?.outcome ?: return AgentLocalNodeUndoIdempotencyResult.NotVisible
            val objectId = if (binding.memoryType == "event") {
                original.eventId
            } else {
                original.revisionId ?: return AgentLocalNodeUndoIdempotencyResult.NotVisible
            }
            val outcome = capture(
                AgentCaptureUndoTarget(
                    eventId = original.eventId,
                    objectType = binding.memoryType,
                    objectId = objectId,
                    createdRevision = original.revision,
                ),
            ) ?: return AgentLocalNodeUndoIdempotencyResult.NotVisible
            undoStored[binding] = payloadDigest to outcome
            return AgentLocalNodeUndoIdempotencyResult.Applied(outcome)
        }
    }

    private companion object {
        const val NOW = "2026-07-14T04:00:00Z"
        const val GOLDEN_SLOT = "idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13"
        const val APPEND_SLOT = "idem_9bf77d38ad5078308ea7f36bf05ffbcc126f402d019652b34435258b800cf1c9"
        const val UNDO_SLOT = "idem_0da22aed7cfd32171578109197a5c3a9303467e0eb31b49af6d1bd841b77e6ca"
        const val SECOND_CREATE_SLOT = "idem_3f4ea8ef4b5fac3b614206c3850c54b62ec7490861f70c94d3da29a5514eb5de"
        const val THIRD_CREATE_SLOT = "idem_a116c68413a0bed1f8e1663a5919405f44c64555e492fe472ae94cc10c8ecf09"
        const val SECOND_APPEND_SLOT = "idem_30aac5009ea7c69ae4874b52608ab65964554bf9d26d3fb8fbacbe64229dba28"
        const val SECOND_UNDO_SLOT = "idem_d64a5a63d6dbf4896094ebf63c61ef81fc9f5b6780d5f26a2f5f4aa7397b8ae0"
        const val GOLDEN_PAYLOAD_DIGEST =
            "sha256_81d4aac0b01c90e3ea84ce7a5c912ed26adb797ffc34e70ffa0b6641b3015d48"
        const val VISIBLE_PAYLOAD_DIGEST =
            "sha256_f1a09e54d085dd5fa6be29663bdc0d1272304fdcec8db653dd9a611c4584dd57"
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
        const val VISIBLE_PAYLOAD_JSON =
            "{\"allow_high_risk\":false,\"end_at\":\"2026-07-14T23:59:59Z\"," +
                "\"limit\":2,\"memory_types\":[\"event\"],\"query\":\"synthetic\"," +
                "\"spaces\":[\"space_work\"],\"start_at\":\"2026-07-14T00:00:00Z\"}"
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
