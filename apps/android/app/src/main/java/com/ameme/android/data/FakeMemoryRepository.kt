package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local synthetic repository for the UI skeleton.
 * It performs no disk, network, account, system-provider, or permission access.
 */
class FakeMemoryRepository(
    private val clock: Clock = Clock.systemDefaultZone(),
) : MemoryRepository {
    private val captureCounter = AtomicInteger(100)
    private val events by lazy { seedEvents().toMutableList() }
    private val locators = mutableMapOf<String, SourceLocator>()

    fun seedEvents(): List<MemoryEvent> {
        val today = LocalDate.now(clock)
        return listOf(
            MemoryEvent(
                id = "evt_synth_review",
                localDate = today,
                time = LocalTime.of(9, 30),
                title = "梳理移动端体验骨架",
                detail = "确认今天页保持单一记录入口，并保留历史搜索范围说明。",
                factStatus = FactStatus.Confirmed,
                sourceLabel = "合成 Agent 任务",
            ),
            MemoryEvent(
                id = "evt_synth_walk",
                localDate = today,
                time = LocalTime.of(12, 20),
                title = "午后短暂散步",
                detail = "这是用于验证生活事件表达的合成记录，不代表真实位置。",
                factStatus = FactStatus.Inferred,
                sourceLabel = "合成照片 + 用户补充",
            ),
            MemoryEvent(
                id = "evt_synth_question",
                localDate = today,
                time = LocalTime.of(15, 0),
                title = "方案讨论",
                detail = "合成日历只证明原计划，是否实际发生仍需确认。",
                factStatus = FactStatus.NeedsReview,
                sourceLabel = "合成日历",
            ),
            MemoryEvent(
                id = "evt_synth_offline",
                localDate = today.minusDays(1),
                time = LocalTime.of(20, 5),
                title = "记录一段晚间想法",
                detail = "离线提交后先保存在本机，等待后续整理。",
                factStatus = FactStatus.Processing,
                sourceLabel = "合成文字",
                isLocalOnly = true,
                userWords = "今天把复杂问题拆成了几个可以验证的小步骤。",
            ),
            MemoryEvent(
                id = "evt_synth_photo",
                localDate = today.minusDays(2),
                time = LocalTime.of(18, 40),
                title = "整理旅行照片",
                detail = "仅保留合成媒体引用，用于验证来源失效时事件仍可阅读。",
                factStatus = FactStatus.Confirmed,
                sourceLabel = "合成照片引用",
            ),
            MemoryEvent(
                id = "evt_synth_run",
                localDate = today.minusDays(3),
                time = LocalTime.of(7, 10),
                title = "完成一次轻松跑",
                detail = "合成活动摘要，不读取或代表任何健康数据。",
                factStatus = FactStatus.Confirmed,
                sourceLabel = "合成活动摘要",
            ),
        )
    }

    override fun loadActiveEvents(): List<MemoryEvent> = events.toList()

    override fun capture(kind: CaptureKind, text: String): MemoryEvent {
        val now = LocalTime.now(clock).withSecond(0).withNano(0)
        val userDescription = text.trim()
        require(kind == CaptureKind.Text) { "Only explicit text capture is available" }
        require(userDescription.isNotEmpty()) { "Text capture requires non-empty user input" }
        return MemoryEvent(
            id = "evt_synth_capture_${captureCounter.incrementAndGet()}",
            localDate = LocalDate.now(clock),
            time = now,
            title = userDescription.take(24),
            detail = "用户原话已先保存在本机；合成整理尚未完成。",
            factStatus = FactStatus.Processing,
            sourceLabel = "用户文字",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
        ).also(events::add)
    }

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent {
        require(request.title.isNotBlank()) { "Source capture title must not be blank" }
        return MemoryEvent(
            id = "evt_synth_source_${captureCounter.incrementAndGet()}",
            localDate = request.localDate,
            time = request.time,
            title = request.title.trim(),
            detail = request.detail.trim(),
            factStatus = request.factStatus,
            sourceLabel = request.sourceKind.name,
            isLocalOnly = true,
            userWords = request.userWords?.trim()?.ifEmpty { null },
        ).also { event ->
            events.add(event)
            request.locatorUri?.let { uri ->
                locators[event.id] = SourceLocator(uri, request.locatorPermissionState)
            }
        }
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = search(events, query, date)

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage {
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        val offset = cursor?.removePrefix("offset:")?.toIntOrNull() ?: 0
        val matches = search(events, query, date).flatMap(DayGroup::events)
        val page = matches.drop(offset).take(pageSize)
        val nextOffset = offset + page.size
        return MemoryPage(
            events = page,
            nextCursor = if (nextOffset < matches.size) "offset:$nextOffset" else null,
            searchBackend = SearchBackend.LikeFallback,
        )
    }

    fun search(
        events: List<MemoryEvent>,
        query: String,
        date: LocalDate?,
    ): List<DayGroup> {
        val normalized = query.trim()
        return events
            .asSequence()
            .filter { date == null || it.localDate == date }
            .filter {
                normalized.isEmpty() || listOf(it.title, it.detail, it.sourceLabel, it.userWords.orEmpty())
                    .any { value -> value.contains(normalized, ignoreCase = true) }
            }
            .groupBy { it.localDate }
            .toSortedMap(compareByDescending { it })
            .map { (groupDate, groupEvents) ->
                DayGroup(groupDate, groupEvents.sortedByDescending { it.time })
            }
    }

    override fun deleteEvent(eventId: String): Boolean = events.removeAll { it.id == eventId }.also { deleted ->
        if (deleted) locators.remove(eventId)
    }

    override fun sourceLocator(eventId: String): SourceLocator? = locators[eventId]
}
