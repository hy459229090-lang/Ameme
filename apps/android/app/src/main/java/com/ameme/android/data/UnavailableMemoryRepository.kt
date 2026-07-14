package com.ameme.android.data

import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import java.time.LocalDate

/** Fail-closed UI adapter used only when the encrypted repository cannot be opened. */
class UnavailableMemoryRepository : MemoryRepository {
    override fun loadActiveEvents(): List<MemoryEvent> = emptyList()

    override fun capture(kind: CaptureKind, text: String): MemoryEvent =
        throw IllegalStateException("Encrypted local repository is unavailable")

    override fun search(query: String, date: LocalDate?): List<DayGroup> = emptyList()

    override fun deleteEvent(eventId: String): Boolean = false
}
