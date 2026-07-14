package com.ameme.android.data.source

import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarProviderQueryTest {
    @Test
    fun providerQueryBindsSelectedIdsTimeWindowAndOneOverflowRow() {
        val start = Instant.parse("2026-07-14T00:00:00Z")
        val end = Instant.parse("2026-07-21T00:00:00Z")
        val query = CalendarImportRequest(
            spaceId = "space_test",
            initiatedByUser = true,
            calendarIds = setOf("9", "2"),
            startInclusive = start,
            endExclusive = end,
            maxItems = 200,
        ).toProviderQuery()

        assertEquals(start.toEpochMilli(), query.startMillis)
        assertEquals(end.toEpochMilli(), query.endMillis)
        assertEquals("calendar_id IN (?,?)", query.selection)
        assertArrayEquals(arrayOf("2", "9"), query.selectionArgs)
        assertEquals(201, query.resultLimit)
    }

    @Test
    fun physicalProviderRowsCannotExceedBudgetEvenWhenRowsWouldBeDiscarded() {
        val budget = ProviderRowLimit(maximumRows = 2, sourceName = "synthetic provider")

        budget.observeRow()
        budget.observeRow()

        assertTrue(runCatching { budget.observeRow() }.isFailure)
    }
}
