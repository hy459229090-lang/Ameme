package com.ameme.android.data.source

import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class CalendarImportRequest(
    val spaceId: String,
    val initiatedByUser: Boolean,
    val calendarIds: Set<String>,
    val startInclusive: Instant,
    val endExclusive: Instant,
)

data class CalendarSourceRecord(
    val calendarId: String,
    val start: Instant,
    val title: String,
    val detail: String,
)

fun interface CalendarDataSource {
    fun read(request: CalendarImportRequest): List<CalendarSourceRecord>
}

class ScopedCalendarAdapter(
    private val expectedSpaceId: String,
    private val repository: MemoryRepository,
    private val dataSource: CalendarDataSource,
    private val zoneId: ZoneId,
) {
    fun import(request: CalendarImportRequest): List<MemoryEvent> {
        validate(request)
        val records = dataSource.read(request)
        records.forEach { record ->
            require(record.calendarId in request.calendarIds) { "Calendar source returned an unselected calendar" }
            require(record.start >= request.startInclusive && record.start < request.endExclusive) {
                "Calendar source returned a record outside the requested window"
            }
            require(record.title.isNotBlank() && record.detail.isNotBlank()) { "Calendar record content must not be blank" }
        }
        return records.map { record ->
            val local = record.start.atZone(zoneId)
            repository.captureSource(
                SourceCaptureRequest(
                    sourceKind = SourceKind.Calendar,
                    title = record.title,
                    detail = record.detail,
                    factStatus = FactStatus.Planned,
                    localDate = local.toLocalDate(),
                    time = local.toLocalTime().withSecond(0).withNano(0),
                ),
            )
        }
    }

    private fun validate(request: CalendarImportRequest) {
        require(request.initiatedByUser) { "Calendar import must be initiated by the user" }
        require(request.spaceId == expectedSpaceId) { "Calendar import space does not match repository space" }
        require(request.calendarIds.isNotEmpty() && request.calendarIds.none(String::isBlank)) {
            "At least one explicit calendar id is required"
        }
        require(request.startInclusive < request.endExclusive) { "Calendar window must be finite and increasing" }
        require(Duration.between(request.startInclusive, request.endExclusive) <= MAX_WINDOW) {
            "Calendar window must not exceed 31 days"
        }
    }

    private companion object {
        val MAX_WINDOW: Duration = Duration.ofDays(31)
    }
}
