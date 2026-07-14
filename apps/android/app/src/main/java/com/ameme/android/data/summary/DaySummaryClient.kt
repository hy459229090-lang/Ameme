package com.ameme.android.data.summary

import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GeneratedDaySummary(
    val text: String,
    val modelOrRuleVersion: String,
)

class DaySummaryClientException(
    val code: String,
    val retryable: Boolean = false,
) : Exception(code)

fun interface DaySummaryClient {
    fun generate(
        snapshot: DaySummarySnapshot,
        subjectRef: String,
        zoneId: ZoneId,
    ): GeneratedDaySummary
}

class HttpDaySummaryClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 20_000,
) : DaySummaryClient {
    private val endpoint: URL

    init {
        require(connectTimeoutMillis in 100..30_000 && readTimeoutMillis in 100..120_000)
        val base = URI(baseUrl.trim().trimEnd('/'))
        val debugLocalCleartext = base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(base.scheme == "https" || debugLocalCleartext) {
            "Inference gateway must use HTTPS except for a debug loopback or Android emulator host alias"
        }
        require(base.rawQuery == null && base.rawFragment == null && base.userInfo == null)
        endpoint = base.resolve("${base.path.trimEnd('/')}/v1/day-summary").toURL()
    }

    override fun generate(
        snapshot: DaySummarySnapshot,
        subjectRef: String,
        zoneId: ZoneId,
    ): GeneratedDaySummary {
        require(snapshot.ledgerRevision >= 1) { "Day ledger revision is unavailable" }
        require(SUBJECT_REF.matches(subjectRef)) { "subjectRef is invalid" }
        val events = snapshot.eligibleEvents
            .take(MAX_EVENTS)
        require(events.size >= MIN_EVENTS) { "Not enough eligible structured events" }
        val request = DaySummaryRequestDto(
            requestId = "req_${UUID.randomUUID().toString().replace("-", "")}",
            subjectRef = subjectRef,
            ledgerId = "day_${snapshot.localDate.toString().replace("-", "")}",
            ledgerRevision = snapshot.ledgerRevision,
            localDate = snapshot.localDate.toString(),
            timezone = zoneId.id,
            events = events.map { it.toProjection(zoneId) },
        )
        val requestBody = JSON.encodeToString(request).encodeToByteArray()
        require(requestBody.size <= MAX_BODY_BYTES) { "Summary request exceeds the client budget" }
        val connection = endpoint.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.use { it.write(requestBody) }
            val status = connection.responseCode
            val responseBytes = readBounded(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_BODY_BYTES,
            )
            val root = JSON.parseToJsonElement(responseBytes.decodeToString()).jsonObject
            if (status !in 200..299) throw root.toGatewayException()
            root.requireExactKeys(setOf("summary"))
            val response = JSON.decodeFromJsonElement(DaySummaryResponseDto.serializer(), root)
            response.summary.validateAgainst(request)
            response.summary.toGenerated()
        } catch (known: DaySummaryClientException) {
            throw known
        } catch (failure: Exception) {
            throw DaySummaryClientException("GATEWAY_UNAVAILABLE", retryable = true)
        } finally {
            requestBody.fill(0)
            connection.disconnect()
        }
    }

    private fun MemoryEvent.toProjection(zoneId: ZoneId): StructuredEventProjectionDto {
        val timestamp = time?.let { localTime ->
            localDate.atTime(localTime).atZone(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        return StructuredEventProjectionDto(
            eventId = id,
            revision = revision,
            localDate = localDate.toString(),
            eventTime = timestamp,
            eventType = eventType.wireValue,
            title = title.take(200),
            detail = detail.take(1_000),
            factStatus = when (factStatus) {
                FactStatus.Confirmed -> "confirmed"
                FactStatus.UserAsserted -> "user_asserted"
                FactStatus.Planned -> "planned"
                else -> throw IllegalArgumentException("Event fact status is not summary eligible")
            },
            evidenceState = evidenceState.wireValue,
            sensitivity = sensitivity.wireValue,
            importance = importance / 100.0,
        )
    }

    private fun SummaryDto.validateAgainst(request: DaySummaryRequestDto) {
        if (
            schemaVersion != OUTPUT_SCHEMA || ledgerId != request.ledgerId ||
            ledgerRevision != request.ledgerRevision || localDate != request.localDate ||
            rulesVersion != RULES_VERSION
        ) {
            throw DaySummaryClientException("RESPONSE_BINDING_MISMATCH")
        }
        val knownIds = request.events.mapTo(mutableSetOf(), StructuredEventProjectionDto::eventId)
        (highlights + progress + openLoops).forEach { item ->
            if (item.eventIds.isEmpty() || item.eventIds.any { it !in knownIds }) {
                throw DaySummaryClientException("RESPONSE_EVIDENCE_MISMATCH")
            }
        }
    }

    private fun SummaryDto.toGenerated(): GeneratedDaySummary {
        val lines = buildList {
            headline.trim().takeIf(String::isNotEmpty)?.let(::add)
            overview.trim().takeIf(String::isNotEmpty)?.let(::add)
            highlights.forEach { add("亮点：${it.text.trim()}") }
            progress.forEach { add("进展：${it.text.trim()}") }
            openLoops.forEach { add("待续：${it.text.trim()}") }
        }.distinct()
        val text = lines.joinToString("\n").take(4_000)
        if (text.isBlank()) throw DaySummaryClientException("EMPTY_SUMMARY")
        val version = listOf(
            rulesVersion,
            model.provider,
            model.modelId,
            model.providerVersion,
        ).joinToString(":").take(256)
        return GeneratedDaySummary(text, version)
    }

    private fun JsonObject.toGatewayException(): DaySummaryClientException {
        return try {
            requireExactKeys(setOf("error"))
            val error = getValue("error").jsonObject
            error.requireExactKeys(setOf("code", "retryable"))
            DaySummaryClientException(
                code = error.getValue("code").jsonPrimitive.content,
                retryable = error.getValue("retryable").jsonPrimitive.content.toBooleanStrict(),
            )
        } catch (_: Exception) {
            DaySummaryClientException("GATEWAY_ERROR")
        }
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        if (keys != expected) throw DaySummaryClientException("RESPONSE_SCHEMA_MISMATCH")
    }

    private fun readBounded(stream: java.io.InputStream?, maximum: Int): ByteArray {
        if (stream == null) throw DaySummaryClientException("EMPTY_GATEWAY_RESPONSE")
        return BufferedInputStream(stream).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maximum) throw DaySummaryClientException("GATEWAY_RESPONSE_TOO_LARGE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
            isLenient = false
        }
        private val SUBJECT_REF = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private const val OUTPUT_SCHEMA = "ameme.day-summary.v1"
        private const val RULES_VERSION = "ameme.day-summary-rules.v1"
        private const val MAX_EVENTS = 100
        private const val MIN_EVENTS = 2
        private const val MAX_BODY_BYTES = 65_536
    }
}

