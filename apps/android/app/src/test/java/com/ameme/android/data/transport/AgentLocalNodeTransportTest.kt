package com.ameme.android.data.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalNodeTransportTest {
    private fun control() = AgentLocalNodeControl(
        protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
        requestId = "req_synthetic_001",
        callerId = "agent_synthetic",
        grantId = "grant_synthetic",
        purpose = "autonomous_memory",
        spaces = setOf("space_work"),
        memoryTypes = setOf("event"),
        operation = "create_event",
        idempotencySlot = "idem_" + "a".repeat(64),
        payloadDigest = "sha256_" + "b".repeat(64),
    )

    @Test
    fun exactScopeRejectsCrossSpaceAndCrossType() {
        val control = control()
        control.requireExactScope("space_work", "event")

        assertFails { control.requireExactScope("space_personal", "event") }
        assertFails { control.requireExactScope("space_work", "revision") }
    }

    @Test
    fun payloadIsDefensivelyCopiedAndRedactedFromDiagnostics() {
        val content = "SYNTHETIC_ANDROID_TRANSPORT_CONTENT".encodeToByteArray()
        val request = AgentLocalNodeRequest(control(), content)
        content.fill(0)

        assertArrayEquals(
            "SYNTHETIC_ANDROID_TRANSPORT_CONTENT".encodeToByteArray(),
            request.payloadCopy(),
        )
        assertFalse(request.toString().contains("SYNTHETIC_ANDROID_TRANSPORT_CONTENT"))
        assertTrue(request.toString().contains("payload=<redacted:"))
        request.close()
        assertTrue(request.toString().contains("payload=<cleared>"))
        assertFailsState { request.payloadCopy() }
    }

    @Test
    fun protocolAndResponseIdentityFailClosed() {
        val request = AgentLocalNodeRequest(control(), byteArrayOf(1))
        val matching = AgentLocalNodeResponse.validatedFor(
            request = request,
            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
            requestId = request.control.requestId,
            status = AgentLocalNodeStatus.Ok,
            resultDigest = "sha256_" + "c".repeat(64),
            payload = byteArrayOf(2),
        )
        matching.requireMatches(request)
        matching.close()
        assertFailsState { matching.payloadCopy() }

        assertFails {
            AgentLocalNodeResponse.validatedFor(
                request = request,
                protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                requestId = "req_synthetic_other",
                status = AgentLocalNodeStatus.Ok,
                resultDigest = "sha256_" + "c".repeat(64),
                payload = byteArrayOf(2),
            )
        }
        assertFails {
            control().copy(protocolVersion = "ameme.agent-local-node.v2")
        }
    }

    @Test
    fun controlRejectsValuesOutsideSharedV1SchemaBeforeDispatch() {
        assertFails { control().copy(requestId = "invalid/request") }
        assertFails { control().copy(callerId = "a".repeat(129)) }
        assertFails { control().copy(spaces = (1..9).map { "space_$it" }.toSet()) }
        assertFails { control().copy(memoryTypes = setOf("artifact")) }
        assertFails { control().copy(memoryTypes = setOf("event", "artifact")) }
        assertFails { control().copy(operation = "capture") }
        assertFails { AgentLocalNodeError(AgentLocalNodeErrorCode.TEMPORARILY_UNAVAILABLE, retryable = false) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected exact-scope rejection.
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
}
