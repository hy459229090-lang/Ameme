package com.ameme.android.data.transport.channel

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPairingBootstrapV2CodecTest {
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
    private val bootstrapId = "boot_" + "b".repeat(32)
    private val bootstrapSecret = ByteArray(32) { 0x42 }
    private val clientNonce = "nonce_" + "1".repeat(64)
    private val serverNonce = "nonce_" + "2".repeat(64)
    private val credentialSecretText = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { 0x24 })

    @Test
    fun signedBootstrapIssuesSeparateBoundCredentialAndRoundTrips() {
        val keyPair = p256KeyPair()
        val publicKey = x963(keyPair)
        val possession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
            pairing = pairing,
            bootstrapId = bootstrapId,
            clientPublicKeyX963 = publicKey,
            clientNonce = clientNonce,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(possession)
            sign()
        }
        possession.fill(0)
        val clientLine = AgentPairingBootstrapV2Codec.buildClientHello(
            pairing = pairing,
            bootstrapId = bootstrapId,
            bootstrapSecret = bootstrapSecret,
            clientPublicKeyX963 = publicKey,
            clientNonce = clientNonce,
            possessionSignatureDer = signature,
        )
        signature.fill(0)
        assertTrue(AgentPairingBootstrapV2Codec.isClientHello(clientLine))

        AgentPairingBootstrapV2Codec.verifyClientHello(
            line = clientLine,
            pairing = pairing,
            expectedBootstrapId = bootstrapId,
            bootstrapSecret = bootstrapSecret,
        ).use { clientHello ->
            assertEquals(bootstrapId, clientHello.bootstrapId)
            assertEquals(clientNonce, clientHello.clientNonce)
            assertArrayEquals(publicKey, clientHello.clientPublicKeyCopy())
            val credentialExpiresAt = Instant.parse("2026-08-28T00:00:00Z")
            val serverLine = AgentPairingBootstrapV2Codec.buildServerHello(
                clientHello = clientHello,
                pairing = pairing,
                bootstrapSecret = bootstrapSecret,
                serverNonce = serverNonce,
                credentialId = "cred_qr_" + "c".repeat(32),
                credentialSecret = credentialSecretText,
                credentialExpiresAt = credentialExpiresAt,
            )
            AgentPairingBootstrapV2Codec.verifyServerHello(
                line = serverLine,
                expectedClientHello = clientHello,
                pairing = pairing,
                bootstrapSecret = bootstrapSecret,
            ).use { serverHello ->
                assertEquals("cred_qr_" + "c".repeat(32), serverHello.credentialId)
                assertEquals(credentialExpiresAt, serverHello.credentialExpiresAt)
                assertArrayEquals(
                    credentialSecretText.encodeToByteArray(),
                    serverHello.credentialSecretCopy(),
                )
            }
            serverLine.fill(0)
        }
        clientLine.fill(0)
        publicKey.fill(0)
    }

    @Test
    fun wrongBootstrapSecretAndPossessionSignatureFailClosed() {
        val keyPair = p256KeyPair()
        val publicKey = x963(keyPair)
        val possession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
            pairing,
            bootstrapId,
            publicKey,
            clientNonce,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(possession)
            sign()
        }
        possession.fill(0)
        val valid = AgentPairingBootstrapV2Codec.buildClientHello(
            pairing,
            bootstrapId,
            bootstrapSecret,
            publicKey,
            clientNonce,
            signature,
        )
        val wrongSecret = bootstrapSecret.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(AgentLocalNodeChannelViolation::class.java) {
            AgentPairingBootstrapV2Codec.verifyClientHello(
                valid,
                pairing,
                bootstrapId,
                wrongSecret,
            )
        }

        val wrongKey = p256KeyPair()
        val wrongPossession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
            pairing,
            bootstrapId,
            publicKey,
            clientNonce,
        )
        val wrongSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(wrongKey.private)
            update(wrongPossession)
            sign()
        }
        val wrongSignatureLine = AgentPairingBootstrapV2Codec.buildClientHello(
            pairing,
            bootstrapId,
            bootstrapSecret,
            publicKey,
            clientNonce,
            wrongSignature,
        )
        assertThrows(AgentLocalNodeChannelViolation::class.java) {
            AgentPairingBootstrapV2Codec.verifyClientHello(
                wrongSignatureLine,
                pairing,
                bootstrapId,
                bootstrapSecret,
            )
        }
        listOf(
            valid,
            wrongSecret,
            wrongPossession,
            wrongSignature,
            wrongSignatureLine,
            signature,
            publicKey,
        ).forEach { it.fill(0) }
    }

    private fun p256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private fun x963(keyPair: KeyPair): ByteArray {
        val publicKey = keyPair.public as ECPublicKey
        return byteArrayOf(0x04) +
            fixed32(publicKey.w.affineX.toByteArray()) +
            fixed32(publicKey.w.affineY.toByteArray())
    }

    private fun fixed32(value: ByteArray): ByteArray {
        val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(unsigned.size <= 32)
        return ByteArray(32 - unsigned.size) + unsigned
    }
}