@Serializable
private data class DaySummaryRequestDto(
    @SerialName("schema_version") val schemaVersion: String = "ameme.day-event-projection.v1",
    @SerialName("request_id") val requestId: String,
    @SerialName("subject_ref") val subjectRef: String,
    @SerialName("data_class") val dataClass: String = "structured",
    @SerialName("ledger_id") val ledgerId: String,
    @SerialName("ledger_revision") val ledgerRevision: Int,
    @SerialName("local_date") val localDate: String,
    val timezone: String,
    val events: List<StructuredEventProjectionDto>,
    @SerialName("output_token_budget") val outputTokenBudget: Int = 800,
)

@Serializable
private data class StructuredEventProjectionDto(
    @SerialName("event_id") val eventId: String,
    val revision: Int,
    @SerialName("local_date") val localDate: String,
    @SerialName("event_time") val eventTime: String?,
    @SerialName("event_type") val eventType: String,
    val title: String,
    val detail: String,
    @SerialName("fact_status") val factStatus: String,
    @SerialName("evidence_state") val evidenceState: String,
    val sensitivity: String,
    val importance: Double,
)

@Serializable
private data class DaySummaryResponseDto(val summary: SummaryDto)

@Serializable
private data class SummaryDto(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("ledger_id") val ledgerId: String,
    @SerialName("ledger_revision") val ledgerRevision: Int,
    @SerialName("local_date") val localDate: String,
    val headline: String,
    val overview: String,
    val highlights: List<SummaryItemDto>,
    val progress: List<SummaryItemDto>,
    @SerialName("open_loops") val openLoops: List<SummaryItemDto>,
    @SerialName("rules_version") val rulesVersion: String,
    val model: SummaryModelDto,
)

@Serializable
private data class SummaryItemDto(
    val text: String,
    @SerialName("event_ids") val eventIds: List<String>,
)

@Serializable
private data class SummaryModelDto(
    val provider: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("provider_version") val providerVersion: String,
    @SerialName("response_id") val responseId: String,
)
