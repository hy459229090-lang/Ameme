package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SearchBackend
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import java.time.LocalDate

/** Fail-closed UI adapter used only when the encrypted repository cannot be opened. */
class UnavailableMemoryRepository : MemoryRepository {
    override fun loadActiveEvents(): List<MemoryEvent> = emptyList()

    override fun capture(kind: CaptureKind, text: String): MemoryEvent =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun search(query: String, date: LocalDate?): List<DayGroup> = emptyList()

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage =
        MemoryPage(emptyList(), null, SearchBackend.LikeFallback)

    override fun deleteEvent(eventId: String): Boolean = false

    override fun sourceLocator(eventId: String): SourceLocator? = null

    override fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> = emptyList()

    override fun markSourceLocatorReleased(eventId: String): Boolean = false
}
