package com.ameme.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.components.EmptyMessage
import com.ameme.android.ui.components.DemoModeNotice
import com.ameme.android.ui.components.EventRow
import com.ameme.android.ui.components.StateNotice
import com.ameme.android.ui.displayDate
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    events: List<MemoryEvent>,
    daySummary: DaySummarySnapshot,
    summaryInFlight: Boolean,
    experienceMode: ExperienceMode,
    persistenceError: String?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onEvent: (String) -> Unit,
    canRecordVoice: Boolean,
    voiceCaptureInFlight: Boolean,
    onRequestPhoto: () -> Unit,
    onRequestVoiceRecording: () -> Unit,
    onRequestVoiceSelection: () -> Unit,
    onRequestCalendar: () -> Unit,
    onCapture: suspend (CaptureKind, String) -> Boolean,
    onGenerateSummary: suspend () -> Boolean,
    demoMode: Boolean = false,
) {
    var showCapture by remember { mutableStateOf(false) }
    var showSummaryConsent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val captureReady = experienceMode != ExperienceMode.Loading && experienceMode != ExperienceMode.RecoverableError
    val allToday = events.filter { it.localDate == today }.sortedBy { it.time }
    val visibleToday = when (experienceMode) {
        ExperienceMode.Empty -> emptyList()
        ExperienceMode.Sparse, ExperienceMode.Loading -> allToday.take(1)
        else -> allToday
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("今天", fontWeight = FontWeight.SemiBold)
                        Text(today.displayDate(), style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSearch,
                        modifier = Modifier.semantics { contentDescription = "搜索历史记录" },
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    }
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics { contentDescription = "打开设置" },
                    ) {
                        Icon(Icons.Outlined.Menu, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (captureReady) showCapture = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("记录") },
                containerColor = if (captureReady) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (captureReady) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "记录一件事"
                    role = Role.Button
                    if (!captureReady) {
                        disabled()
                    } else {
                        semanticsOnClick {
                            showCapture = true
                            true
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
        ) {
            item {
                Text(
                    when {
                        experienceMode == ExperienceMode.Empty -> "当前可见范围内还没有记录"
                        experienceMode == ExperienceMode.Sparse -> "当前只有少量获准记录"
                        else -> "已整理 ${visibleToday.count { it.factStatus != FactStatus.Processing }} 件事"
                    },
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                StateNotice(
                    mode = experienceMode,
                    modifier = Modifier.padding(bottom = 12.dp),
                    onAction = if (experienceMode != ExperienceMode.Ready) onSettings else null,
                )
            }
            if (demoMode) {
                item {
                    DemoModeNotice(modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            persistenceError?.let { error ->
                item {
                    Text(
                        error,
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (visibleToday.isEmpty()) {
                item {
                    EmptyMessage(
                        title = "从一件真实的事开始",
                        detail = "可以直接点击“记录”；这个骨架不会用虚构示例冒充你的内容。",
                    )
                }
            } else {
                items(visibleToday, key = { it.id }) { event ->
                    EventRow(event = event, onClick = { onEvent(event.id) })
                    if (event.factStatus == FactStatus.NeedsReview) {
                        VerificationPrompt(eventTitle = event.title, onOpen = { onEvent(event.id) })
                    }
                }
            }
            item {
                DaySummarySection(
                    snapshot = daySummary,
                    inFlight = summaryInFlight,
                    onGenerate = { showSummaryConsent = true },
                )
            }
        }
    }

    if (showCapture) {
        CaptureBottomSheet(
            onDismiss = { showCapture = false },
            canRecordVoice = canRecordVoice,
            voiceCaptureInFlight = voiceCaptureInFlight,
            onRequestPhoto = {
                showCapture = false
                onRequestPhoto()
            },
            onRequestVoiceRecording = {
                showCapture = false
                onRequestVoiceRecording()
            },
            onRequestVoiceSelection = {
                showCapture = false
                onRequestVoiceSelection()
            },
            onRequestCalendar = {
                showCapture = false
                onRequestCalendar()
            },
            onSave = { kind, text ->
                val saved = onCapture(kind, text)
                if (saved) showCapture = false
                saved
            },
        )
    }

    if (showSummaryConsent) {
        AlertDialog(
            onDismissRequest = { if (!summaryInFlight) showSummaryConsent = false },
            title = { Text("生成今日小结？") },
            text = {
                Text(
                    "将把今天已保存的结构化事件发送到推理服务。不会上传照片、音频原文件、SourceLocator 或搜索记录；事件仍以本机加密库为准。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!summaryInFlight) {
                            scope.launch {
                                onGenerateSummary()
                                showSummaryConsent = false
                            }
                        }
                    },
                    enabled = !summaryInFlight,
                ) { Text(if (summaryInFlight) "正在生成…" else "同意并生成") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSummaryConsent = false },
                    enabled = !summaryInFlight,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VerificationPrompt(eventTitle: String, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要核验", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("“$eventTitle”需要你的确认。", fontWeight = FontWeight.Medium)
            Text(
                "打开详情后可补充原话、查看来源并继续处理；不会用未保存的按钮操作改变事实。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpen) { Text("查看并核验") }
        }
    }
}

@Composable
private fun DaySummarySection(
    snapshot: DaySummarySnapshot,
    inFlight: Boolean,
    onGenerate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp)) {
        HorizontalDivider()
        Text(
            "今日小结",
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            inFlight || snapshot.state == DaySummaryState.Processing -> {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp))
                    Text("正在基于本日结构化事件生成…")
                }
            }
            snapshot.state == DaySummaryState.Insufficient -> {
                Text(
                    "当前可用于小结的已确认、用户陈述或计划事件不足 2 条；继续记录即可，不会生成空泛模板。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.summary != null -> {
                if (snapshot.state == DaySummaryState.Stale) {
                    Text(
                        "底层事件已变化，下面是旧版小结。刷新前不会把它当作当前结果。",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    snapshot.summary.text,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onGenerate,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(if (snapshot.state == DaySummaryState.Stale) "刷新小结" else "重新生成") }
            }
            else -> {
                Text(
                    "可按需生成 2–3 行摘要；它只读取今天的结构化事件，不读取照片或音频原文件。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGenerate, modifier = Modifier.padding(top = 12.dp)) {
                    Text("生成 AI 小结")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureBottomSheet(
    onDismiss: () -> Unit,
    canRecordVoice: Boolean,
    voiceCaptureInFlight: Boolean,
    onRequestPhoto: () -> Unit,
    onRequestVoiceRecording: () -> Unit,
    onRequestVoiceSelection: () -> Unit,
    onRequestCalendar: () -> Unit,
    onSave: suspend (CaptureKind, String) -> Boolean,
) {
    var selectedKind by remember { mutableStateOf<CaptureKind?>(null) }
    var text by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("记录一件事", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "文字会直接写入本机加密节点；照片只通过系统选择器读取你本次选择的内容。",
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedKind == null) {
                CaptureChoice(Icons.Outlined.EditNote, CaptureKind.Text, "输入一句话") { selectedKind = it }
                CaptureChoice(
                    Icons.Outlined.MicNone,
                    CaptureKind.Voice,
                    "调用系统录音，或选择已有音频",
                ) { selectedKind = it }
                CaptureChoice(Icons.Outlined.PhotoCamera, CaptureKind.Photo, "从系统照片选择器选择一张") {
                    onRequestPhoto()
                }
                CaptureChoice(Icons.Outlined.CalendarMonth, CaptureKind.Import, "选择日历和最多 31 天的日期范围") {
                    onRequestCalendar()
                }
            } else {
                val kind = requireNotNull(selectedKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Text(kind.label, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                }
                if (kind == CaptureKind.Voice) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        Text(
                            "Ameme 不申请麦克风权限。系统返回或你选择音频后，只保存来源引用，不生成转写或占位内容。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (canRecordVoice) {
                        Button(
                            onClick = onRequestVoiceRecording,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !voiceCaptureInFlight,
                        ) { Text("调用系统录音") }
                    }
                    OutlinedButton(
                        onClick = onRequestVoiceSelection,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !voiceCaptureInFlight,
                    ) { Text(if (voiceCaptureInFlight) "正在处理音频" else "选择已有音频") }
                }
                if (kind == CaptureKind.Text) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            saveError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("写下一句话") },
                        minLines = 2,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (kind == CaptureKind.Text) {
                    Button(
                        onClick = {
                            if (!isSaving) {
                                isSaving = true
                                scope.launch {
                                    val saved = onSave(kind, text)
                                    isSaving = false
                                    if (saved) {
                                        onDismiss()
                                    } else {
                                        saveError = "保存失败；本机数据没有改变，请重试。"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && (kind != CaptureKind.Text || text.isNotBlank()),
                    ) {
                        Text(if (isSaving) "正在保存…" else "保存到本机")
                    }
                    saveError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { selectedKind = null },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回记录方式")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CaptureChoice(
    icon: ImageVector,
    kind: CaptureKind,
    detail: String,
    onClick: (CaptureKind) -> Unit,
) {
    ListItem(
        headlineContent = { Text(kind.label) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .clickable(role = Role.Button) { onClick(kind) }
            .semantics { contentDescription = "${kind.label}：$detail" },
    )
}
