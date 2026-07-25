package com.ameme.android.data.source

import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedCalendarAdapterTest {
    private val repository = FakeMemoryRepository()
    private val start = Instant.parse("2026-07-01T00:00:00Z")
    private val end = Instant.parse("2026-07-08T00:00:00Z")

    @Test
    fun explicitScopedWindowImportsPlannedEvents() {
        val cancellation = CalendarImportCancellation()
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ ->
                listOf(record(calendarId = "cal-work", start = start.plusSeconds(3_600)))
            },
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val imported = adapter.import(request(), cancellation)

        assertEquals(1, imported.size)
        assertEquals(FactStatus.Planned, imported.single().factStatus)
        assertEquals("Calendar", imported.single().sourceLabel)
        assertEquals(
            LocatorPermissionState.ProviderRead,
            repository.sourceLocator(imported.single().id)?.permissionState,
        )
        assertEquals(Sensitivity.Confidential, imported.single().sensitivity)
        assertTrue(cancellation.isCommitComplete)
    }

    @Test
    fun backgroundBlankCalendarWrongSpaceAndOversizedWindowFailBeforeReading() {
        var reads = 0
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ -> reads++; emptyList() },
            zoneId = ZoneId.of("UTC"),
        )
        val invalid = listOf(
            request().copy(initiatedByUser = false),
            request().copy(calendarIds = emptySet()),
            request().copy(spaceId = "space_other"),
            request().copy(endExclusive = start.plusSeconds(32L * 86_400L)),
        )

        invalid.forEach { assertTrue(runCatching { adapter.import(it) }.isFailure) }
        assertEquals(0, reads)
    }

    @Test
    fun sourceCannotEscapeSelectedCalendarOrTimeWindow() {
        val invalidRecords = listOf(
            record(calendarId = "cal-other", start = start.plusSeconds(60)),
            record(calendarId = "cal-work", start = end),
        )
        invalidRecords.forEach { record ->
            val adapter = ScopedCalendarAdapter(
                expectedSpaceId = SPACE,
                repository = repository,
                dataSource = CalendarDataSource { _, _ -> listOf(record) },
                zoneId = ZoneId.of("UTC"),
            )
            assertTrue(runCatching { adapter.import(request()) }.isFailure)
        }
    }

    @Test
    fun cancellationAndPermissionDenialCreateNoEvents() {
        val before = repository.loadActiveEvents().size
        var reads = 0
        val cancelledSource = CalendarDataSource { _, cancellation ->
            reads++
            cancellation.throwIfCancelled()
            listOf(record())
        }
        val cancelledAdapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = cancelledSource,
            zoneId = ZoneId.of("UTC"),
        )
        val cancellation = CalendarImportCancellation().apply { cancel() }

        assertTrue(runCatching { cancelledAdapter.import(request(), cancellation) }.isFailure)
        assertEquals(0, reads)
        assertEquals(before, repository.loadActiveEvents().size)

        val deniedAdapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ -> throw SecurityException("synthetic denied") },
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(runCatching { deniedAdapter.import(request()) }.isFailure)
        assertEquals(before, repository.loadActiveEvents().size)
    }

    @Test
    fun itemLimitIsExplicitAndValidatedBeforeReading() {
        var reads = 0
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ -> reads++; emptyList() },
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue(runCatching { adapter.import(request().copy(maxItems = 0)) }.isFailure)
        assertTrue(runCatching { adapter.import(request().copy(maxItems = 501)) }.isFailure)
        assertEquals(0, reads)
    }

    @Test
    fun cancellationAfterProviderReadAndBatchFailureNeverExposePartialEvents() {
        val before = repository.loadActiveEvents().size
        val cancellation = CalendarImportCancellation()
        val cancelledAdapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ ->
                cancellation.cancel()
                listOf(record())
            },
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(runCatching { cancelledAdapter.import(request(), cancellation) }.isFailure)
        assertEquals(before, repository.loadActiveEvents().size)

        var batchCalls = 0
        val failingBatchRepository = object : MemoryRepository by repository {
            override fun captureSources(requests: List<SourceCaptureRequest>): List<com.ameme.android.domain.MemoryEvent> {
                batchCalls++
                error("synthetic atomic batch failure")
            }
        }
        val failingAdapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = failingBatchRepository,
            dataSource = CalendarDataSource { _, _ -> listOf(record()) },
            zoneId = ZoneId.of("UTC"),
        )
        val failedCommit = CalendarImportCancellation()
        assertTrue(runCatching { failingAdapter.import(request(), failedCommit) }.isFailure)
        assertEquals(1, batchCalls)
        assertEquals(before, repository.loadActiveEvents().size)
        assertTrue(failedCommit.isCommitFailed)
        assertFalse(failedCommit.cancel())
    }

    @Test
    fun repeatedCalendarInstanceIsIdempotentWithinBatchAndAgainstActiveEvents() {
        val before = repository.loadActiveEvents().size
        val repeated = record(start = start.plusSeconds(3_600))
        val nextInstance = record(start = start.plusSeconds(7_200))
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ -> listOf(repeated, repeated, nextInstance) },
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val first = adapter.import(request())
        val second = adapter.import(request())

        assertEquals(2, first.size)
        assertTrue(second.isEmpty())
        assertEquals(before + 2, repository.loadActiveEvents().size)
    }

    @Test
    fun allDayEventKeepsUtcCalendarDateAndHasNoClockTime() {
        val utcMidnight = Instant.parse("2026-07-02T00:00:00Z")
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { _, _ ->
                listOf(record(start = utcMidnight, isAllDay = true))
            },
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        val imported = adapter.import(request()).single()

        assertEquals(LocalDate.of(2026, 7, 2), imported.localDate)
        assertEquals(null, imported.time)
        assertTrue(imported.detail.startsWith("全天计划；"))
    }

    private fun record(
        calendarId: String = "cal-work",
        start: Instant = this.start.plusSeconds(60),
        isAllDay: Boolean = false,
    ) = CalendarSourceRecord(
        eventId = "42",
        calendarId = calendarId,
        start = start,
        title = "合成评审",
        detail = "只表示计划",
        locatorUri = "content://com.android.calendar/events/42",
        isAllDay = isAllDay,
    )

    private fun request() = CalendarImportRequest(
        spaceId = SPACE,
        initiatedByUser = true,
        calendarIds = setOf("cal-work"),
        startInclusive = start,
        endExclusive = end,
    )

    private companion object {
        const val SPACE = "space_test"
    }
}
