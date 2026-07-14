package com.ameme.android.data.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.data.local.SyntheticDatabaseKeyProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLocalNodeEndpointInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile = File(context.cacheDir, "agent-local-node-endpoint-${System.nanoTime()}.db")
    private val key = ByteArray(32) { index -> (index + 17).toByte() }
    private val clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC)

    @After
    fun cleanUp() {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .forEach(File::delete)
        key.fill(0)
    }

    @Test
    fun verifiedCreateEventPersistsAcrossSqlCipherRepositoryReopen() = runBlocking {
        val repository = openRepository()
        val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
            repository = repository,
            repositorySpaceId = SPACE_ID,
            verifiedSession = VerifiedAgentLocalNodeSession(
                callerId = "agent_synthetic",
                grantId = "grant_synthetic",
                purpose = "autonomous_memory",
                allowedSpaces = setOf("space_personal", SPACE_ID),
                allowedMemoryTypes = setOf("event", "revision"),
                allowedOperations = setOf("create_event"),
                allowedSensitivities = setOf("personal"),
                allowedDataClasses = setOf("structured"),
                expiresAt = Instant.parse("2026-07-14T05:00:00Z"),
            ),
            idempotencyRegistry = OneCaptureTestRegistry(),
            clock = clock,
        )
        val payload = AgentLocalNodeCreateEventPayload(
            space = SPACE_ID,
            memoryType = "event",
            content = CONTENT,
            eventType = "result",
            evidenceState = "observed",
            factStatus = "confirmed",
            sensitivity = "personal",
            dataClass = "structured",
            now = NOW,
            eventTime = NOW,
        )
        val bytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
        val request = try {
            AgentLocalNodeRequest(
                control = AgentLocalNodeControl(
                    protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                    requestId = "req_android_sqlcipher",
                    callerId = "agent_synthetic",
                    grantId = "grant_synthetic",
                    purpose = "autonomous_memory",
                    spaces = setOf(SPACE_ID),
                    memoryTypes = setOf("event"),
                    operation = "create_event",
                    idempotencySlot = GOLDEN_SLOT,
                    payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(bytes),
                ),
                payload = bytes,
            )
        } finally {
            bytes.fill(0)
        }

        val response = endpoint.exchange(request)
        assertEquals(AgentLocalNodeStatus.Ok, response.status)
        val resultBytes = response.payloadCopy()
        val result = try {
            AgentLocalNodeApplicationCodec.decodeCreateEventResult(resultBytes)
        } finally {
            resultBytes.fill(0)
        }
        repository.close()

        openRepository().use { reopened ->
            val stored = reopened.loadActiveEvents().single { it.id == result.eventId }
            assertEquals(CONTENT, stored.detail)
            assertEquals("AgentAutonomous", stored.sourceLabel)
            assertTrue(databaseFile.length() > 0L)
        }
    }

    private fun openRepository(): LocalMemoryRepository = LocalMemoryRepository.open(
        context = context,
        spaceId = SPACE_ID,
        keyProvider = SyntheticDatabaseKeyProvider(key),
        databaseFile = databaseFile,
        clock = clock,
        seedSyntheticEvents = false,
    )

    private class OneCaptureTestRegistry : AgentLocalNodeIdempotencyRegistry {
        private var binding: AgentLocalNodeIdempotencyBinding? = null
        private var digest: String? = null
        private var outcome: AgentLocalNodeCaptureOutcome? = null

        @Synchronized
        override fun resolveOrCapture(
            binding: AgentLocalNodeIdempotencyBinding,
            payloadDigest: String,
            capture: () -> AgentLocalNodeCaptureOutcome,
        ): AgentLocalNodeIdempotencyResult {
            val existing = outcome
            if (existing != null) {
                return if (this.binding == binding && digest == payloadDigest) {
                    AgentLocalNodeIdempotencyResult.Applied(existing)
                } else {
                    AgentLocalNodeIdempotencyResult.Conflict
                }
            }
            val created = capture()
            this.binding = binding
            digest = payloadDigest
            outcome = created
            return AgentLocalNodeIdempotencyResult.Applied(created)
        }
    }

    private companion object {
        const val SPACE_ID = "space_work"
        const val NOW = "2026-07-14T04:00:00Z"
        const val CONTENT = "Synthetic SQLCipher Agent capture."
        const val GOLDEN_SLOT = "idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13"
    }
}
