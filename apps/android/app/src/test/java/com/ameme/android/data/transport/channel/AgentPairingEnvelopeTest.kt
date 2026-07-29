package com.ameme.android.data.transport.channel

import com.ameme.android.data.transport.AgentPairingMaterial
import com.ameme.android.data.transport.CreatedAgentPairing
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentPairingEnvelopeTest {
    private val pairing = AndroidLocalNodePairingMaterial(
        channelProtocol = "ameme.agent-local-node.channel.v1",
        endpointRef = "endpoint-ref:synthetic/android",
        credentialRef = "credential-ref:env/SYNTHETIC_SECRET",
        expectedDeviceId = "device_synthetic_001",
        sessionBindingRef = "session-binding-ref:synthetic/session",
        pairingId = "pair_synthetic_001",
        host = "127.0.0.1",
        port = 44_321,
        tlsCertificateSha256 = "sha256_" + "a".repeat(64),
    )
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val bootstrapId = "boot_" + "b".repeat(32)
    private val secretText = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { 0x42 })

    @Test
    fun canonicalEnvelope_roundTripsBootstrapSecretAndExpiry() {
        val payload = AgentPairingEnvelope.encode(
            pairing = pairing,
            bootstrapId = bootstrapId,
            bootstrapSecret = secretText,
            expiresAt = now.plusSeconds(300),
            pairingExpiresAt = now.plusSeconds(30 * 24 * 60 * 60L),
        )
        val golden = "ameme-pairing-v2:eyJib290c3RyYXBfaWQiOiJib290X2JiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiIiwiYm9vdHN0cmFwX3NlY3JldCI6IlFrSkNRa0pDUWtKQ1FrSkNRa0pDUWtKQ1FrSkNRa0pDUWtKQ1FrSkNRa0kiLCJlbnZlbG9wZV92ZXJzaW9uIjoyLCJleHBpcmVzX2F0X21zIjoiMTgwMDAwMDMwMDAwMCIsInBhaXJpbmciOnsiY2hhbm5lbF9wcm90b2NvbCI6ImFtZW1lLmFnZW50LWxvY2FsLW5vZGUuY2hhbm5lbC52MSIsImNyZWRlbnRpYWxfcmVmIjoiY3JlZGVudGlhbC1yZWY6ZW52L1NZTlRIRVRJQ19TRUNSRVQiLCJlbmRwb2ludF9yZWYiOiJlbmRwb2ludC1yZWY6c3ludGhldGljL2FuZHJvaWQiLCJleHBlY3RlZF9kZXZpY2VfaWQiOiJkZXZpY2Vfc3ludGhldGljXzAwMSIsImhvc3QiOiIxMjcuMC4wLjEiLCJwYWlyaW5nX2lkIjoicGFpcl9zeW50aGV0aWNfMDAxIiwicG9ydCI6NDQzMjEsInNlc3Npb25fYmluZGluZ19yZWYiOiJzZXNzaW9uLWJpbmRpbmctcmVmOnN5bnRoZXRpYy9zZXNzaW9uIiwidGxzX2NlcnRpZmljYXRlX3NoYTI1NiI6InNoYTI1Nl9hYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIn0sInBhaXJpbmdfZXhwaXJlc19hdF9tcyI6IjE4MDI1OTIwMDAwMDAifQ"

        assertEquals(golden, payload)
        AgentPairingEnvelope.parse(payload, now).use { parsed ->
            assertEquals(pairing.pairingId, parsed.pairing.pairingId)
            assertEquals(bootstrapId, parsed.bootstrapId)
            assertEquals(now.plusSeconds(300), parsed.expiresAt)
            assertEquals(now.plusSeconds(30 * 24 * 60 * 60L), parsed.pairingExpiresAt)
            assertArrayEquals(ByteArray(32) { 0x42 }, parsed.bootstrapSecret)
        }
    }

    @Test
    fun expiredOversizedAndMalformedPayloads_failClosed() {
        val pairingExpiry = now.plusSeconds(30 * 24 * 60 * 60L)
        val payload = AgentPairingEnvelope.encode(
            pairing,
            bootstrapId,
            secretText,
            now.plusSeconds(300),
            pairingExpiry,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(payload, now.plusSeconds(301))
        }
        val oversizedLifetime =
            AgentPairingEnvelope.encode(
                pairing,
                bootstrapId,
                secretText,
                now.plusSeconds(601),
                pairingExpiry,
            )
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(oversizedLifetime, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(payload.removePrefix("ameme-"), now)
        }
        val encodedJson = payload.removePrefix(AgentPairingEnvelope.PREFIX)
        val json = String(Base64.getUrlDecoder().decode(encodedJson), Charsets.UTF_8)
        val withWhitespace = encodeRawJson(" $json")
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(withWhitespace, now)
        }
        val withDuplicateVersion = encodeRawJson(
            json.replaceFirst("{", "{\"envelope_version\":1,"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(withDuplicateVersion, now)
        }
        val withUnknownField = encodeRawJson(json.dropLast(1) + ",\"unknown\":true}")
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(withUnknownField, now)
        }
        val shortSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        val withShortSecret = encodeRawJson(json.replace(secretText, shortSecret))
        assertThrows(IllegalArgumentException::class.java) {
            AgentPairingEnvelope.parse(withShortSecret, now)
        }
    }

    @Test
    fun createdPairingExportsFixedFiveMinuteBootstrapWithoutShorteningChannelCredential() {
        val pairingExpiry = now.plusSeconds(30 * 24 * 60 * 60L)
        val created = CreatedAgentPairing(
            material = AgentPairingMaterial(
                channelProtocol = pairing.channelProtocol,
                endpointRef = pairing.endpointRef,
                credentialRef = pairing.credentialRef,
                expectedDeviceId = pairing.expectedDeviceId,
                sessionBindingRef = pairing.sessionBindingRef,
                pairingId = pairing.pairingId,
                host = pairing.host,
                port = pairing.port,
                tlsCertificateSha256 = pairing.tlsCertificateSha256,
            ),
            developerChannelSecret = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { 0x24 }),
            bootstrapId = bootstrapId,
            bootstrapSecret = secretText,
            bootstrapExpiresAt = now.plusSeconds(300),
            expiresAt = pairingExpiry,
        )

        AgentPairingEnvelope.parse(created.pairingQrPayload(), now).use { parsed ->
            assertEquals(now.plusSeconds(300), parsed.expiresAt)
            assertEquals(pairingExpiry, parsed.pairingExpiresAt)
        }
    }

    private fun encodeRawJson(value: String): String =
        AgentPairingEnvelope.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.encodeToByteArray())
}
