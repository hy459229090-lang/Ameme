package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import java.io.Closeable
import java.time.LocalDate

interface MemoryRepository : Closeable {
    fun loadActiveEvents(): List<MemoryEvent>

    fun capture(kind: CaptureKind, text: String): MemoryEvent

    fun captureSource(request: SourceCaptureRequest): MemoryEvent

    fun search(query: String, date: LocalDate?): List<DayGroup>

    fun searchPage(
        query: String,
        date: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage

    fun sourceLocator(eventId: String): SourceLocator?

    fun deleteEvent(eventId: String): Boolean

    override fun close() = Unit
}
