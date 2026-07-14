package com.ameme.android.data.local

import android.content.Context
import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.PendingSourceLocatorRelease
import java.net.URI
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class LocalMemoryRepository(
    private val database: LocalEventDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) : MemoryRepository {
    override fun loadActiveEvents(): List<MemoryEvent> = database.readActive()

    override fun capture(kind: CaptureKind, text: String): MemoryEvent {
        val userDescription = text.trim()
        require(kind == CaptureKind.Text) { "Only explicit text capture is available" }
        require(userDescription.isNotEmpty()) { "Text capture requires non-empty user input" }
        val event = MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = LocalDate.now(clock),
            time = LocalTime.now(clock).withSecond(0).withNano(0),
            title = userDescription.take(24),
            detail = "用户原话已先保存在加密本机节点；整理尚未完成。",
            factStatus = FactStatus.Processing,
            sourceLabel = "用户文字",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
        )
        return database.insertCaptured(event)
    }

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent {
        val event = eventFromSource(request)
        return database.insertCaptured(event, request)
    }

    override fun captureSources(requests: List<SourceCaptureRequest>): List<MemoryEvent> {
        require(requests.isNotEmpty()) { "Source capture batch must not be empty" }
        val entries = requests.map { request -> eventFromSource(request) to request }
        return database.insertCapturedSourceBatch(entries)
    }

    private fun eventFromSource(request: SourceCaptureRequest): MemoryEvent {
        require(request.title.isNotBlank()) { "Source capture title must not be blank" }
        require(request.detail.isNotBlank()) { "Source capture detail must not be blank" }
        request.locatorUri?.let { locator ->
            require(locator.length <= 4_096) { "Source locator is too long" }
            require(URI.create(locator).scheme == "content") { "Only content URI locators are accepted" }
        }
        request.mimeType?.let { require(it.length <= 128) { "MIME type is too long" } }
        request.sourceInstanceKey?.let { key ->
            require(key.isNotBlank() && key.length <= 256) { "Source instance key is invalid" }
        }
        return MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = request.localDate,
            time = request.time,
            title = request.title.trim().take(256),
            detail = request.detail.trim().take(4_096),
            factStatus = request.factStatus,
            sourceLabel = request.sourceKind.name,
            isLocalOnly = true,
            userWords = request.userWords?.trim()?.take(16_384)?.ifEmpty { null },
        )
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = database
        .readPage(query, date, cursor = null, pageSize = 100).events
        .groupBy(MemoryEvent::localDate)
        .toSortedMap(compareByDescending { it })
        .map { (groupDate, events) -> DayGroup(groupDate, events) }

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage =
        database.readPage(query, date, cursor, pageSize)

    override fun deleteEvent(eventId: String): Boolean = database.deleteEvent(eventId)

    override fun sourceLocator(eventId: String): SourceLocator? = database.sourceLocator(eventId)

    override fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> =
        database.pendingSourceLocatorReleases()

    override fun markSourceLocatorReleased(eventId: String): Boolean =
        database.markSourceLocatorReleased(eventId)

    override fun close() = database.close()

    companion object {
        const val DATABASE_NAME = "ameme-local-events.db"

        fun open(
            context: Context,
            spaceId: String,
            keyProvider: DatabaseKeyProvider? = null,
            databaseFile: File = context.getDatabasePath(DATABASE_NAME),
            clock: Clock = Clock.systemDefaultZone(),
            seedSyntheticEvents: Boolean = false,
            enableFts: Boolean = true,
        ): LocalMemoryRepository {
            val provider = keyProvider ?: AndroidKeystoreDatabaseKeyProvider(context, databaseFile)
            val database = LocalEventDatabase.open(databaseFile, provider, spaceId, enableFts)
            if (seedSyntheticEvents) database.seedIfEmpty(FakeMemoryRepository(clock).seedEvents())
            return LocalMemoryRepository(database, clock)
        }
    }
}
