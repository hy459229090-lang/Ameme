package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Fail-closed UI adapter used only when the encrypted repository cannot be opened. */
class UnavailableMemoryRepository : MemoryRepository {
    override fun loadActiveEvents(): List<MemoryEvent> = emptyList()

    override fun loadDaySummary(localDate: LocalDate): DaySummarySnapshot =
        DaySummarySnapshot(localDate, 0, emptyList(), DaySummaryState.Insufficient)

    override fun beginDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun completeDaySummary(
        localDate: LocalDate,
        expectedLedgerRevision: Int,
        text: String,
        modelOrRuleVersion: String,
    ): DaySummarySnapshot = throw IllegalStateException("Encrypted local repository is unavailable")

    override fun failDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot =
        loadDaySummary(localDate)

    override fun capture(kind: CaptureKind, text: String): MemoryEvent =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun captureSources(requests: List<SourceCaptureRequest>): List<MemoryEvent> =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun search(query: String, date: LocalDate?): List<DayGroup> = emptyList()

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage =
        MemoryPage(emptyList(), null, SearchBackend.LikeFallback)

    override fun searchPage(
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage = MemoryPage(emptyList(), null, SearchBackend.LikeFallback)

    override fun deleteEvent(eventId: String): Boolean = false

    override fun sourceLocator(eventId: String): SourceLocator? = null

    override fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> = emptyList()

    override fun markSourceLocatorReleased(eventId: String): Boolean = false

    override fun updateEvent(
        eventId: String,
        factStatus: FactStatus?,
        userWords: String?,
    ): MemoryEvent? = null

    override fun appendAgentRevision(
        eventId: String,
        content: String,
        evidenceState: EvidenceState,
        factStatus: FactStatus,
        allowedSensitivities: Set<Sensitivity>,
    ): AgentRevisionAppendResult? = null

    override fun undoAgentCapture(
        target: AgentCaptureUndoTarget,
        allowedSensitivities: Set<Sensitivity>,
        undoneAt: Instant,
    ): AgentCaptureUndoResult? = null

    override fun readAgentVisibleEvents(
        query: String,
        startAt: Instant?,
        endAt: Instant?,
        timeZone: ZoneId,
        allowedSensitivities: Set<Sensitivity>,
        allowHighRisk: Boolean,
        limit: Int,
    ): AgentVisibleEventsReadResult = AgentVisibleEventsReadResult(emptyList(), riskFiltered = false)
}
