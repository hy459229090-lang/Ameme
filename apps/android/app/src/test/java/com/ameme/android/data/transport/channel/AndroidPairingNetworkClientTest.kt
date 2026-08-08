package com.ameme.android.data.transport.channel

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPairingNetworkClientTest {
    private val now = Instant.parse("2026-07-29T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val bootstrapSecret = ByteArray(32) { 1 }
    private val applicationSecret = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { 7 })
        .encodeToByteArray()
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
    private val payload = AgentPairingEnvelope.encode(
        pairing = pairing,
        bootstrapId = "boot_" + "b".repeat(32),
        bootstrapSecret = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bootstrapSecret),
        expiresAt = now.plusSeconds(300),
        pairingExpiresAt = now.plusSeconds(30L * 24 * 60 * 60),
    )

    @Test
    fun bootstrapUsesPossessionProofAndReturnsSeparateBoundCredential() = runBlocking {
        val key = SoftwareClientKey()
        var sentLine: ByteArray? = null
        val factory = PairingTlsLineConnectionFactory {
            FakeConnection { received ->
                sentLine = received.copyOf()
                val hello = AgentPairingBootstrapV2Codec.verifyClientHello(
                    line = received,
                    pairing = pairing,
                    expectedBootstrapId = "boot_" + "b".repeat(32),
                    bootstrapSecret = bootstrapSecret,
                )
                hello.use {
                    AgentPairingBootstrapV2Codec.buildServerHello(
                        clientHello = it,
                        pairing = pairing,
                        bootstrapSecret = bootstrapSecret,
                        serverNonce = "nonce_" + "2".repeat(64),
                        credentialId = "cred_synthetic_001",
                        credentialSecret = applicationSecret.decodeToString(),
                        credentialExpiresAt = now.plusSeconds(30L * 24 * 60 * 60),
                    )
                }
            }
        }
        val client = AndroidPairingBootstrapNetworkClient(factory, clock)

        client.provision(payload, key).use { issued ->
            assertEquals("cred_synthetic_001", issued.credentialId)
            assertEquals(key.thumbprint(), issued.clientKeyThumbprint)
            assertArrayEquals(applicationSecret, issued.channelSecretCopy())
            assertFalse(issued.channelSecretCopy().contentEquals(bootstrapSecret))
        }
        assertTrue(requireNotNull(sentLine).isNotEmpty())
        requireNotNull(sentLine).fill(0)
        Unit
    }

    @Test
    fun bootstrapRejectsCredentialExpiryOutsideEnvelope() {
        val key = SoftwareClientKey()
        val factory = PairingTlsLineConnectionFactory {
            FakeConnection { received ->
                AgentPairingBootstrapV2Codec.verifyClientHello(
                    line = received,
                    pairing = pairing,
                    expectedBootstrapId = "boot_" + "b".repeat(32),
                    bootstrapSecret = bootstrapSecret,
                ).use {
                    AgentPairingBootstrapV2Codec.buildServerHello(
                        clientHello = it,
                        pairing = pairing,
                        bootstrapSecret = bootstrapSecret,
                        serverNonce = "nonce_" + "3".repeat(64),
                        credentialId = "cred_synthetic_002",
                        credentialSecret = applicationSecret.decodeToString(),
                        credentialExpiresAt = now.plusSeconds(31L * 24 * 60 * 60),
                    )
                }
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AndroidPairingBootstrapNetworkClient(factory, clock)
                    .provision(payload, key)
                    .close()
            }
        }
    }

    @Test
    fun applicationClientAuthenticatesHmacBeforePublishingCapabilities() = runBlocking {
        val factory = PairingTlsLineConnectionFactory {
            FakeConnection { received ->
                val hello = AndroidLocalNodeChannelCodec.verifyClientHello(
                    line = received,
                    pairing = pairing,
                    secret = applicationSecret,
                )
                AndroidLocalNodeChannelCodec.buildServerHello(
                    clientHello = hello,
                    pairing = pairing,
                    secret = applicationSecret,
                    serverNonce = "nonce_" + "4".repeat(64),
                    sessionId = "sess_synthetic_001",
                    supportedOperations = setOf(
                        AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT,
                    ),
                )
            }
        }
        val client = AndroidLocalNodeNetworkClient(
            pairing = pairing,
            channelSecret = applicationSecret,
            connectionFactory = factory,
        )

        assertTrue(client.supportedOperations.isEmpty())
        client.connect()
        assertEquals(
            setOf(AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT),
            client.supportedOperations,
        )
        client.close()
        assertTrue(client.supportedOperations.isEmpty())
        assertFalse(client.toString().contains(applicationSecret.decodeToString()))
    }

    @Test
    fun applicationClientRejectsUnauthenticatedServerHello() {
        val factory = PairingTlsLineConnectionFactory {
            FakeConnection {
                """{"message_type":"server_hello","proof":"hmac_${"0".repeat(64)}"}"""
                    .encodeToByteArray()
            }
        }
        val client = AndroidLocalNodeNetworkClient(
            pairing = pairing,
            channelSecret = applicationSecret,
            connectionFactory = factory,
        )

        assertThrows(AgentLocalNodeChannelViolation::class.java) {
            runBlocking { client.connect() }
        }
        assertTrue(client.supportedOperations.isEmpty())
    }

    private class FakeConnection(
        private val response: (ByteArray) -> ByteArray,
    ) : PairingTlsLineConnection {
        private var sent: ByteArray? = null
        private var closed = false

        override fun sendLine(line: ByteArray, maximum: Int) {
            check(!closed && line.isNotEmpty() && line.size <= maximum)
            sent = line.copyOf()
        }

        override fun receiveLine(maximum: Int): ByteArray {
            check(!closed)
            val request = requireNotNull(sent)
            val value = response(request)
            check(value.isNotEmpty() && value.size <= maximum)
            return value
        }

        override fun close() {
            closed = true
            sent?.fill(0)
            sent = null
        }
    }

    private class SoftwareClientKey : AndroidPairingClientKey {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        override fun publicKeyX963(): ByteArray {
            val publicKey = keyPair.public as ECPublicKey
            return byteArrayOf(0x04) +
                fixed32(publicKey.w.affineX.toByteArray()) +
                fixed32(publicKey.w.affineY.toByteArray())
        }

        override fun thumbprint(): String {
            val publicKey = publicKeyX963()
            return try {
                "sha256_" + MessageDigest.getInstance("SHA-256")
                    .digest(publicKey)
                    .joinToString("") { "%02x".format(it) }
            } finally {
                publicKey.fill(0)
            }
        }

        override fun signPossession(payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }

        private fun fixed32(value: ByteArray): ByteArray {
            val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
            require(unsigned.size <= 32)
            return ByteArray(32 - unsigned.size) + unsigned
        }
    }
}
