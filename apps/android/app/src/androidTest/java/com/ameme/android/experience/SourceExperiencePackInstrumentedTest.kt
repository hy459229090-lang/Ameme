package com.ameme.android.experience

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prepares synthetic system sources only; it never opens or writes the Ameme repository. */
@RunWith(AndroidJUnit4::class)
class SourceExperiencePackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context = instrumentation.context
    private val resolver = testContext.contentResolver

    @Test
    fun prepareSyntheticPhotoAudioAndCalendarSources() {
        assumeTrue(
            "Runs only for an explicitly requested local source experience pack.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val photo = replacePhoto()
        val audio = replaceAudio()
        val calendarEvents = withShellCalendarPermissions {
            val calendarId = findOrCreateCalendar()
            replaceCalendarEvents(calendarId)
        }

        assertReadable(photo, "image/png")
        assertReadable(audio, "audio/wav", "audio/x-wav")
        assertEquals(
            1,
            countSyntheticMedia(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                PHOTO_NAME,
            ),
        )
        assertEquals(
            1,
            countSyntheticMedia(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                AUDIO_NAME,
            ),
        )
        assertEquals(2, calendarEvents)
        assertEquals(2, withShellCalendarPermissions { countSyntheticCalendarEvents(calendarId = null) })
        println(
            "AMEME_SOURCE_EXPERIENCE_PACK " +
                "photo=1 audio=1 calendar=1 calendar_events=$calendarEvents",
        )
    }

    @Test
    fun removeSyntheticSystemSources() {
        assumeTrue(
            "Runs only for an explicitly requested local source experience cleanup.",
            InstrumentationRegistry.getArguments().getString(CLEANUP_ARGUMENT) == "true",
        )
        deleteOwnedMedia(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            PHOTO_NAME,
        )
        deleteOwnedMedia(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            AUDIO_NAME,
        )
        assertEquals(
            0,
            countSyntheticMedia(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                PHOTO_NAME,
            ),
        )
        assertEquals(
            0,
            countSyntheticMedia(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                AUDIO_NAME,
            ),
        )
        withShellCalendarPermissions {
            resolver.delete(
                syncAdapterUri(CalendarContract.Calendars.CONTENT_URI),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(CALENDAR_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
            )
            assertEquals(0, countSyntheticCalendars())
            assertEquals(0, countSyntheticCalendarEvents(calendarId = null))
        }
        println("AMEME_SOURCE_EXPERIENCE_CLEANUP photo=0 audio=0 calendar=0")
    }

    private fun replacePhoto(): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        deleteOwnedMedia(collection, PHOTO_NAME)
        val uri = requireNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, PHOTO_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$PHOTO_DIRECTORY/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                val bitmap = Bitmap.createBitmap(1080, 720, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.rgb(18, 37, 66))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    paint.textSize = 104f
                    canvas.drawText("AMEME", 540f, 285f, paint)
                    paint.textSize = 50f
                    canvas.drawText("SYNTHETIC PHOTO", 540f, 390f, paint)
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = 34f
                    canvas.drawText("Use the formal Android Photo Picker", 540f, 475f, paint)
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "synthetic photo compression failed"
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            publishMedia(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun replaceAudio(): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        deleteOwnedMedia(collection, AUDIO_NAME)
        val uri = requireNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, AUDIO_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$AUDIO_DIRECTORY/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.IS_MUSIC, 0)
                },
            ),
        )
        try {
            val wave = syntheticWave()
            try {
                resolver.openOutputStream(uri, "w")!!.use { it.write(wave) }
            } finally {
                wave.fill(0)
            }
            publishMedia(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun findOrCreateCalendar(): Long {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CALENDAR_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        val uri = resolver.insert(
            syncAdapterUri(CalendarContract.Calendars.CONTENT_URI),
            ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, CALENDAR_ACCOUNT)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_DISPLAY_NAME)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, CALENDAR_ACCOUNT)
                put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(55, 105, 175))
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            },
        )
        return ContentUris.parseId(requireNotNull(uri) { "synthetic calendar insert failed" })
    }

    private fun replaceCalendarEvents(calendarId: Long): Int {
        resolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf(calendarId.toString(), "$CALENDAR_MARKER%"),
        )
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val events = listOf(
            SyntheticCalendarEvent(
                title = "Ameme 合成项目复盘",
                description = "$CALENDAR_MARKER；用于验证日历只表示计划。",
                startMillis = today.atTime(10, 30).atZone(zone).toInstant().toEpochMilli(),
                durationMinutes = 45,
            ),
            SyntheticCalendarEvent(
                title = "Ameme 合成散步计划",
                description = "$CALENDAR_MARKER；用于验证跨工作与生活场景。",
                startMillis = today.plusDays(1).atTime(18, 20).atZone(zone).toInstant().toEpochMilli(),
                durationMinutes = 30,
            ),
        )
        events.forEach { event ->
            val inserted = resolver.insert(
                CalendarContract.Events.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DESCRIPTION, event.description)
                    put(CalendarContract.Events.DTSTART, event.startMillis)
                    put(CalendarContract.Events.DTEND, event.startMillis + event.durationMinutes * 60_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                },
            )
            assertNotNull("synthetic calendar event insert failed", inserted)
        }
        return events.size
    }

    private fun deleteOwnedMedia(collection: Uri, displayName: String) {
        val syntheticNamePrefix = displayName.substringBeforeLast('.')
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$syntheticNamePrefix%"),
        )
    }

    private fun countSyntheticMedia(collection: Uri, displayName: String): Int {
        val syntheticNamePrefix = displayName.substringBeforeLast('.')
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$syntheticNamePrefix%"),
            null,
        )?.use { it.count } ?: 0
    }

    private fun countSyntheticCalendarEvents(calendarId: Long?): Int {
        val selection = buildString {
            append("${CalendarContract.Events.DESCRIPTION} LIKE ?")
            if (calendarId != null) append(" AND ${CalendarContract.Events.CALENDAR_ID} = ?")
        }
        val arguments = if (calendarId == null) {
            arrayOf("$CALENDAR_MARKER%")
        } else {
            arrayOf("$CALENDAR_MARKER%", calendarId.toString())
        }
        return resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            selection,
            arguments,
            null,
        )?.use { it.count } ?: 0
    }

    private fun countSyntheticCalendars(): Int = resolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
        arrayOf(CALENDAR_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
        null,
    )?.use { it.count } ?: 0

    private fun publishMedia(uri: Uri) {
        assertEquals(
            1,
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            ),
        )
    }

    private fun assertReadable(uri: Uri, vararg acceptedMimeTypes: String) {
        val actualMimeType = resolver.getType(uri)
        assertTrue(
            "prepared source MIME type must remain in the expected family",
            actualMimeType in acceptedMimeTypes,
        )
        resolver.openInputStream(uri)!!.use { input ->
            assertTrue("prepared system source must be non-empty", input.read() >= 0)
        }
    }

    private fun syncAdapterUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private inline fun <T> withShellCalendarPermissions(block: () -> T): T {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
        return try {
            block()
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun syntheticWave(): ByteArray {
        val sampleCount = SAMPLE_RATE * AUDIO_SECONDS
        val pcmSize = sampleCount * 2
        return ByteArrayOutputStream(44 + pcmSize).use { output ->
            output.writeAscii("RIFF")
            output.writeLe32(36 + pcmSize)
            output.writeAscii("WAVEfmt ")
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(SAMPLE_RATE)
            output.writeLe32(SAMPLE_RATE * 2)
            output.writeLe16(2)
            output.writeLe16(16)
            output.writeAscii("data")
            output.writeLe32(pcmSize)
            repeat(sampleCount) { index ->
                val envelope = if (index < SAMPLE_RATE / 10) index.toDouble() / (SAMPLE_RATE / 10) else 1.0
                val sample = (sin(2.0 * PI * TONE_HZ * index / SAMPLE_RATE) * 10_000 * envelope).toInt()
                output.writeLe16(sample)
            }
            output.toByteArray()
        }
    }

    private data class SyntheticCalendarEvent(
        val title: String,
        val description: String,
        val startMillis: Long,
        val durationMinutes: Int,
    )

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.encodeToByteArray())

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private companion object {
        const val ENABLE_ARGUMENT = "amemeSourceExperiencePack"
        const val CLEANUP_ARGUMENT = "amemeSourceExperienceCleanup"
        const val PHOTO_NAME = "Ameme Synthetic Photo.png"
        const val AUDIO_NAME = "Ameme Synthetic Tone.wav"
        val PHOTO_DIRECTORY: String = Environment.DIRECTORY_PICTURES + "/Ameme Experience"
        val AUDIO_DIRECTORY: String = Environment.DIRECTORY_MUSIC + "/Ameme Experience"
        const val CALENDAR_ACCOUNT = "ameme.synthetic.local"
        const val CALENDAR_DISPLAY_NAME = "Ameme Synthetic Calendar"
        const val CALENDAR_MARKER = "AMEME_SYNTHETIC_SOURCE_PACK"
        const val SAMPLE_RATE = 16_000
        const val AUDIO_SECONDS = 2
        const val TONE_HZ = 440.0
    }
}
