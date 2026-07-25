package com.ameme.android.data.source

import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class CalendarImportRequest(
    val spaceId: String,
    val initiatedByUser: Boolean,
    val calendarIds: Set<String>,
    val startInclusive: Instant,
    val endExclusive: Instant,
    val maxItems: Int = 200,
)

data class CalendarSourceRecord(
    val eventId: String,
    val calendarId: String,
    val start: Instant,
    val title: String,
    val detail: String,
    val locatorUri: String,
    val isAllDay: Boolean = false,
)

fun interface CalendarDataSource {
    fun read(
        request: CalendarImportRequest,
        cancellation: CalendarImportCancellation,
    ): List<CalendarSourceRecord>
}

class CalendarImportCancelled : RuntimeException("Calendar import was cancelled")

class CalendarImportCancellation {
    private val state = AtomicInteger(STATE_ACTIVE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean get() = state.get() == STATE_CANCELLED
    val isCommitComplete: Boolean get() = state.get() == STATE_COMMIT_COMPLETE
    val isCommitFailed: Boolean get() = state.get() == STATE_COMMIT_FAILED

    fun cancel(): Boolean {
        if (!state.compareAndSet(STATE_ACTIVE, STATE_CANCELLED)) return false
        listeners.forEach { it() }
        return true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CalendarImportCancelled()
    }

    fun invokeOnCancel(listener: () -> Unit): AutoCloseable {
        if (isCancelled) {
            listener()
            return AutoCloseable { }
        }
        listeners += listener
        if (isCancelled && listeners.remove(listener)) listener()
        return AutoCloseable { listeners.remove(listener) }
    }

    fun beginCommit() {
        if (!state.compareAndSet(STATE_ACTIVE, STATE_COMMITTING)) throw CalendarImportCancelled()
        listeners.clear()
    }

    fun completeCommit() {
        check(state.compareAndSet(STATE_COMMITTING, STATE_COMMIT_COMPLETE)) {
            "Calendar commit was not in progress"
        }
    }

    fun failCommit() {
        check(state.compareAndSet(STATE_COMMITTING, STATE_COMMIT_FAILED)) {
            "Calendar commit was not in progress"
        }
    }

    private companion object {
        const val STATE_ACTIVE = 0
        const val STATE_CANCELLED = 1
        const val STATE_COMMITTING = 2
        const val STATE_COMMIT_COMPLETE = 3
        const val STATE_COMMIT_FAILED = 4
    }
}

class ScopedCalendarAdapter(
    private val expectedSpaceId: String,
    private val repository: MemoryRepository,
    private val dataSource: CalendarDataSource,
    private val zoneId: ZoneId,
) {
    fun import(
        request: CalendarImportRequest,
        cancellation: CalendarImportCancellation = CalendarImportCancellation(),
    ): List<MemoryEvent> {
        validate(request)
        cancellation.throwIfCancelled()
        val records = dataSource.read(request, cancellation)
        cancellation.throwIfCancelled()
        require(records.size <= request.maxItems) { "Calendar source exceeded the explicit item limit" }
        records.forEach { record ->
            require(record.calendarId in request.calendarIds) { "Calendar source returned an unselected calendar" }
            require(record.start >= request.startInclusive && record.start < request.endExclusive) {
                "Calendar source returned a record outside the requested window"
            }
            require(record.title.isNotBlank() && record.detail.isNotBlank()) { "Calendar record content must not be blank" }
            require(record.eventId.isNotBlank()) { "Calendar event id must not be blank" }
            require(record.locatorUri == "$CALENDAR_EVENT_URI_PREFIX${record.eventId}") {
                "Calendar locator must match the returned event id"
            }
        }
        val sourceRequests = records.map { record ->
            val local = record.start.atZone(zoneId)
            val localDate = if (record.isAllDay) {
                record.start.atZone(ZoneOffset.UTC).toLocalDate()
            } else {
                local.toLocalDate()
            }
            SourceCaptureRequest(
                sourceKind = SourceKind.Calendar,
                title = record.title,
                detail = if (record.isAllDay) "全天计划；${record.detail}" else record.detail,
                factStatus = FactStatus.Planned,
                localDate = localDate,
                time = if (record.isAllDay) null else local.toLocalTime().withSecond(0).withNano(0),
                locatorUri = record.locatorUri,
                mimeType = CALENDAR_EVENT_MIME,
                locatorPermissionState = LocatorPermissionState.ProviderRead,
                sourceInstanceKey = record.start.toEpochMilli().toString(),
                sensitivity = Sensitivity.Confidential,
            )
        }
        cancellation.beginCommit()
        return try {
            val imported = if (sourceRequests.isEmpty()) emptyList() else repository.captureSources(sourceRequests)
            cancellation.completeCommit()
            imported
        } catch (error: Throwable) {
            cancellation.failCommit()
            throw error
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
        require(request.maxItems in 1..MAX_ITEMS) { "Calendar item limit must be between 1 and 500" }
    }

    private companion object {
        val MAX_WINDOW: Duration = Duration.ofDays(31)
        const val MAX_ITEMS = 500
        const val CALENDAR_EVENT_URI_PREFIX = "content://com.android.calendar/events/"
        const val CALENDAR_EVENT_MIME = "vnd.android.cursor.item/event"
    }
}
