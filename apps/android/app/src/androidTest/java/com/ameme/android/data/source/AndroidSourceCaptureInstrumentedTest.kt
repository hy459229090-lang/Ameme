package com.ameme.android.data.source

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSourceCaptureInstrumentedTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T04:30:00Z"), ZoneId.of("Asia/Shanghai"))
    private val parser = IncomingShareParser(clock)

    @Test
    fun sharedTextRequiresNonBlankBoundedTextAndIgnoresInjectedTitle() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  合成用户原话  ")
            putExtra(Intent.EXTRA_TITLE, "不可信来源标题")
        }

        val parsed = parser.parse(intent) { _, _ -> error("Text share must not resolve a URI") }

        assertEquals(SourceKind.SharedText, parsed?.sourceKind)
        assertEquals("合成用户原话", parsed?.title)
        assertEquals("合成用户原话", parsed?.userWords)
        assertEquals(FactStatus.UserAsserted, parsed?.factStatus)
        assertEquals(EvidenceState.UserAsserted, parsed?.evidenceState)
        assertNull(parser.parse(Intent(intent).putExtra(Intent.EXTRA_TEXT, "   ")) { _, _ -> error("unused") })
        assertNull(parser.parse(Intent(intent).putExtra(Intent.EXTRA_TEXT, "x".repeat(16_385))) { _, _ -> error("unused") })
    }

    @Test
    fun sharedContentRequiresAllowedMimeReadGrantAndContentUriWithSessionFallback() {
        val valid = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://synthetic.provider/photo/1"))
            putExtra(Intent.EXTRA_TITLE, "伪造标题")
        }
        val parsed = parser.parse(valid) { _, _ -> LocatorPermissionState.SessionRead }

        assertEquals(SourceKind.SharedContent, parsed?.sourceKind)
        assertEquals("收到一张分享图片", parsed?.title)
        assertEquals(LocatorPermissionState.SessionRead, parsed?.locatorPermissionState)
        assertEquals(Sensitivity.Confidential, parsed?.sensitivity)
        assertNull(parser.parse(Intent(valid).apply { flags = 0 }) { _, _ -> LocatorPermissionState.SessionRead })
        assertNull(
            parser.parse(
                Intent(valid).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/injected.png")),
            ) { _, _ -> LocatorPermissionState.SessionRead },
        )
        assertNull(parser.parse(Intent(valid).apply { type = "application/zip" }) { _, _ -> LocatorPermissionState.SessionRead })
    }

    @Test
    fun multipleShareItemsAreRejected() {
        val clip = ClipData.newPlainText("one", "one").apply { addItem(ClipData.Item("two")) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = clip
            putExtra(Intent.EXTRA_TEXT, "合成文本")
        }

        assertNull(parser.parse(intent) { _, _ -> error("unused") })
    }

    @Test
    fun cancelledPickerCreatesNoEventAndContentPickerUsesResolvedLifecycle() {
        val repository = FakeMemoryRepository(clock)
        var grants = 0
        val coordinator = PhotoCaptureCoordinator(
            repository = repository,
            grantResolver = UriGrantResolver { _, _ -> grants++; LocatorPermissionState.SessionRead },
            clock = clock,
        )
        val before = repository.loadActiveEvents().size

        assertNull(coordinator.capture(null))
        assertEquals(before, repository.loadActiveEvents().size)
        assertEquals(0, grants)

        val captured = coordinator.capture(Uri.parse("content://synthetic.provider/photo/2"))
        assertEquals(before + 1, repository.loadActiveEvents().size)
        assertEquals(1, grants)
        assertTrue(captured?.detail?.contains("当前授权会话") == true)
        assertEquals(Sensitivity.Confidential, captured?.sensitivity)
        assertEquals(FactStatus.Confirmed, captured?.factStatus)
    }

    @Test
    fun persistedPhotoGrantIsReleasedWhenEncryptedCommitFails() {
        val delegate = FakeMemoryRepository(clock)
        val failing = object : MemoryRepository by delegate {
            override fun captureSource(request: SourceCaptureRequest) = error("synthetic commit failure")
        }
        var releases = 0
        val resolver = object : UriGrantResolver {
            override fun resolve(uri: Uri, grantFlags: Int) = LocatorPermissionState.PersistedRead
            override fun releasePersisted(uri: Uri): Boolean = true.also { releases++ }
        }
        val coordinator = PhotoCaptureCoordinator(failing, resolver, clock)

        assertTrue(runCatching { coordinator.capture(Uri.parse("content://synthetic.provider/photo/fail")) }.isFailure)
        assertEquals(1, releases)
    }

    @Test
    fun voiceUsesOnlyExplicitSystemResultAndNeverInventsTranscript() {
        val repository = FakeMemoryRepository(clock)
        var grants = 0
        val coordinator = VoiceCaptureCoordinator(
            repository = repository,
            grantResolver = UriGrantResolver { _, flags ->
                grants++
                assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                LocatorPermissionState.SessionRead
            },
            clock = clock,
        )
        val before = repository.loadActiveEvents().size

        assertNull(
            coordinator.capture(
                uri = null,
                grantFlags = 0,
                origin = VoiceCaptureOrigin.SystemRecorder,
            ),
        )
        assertEquals(before, repository.loadActiveEvents().size)
        assertEquals(0, grants)

        val captured = coordinator.capture(
            uri = Uri.parse("content://synthetic.recorder/audio/1"),
            grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            origin = VoiceCaptureOrigin.SystemRecorder,
            mimeType = "audio/ogg",
        )
        assertEquals(SourceKind.VoiceRecorder, captured?.sourceLabel?.let(SourceKind::valueOf))
        assertNull(captured?.userWords)
        assertTrue(captured?.detail?.contains("未转写、未推断") == true)
        assertFalse(captured?.detail?.contains("合成语音内容") == true)
        assertEquals(1, grants)
        assertEquals(Sensitivity.Confidential, captured?.sensitivity)
    }

    @Test
    fun voiceRejectsMissingReadGrantAndNonContentUriWithoutCreatingEvent() {
        val repository = FakeMemoryRepository(clock)
        val coordinator = VoiceCaptureCoordinator(
            repository = repository,
            grantResolver = ContentUriGrantResolver(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            ),
            clock = clock,
        )
        val before = repository.loadActiveEvents().size

        assertTrue(
            runCatching {
                coordinator.capture(
                    Uri.parse("content://synthetic.recorder/audio/2"),
                    grantFlags = 0,
                    origin = VoiceCaptureOrigin.SystemPicker,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                coordinator.capture(
                    Uri.parse("file:///sdcard/voice.m4a"),
                    grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    origin = VoiceCaptureOrigin.SystemPicker,
                )
            }.isFailure,
        )
        assertEquals(before, repository.loadActiveEvents().size)
    }
}
