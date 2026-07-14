package com.ameme.android.data.transport.channel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalNodeChannelCodecTest {
    private val secret = "synthetic-pairing-secret-32-bytes-minimum-value".encodeToByteArray()
    private val pairing = AndroidLocalNodePairingMaterial(
        channelProtocol = AndroidLocalNodeChannelCodec.CHANNEL_PROTOCOL_VERSION,
        endpointRef = "endpoint-ref:synthetic/android",
        credentialRef = "credential-ref:env/SYNTHETIC_SECRET",
        expectedDeviceId = "device_synthetic_001",
        sessionBindingRef = "session-binding-ref:synthetic/session",
        pairingId = "pair_synthetic_001",
        host = "127.0.0.1",
        port = 44_321,
        tlsCertificateSha256 = "sha256_" + "a".repeat(64),
    )

    @Test
    fun pythonGoldenPairingHelloAndSessionKeyMatchByteForByte() {
        assertEquals(PYTHON_PAIRING, pairing.canonicalBytes().decodeToString())
        val parsedPairing = AndroidLocalNodeChannelCodec.parsePairingMaterial(
            PYTHON_PAIRING.encodeToByteArray(),
        )
        assertEquals(PYTHON_PAIRING, parsedPairing.canonicalBytes().decodeToString())

        val clientLine = AndroidLocalNodeChannelCodec.buildClientHello(
            pairing,
            secret,
            clientNonce = "nonce_" + "1".repeat(64),
        )
        assertEquals(PYTHON_CLIENT_HELLO, clientLine.decodeToString())
        val clientHello = AndroidLocalNodeChannelCodec.verifyClientHello(
            clientLine,
            pairing,
            secret,
        )
        val serverLine = AndroidLocalNodeChannelCodec.buildServerHello(
            clientHello,
            pairing,
            secret,
            serverNonce = "nonce_" + "2".repeat(64),
            sessionId = "sess_synthetic_001",
            supportedOperations = setOf("create_event"),
        )
        assertEquals(PYTHON_SERVER_HELLO, serverLine.decodeToString())
        val serverHello = AndroidLocalNodeChannelCodec.verifyServerHello(
            serverLine,
            clientHello,
            pairing,
            secret,
        )
        val sessionKey = AndroidLocalNodeChannelCodec.deriveSessionKey(
            clientHello,
            serverHello,
            secret,
        )
        assertEquals(PYTHON_SESSION_KEY_HEX, sessionKey.toHex())
        assertFalse(clientHello.toString().contains(pairing.expectedDeviceId))
        assertFalse(serverHello.toString().contains(serverHello.sessionId))
        sessionKey.fill(0)
    }

    @Test
    fun pythonGoldenRequestAndResponseFramesMatchByteForByte() {
        val clientHello = AndroidLocalNodeChannelCodec.verifyClientHello(
            PYTHON_CLIENT_HELLO.encodeToByteArray(),
            pairing,
            secret,
        )
        val serverHello = AndroidLocalNodeChannelCodec.verifyServerHello(
            PYTHON_SERVER_HELLO.encodeToByteArray(),
            clientHello,
            pairing,
            secret,
        )
        val sessionKey = AndroidLocalNodeChannelCodec.deriveSessionKey(clientHello, serverHello, secret)
        val requestLine = AndroidLocalNodeChannelCodec.buildRequestFrame(
            applicationLine = PYTHON_APPLICATION_REQUEST.encodeToByteArray(),
            sessionKey = sessionKey,
            sessionId = serverHello.sessionId,
            sequence = 1,
            nonce = "nonce_" + "4".repeat(64),
        )
        assertEquals(PYTHON_REQUEST_FRAME, requestLine.decodeToString())
        val requestFrame = AndroidLocalNodeChannelCodec.parseRequestFrame(
            requestLine,
            sessionKey,
            expectedSessionId = serverHello.sessionId,
            expectedSequence = 1,
            seenNonces = mutableSetOf(),
        )
        assertArrayEquals(
            PYTHON_APPLICATION_REQUEST.encodeToByteArray(),
            requestFrame.applicationLineCopy(),
        )
        assertEquals("create_event", requestFrame.operation)
        val responseLine = AndroidLocalNodeChannelCodec.buildResponseFrame(
            applicationLine = PYTHON_APPLICATION_RESPONSE.encodeToByteArray(),
            requestFrame = requestFrame,
            sessionKey = sessionKey,
            nonce = "nonce_" + "5".repeat(64),
        )
        assertEquals(PYTHON_RESPONSE_FRAME, responseLine.decodeToString())
        val responseFrame = AndroidLocalNodeChannelCodec.parseResponseFrame(
            responseLine,
            sessionKey,
            requestFrame,
            seenNonces = mutableSetOf(),
        )
        assertArrayEquals(
            PYTHON_APPLICATION_RESPONSE.encodeToByteArray(),
            responseFrame.applicationLineCopy(),
        )
        assertFalse(requestFrame.toString().contains("Synthetic milestone"))
        assertFalse(responseFrame.toString().contains("evt_synthetic_001"))
        responseFrame.close()
        requestFrame.close()
        sessionKey.fill(0)
    }

    @Test
    fun duplicateFloatProofSequenceAndReplayFailuresAreContentFree() {
        val duplicate = PYTHON_PAIRING.replace(
            "{\"channel_protocol\":",
            "{\"channel_protocol\":\"duplicate\",\"channel_protocol\":",
        )
        assertChannelFailure(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE) {
            AndroidLocalNodeChannelCodec.parsePairingMaterial(duplicate.encodeToByteArray())
        }
        val floatPort = PYTHON_PAIRING.replace("\"port\":44321", "\"port\":44321.0")
        assertChannelFailure(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE) {
            AndroidLocalNodeChannelCodec.parsePairingMaterial(floatPort.encodeToByteArray())
        }
        val badProof = PYTHON_CLIENT_HELLO.replace("\"proof\":\"hmac_", "\"proof\":\"hmac_0")
        assertChannelFailure(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED) {
            AndroidLocalNodeChannelCodec.verifyClientHello(badProof.encodeToByteArray(), pairing, secret)
        }

        val clientHello = AndroidLocalNodeChannelCodec.verifyClientHello(
            PYTHON_CLIENT_HELLO.encodeToByteArray(), pairing, secret,
        )
        val serverHello = AndroidLocalNodeChannelCodec.verifyServerHello(
            PYTHON_SERVER_HELLO.encodeToByteArray(), clientHello, pairing, secret,
        )
        val sessionKey = AndroidLocalNodeChannelCodec.deriveSessionKey(clientHello, serverHello, secret)
        assertChannelFailure(AgentLocalNodeChannelErrorCode.CHANNEL_SEQUENCE_INVALID) {
            AndroidLocalNodeChannelCodec.parseRequestFrame(
                PYTHON_REQUEST_FRAME.encodeToByteArray(),
                sessionKey,
                serverHello.sessionId,
                expectedSequence = 2,
                seenNonces = mutableSetOf(),
            )
        }
        val requestFrame = AndroidLocalNodeChannelCodec.parseRequestFrame(
            PYTHON_REQUEST_FRAME.encodeToByteArray(),
            sessionKey,
            serverHello.sessionId,
            expectedSequence = 1,
            seenNonces = mutableSetOf(),
        )
        val reusedNonceResponse = AndroidLocalNodeChannelCodec.buildResponseFrame(
            PYTHON_APPLICATION_RESPONSE.encodeToByteArray(),
            requestFrame,
            sessionKey,
            nonce = requestFrame.nonce,
        )
        assertChannelFailure(AgentLocalNodeChannelErrorCode.CHANNEL_REPLAY) {
            AndroidLocalNodeChannelCodec.parseResponseFrame(
                reusedNonceResponse,
                sessionKey,
                requestFrame,
                mutableSetOf(),
            )
        }
        assertTrue(requestFrame.idempotencyRef.startsWith("idem_"))
        requestFrame.close()
        sessionKey.fill(0)
    }

    @Test
    fun canonicalJsonMatchesPythonOrderingEscapesAndIntegerNormalization() {
        val parsed = StrictCanonicalJson.parseLine(
            "{\"中\":\"值\",\"a\":-0,\"escape\":\"\\b\\f\\n\\r\\t\"}".encodeToByteArray(),
            1_024,
        )
        assertEquals(
            "{\"a\":0,\"escape\":\"\\b\\f\\n\\r\\t\",\"中\":\"值\"}",
            StrictCanonicalJson.canonicalBytes(parsed).decodeToString(),
        )
    }

    private fun assertChannelFailure(
        expected: AgentLocalNodeChannelErrorCode,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("expected AgentLocalNodeChannelViolation")
        } catch (failure: AgentLocalNodeChannelViolation) {
            assertEquals(expected, failure.code)
            assertEquals(expected.name, failure.message)
            assertFalse(failure.message.orEmpty().contains("Synthetic milestone"))
        }
    }

    companion object {
        const val PYTHON_PAIRING = """{"channel_protocol":"ameme.agent-local-node.channel.v1","credential_ref":"credential-ref:env/SYNTHETIC_SECRET","endpoint_ref":"endpoint-ref:synthetic/android","expected_device_id":"device_synthetic_001","host":"127.0.0.1","pairing_id":"pair_synthetic_001","port":44321,"session_binding_ref":"session-binding-ref:synthetic/session","tls_certificate_sha256":"sha256_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        const val PYTHON_CLIENT_HELLO = """{"channel_protocol":"ameme.agent-local-node.channel.v1","client_nonce":"nonce_1111111111111111111111111111111111111111111111111111111111111111","expected_device_id":"device_synthetic_001","message_type":"client_hello","pairing_id":"pair_synthetic_001","proof":"hmac_01ed3976589e980c96c91ed8ccbbe30567ce2452849ba28ea5cf5077a1e735b1","sequence":0,"session_binding_ref":"session-binding-ref:synthetic/session","tls_certificate_sha256":"sha256_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        const val PYTHON_SERVER_HELLO = """{"channel_protocol":"ameme.agent-local-node.channel.v1","client_nonce":"nonce_1111111111111111111111111111111111111111111111111111111111111111","device_id":"device_synthetic_001","message_type":"server_hello","pairing_id":"pair_synthetic_001","proof":"hmac_4559f4cdb682ecb378e23efe2bb709defff24b591e345d4306ebd0f3d4f2bb31","sequence":0,"server_nonce":"nonce_2222222222222222222222222222222222222222222222222222222222222222","session_binding_ref":"session-binding-ref:synthetic/session","session_id":"sess_synthetic_001","supported_operations":["create_event"],"tls_certificate_sha256":"sha256_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        const val PYTHON_SESSION_KEY_HEX = "f89a4dac0778e42dfff501dc0b4fdc9cac1a10e5758be1819408a34e0245d5f6"
        const val PYTHON_APPLICATION_REQUEST = """{"control":{"caller_id":"agent_synthetic","grant_id":"grant_synthetic","idempotency_slot":"idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13","memory_types":["event"],"operation":"create_event","payload_digest":"sha256_81d4aac0b01c90e3ea84ce7a5c912ed26adb797ffc34e70ffa0b6641b3015d48","purpose":"autonomous_memory","spaces":["space_work"]},"payload":{"content":"Synthetic milestone was verified by a tool.","data_class":"structured","event_time":"2026-07-14T04:00:00Z","event_type":"result","evidence_state":"observed","fact_status":"confirmed","memory_type":"event","now":"2026-07-14T04:00:00Z","sensitivity":"personal","space":"space_work"},"protocol_version":"ameme.agent-local-node.v1","request_id":"req_create_event"}"""
        const val PYTHON_REQUEST_FRAME = """{"application_b64":"eyJjb250cm9sIjp7ImNhbGxlcl9pZCI6ImFnZW50X3N5bnRoZXRpYyIsImdyYW50X2lkIjoiZ3JhbnRfc3ludGhldGljIiwiaWRlbXBvdGVuY3lfc2xvdCI6ImlkZW1fNzkxODVlZWZhYWY3YWQwMTZhOTY1YjExOGQ3YTQwYmFkNTFhNzlmMDFlNGVlYmIzMjNjMmIxYjA4OTMyOGYxMyIsIm1lbW9yeV90eXBlcyI6WyJldmVudCJdLCJvcGVyYXRpb24iOiJjcmVhdGVfZXZlbnQiLCJwYXlsb2FkX2RpZ2VzdCI6InNoYTI1Nl84MWQ0YWFjMGIwMWM5MGUzZWE4NGNlN2E1YzkxMmVkMjZhZGI3OTdmZmMzNGU3MGZmYTBiNjY0MWIzMDE1ZDQ4IiwicHVycG9zZSI6ImF1dG9ub21vdXNfbWVtb3J5Iiwic3BhY2VzIjpbInNwYWNlX3dvcmsiXX0sInBheWxvYWQiOnsiY29udGVudCI6IlN5bnRoZXRpYyBtaWxlc3RvbmUgd2FzIHZlcmlmaWVkIGJ5IGEgdG9vbC4iLCJkYXRhX2NsYXNzIjoic3RydWN0dXJlZCIsImV2ZW50X3RpbWUiOiIyMDI2LTA3LTE0VDA0OjAwOjAwWiIsImV2ZW50X3R5cGUiOiJyZXN1bHQiLCJldmlkZW5jZV9zdGF0ZSI6Im9ic2VydmVkIiwiZmFjdF9zdGF0dXMiOiJjb25maXJtZWQiLCJtZW1vcnlfdHlwZSI6ImV2ZW50Iiwibm93IjoiMjAyNi0wNy0xNFQwNDowMDowMFoiLCJzZW5zaXRpdml0eSI6InBlcnNvbmFsIiwic3BhY2UiOiJzcGFjZV93b3JrIn0sInByb3RvY29sX3ZlcnNpb24iOiJhbWVtZS5hZ2VudC1sb2NhbC1ub2RlLnYxIiwicmVxdWVzdF9pZCI6InJlcV9jcmVhdGVfZXZlbnQifQ==","application_digest":"sha256_1458da7b922e66244d08e89af233bf73fd6bfac383152f79a9aa6726fce18b71","application_protocol":"ameme.agent-local-node.v1","channel_protocol":"ameme.agent-local-node.channel.v1","idempotency_ref":"idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13","message_type":"request","nonce":"nonce_4444444444444444444444444444444444444444444444444444444444444444","proof":"hmac_7256f4f31681ff3ec13dac8f334c8b711c65e668f51ba81bdc7f60db8851c9d5","sequence":1,"session_id":"sess_synthetic_001"}"""
        const val PYTHON_APPLICATION_RESPONSE = """{"error":null,"protocol_version":"ameme.agent-local-node.v1","request_id":"req_create_event","result":{"event_id":"evt_synthetic_001","object_type":"event","revision":1},"result_digest":"sha256_b45b65aff991f6fb523b054b9e7382710b450ac841cf1692b7f50acdba5c501c","status":"ok"}"""
        const val PYTHON_RESPONSE_FRAME = """{"application_b64":"eyJlcnJvciI6bnVsbCwicHJvdG9jb2xfdmVyc2lvbiI6ImFtZW1lLmFnZW50LWxvY2FsLW5vZGUudjEiLCJyZXF1ZXN0X2lkIjoicmVxX2NyZWF0ZV9ldmVudCIsInJlc3VsdCI6eyJldmVudF9pZCI6ImV2dF9zeW50aGV0aWNfMDAxIiwib2JqZWN0X3R5cGUiOiJldmVudCIsInJldmlzaW9uIjoxfSwicmVzdWx0X2RpZ2VzdCI6InNoYTI1Nl9iNDViNjVhZmY5OTFmNmZiNTIzYjA1NGI5ZTczODI3MTBiNDUwYWM4NDFjZjE2OTJiN2Y1MGFjZGJhNWM1MDFjIiwic3RhdHVzIjoib2sifQ==","application_digest":"sha256_9dc47be5cdc975fedb916b4e2a09581fa05db5dc1fa9ef27dd35d55391258be6","application_protocol":"ameme.agent-local-node.v1","channel_protocol":"ameme.agent-local-node.channel.v1","idempotency_ref":"idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13","message_type":"response","nonce":"nonce_5555555555555555555555555555555555555555555555555555555555555555","proof":"hmac_343f9287c87f2297eb7271b6145858fdcea9f071e51eadb05f93c0c02042a97e","request_nonce":"nonce_4444444444444444444444444444444444444444444444444444444444444444","sequence":1,"session_id":"sess_synthetic_001"}"""
    }
}
