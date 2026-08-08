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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * Process-local synthetic repository for the UI skeleton.
 * It performs no disk, network, account, system-provider, or permission access.
 */
class FakeMemoryRepository(
    private val clock: Clock = Clock.systemDefaultZone(),
) : MemoryRepository, ReuseRepository {
    private val captureCounter = AtomicInteger(100)
    private val events by lazy { seedEvents().toMutableList() }
    private val locators = mutableMapOf<String, SourceLocator>()
    private val pendingReleases = mutableMapOf<String, PendingSourceLocatorRelease>()
    private val activeSourceInstances = mutableMapOf<String, String>()
    private val summaries = mutableMapOf<LocalDate, DaySummary>()
    private val ledgerRevisions = mutableMapOf<LocalDate, Int>()
    private val agentRevisionSnapshots = mutableMapOf<String, MemoryEvent>()
    private val reuseAttempts = mutableMapOf<String, FakeReuseAttempt>()

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

    override fun appendAgentRevision(
        eventId: String,
        content: String,
        evidenceState: EvidenceState,
        factStatus: FactStatus,
        allowedSensitivities: Set<Sensitivity>,
    ): AgentRevisionAppendResult? {
        require(allowedSensitivities.isNotEmpty())
        require(content.isNotBlank() && content.codePointCount(0, content.length) <= 4_000)
        val index = events.indexOfFirst { it.id == eventId }
        if (index < 0) return null
        val current = events[index]
        if (current.sensitivity !in allowedSensitivities) return null
        val updated = current.copy(
            detail = content,
            factStatus = factStatus,
            evidenceState = evidenceState,
            revision = current.revision + 1,
        )
        val revisionId = "rev_${UUID.randomUUID()}"
        agentRevisionSnapshots[revisionId] = current
        events[index] = updated
        ledgerRevisions.merge(updated.localDate, 1, Int::plus)
        summaries.remove(updated.localDate)
        return AgentRevisionAppendResult(
            event = updated,
            revisionId = revisionId,
        )
    }

    override fun undoAgentCapture(
        target: AgentCaptureUndoTarget,
        allowedSensitivities: Set<Sensitivity>,
        undoneAt: Instant,
    ): AgentCaptureUndoResult? {
        require(allowedSensitivities.isNotEmpty())
        val index = events.indexOfFirst { it.id == target.eventId }
        if (index < 0) return null
        val current = events[index]
        if (current.sensitivity !in allowedSensitivities) return null
        return when (target.objectType) {
            "event" -> {
                if (
                    target.objectId != target.eventId ||
                    target.createdRevision != 1
                ) {
                    return null
                }
                if (current.revision != target.createdRevision) {
                    throw AgentCaptureUndoConflictException()
                }
                if (!deleteEvent(target.eventId)) return null
                AgentCaptureUndoResult(
                    eventId = target.eventId,
                    objectType = "event",
                    objectId = target.objectId,
                    terminalRevision = target.createdRevision + 1,
                )
            }
            "revision" -> {
                val previous = agentRevisionSnapshots[target.objectId] ?: return null
                if (
                    previous.id != target.eventId ||
                    previous.revision + 1 != target.createdRevision
                ) {
                    return null
                }
                if (current.revision != target.createdRevision) {
                    throw AgentCaptureUndoConflictException()
                }
                val compensationRevisionId = "rev_${UUID.randomUUID()}"
                val restored = previous.copy(revision = current.revision + 1)
                events[index] = restored
                ledgerRevisions.merge(restored.localDate, 1, Int::plus)
                summaries.remove(restored.localDate)
                AgentCaptureUndoResult(
                    eventId = target.eventId,
                    objectType = "revision",
                    objectId = target.objectId,
                    terminalRevision = restored.revision,
                    compensationRevisionId = compensationRevisionId,
                )
            }
            else -> null
        }
    }

    override fun readAgentVisibleEvents(
        query: String,
        startAt: Instant?,
        endAt: Instant?,
        timeZone: ZoneId,
        allowedSensitivities: Set<Sensitivity>,
        allowHighRisk: Boolean,
        limit: Int,
    ): AgentVisibleEventsReadResult {
        require(query.codePointCount(0, query.length) <= 1_000)
        require(allowedSensitivities.isNotEmpty())
        require(limit in 1..100)
        require(startAt == null || endAt == null || !endAt.isBefore(startAt))
        val effectiveSensitivities = if (allowHighRisk) {
            allowedSensitivities
        } else {
            allowedSensitivities - Sensitivity.Restricted
        }
        val terms = searchTerms(query)
        fun MemoryEvent.matches(): Boolean {
            val at = localDate.atTime(time ?: LocalTime.MIN).atZone(timeZone).toInstant()
            if (startAt != null && at.isBefore(startAt)) return false
            if (endAt != null && at.isAfter(endAt)) return false
            val haystack = listOf(title, detail)
                .joinToString("\n")
                .lowercase()
            return terms.all { it.lowercase() in haystack }
        }
        val matching = events.filter { it.matches() }
        val riskFiltered =
            !allowHighRisk &&
                Sensitivity.Restricted in allowedSensitivities &&
                matching.any { it.sensitivity == Sensitivity.Restricted }
        return AgentVisibleEventsReadResult(
            events = matching
                .asSequence()
                .filter { it.sensitivity in effectiveSensitivities }
                .sortedWith(
                    compareByDescending<MemoryEvent> { it.localDate }
                        .thenByDescending { it.time ?: LocalTime.MIN }
                        .thenByDescending(MemoryEvent::id),
                )
                .take(limit)
                .toList(),
            riskFiltered = riskFiltered,
        )
    }

    override fun buildReuseContext(request: ReuseRequest): ReuseContext {
        require(request.spaceId == "space_personal") { "reuse request space is not available in demo mode" }
        val effectiveStart = request.startDate ?: request.meetingAnchorDate
            ?.takeIf { request.intent == ReuseIntent.PreMeetingContext && request.endDate == null }
        val effectiveEnd = request.endDate ?: request.meetingAnchorDate
            ?.takeIf { request.intent == ReuseIntent.PreMeetingContext && request.startDate == null }
        val matching = search(events, request.query, effectiveStart, effectiveEnd)
            .flatMap(DayGroup::events)
        val visible = matching.filter { it.sensitivity != Sensitivity.Restricted }
        val selected = visible.take(request.limit)
        val selectionReason = when (request.intent) {
            ReuseIntent.HistoricalSearch -> if (request.query.isBlank()) {
                ReuseSelectionReason.DateMatch
            } else {
                ReuseSelectionReason.KeywordMatch
            }
            ReuseIntent.ProjectResume -> ReuseSelectionReason.KeywordMatch
            ReuseIntent.PreMeetingContext -> if (request.meetingAnchorDate != null) {
                ReuseSelectionReason.MeetingAnchor
            } else {
                ReuseSelectionReason.KeywordMatch
            }
            ReuseIntent.DecisionCommitmentRecall -> ReuseSelectionReason.ActiveDecision
        }
        val references = selected.map { event ->
            ReuseReference(
                objectType = ReuseObjectType.Event,
                objectId = event.id,
                revision = event.revision,
                localDate = event.localDate,
                sensitivity = event.sensitivity,
                selectionReason = selectionReason,
            )
        }
        val context = ReuseContext(
            attemptId = "reuse_${UUID.randomUUID()}",
            intent = request.intent,
            rangeState = when {
                references.isEmpty() -> ReuseRangeState.Empty
                visible.size > request.limit -> ReuseRangeState.PartialForLocalScope
                else -> ReuseRangeState.CompleteForLocalScope
            },
            references = references,
            exclusions = if (matching.any { it.sensitivity == Sensitivity.Restricted }) {
                setOf(ReuseExclusion.Restricted)
            } else {
                emptySet()
            },
            createdAt = request.requestedAt,
            expiresAt = request.requestedAt.plusSeconds(REUSE_CONTEXT_TTL_SECONDS),
        )
        reuseAttempts[context.attemptId] = FakeReuseAttempt(context)
        return context
    }

    override fun revalidateReuseContext(context: ReuseContext, at: Instant): ReuseContext {
        if (!context.expiresAt.isAfter(at)) {
            return context.copy(
                rangeState = ReuseRangeState.Empty,
                references = emptyList(),
                exclusions = context.exclusions + ReuseExclusion.Expired,
            )
        }
        val exclusions = context.exclusions.toMutableSet()
        val valid = context.references.filter { reference ->
            val event = events.firstOrNull { it.id == reference.objectId }
            when {
                event == null -> {
                    exclusions += ReuseExclusion.Deleted
                    false
                }
                event.revision != reference.revision || event.sensitivity != reference.sensitivity -> {
                    exclusions += ReuseExclusion.Invalidated
                    false
                }
                event.sensitivity == Sensitivity.Restricted -> {
                    exclusions += ReuseExclusion.Restricted
                    false
                }
                else -> true
            }
        }
        return context.copy(
            rangeState = when {
                valid.isEmpty() -> ReuseRangeState.Empty
                context.rangeState == ReuseRangeState.PartialForLocalScope ->
                    ReuseRangeState.PartialForLocalScope
                else -> ReuseRangeState.CompleteForLocalScope
            },
            references = valid,
            exclusions = exclusions,
        )
    }

    override fun resolveReuseContext(context: ReuseContext, at: Instant): ResolvedReuseContext {
        val valid = revalidateReuseContext(context, at)
        val items = valid.references.map { reference ->
            val event = checkNotNull(events.firstOrNull { it.id == reference.objectId })
            check(event.revision == reference.revision)
            ResolvedReuseItem(reference = reference, sourceEvent = event)
        }
        return ResolvedReuseContext(valid, items)
    }

    override fun recordReuseOutcome(submission: ReuseOutcomeSubmission): Boolean {
        val attempt = reuseAttempts[submission.attemptId] ?: return false
        if (attempt.outcome != null || !attempt.context.expiresAt.isAfter(submission.submittedAt)) return false
        reuseAttempts[submission.attemptId] = attempt.copy(
            outcome = submission.outcome,
            userAction = submission.userAction,
            submittedAt = submission.submittedAt,
        )
        return true
    }

    override fun reuseTelemetryAggregates(since: Instant): List<ReuseTelemetryAggregate> =
        reuseAttempts.values
            .filter { !it.context.createdAt.isBefore(since) }
            .groupBy { attempt ->
                FakeReuseAggregateKey(
                    intent = attempt.context.intent,
                    outcome = attempt.outcome,
                    userAction = attempt.userAction,
                    resultCountBucket = ReuseResultCountBucket.from(attempt.context.references.size),
                )
            }
            .map { (key, attempts) ->
                ReuseTelemetryAggregate(
                    intent = key.intent,
                    outcome = key.outcome,
                    userAction = key.userAction,
                    resultCountBucket = key.resultCountBucket,
                    attemptCount = attempts.size,
                )
            }

    override fun helpfulReuseCount(since: Instant): Int = reuseAttempts.values.count { attempt ->
        attempt.outcome == ReuseOutcome.Useful &&
            attempt.submittedAt?.let { !it.isBefore(since) } == true
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
        const val REUSE_CONTEXT_TTL_SECONDS = 15L * 60L
    }
}

private data class FakeReuseAttempt(
    val context: ReuseContext,
    val outcome: ReuseOutcome? = null,
    val userAction: ReuseUserAction? = null,
    val submittedAt: Instant? = null,
)

private data class FakeReuseAggregateKey(
    val intent: ReuseIntent,
    val outcome: ReuseOutcome?,
    val userAction: ReuseUserAction?,
    val resultCountBucket: ReuseResultCountBucket,
)
