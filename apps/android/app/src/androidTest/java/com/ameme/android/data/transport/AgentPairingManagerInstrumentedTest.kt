package com.ameme.android.data.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.transport.channel.AgentPairingBootstrapV2Codec
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPairingManagerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = Instant.parse("2026-07-14T00:00:00Z")
    private lateinit var manager: AgentPairingManager

    @Before
    fun setUp() {
        manager = AgentPairingManager(context, Clock.fixed(now, ZoneOffset.UTC))
        manager.revoke()
    }

    @After
    fun tearDown() {
        manager.revoke()
    }

    @Test
    fun create_load_and_revoke_keep_secret_out_of_pairing_file() {
        val created = manager.create(host = "127.0.0.1", port = 43_821)
        val developerChannelSecret = requireNotNull(created.developerChannelSecret)
        val pairingFileText = manager.pairingFile().readText()
        val persisted = AgentPairingManager.json.decodeFromString<AgentPairingMaterial>(pairingFileText)

        assertEquals(created.material, persisted)
        assertEquals("ameme.agent-local-node.channel.v1", persisted.channelProtocol)
        assertEquals("credential-ref:env/AMEME_ANDROID_PAIRING_SECRET", persisted.credentialRef)
        assertTrue(persisted.tlsCertificateSha256.matches(Regex("sha256_[0-9a-f]{64}")))
        assertFalse(pairingFileText.contains(developerChannelSecret))
        assertFalse(pairingFileText.contains(created.bootstrapSecret))
        assertEquals(
            32,
            java.util.Base64.getUrlDecoder().decode(developerChannelSecret).size,
        )
        assertEquals(
            32,
            java.util.Base64.getUrlDecoder().decode(created.bootstrapSecret).size,
        )

        val active = requireNotNull(manager.loadActive())
        try {
            assertEquals(created.material, active.material)
            assertEquals(now.plusSeconds(30L * 24 * 60 * 60), active.expiresAt)
            assertEquals(1, active.credentials.size)
            assertEquals(AgentPairingCredentialKind.Developer, active.credentials.single().kind)
            assertArrayEquals(
                developerChannelSecret.encodeToByteArray(),
                active.credentials.single().secretCopy(),
            )
            assertEquals(
                setOf("create_event", "append_revision", "undo_capture", "visible_events"),
                active.accessGrantPolicy.operations,
            )
        } finally {
            active.close()
        }

        manager.revoke()
        assertNull(manager.loadActive())
        assertFalse(manager.pairingFile().exists())
        assertFalse(
            java.io.File(manager.pairingFile().parentFile, "${manager.pairingFile().name}.tmp")
                .exists(),
        )
    }

    @Test
    fun revoke_removes_interrupted_temporary_pairing_material() {
        manager.create(host = "127.0.0.1", port = 43_821)
        val temporary = java.io.File(
            manager.pairingFile().parentFile,
            "${manager.pairingFile().name}.tmp",
        )
        temporary.writeText("content-free interrupted material")

        manager.revoke()

        assertNull(manager.loadActive())
        assertFalse(manager.pairingFile().exists())
        assertFalse(temporary.exists())
    }

    @Test
    fun expired_pairing_is_not_loaded() {
        manager.create(host = "127.0.0.1", port = 43_821)
        val expiredView = AgentPairingManager(
            context,
            Clock.fixed(now.plusSeconds(31L * 24 * 60 * 60), ZoneOffset.UTC),
        )

        assertNull(expiredView.loadActive())
        assertFalse(expiredView.pairingFile().exists())
    }

    @Test
    fun legacy_pairing_without_explicit_operations_is_revoked_instead_of_expanded() {
        manager.create(host = "127.0.0.1", port = 43_821)
        val preferences = context.getSharedPreferences(
            "ameme_agent_pairing",
            android.content.Context.MODE_PRIVATE,
        )
        assertTrue(preferences.edit().remove("grant_operations").commit())

        assertNull(manager.loadActive())
        assertFalse(manager.pairingFile().exists())
    }

    @Test
    fun releaseModePersistsNoDeveloperBearer_andActivatesOnlyProvisionedDeviceCredential() {
        val releaseManager = AgentPairingManager(
            context = context,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            enableDeveloperCredential = false,
        )
        val created = releaseManager.create(host = "127.0.0.1", port = 43_821)
        assertNull(created.developerChannelSecret)
        releaseManager.loadActive().use { pending ->
            assertTrue(requireNotNull(pending).credentials.isEmpty())
        }

        val envelope = AgentPairingEnvelope.parse(created.pairingQrPayload(), now)
        val hello = bootstrapHello(envelope, p256KeyPair(), '7')
        val response = releaseManager.provisionBootstrap(hello)
        releaseManager.loadActive().use { active ->
            assertEquals(
                listOf(AgentPairingCredentialKind.DeviceBootstrapV2),
                requireNotNull(active).credentials.map { it.kind },
            )
        }

        hello.fill(0)
        response.fill(0)
        envelope.close()
    }

    @Test
    fun bootstrap_isConsumedOnce_retriesOnlySameDeviceKey_andIssuesSeparateCredential() {
        val created = manager.create(host = "127.0.0.1", port = 43_821)
        val envelope = AgentPairingEnvelope.parse(created.pairingQrPayload(), now)
        val keyPair = p256KeyPair()
        val firstHello = bootstrapHello(
            envelope = envelope,
            keyPair = keyPair,
            nonceDigit = '1',
        )
        val firstResponse = manager.provisionBootstrap(firstHello)
        val verifiedFirst = AgentPairingBootstrapV2Codec.verifyClientHello(
            firstHello,
            envelope.pairing,
            envelope.bootstrapId,
            envelope.bootstrapSecret,
        )
        val issued = verifiedFirst.use { verified ->
            AgentPairingBootstrapV2Codec.verifyServerHello(
                firstResponse,
                verified,
                envelope.pairing,
                envelope.bootstrapSecret,
            )
        }
        issued.use { credential ->
            val active = requireNotNull(manager.loadActive())
            try {
                assertEquals(2, active.credentials.size)
                val qrCredential = active.credentials.single {
                    it.kind == AgentPairingCredentialKind.DeviceBootstrapV2
                }
                assertEquals(credential.credentialId, qrCredential.credentialId)
                assertArrayEquals(
                    credential.credentialSecretCopy(),
                    qrCredential.secretCopy(),
                )
            } finally {
                active.close()
            }

            val retryHello = bootstrapHello(
                envelope = envelope,
                keyPair = keyPair,
                nonceDigit = '2',
            )
            val retryResponse = manager.provisionBootstrap(retryHello)
            val retryVerified = AgentPairingBootstrapV2Codec.verifyClientHello(
                retryHello,
                envelope.pairing,
                envelope.bootstrapId,
                envelope.bootstrapSecret,
            )
            retryVerified.use {
                AgentPairingBootstrapV2Codec.verifyServerHello(
                    retryResponse,
                    it,
                    envelope.pairing,
                    envelope.bootstrapSecret,
                ).use { retried ->
                    assertEquals(credential.credentialId, retried.credentialId)
                    assertArrayEquals(
                        credential.credentialSecretCopy(),
                        retried.credentialSecretCopy(),
                    )
                }
            }
            retryHello.fill(0)
            retryResponse.fill(0)
        }

        val attackerHello = bootstrapHello(
            envelope = envelope,
            keyPair = p256KeyPair(),
            nonceDigit = '3',
        )
        assertThrows(IllegalStateException::class.java) {
            manager.provisionBootstrap(attackerHello)
        }
        listOf(firstHello, firstResponse, attackerHello).forEach { it.fill(0) }
        envelope.close()
    }

    @Test
    fun expiredPendingBootstrap_isRemoved_withoutRevivingQrCredential() {
        val created = manager.create(host = "127.0.0.1", port = 43_821)
        val envelope = AgentPairingEnvelope.parse(created.pairingQrPayload(), now)
        val expiredManager = AgentPairingManager(
            context,
            Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC),
        )
        val hello = bootstrapHello(envelope, p256KeyPair(), '4')

        assertThrows(IllegalStateException::class.java) {
            expiredManager.provisionBootstrap(hello)
        }
        val active = requireNotNull(expiredManager.loadActive())
        try {
            assertEquals(
                listOf(AgentPairingCredentialKind.Developer),
                active.credentials.map { it.kind },
            )
        } finally {
            active.close()
        }
        hello.fill(0)
        envelope.close()
    }

    private fun bootstrapHello(
        envelope: AgentPairingEnvelope.Parsed,
        keyPair: KeyPair,
        nonceDigit: Char,
    ): ByteArray {
        val publicKey = x963(keyPair)
        val nonce = "nonce_" + nonceDigit.toString().repeat(64)
        val possession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
            envelope.pairing,
            envelope.bootstrapId,
            publicKey,
            nonce,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(possession)
            sign()
        }
        possession.fill(0)
        return AgentPairingBootstrapV2Codec.buildClientHello(
            envelope.pairing,
            envelope.bootstrapId,
            envelope.bootstrapSecret,
            publicKey,
            nonce,
            signature,
        ).also {
            publicKey.fill(0)
            signature.fill(0)
        }
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
