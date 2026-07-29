package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.DaySummary
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import com.ameme.android.domain.Sensitivity
import java.io.Closeable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AgentRevisionAppendResult(
    val event: MemoryEvent,
    val revisionId: String,
)

data class AgentCaptureUndoTarget(
    val eventId: String,
    val objectType: String,
    val objectId: String,
    val createdRevision: Int,
)

data class AgentCaptureUndoResult(
    val eventId: String,
    val objectType: String,
    val objectId: String,
    val terminalRevision: Int,
    val compensationRevisionId: String? = null,
)

class AgentCaptureUndoConflictException : IllegalStateException("Agent capture is no longer the current head")

data class AgentVisibleEventsReadResult(
    val events: List<MemoryEvent>,
    val riskFiltered: Boolean,
)

interface MemoryRepository : Closeable {
    fun loadActiveEvents(): List<MemoryEvent>

    fun loadDaySummary(localDate: LocalDate): DaySummarySnapshot

    fun beginDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot

    fun completeDaySummary(
        localDate: LocalDate,
        expectedLedgerRevision: Int,
        text: String,
        modelOrRuleVersion: String,
    ): DaySummarySnapshot

    fun failDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot

    fun capture(kind: CaptureKind, text: String): MemoryEvent

    fun captureSource(request: SourceCaptureRequest): MemoryEvent

    fun captureSources(requests: List<SourceCaptureRequest>): List<MemoryEvent>

    fun search(query: String, date: LocalDate?): List<DayGroup>

    fun searchPage(
        query: String,
        date: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage

    fun searchPage(
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage

    fun sourceLocator(eventId: String): SourceLocator?

    fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease>

    fun markSourceLocatorReleased(eventId: String): Boolean

    fun updateEvent(
        eventId: String,
        factStatus: FactStatus? = null,
        userWords: String? = null,
    ): MemoryEvent?

    /**
     * Appends one Agent-authored revision without expanding read or long-term-memory authority.
     *
     * A missing or deleted target is deliberately indistinguishable and returns null.
     */
    fun appendAgentRevision(
        eventId: String,
        content: String,
        evidenceState: EvidenceState,
        factStatus: FactStatus,
        allowedSensitivities: Set<Sensitivity>,
    ): AgentRevisionAppendResult?

    /**
     * Reverses only the exact Agent mutation identified by [target].
     *
     * Event capture is tombstoned; Revision capture appends a compensating revision.
     * Missing, deleted, or sensitivity-invisible targets return null. A changed head
     * raises [AgentCaptureUndoConflictException] and is never overwritten.
     */
    fun undoAgentCapture(
        target: AgentCaptureUndoTarget,
        allowedSensitivities: Set<Sensitivity>,
        undoneAt: Instant,
    ): AgentCaptureUndoResult?

    /**
     * Returns a bounded, current-projection-only Agent read.
     *
     * The repository is already bound to one space. Sensitivities outside the verified
     * session are invisible and must not influence [AgentVisibleEventsReadResult.riskFiltered].
     */
    fun readAgentVisibleEvents(
        query: String,
        startAt: Instant?,
        endAt: Instant?,
        timeZone: ZoneId,
        allowedSensitivities: Set<Sensitivity>,
        allowHighRisk: Boolean,
        limit: Int,
    ): AgentVisibleEventsReadResult

    fun deleteEvent(eventId: String): Boolean

    override fun close() = Unit
}
