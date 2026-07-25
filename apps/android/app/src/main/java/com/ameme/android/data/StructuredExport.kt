package com.ameme.android.data

import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StructuredExportEnvelope(
    val schemaVersion: Int,
    val space: String,
    val exportedAt: String,
    val eventCount: Int,
    val events: List<StructuredExportEvent>,
)

@Serializable
data class StructuredExportEvent(
    val id: String,
    val localDate: String,
    val time: String?,
    val title: String,
    val detail: String,
    val factStatus: String,
    val sourceLabel: String,
    val isLocalOnly: Boolean,
    val userWords: String?,
    val revision: Int,
    val eventType: String,
    val evidenceState: String,
    val sensitivity: String,
    val importance: Int,
)

object StructuredExportWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(
        events: List<MemoryEvent>,
        space: String,
        exportedAt: Instant = Instant.now(),
    ): String {
        val exportable = events
            .filter { it.sensitivity != Sensitivity.Restricted }
            .map { event ->
                StructuredExportEvent(
                    id = event.id,
                    localDate = event.localDate.toString(),
                    time = event.time?.toString(),
                    title = event.title,
                    detail = event.detail,
                    factStatus = event.factStatus.wireValue,
                    sourceLabel = event.sourceLabel,
                    isLocalOnly = event.isLocalOnly,
                    userWords = event.userWords,
                    revision = event.revision,
                    eventType = event.eventType.wireValue,
                    evidenceState = event.evidenceState.wireValue,
                    sensitivity = event.sensitivity.wireValue,
                    importance = event.importance,
                )
            }
        return json.encodeToString(
            StructuredExportEnvelope(
                schemaVersion = 1,
                space = space,
                exportedAt = exportedAt.toString(),
                eventCount = exportable.size,
                events = exportable,
            ),
        )
    }
}
