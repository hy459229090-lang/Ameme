package com.ameme.android.data.transport

import com.ameme.android.data.transport.channel.AndroidAuthenticatedPairingClient
import com.ameme.android.data.transport.channel.AndroidAuthenticatedPairingClientFactory
import com.ameme.android.data.transport.channel.AndroidLocalNodeChannelCodec
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial
import com.ameme.android.data.transport.channel.AndroidPairingClientKey
import com.ameme.android.data.transport.channel.AndroidPairingIssuedCredential
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPairingExperienceConnectorTest {
    private val now = Instant.parse("2026-07-29T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
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
            .encodeToString(ByteArray(32) { 1 }),
        expiresAt = now.plusSeconds(300),
        pairingExpiresAt = now.plusSeconds(30L * 24 * 60 * 60),
    )

    @Test
    fun qrCandidateBecomesVisibleOnlyAfterBootstrapAndApplicationAuthentication() = runBlocking {
        val store = FakeCredentialStore()
        val clientFactory = FakeClientFactory(
            operations = setOf(AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT),
        )
        var provisions = 0
        val connector = connector(
            store = store,
            clientFactory = clientFactory,
            provision = { _ ->
                provisions += 1
                issued()
            },
        )

        val candidate = connector.resolvePairingPayload(payload)
        assertEquals(PairingExperienceMethod.QrCode, candidate.method)
        assertEquals(now.plusSeconds(300), candidate.authorizationExpiresAt)
        assertNull(store.pending)

        val connection = connector.connect(candidate)

        assertEquals(1, provisions)
        assertEquals(1, clientFactory.connectCount)
        assertTrue(store.active != null)
        assertFalse(connection.simulated)
        assertEquals(pairing.pairingId, connection.id)
        assertEquals(listOf("写入结构化工作记录"), connection.capabilities)
    }

    @Test
    fun invalidPayloadAndMissingCreateCapabilityFailClosed() = runBlocking {
        val store = FakeCredentialStore()
        val invalid = assertThrows(PairingExperienceException::class.java) {
            runBlocking {
                connector(store).resolvePairingPayload("ameme-pairing-v2:not-valid")
            }
        }
        assertEquals(PairingExperienceFailure.InvalidPairingPayload, invalid.failure)

        val connector = connector(
            store = store,
            clientFactory = FakeClientFactory(operations = setOf("visible_events")),
        )
        val candidate = connector.resolvePairingPayload(payload)
        val failed = assertThrows(PairingExperienceException::class.java) {
            runBlocking { connector.connect(candidate) }
        }
        assertEquals(PairingExperienceFailure.ConnectionFailed, failed.failure)
        assertTrue(store.active != null)
    }

    @Test
    fun pendingBootstrapAndActiveCredentialRestoreOnlyAfterRealReconnect() = runBlocking {
        val store = FakeCredentialStore()
        store.savePending(payload, now)
        val clientFactory = FakeClientFactory(
            operations = setOf(AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT),
        )
        val connector = connector(store, clientFactory)

        val restored = connector.restoreConnection()

        assertNotNull(restored)
        assertEquals(1, clientFactory.connectCount)
        assertTrue(store.active != null)
        assertNull(store.pending)

        connector.disconnect(requireNotNull(restored))
        assertTrue(clientFactory.lastClient?.closed == true)
        assertTrue(store.cleared)
        assertNull(connector.restoreConnection())
    }

    @Test
    fun expiredCandidateCannotPersistOrProvisionASecret() = runBlocking {
        val candidateClock = Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC)
        val store = FakeCredentialStore()
        val connector = ProductionPairingExperienceConnector(
            lanConnector = FakeLanConnector(),
            credentialStore = store,
            bootstrapProvisioner = { _, _ -> issued() },
            clientFactory = FakeClientFactory(
                setOf(AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT),
            ),
            clock = candidateClock,
            retryDelaysMillis = emptyList(),
        )
        val candidate = PairingExperienceCandidate(
            id = pairing.pairingId,
            deviceName = "Ameme 设备",
            agentName = "Ameme Local Node",
            method = PairingExperienceMethod.QrCode,
            capabilities = listOf("写入结构化工作记录"),
            simulated = false,
            authorizationExpiresAt = now.plusSeconds(300),
        )

        val failed = assertThrows(PairingExperienceException::class.java) {
            runBlocking { connector.connect(candidate) }
        }

        assertEquals(PairingExperienceFailure.CandidateUnavailable, failed.failure)
        assertNull(store.pending)
        assertNull(store.active)
    }

    @Test
    fun scanningAnotherQrReplacesTheUnconfirmedInMemoryBootstrap() = runBlocking {
        val store = FakeCredentialStore()
        val connector = connector(
            store = store,
            provision = { candidatePayload ->
                AgentPairingEnvelope.parse(candidatePayload, now).use {
                    issued(it.pairing)
                }
            },
        )
        val firstCandidate = connector.resolvePairingPayload(payload)
        val secondPairing = AndroidLocalNodePairingMaterial(
            channelProtocol = pairing.channelProtocol,
            endpointRef = pairing.endpointRef,
            credentialRef = pairing.credentialRef,
            pairingId = "pair_synthetic_002",
            expectedDeviceId = "device_synthetic_002",
            sessionBindingRef = "session-binding-ref:synthetic/session-2",
            host = pairing.host,
            port = pairing.port,
            tlsCertificateSha256 = pairing.tlsCertificateSha256,
        )
        val secondPayload = AgentPairingEnvelope.encode(
            pairing = secondPairing,
            bootstrapId = "boot_" + "c".repeat(32),
            bootstrapSecret = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { 2 }),
            expiresAt = now.plusSeconds(300),
            pairingExpiresAt = now.plusSeconds(30L * 24 * 60 * 60),
        )
        val secondCandidate = connector.resolvePairingPayload(secondPayload)

        val replaced = assertThrows(PairingExperienceException::class.java) {
            runBlocking { connector.connect(firstCandidate) }
        }
        assertEquals(PairingExperienceFailure.CandidateUnavailable, replaced.failure)
        assertNull(store.pending)

        val connected = connector.connect(secondCandidate)
        assertEquals(secondPairing.pairingId, connected.id)
    }

    private fun connector(
        store: FakeCredentialStore = FakeCredentialStore(),
        clientFactory: FakeClientFactory = FakeClientFactory(
            setOf(AndroidLocalNodeChannelCodec.OPERATION_CREATE_EVENT),
        ),
        provision: (String) -> AndroidPairingIssuedCredential = { issued() },
    ) = ProductionPairingExperienceConnector(
        lanConnector = FakeLanConnector(),
        credentialStore = store,
        bootstrapProvisioner = { pairingPayload, _ -> provision(pairingPayload) },
        clientFactory = clientFactory,
        clock = clock,
        retryDelaysMillis = emptyList(),
    )

    private fun issued(
        pairingMaterial: AndroidLocalNodePairingMaterial = pairing,
    ): AndroidPairingIssuedCredential =
        AndroidPairingIssuedCredential(
            pairing = pairingMaterial,
            credentialId = "cred_synthetic_001",
            channelSecret = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { 7 })
                .encodeToByteArray(),
            clientKeyThumbprint = THUMBPRINT,
            expiresAt = now.plusSeconds(30L * 24 * 60 * 60),
        )

    private class FakeLanConnector : PairingExperienceConnector {
        override val simulated: Boolean = false

        override suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate =
            throw PairingExperienceException(PairingExperienceFailure.NoDeviceFound)

        override suspend fun connect(
            candidate: PairingExperienceCandidate,
        ): PairingExperienceConnection =
            throw PairingExperienceException(PairingExperienceFailure.AuthorizationRequired)

        override suspend fun disconnect(connection: PairingExperienceConnection) = Unit
    }

    private class FakeCredentialStore : AndroidPairingCredentialStoring {
        var pending: AndroidPendingPairingCredential? = null
        var active: ActiveFields? = null
        var cleared = false

        override fun savePending(
            pairingPayload: String,
            now: Instant,
        ): AndroidPendingPairingCredential {
            val parsed = AgentPairingEnvelope.parse(pairingPayload, now)
            return parsed.use {
                AndroidPendingPairingCredential(
                    pairingId = it.pairing.pairingId,
                    pairingPayload = pairingPayload,
                    clientKeyThumbprint = THUMBPRINT,
                ).also { saved -> pending = saved }
            }
        }

        override fun loadPending(
            pairingId: String?,
            now: Instant,
        ): AndroidPendingPairingCredential? =
            pending?.takeIf { pairingId == null || pairingId == it.pairingId }

        override fun clientKey(
            pending: AndroidPendingPairingCredential,
        ): AndroidPairingClientKey = object : AndroidPairingClientKey {
            override fun publicKeyX963(): ByteArray = byteArrayOf(0x04) + ByteArray(64)

            override fun thumbprint(): String = THUMBPRINT

            override fun signPossession(payload: ByteArray): ByteArray = ByteArray(70)
        }

        override fun saveActive(issued: AndroidPairingIssuedCredential) {
            val secret = issued.channelSecretCopy()
            active = ActiveFields(
                pairing = issued.pairing,
                credentialId = issued.credentialId,
                secret = secret,
                expiresAt = issued.expiresAt,
            )
            pending = null
        }

        override fun loadActive(now: Instant): AndroidStoredPairingCredential? =
            active?.takeIf { it.expiresAt.isAfter(now) }?.let {
                AndroidStoredPairingCredential(
                    pairing = it.pairing,
                    credentialId = it.credentialId,
                    channelSecret = it.secret,
                    clientKeyThumbprint = THUMBPRINT,
                    expiresAt = it.expiresAt,
                )
            }

        override fun clearAndVerify() {
            pending = null
            active?.secret?.fill(0)
            active = null
            cleared = true
        }
    }

    private data class ActiveFields(
        val pairing: AndroidLocalNodePairingMaterial,
        val credentialId: String,
        val secret: ByteArray,
        val expiresAt: Instant,
    )

    private class FakeClientFactory(
        private val operations: Set<String>,
        private val failure: Throwable? = null,
    ) : AndroidAuthenticatedPairingClientFactory {
        var connectCount = 0
        var lastClient: FakeClient? = null

        override fun create(
            pairing: AndroidLocalNodePairingMaterial,
            channelSecret: ByteArray,
        ): AndroidAuthenticatedPairingClient =
            FakeClient(pairing, operations, failure) {
                connectCount += 1
            }.also { lastClient = it }
    }

    private class FakeClient(
        override val pairing: AndroidLocalNodePairingMaterial,
        override val supportedOperations: Set<String>,
        private val failure: Throwable?,
        private val onConnect: () -> Unit,
    ) : AndroidAuthenticatedPairingClient {
        var closed = false

        override suspend fun connect() {
            onConnect()
            failure?.let { throw it }
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val THUMBPRINT =
            "sha256_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
