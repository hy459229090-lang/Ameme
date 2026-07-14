package com.ameme.android.data.local

import android.content.Context
import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
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
        require(kind != CaptureKind.Text || userDescription.isNotEmpty()) {
            "Text capture requires non-empty user input"
        }
        val event = MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = LocalDate.now(clock),
            time = LocalTime.now(clock).withSecond(0).withNano(0),
            title = when (kind) {
                CaptureKind.Text -> userDescription.take(24)
                CaptureKind.Voice -> "模拟语音引用"
                CaptureKind.Photo -> "模拟照片引用"
                CaptureKind.Import -> "模拟导入引用"
            },
            detail = when (kind) {
                CaptureKind.Text -> "用户原话已先保存在加密本机节点；整理尚未完成。"
                else -> buildString {
                    append("当前只创建 mock 引用，没有访问系统${kind.label}能力。")
                    if (userDescription.isNotEmpty()) append(" 用户补充：$userDescription")
                }
            },
            factStatus = FactStatus.Processing,
            sourceLabel = if (kind == CaptureKind.Text) "用户文字" else "mock ${kind.label}引用",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
        )
        return database.insertCaptured(event)
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = database
        .readActive(query, date)
        .groupBy(MemoryEvent::localDate)
        .toSortedMap(compareByDescending { it })
        .map { (groupDate, events) -> DayGroup(groupDate, events) }

    override fun deleteEvent(eventId: String): Boolean = database.deleteEvent(eventId)

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
        ): LocalMemoryRepository {
            val provider = keyProvider ?: AndroidKeystoreDatabaseKeyProvider(context, databaseFile)
            val database = LocalEventDatabase.open(databaseFile, provider, spaceId)
            if (seedSyntheticEvents) database.seedIfEmpty(FakeMemoryRepository(clock).seedEvents())
            return LocalMemoryRepository(database, clock)
        }
    }
}
