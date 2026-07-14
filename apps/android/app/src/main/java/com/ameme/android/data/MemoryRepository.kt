package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.DaySummary
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import java.io.Closeable
import java.time.LocalDate

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

    fun sourceLocator(eventId: String): SourceLocator?

    fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease>

    fun markSourceLocatorReleased(eventId: String): Boolean

    fun deleteEvent(eventId: String): Boolean

    override fun close() = Unit
}
