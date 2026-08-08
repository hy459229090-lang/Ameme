package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.FactStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class FakeMemoryRepositoryTest {
    private val clock = Clock.fixed(
        Instant.parse("2026-07-14T04:30:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )
    private val repository = FakeMemoryRepository(clock)

    @Test
    fun capture_isDeterministicLocalOnlyAndUserAsserted() {
        val captured = repository.capture(CaptureKind.Text, "  合成的试用记录  ")

        assertEquals(LocalDate.of(2026, 7, 14), captured.localDate)
        assertEquals("合成的试用记录", captured.title)
        assertEquals(FactStatus.UserAsserted, captured.factStatus)
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

    @Test
    fun search_matchesEachWhitespaceTermAcrossFieldsLikeTheSqlCipherPath() {
        val events = repository.seedEvents()
        val date = LocalDate.of(2026, 7, 14)

        val matches = repository.search(events, "方案 日历", date)

        assertEquals(1, matches.size)
        assertEquals("evt_synth_question", matches.single().events.single().id)
        assertTrue(repository.search(events, "方案 不存在", date).isEmpty())
    }

    @Test
    fun searchPage_supportsInclusiveDateRange() {
        val page = repository.searchPage(
            query = "",
            startDate = LocalDate.of(2026, 7, 13),
            endDate = LocalDate.of(2026, 7, 14),
            cursor = null,
            pageSize = 100,
        )

        assertTrue(page.events.isNotEmpty())
        assertTrue(page.events.all {
            !it.localDate.isBefore(LocalDate.of(2026, 7, 13)) &&
                !it.localDate.isAfter(LocalDate.of(2026, 7, 14))
        })
        assertTrue(page.events.any { it.localDate == LocalDate.of(2026, 7, 14) })
    }

    @Test
    fun blankTextAndUnsupportedVoiceAreRejectedWithoutPlaceholderEvents() {
        try {
            repository.capture(CaptureKind.Text, "   ")
            fail("Blank text capture must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: no placeholder sentence may be persisted as user input.
        }

        assertTrue(runCatching { repository.capture(CaptureKind.Voice, "   ") }.isFailure)
    }

    @Test
    fun structuredExportExcludesRestrictedEventsAndKeepsScopeMetadata() {
        val events = repository.seedEvents() + com.ameme.android.domain.MemoryEvent(
            id = "evt_restricted",
            localDate = LocalDate.of(2026, 7, 14),
            time = null,
            title = "受限示例",
            detail = "不应进入导出",
            factStatus = FactStatus.Confirmed,
            sourceLabel = "测试",
            sensitivity = com.ameme.android.domain.Sensitivity.Restricted,
        )

        val json = StructuredExportWriter.encode(
            events = events,
            space = "Personal",
            exportedAt = Instant.parse("2026-07-14T04:30:00Z"),
        )

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"space\": \"Personal\""))
        assertTrue(json.contains("\"factStatus\": \"user_asserted\""))
        assertTrue(!json.contains("evt_restricted"))
        assertTrue(!json.contains("受限示例"))
    }

    @Test
    fun reuseJourney_resolvesMockEventsAndRecordsContentFreeFeedback() {
        val now = clock.instant()
        val context = repository.buildReuseContext(
            ReuseRequest(
                spaceId = "space_personal",
                intent = ReuseIntent.DecisionCommitmentRecall,
                requestedAt = now,
            ),
        )

        val resolved = repository.resolveReuseContext(context, now.plusSeconds(1))

        assertTrue(resolved.items.isNotEmpty())
        assertTrue(resolved.items.all { it.reference.revision == it.sourceEvent.revision })
        assertTrue(
            repository.recordReuseOutcome(
                ReuseOutcomeSubmission(
                    attemptId = context.attemptId,
                    outcome = ReuseOutcome.Useful,
                    submittedAt = now.plusSeconds(2),
                ),
            ),
        )
        assertEquals(1, repository.helpfulReuseCount(now))
        assertEquals(1, repository.reuseTelemetryAggregates(now).single().attemptCount)

        val changed = resolved.items.first().sourceEvent
        repository.updateEvent(changed.id, factStatus = FactStatus.Confirmed, userWords = "已更新")
        val stale = repository.revalidateReuseContext(context, now.plusSeconds(3))
        assertTrue(stale.references.none { it.objectId == changed.id })
        assertTrue(ReuseExclusion.Invalidated in stale.exclusions)
    }
}
