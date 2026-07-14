package com.ameme.android.data.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.LocalMemoryRepository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One-time local demo seam. The production APK exposes no seed or import endpoint. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSerializationApi::class)
class CodexSkillDemoSeedInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.systemUTC()
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
    }

    @Test
    fun localCodexSkillSeedIntoProductionSqlCipher() = runBlocking {
        assumeTrue(
            "Runs only for an explicitly supplied local Codex demo seed.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val seedFile = context.filesDir.resolve(SEED_FILE_NAME)
        require(seedFile.isFile && seedFile.length() in 1..MAX_SEED_BYTES) {
            "local Codex demo seed is missing or oversized"
        }
        val document = try {
            json.decodeFromString<CodexDemoSeedDocument>(seedFile.readText(Charsets.UTF_8))
        } finally {
            assertTrue("one-time local seed must be removed", seedFile.delete())
        }
        document.validate()

        val repository = LocalMemoryRepository.open(
            context = context,
            spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
            seedSyntheticEvents = false,
        )
        try {
            val existing = repository.loadActiveEvents()
                .map { event -> "${event.localDate}|${event.time}|${event.detail}" }
                .toMutableSet()
            val session = VerifiedAgentLocalNodeSession(
                callerId = CALLER_ID,
                grantId = GRANT_ID,
                purpose = MemoryRepositoryAgentLocalNodeEndpoint.PURPOSE_AUTONOMOUS_MEMORY,
                allowedSpaces = setOf(LocalEventDatabase.DEFAULT_SPACE_ID),
                allowedMemoryTypes = setOf(MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT),
                allowedOperations = setOf(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT),
                allowedSensitivities = document.events.map(CodexDemoSeedEvent::sensitivity).toSet(),
                allowedDataClasses = setOf("structured"),
                expiresAt = clock.instant().plusSeconds(3_600),
            )
            val endpoint = MemoryRepositoryAgentLocalNodeEndpoint.enabledForVerifiedSession(
                repository = repository,
                repositorySpaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
                verifiedSession = session,
                idempotencyRegistry = DemoIdempotencyRegistry(),
                clock = clock,
            )
            var inserted = 0
            var skipped = 0
            document.events.forEachIndexed { index, event ->
                val eventTimestamp = OffsetDateTime.parse(event.eventTime)
                val signature = "${eventTimestamp.toLocalDate()}|" +
                    "${eventTimestamp.toLocalTime().withSecond(0).withNano(0)}|${event.content.trim()}"
                if (signature in existing) {
                    skipped += 1
                    return@forEachIndexed
                }
                val payload = AgentLocalNodeCreateEventPayload(
                    space = LocalEventDatabase.DEFAULT_SPACE_ID,
                    memoryType = MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT,
                    content = event.content,
                    eventType = event.eventType,
                    evidenceState = event.evidenceState,
                    factStatus = event.factStatus,
                    sensitivity = event.sensitivity,
                    dataClass = event.dataClass,
                    now = Instant.now(clock).toString(),
                    eventTime = event.eventTime,
                )
                val payloadBytes = AgentLocalNodeApplicationCodec.encodeCreateEvent(payload)
                val request = try {
                    AgentLocalNodeRequest(
                        control = AgentLocalNodeControl(
                            protocolVersion = AgentLocalNodeControl.PROTOCOL_VERSION,
                            requestId = "req_codex_demo_${index}_${event.sourceTurnId.takeLast(12)}",
                            callerId = CALLER_ID,
                            grantId = GRANT_ID,
                            purpose = MemoryRepositoryAgentLocalNodeEndpoint.PURPOSE_AUTONOMOUS_MEMORY,
                            spaces = setOf(LocalEventDatabase.DEFAULT_SPACE_ID),
                            memoryTypes = setOf(MemoryRepositoryAgentLocalNodeEndpoint.MEMORY_TYPE_EVENT),
                            operation = MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_CREATE_EVENT,
                            idempotencySlot = event.idempotencySlot,
                            payloadDigest = AgentLocalNodeApplicationCodec.canonicalDigest(payloadBytes),
                        ),
                        payload = payloadBytes,
                    )
                } finally {
                    payloadBytes.fill(0)
                }
                endpoint.exchange(request).use { response ->
                    assertEquals(AgentLocalNodeStatus.Ok, response.status)
                }
                existing.add(signature)
                inserted += 1
            }
            assertEquals(document.events.size, inserted + skipped)
            assertTrue(inserted > 0 || skipped > 0)
            println("AMEME_CODEX_DEMO_SEED inserted=$inserted skipped=$skipped")
        } finally {
            repository.close()
        }
    }

    @Serializable
    private data class CodexDemoSeedDocument(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("thread_id") val threadId: String,
        @SerialName("generated_at") val generatedAt: String,
        val events: List<CodexDemoSeedEvent>,
    ) {
        fun validate() {
            require(schemaVersion == 1) { "unsupported local demo seed version" }
            require(IDENTIFIER.matches(threadId)) { "invalid thread_id" }
            OffsetDateTime.parse(generatedAt)
            require(events.size in 1..MAX_EVENTS) { "invalid local demo event count" }
            require(events.map(CodexDemoSeedEvent::sourceTurnId).toSet().size == events.size) {
                "source_turn_id must be unique"
            }
            events.forEach(CodexDemoSeedEvent::validate)
        }
    }

    @Serializable
    private data class CodexDemoSeedEvent(
        @SerialName("source_turn_id") val sourceTurnId: String,
        val content: String,
        @SerialName("event_time") val eventTime: String,
        @SerialName("event_type") val eventType: String,
        @SerialName("evidence_state") val evidenceState: String,
        @SerialName("fact_status") val factStatus: String,
        val sensitivity: String,
        @SerialName("data_class") val dataClass: String,
        @SerialName("idempotency_slot") val idempotencySlot: String,
    ) {
        fun validate() {
            require(IDENTIFIER.matches(sourceTurnId)) { "invalid source_turn_id" }
            require(content.isNotBlank() && content.length <= 4_000 && '\u0000' !in content) {
                "invalid demo content"
            }
            OffsetDateTime.parse(eventTime)
            require(eventType in EVENT_TYPES) { "invalid event_type" }
            require(evidenceState to factStatus in EVIDENCE_FACT_PAIRS) {
                "invalid evidence/fact pair"
            }
            require(sensitivity in setOf("public", "personal")) { "unsupported sensitivity" }
            require(dataClass == "structured") { "unsupported data_class" }
            require(IDEMPOTENCY_SLOT.matches(idempotencySlot)) { "invalid idempotency_slot" }
        }
    }

    private class DemoIdempotencyRegistry : AgentLocalNodeIdempotencyRegistry {
        private data class Stored(
            val digest: String,
            val outcome: AgentLocalNodeCaptureOutcome,
        )

        private val stored = mutableMapOf<AgentLocalNodeIdempotencyBinding, Stored>()

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
    }

    private companion object {
        const val ENABLE_ARGUMENT = "amemeCodexDemoSeed"
        const val SEED_FILE_NAME = "ameme-codex-demo-seed.json"
        const val MAX_SEED_BYTES = 64 * 1024L
        const val MAX_EVENTS = 12
        const val CALLER_ID = "agent_codex_local_demo"
        const val GRANT_ID = "grant_codex_local_demo"
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val IDEMPOTENCY_SLOT = Regex("idem_[0-9a-f]{64}")
        val EVENT_TYPES = setOf(
            "activity",
            "communication",
            "decision",
            "result",
            "state_change",
            "milestone",
            "experience",
        )
        val EVIDENCE_FACT_PAIRS = setOf(
            "observed" to "confirmed",
            "user_asserted" to "user_asserted",
            "inferred" to "low_confidence_candidate",
        )
    }
}
