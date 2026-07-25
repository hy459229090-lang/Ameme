package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.DaySummary
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import com.ameme.android.domain.LocatorPermissionState
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

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
    private val pendingReleases = mutableMapOf<String, PendingSourceLocatorRelease>()
    private val activeSourceInstances = mutableMapOf<String, String>()
    private val summaries = mutableMapOf<LocalDate, DaySummary>()
    private val ledgerRevisions = mutableMapOf<LocalDate, Int>()

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
                sensitivity = Sensitivity.Confidential,
            ),
            MemoryEvent(
                id = "evt_synth_question",
                localDate = today,
                time = LocalTime.of(15, 0),
                title = "方案讨论",
                detail = "合成日历只证明原计划，是否实际发生仍需确认。",
                factStatus = FactStatus.NeedsReview,
                sourceLabel = "合成日历",
                sensitivity = Sensitivity.Confidential,
            ),
            MemoryEvent(
                id = "evt_synth_addendum",
                localDate = today,
                time = LocalTime.of(16, 20),
                title = "补充一段原话",
                detail = "这条用户陈述用于体验小结、详情补充和 Revision 更新。",
                factStatus = FactStatus.UserAsserted,
                sourceLabel = "合成文字补充",
                isLocalOnly = true,
                userWords = "今天把可验证的部分先落下来。",
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
                sensitivity = Sensitivity.Confidential,
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

    override fun loadDaySummary(localDate: LocalDate): DaySummarySnapshot {
        val dayEvents = events.filter { it.localDate == localDate }
        val revision = ledgerRevisions.getOrPut(localDate) { if (dayEvents.isEmpty()) 0 else 1 }
        val summary = summaries[localDate]
        val state = when {
            dayEvents.count {
                it.sensitivity != Sensitivity.Restricted &&
                    it.factStatus in setOf(FactStatus.Confirmed, FactStatus.UserAsserted, FactStatus.Planned)
            } < 2 -> DaySummaryState.Insufficient
            summary == null -> DaySummaryState.Absent
            summary.basedOnLedgerRevision == revision -> summary.state
            else -> DaySummaryState.Stale
        }
        return DaySummarySnapshot(localDate, revision, dayEvents, state, summary)
    }

    override fun beginDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot {
        val snapshot = loadDaySummary(localDate)
        require(snapshot.ledgerRevision == expectedLedgerRevision) { "Day ledger revision changed" }
        if (snapshot.eligibleEvents.size < 2) return snapshot.copy(state = DaySummaryState.Insufficient)
        return snapshot.copy(state = DaySummaryState.Processing)
    }

    override fun completeDaySummary(
        localDate: LocalDate,
        expectedLedgerRevision: Int,
        text: String,
        modelOrRuleVersion: String,
    ): DaySummarySnapshot {
        require(text.isNotBlank() && text.length <= 4_000) { "Summary text is invalid" }
        val current = loadDaySummary(localDate)
        if (current.ledgerRevision != expectedLedgerRevision) return current.copy(state = DaySummaryState.Stale)
        val summary = DaySummary(
            id = "sum_${UUID.randomUUID()}",
            localDate = localDate,
            basedOnLedgerRevision = expectedLedgerRevision,
            text = text,
            state = DaySummaryState.Ready,
            modelOrRuleVersion = modelOrRuleVersion,
            createdAtEpochMillis = clock.millis(),
        )
        summaries[localDate] = summary
        return current.copy(state = DaySummaryState.Ready, summary = summary)
    }

    override fun failDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot {
        val current = loadDaySummary(localDate)
        val state = if (current.summary == null) DaySummaryState.Absent else DaySummaryState.Stale
        return current.copy(state = state)
    }

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
            factStatus = FactStatus.UserAsserted,
            sourceLabel = "用户文字",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
        ).also(events::add)
            .also { ledgerRevisions.merge(it.localDate, 1, Int::plus) }
    }

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent {
        request.sourceIdentity()?.let { identity ->
            val existingId = activeSourceInstances.entries.firstOrNull { it.value == identity }?.key
            if (existingId != null) return events.first { it.id == existingId }
        }
        return captureSources(listOf(request)).single()
    }

    override fun captureSources(requests: List<SourceCaptureRequest>): List<MemoryEvent> {
        require(requests.isNotEmpty()) { "Source capture batch must not be empty" }
        requests.forEach { request ->
            require(request.title.isNotBlank()) { "Source capture title must not be blank" }
            require(request.detail.isNotBlank()) { "Source capture detail must not be blank" }
        }
        val seenIdentities = activeSourceInstances.values.toMutableSet()
        val uniqueRequests = requests.filter { request ->
            request.sourceIdentity()?.let(seenIdentities::add) ?: true
        }
        val captured = uniqueRequests.map { request ->
            MemoryEvent(
                id = "evt_synth_source_${captureCounter.incrementAndGet()}",
                localDate = request.localDate,
                time = request.time,
                title = request.title.trim(),
                detail = request.detail.trim(),
                factStatus = request.factStatus,
                sourceLabel = request.sourceKind.name,
                isLocalOnly = true,
                userWords = request.userWords?.trim()?.ifEmpty { null },
                eventType = request.eventType,
                evidenceState = request.evidenceState,
                sensitivity = request.sensitivity,
                importance = request.importance.coerceIn(0, 100),
            ) to request
        }
        captured.forEach { (event, request) ->
            events.add(event)
            ledgerRevisions.merge(event.localDate, 1, Int::plus)
            request.locatorUri?.let { uri ->
                locators[event.id] = SourceLocator(uri, request.locatorPermissionState)
            }
            request.sourceIdentity()?.let { identity -> activeSourceInstances[event.id] = identity }
        }
        return captured.map { it.first }
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = search(events, query, date)

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage {
        return searchPage(query, date, date, cursor, pageSize)
    }

    override fun searchPage(
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage {
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        require(startDate == null || endDate == null || !endDate.isBefore(startDate)) {
            "Search end date must not be before start date"
        }
        val offset = cursor?.removePrefix("offset:")?.toIntOrNull() ?: 0
        val matches = search(events, query, startDate, endDate).flatMap(DayGroup::events)
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
    ): List<DayGroup> = search(events, query, date, date)

    fun search(
        events: List<MemoryEvent>,
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): List<DayGroup> {
        val terms = searchTerms(query)
        return events
            .asSequence()
            .filter { startDate == null || !it.localDate.isBefore(startDate) }
            .filter { endDate == null || !it.localDate.isAfter(endDate) }
            .filter {
                val fields = listOf(it.title, it.detail, it.sourceLabel, it.userWords.orEmpty())
                terms.all { term -> fields.any { value -> value.contains(term, ignoreCase = true) } }
            }
            .groupBy { it.localDate }
            .toSortedMap(compareByDescending { it })
            .map { (groupDate, groupEvents) ->
                DayGroup(groupDate, groupEvents.sortedByDescending { it.time })
            }
    }

    override fun deleteEvent(eventId: String): Boolean {
        val deletedDate = events.firstOrNull { it.id == eventId }?.localDate
        return events.removeAll { it.id == eventId }.also { deleted ->
        if (deleted) {
            val locator = locators.remove(eventId)
            activeSourceInstances.remove(eventId)
            if (locator?.permissionState == LocatorPermissionState.PersistedRead) {
                pendingReleases[eventId] = PendingSourceLocatorRelease(eventId, locator.uri)
            }
            deletedDate?.let {
                ledgerRevisions.merge(it, 1, Int::plus)
                summaries.remove(it)
            }
        }
        }
    }

    override fun sourceLocator(eventId: String): SourceLocator? = locators[eventId]

    override fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> = pendingReleases.values.toList()

    override fun markSourceLocatorReleased(eventId: String): Boolean = pendingReleases.remove(eventId) != null

    override fun updateEvent(
        eventId: String,
        factStatus: FactStatus?,
        userWords: String?,
    ): MemoryEvent? {
        val index = events.indexOfFirst { it.id == eventId }
        if (index < 0) return null
        val current = events[index]
        val updated = current.copy(
            factStatus = factStatus ?: current.factStatus,
            userWords = userWords?.trim()?.ifEmpty { null } ?: current.userWords,
            revision = current.revision + 1,
        )
        events[index] = updated
        ledgerRevisions.merge(updated.localDate, 1, Int::plus)
        summaries.remove(updated.localDate)
        return updated
    }

    private fun SourceCaptureRequest.sourceIdentity(): String? {
        val uri = locatorUri ?: return null
        val instance = sourceInstanceKey ?: return null
        return listOf(sourceKind.name, uri, instance).joinToString("\u0000")
    }

    private fun searchTerms(value: String): List<String> = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(MAX_SEARCH_TERMS)

    private companion object {
        const val MAX_SEARCH_TERMS = 16
    }
}
