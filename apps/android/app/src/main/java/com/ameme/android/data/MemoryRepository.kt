package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import java.io.Closeable
import java.time.LocalDate

interface MemoryRepository : Closeable {
    fun loadActiveEvents(): List<MemoryEvent>

    fun capture(kind: CaptureKind, text: String): MemoryEvent

    fun search(query: String, date: LocalDate?): List<DayGroup>

    fun deleteEvent(eventId: String): Boolean

    override fun close() = Unit
}
