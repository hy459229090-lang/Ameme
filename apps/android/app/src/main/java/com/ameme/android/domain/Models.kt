package com.ameme.android.domain

import java.time.LocalDate
import java.time.LocalTime

enum class ExperienceMode(
    val label: String,
    val description: String,
) {
    Ready("正常", "本机内容可用"),
    Empty("空白", "当前范围没有可见记录"),
    Sparse("稀疏", "当前只有少量获准记录"),
    Loading("加载中", "首次导入或索引正在更新"),
    Partial("部分范围", "一台合成设备尚未参与"),
    Offline("离线", "继续使用本机记录"),
    RecoverableError("可恢复错误", "已有内容安全，可重试当前步骤"),
    PermissionLimited("权限受限", "一个来源未连接，其他路径仍可用"),
}

enum class FactStatus(val label: String) {
    Confirmed("已记录"),
    Planned("计划，未确认发生"),
    Inferred("推测"),
    NeedsReview("待核验"),
    Conflict("冲突"),
    Processing("整理中"),
}

enum class CaptureKind(val label: String) {
    Text("文字"),
    Voice("语音"),
    Photo("照片"),
    Import("导入"),
}

enum class SourceKind {
    PhotoPicker,
    SharedText,
    SharedContent,
    Calendar,
}

enum class LocatorPermissionState {
    PersistedRead,
    SessionRead,
    NoLocator,
}

data class SourceLocator(
    val uri: String,
    val permissionState: LocatorPermissionState,
)

data class SourceCaptureRequest(
    val sourceKind: SourceKind,
    val title: String,
    val detail: String,
    val factStatus: FactStatus,
    val localDate: LocalDate,
    val time: LocalTime?,
    val userWords: String? = null,
    val locatorUri: String? = null,
    val mimeType: String? = null,
    val locatorPermissionState: LocatorPermissionState = LocatorPermissionState.NoLocator,
)

enum class SearchBackend {
    Fts5,
    LikeFallback,
}

data class MemoryPage(
    val events: List<MemoryEvent>,
    val nextCursor: String?,
    val searchBackend: SearchBackend,
)

data class MemoryEvent(
    val id: String,
    val localDate: LocalDate,
    val time: LocalTime?,
    val title: String,
    val detail: String,
    val factStatus: FactStatus,
    val sourceLabel: String,
    val isLocalOnly: Boolean = false,
    val userWords: String? = null,
)

data class DayGroup(
    val date: LocalDate,
    val events: List<MemoryEvent>,
)

enum class DeleteStep(val label: String) {
    Queued("已受理"),
    LocalDeleting("清理本机对象"),
    SyncPropagating("等待合成设备确认"),
    Recomputing("重算日流与索引"),
    PartialFailed("部分失败，可重试"),
    Completed("已完成"),
}
