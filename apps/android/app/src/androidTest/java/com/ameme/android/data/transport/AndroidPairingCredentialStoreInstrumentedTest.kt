package com.ameme.android.data.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.transport.channel.AndroidLocalNodeChannelCodec
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial
import com.ameme.android.data.transport.channel.AndroidPairingIssuedCredential
import com.ameme.android.data.transport.channel.AgentPairingBootstrapV2Codec
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
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
class AndroidPairingCredentialStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = Instant.parse("2026-07-29T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val bootstrapSecretText = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { 1 })
    private val channelSecret = Base64.getUrlEncoder().withoutPadding()
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
        bootstrapSecret = bootstrapSecretText,
        expiresAt = now.plusSeconds(300),
        pairingExpiresAt = now.plusSeconds(30L * 24 * 60 * 60),
    )
    private lateinit var store: AndroidKeystorePairingCredentialStore
    private lateinit var recordFile: File

    @Before
    fun setUp() {
        store = AndroidKeystorePairingCredentialStore(context, clock)
        runCatching(store::clearAndVerify)
        recordFile = File(
            context.noBackupFilesDir,
            AndroidKeystorePairingCredentialStore.RECORD_FILE_NAME,
        )
    }

    @After
    fun tearDown() {
        runCatching(store::clearAndVerify)
    }

    @Test
    fun pendingAndActiveRoundTripUseNonExportableKeyAndEncryptedNoBackupRecord() {
        val pending = store.savePending(payload, now)
        assertTrue(recordFile.isFile)
        val encryptedPending = recordFile.readText()
        assertFalse(encryptedPending.contains(payload))
        assertFalse(encryptedPending.contains(bootstrapSecretText))

        val key = store.clientKey(pending)
        val envelope = AgentPairingEnvelope.parse(payload, now)
        val publicKey = key.publicKeyX963()
        val nonce = "nonce_" + "1".repeat(64)
        val possession = AgentPairingBootstrapV2Codec.clientPossessionPayload(
            pairing = envelope.pairing,
            bootstrapId = envelope.bootstrapId,
            clientPublicKeyX963 = publicKey,
            clientNonce = nonce,
        )
        val signature = key.signPossession(possession)
        val hello = AgentPairingBootstrapV2Codec.buildClientHello(
            pairing = envelope.pairing,
            bootstrapId = envelope.bootstrapId,
            bootstrapSecret = envelope.bootstrapSecret,
            clientPublicKeyX963 = publicKey,
            clientNonce = nonce,
            possessionSignatureDer = signature,
        )
        AgentPairingBootstrapV2Codec.verifyClientHello(
            line = hello,
            pairing = envelope.pairing,
            expectedBootstrapId = envelope.bootstrapId,
            bootstrapSecret = envelope.bootstrapSecret,
        ).close()
        listOf(publicKey, possession, signature, hello).forEach { it.fill(0) }
        envelope.close()

        AndroidPairingIssuedCredential(
            pairing = pairing,
            credentialId = "cred_synthetic_001",
            channelSecret = channelSecret,
            clientKeyThumbprint = pending.clientKeyThumbprint,
            expiresAt = now.plusSeconds(30L * 24 * 60 * 60),
        ).use(store::saveActive)
        assertNull(store.loadPending(now = now))
        store.loadActive(now).use { active ->
            val restored = requireNotNull(active)
            assertEquals(pairing.pairingId, restored.pairing.pairingId)
            assertEquals("cred_synthetic_001", restored.credentialId)
            assertArrayEquals(channelSecret, restored.channelSecretCopy())
        }
        val encryptedActive = recordFile.readText()
        assertFalse(encryptedActive.contains(channelSecret.decodeToString()))
        assertFalse(encryptedActive.contains(pairing.pairingId))

        store.clearAndVerify()
        assertFalse(recordFile.exists())
        assertNull(store.loadActive(now))
    }

    @Test
    fun corruptOrExpiredRecordFailsClosedAndIsRemoved() {
        store.savePending(payload, now)
        recordFile.writeText("synthetic-corrupt-record")

        val corrupt = assertThrows(AndroidPairingCredentialStoreException::class.java) {
            store.loadPending(now = now)
        }
        assertEquals(AndroidPairingCredentialStoreFailure.Corrupt, corrupt.failure)
        assertFalse(recordFile.exists())

        val pending = store.savePending(payload, now)
        AndroidPairingIssuedCredential(
            pairing = pairing,
            credentialId = "cred_synthetic_002",
            channelSecret = channelSecret,
            clientKeyThumbprint = pending.clientKeyThumbprint,
            expiresAt = now.plusSeconds(600),
        ).use(store::saveActive)
        val expiredStore = AndroidKeystorePairingCredentialStore(
            context,
            Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC),
        )
        assertNull(expiredStore.loadActive(now.plusSeconds(601)))
        assertFalse(recordFile.exists())
        expiredStore.clearAndVerify()
    }
}
