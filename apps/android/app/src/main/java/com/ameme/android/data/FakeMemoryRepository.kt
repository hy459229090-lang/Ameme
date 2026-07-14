package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
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
        require(kind != CaptureKind.Text || userDescription.isNotEmpty()) {
            "Text capture requires non-empty user input"
        }
        return MemoryEvent(
            id = "evt_synth_capture_${captureCounter.incrementAndGet()}",
            localDate = LocalDate.now(clock),
            time = now,
            title = when (kind) {
                CaptureKind.Text -> userDescription.take(24)
                CaptureKind.Voice -> "模拟语音引用"
                CaptureKind.Photo -> "模拟照片引用"
                CaptureKind.Import -> "模拟导入引用"
            },
            detail = when (kind) {
                CaptureKind.Text -> "用户原话已先保存在本机；合成整理尚未完成。"
                else -> buildString {
                    append("当前只创建 mock 引用，没有访问系统${kind.label}能力。")
                    if (userDescription.isNotEmpty()) append(" 用户补充：$userDescription")
                }
            },
            factStatus = FactStatus.Processing,
            sourceLabel = if (kind == CaptureKind.Text) "用户文字" else "mock ${kind.label}引用",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
        ).also(events::add)
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = search(events, query, date)

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

    override fun deleteEvent(eventId: String): Boolean = events.removeAll { it.id == eventId }
}
