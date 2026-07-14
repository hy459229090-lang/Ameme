package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.FactStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMemoryRepositoryTest {
    private val clock = Clock.fixed(
        Instant.parse("2026-07-14T04:30:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )
    private val repository = FakeMemoryRepository(clock)

    @Test
    fun capture_isDeterministicLocalOnlyAndProcessing() {
        val captured = repository.capture(CaptureKind.Text, "  合成的试用记录  ")

        assertEquals(LocalDate.of(2026, 7, 14), captured.localDate)
        assertEquals("合成的试用记录", captured.title)
        assertEquals(FactStatus.Processing, captured.factStatus)
        assertTrue(captured.isLocalOnly)
        assertEquals("合成的试用记录", captured.userWords)
    }

    @Test
    fun search_combinesKeywordAndDateAndKeepsDayGrouping() {
        val events = repository.seedEvents()
        val date = LocalDate.of(2026, 7, 14)

        val groups = repository.search(events, "方案", date)

        assertEquals(1, groups.size)
        assertEquals(date, groups.single().date)
        assertTrue(groups.single().events.all { it.localDate == date })
        assertTrue(groups.single().events.any { it.title.contains("方案") || it.detail.contains("方案") })
    }
}
