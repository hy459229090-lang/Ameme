package com.ameme.android.data.source

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

fun interface UriGrantResolver {
    fun resolve(uri: Uri, grantFlags: Int): LocatorPermissionState

    fun releasePersisted(uri: Uri): Boolean = true
}

class ContentUriGrantResolver(private val contentResolver: ContentResolver) : UriGrantResolver {
    override fun resolve(uri: Uri, grantFlags: Int): LocatorPermissionState {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs are accepted" }
        val mayPersist = grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val hasRead = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (mayPersist && hasRead) {
            val persisted = runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (persisted) return LocatorPermissionState.PersistedRead
        }
        return LocatorPermissionState.SessionRead
    }

    override fun releasePersisted(uri: Uri): Boolean = runCatching {
        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess
}

class IncomingShareParser(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun parse(intent: Intent, permissionState: (Uri, Int) -> LocatorPermissionState): SourceCaptureRequest? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.clipData?.itemCount?.let { it > 1 } == true) return null
        val mime = intent.type?.lowercase()?.takeIf { it.length <= MAX_MIME_LENGTH } ?: return null
        if (mime == "text/plain") {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
            return SourceCaptureRequest(
                sourceKind = SourceKind.SharedText,
                title = text.lineSequence().first().take(64),
                detail = "通过系统分享入口保存的用户文字。",
                factStatus = FactStatus.Processing,
                localDate = LocalDate.now(clock),
                time = LocalTime.now(clock).withSecond(0).withNano(0),
                userWords = text,
            )
        }
        if (!(mime.startsWith("image/") || mime == "application/pdf")) return null
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.toString().length > MAX_URI_LENGTH) return null
        return SourceCaptureRequest(
            sourceKind = SourceKind.SharedContent,
            title = if (mime == "application/pdf") "收到一份分享文档" else "收到一张分享图片",
            detail = "仅保存系统内容引用；是否可长期读取由来源授权决定。",
            factStatus = FactStatus.Processing,
            localDate = LocalDate.now(clock),
            time = LocalTime.now(clock).withSecond(0).withNano(0),
            locatorUri = uri.toString(),
            mimeType = mime,
            locatorPermissionState = permissionState(uri, intent.flags),
        )
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 16_384
        const val MAX_URI_LENGTH = 4_096
        const val MAX_MIME_LENGTH = 128
    }
}

class PhotoCaptureCoordinator(
    private val repository: MemoryRepository,
    private val grantResolver: UriGrantResolver,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun capture(uri: Uri?): MemoryEvent? {
        if (uri == null) return null
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Photo Picker returned a non-content URI" }
        val permission = grantResolver.resolve(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return try {
            repository.captureSource(
                SourceCaptureRequest(
                sourceKind = SourceKind.PhotoPicker,
                title = "选择了一张照片",
                detail = if (permission == LocatorPermissionState.PersistedRead) {
                    "照片引用已获长期读取授权，内容仍保留在系统来源中。"
                } else {
                    "照片引用仅在当前授权会话可读取；事件事实已独立保存在本机。"
                },
                factStatus = FactStatus.Processing,
                localDate = LocalDate.now(clock),
                time = LocalTime.now(clock).withSecond(0).withNano(0),
                locatorUri = uri.toString(),
                mimeType = "image/*",
                locatorPermissionState = permission,
                ),
            )
        } catch (error: Throwable) {
            if (permission == LocatorPermissionState.PersistedRead) grantResolver.releasePersisted(uri)
            throw error
        }
    }
}

enum class VoiceCaptureAvailability { Unsupported, Available }

interface VoiceCaptureContract {
    val availability: VoiceCaptureAvailability
}

object UnsupportedVoiceCaptureContract : VoiceCaptureContract {
    override val availability = VoiceCaptureAvailability.Unsupported
}
