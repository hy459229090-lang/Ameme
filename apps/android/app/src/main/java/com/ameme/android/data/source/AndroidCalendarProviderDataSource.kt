package com.ameme.android.data.source

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.os.Bundle
import android.os.OperationCanceledException
import android.provider.CalendarContract
import java.time.Instant

data class ReadableCalendar(
    val id: String,
    val displayName: String,
)

internal data class CalendarProviderQuery(
    val startMillis: Long,
    val endMillis: Long,
    val selection: String,
    val selectionArgs: Array<String>,
    val resultLimit: Int,
)

internal fun CalendarImportRequest.toProviderQuery(): CalendarProviderQuery {
    val selected = calendarIds.sorted().toTypedArray()
    return CalendarProviderQuery(
        startMillis = startInclusive.toEpochMilli(),
        endMillis = endExclusive.toEpochMilli(),
        selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${selected.joinToString(",") { "?" }})",
        selectionArgs = selected,
        resultLimit = maxItems + 1,
    )
}

class AndroidCalendarProviderDataSource(
    private val contentResolver: ContentResolver,
    private val hasReadPermission: () -> Boolean,
) : CalendarDataSource {
    constructor(context: Context) : this(
        contentResolver = context.contentResolver,
        hasReadPermission = {
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        },
    )

    fun listCalendars(
        cancellation: CalendarImportCancellation = CalendarImportCancellation(),
    ): List<ReadableCalendar> {
        requirePermission()
        return withCancellationSignal(cancellation) { signal ->
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDAR_PROJECTION,
                Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${CalendarContract.Calendars.VISIBLE} = ?",
                    )
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("1"))
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE ASC",
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_CALENDARS + 1)
                },
                signal,
            )?.use { cursor ->
                val rowLimit = ProviderRowLimit(MAX_CALENDARS, "Calendar catalog provider")
                buildList {
                    while (cursor.moveToNext()) {
                        cancellation.throwIfCancelled()
                        rowLimit.observeRow()
                        val id = cursor.getLong(0).toString()
                        val name = cursor.getString(1)?.trim().orEmpty().take(MAX_DISPLAY_NAME)
                        if (name.isNotEmpty()) add(ReadableCalendar(id, name))
                    }
                }
            }.orEmpty()
        }
    }

    override fun read(
        request: CalendarImportRequest,
        cancellation: CalendarImportCancellation,
    ): List<CalendarSourceRecord> {
        requirePermission()
        require(request.calendarIds.isNotEmpty()) { "Calendar ids must be explicit" }
        return withCancellationSignal(cancellation) { signal ->
            val query = request.toProviderQuery()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
                ContentUris.appendId(builder, query.startMillis)
                ContentUris.appendId(builder, query.endMillis)
            }.build()
            contentResolver.query(
                uri,
                INSTANCE_PROJECTION,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        query.selectionArgs,
                    )
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC",
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, query.resultLimit)
                },
                signal,
            )?.use { cursor ->
                val rowLimit = ProviderRowLimit(request.maxItems, "Calendar instances provider")
                buildList {
                    while (cursor.moveToNext()) {
                        cancellation.throwIfCancelled()
                        rowLimit.observeRow()
                        val eventId = cursor.getLong(0).toString()
                        val title = cursor.getString(3)?.trim().orEmpty().take(MAX_TITLE)
                        if (title.isEmpty()) continue
                        val description = cursor.getString(4)?.trim().orEmpty().take(MAX_DETAIL)
                        val location = cursor.getString(5)?.trim().orEmpty().take(MAX_LOCATION)
                        val detail = when {
                            description.isNotEmpty() -> description
                            location.isNotEmpty() -> "地点：$location；仅代表日历计划，尚未确认发生。"
                            else -> "来自用户本次选择的日历范围；仅代表计划，尚未确认发生。"
                        }
                        add(
                            CalendarSourceRecord(
                                eventId = eventId,
                                calendarId = cursor.getLong(1).toString(),
                                start = Instant.ofEpochMilli(cursor.getLong(2)),
                                title = title,
                                detail = detail,
                                locatorUri = ContentUris.withAppendedId(
                                    CalendarContract.Events.CONTENT_URI,
                                    eventId.toLong(),
                                ).toString(),
                                isAllDay = cursor.getInt(6) != 0,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }
    }

    private fun requirePermission() {
        if (!hasReadPermission()) throw SecurityException("Calendar read permission is not granted")
    }

    private fun <T> withCancellationSignal(
        cancellation: CalendarImportCancellation,
        block: (CancellationSignal) -> T,
    ): T {
        cancellation.throwIfCancelled()
        val signal = CancellationSignal()
        val registration = cancellation.invokeOnCancel(signal::cancel)
        return try {
            block(signal)
        } catch (error: OperationCanceledException) {
            if (cancellation.isCancelled) throw CalendarImportCancelled()
            throw error
        } finally {
            registration.close()
        }
    }

    private companion object {
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )
        const val MAX_CALENDARS = 100
        const val MAX_DISPLAY_NAME = 128
        const val MAX_TITLE = 256
        const val MAX_DETAIL = 4_096
        const val MAX_LOCATION = 512
    }
}
