package com.ameme.android.data.source

import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.domain.FactStatus
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedCalendarAdapterTest {
    private val repository = FakeMemoryRepository()
    private val start = Instant.parse("2026-07-01T00:00:00Z")
    private val end = Instant.parse("2026-07-08T00:00:00Z")

    @Test
    fun explicitScopedWindowImportsPlannedEvents() {
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource {
                listOf(CalendarSourceRecord("cal-work", start.plusSeconds(3_600), "合成评审", "只表示计划"))
            },
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val imported = adapter.import(request())

        assertEquals(1, imported.size)
        assertEquals(FactStatus.Planned, imported.single().factStatus)
        assertEquals("Calendar", imported.single().sourceLabel)
    }

    @Test
    fun backgroundBlankCalendarWrongSpaceAndOversizedWindowFailBeforeReading() {
        var reads = 0
        val adapter = ScopedCalendarAdapter(
            expectedSpaceId = SPACE,
            repository = repository,
            dataSource = CalendarDataSource { reads++; emptyList() },
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
            CalendarSourceRecord("cal-other", start.plusSeconds(60), "合成越界", "wrong calendar"),
            CalendarSourceRecord("cal-work", end, "合成越界", "wrong time"),
        )
        invalidRecords.forEach { record ->
            val adapter = ScopedCalendarAdapter(
                expectedSpaceId = SPACE,
                repository = repository,
                dataSource = CalendarDataSource { listOf(record) },
                zoneId = ZoneId.of("UTC"),
            )
            assertTrue(runCatching { adapter.import(request()) }.isFailure)
        }
    }

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
