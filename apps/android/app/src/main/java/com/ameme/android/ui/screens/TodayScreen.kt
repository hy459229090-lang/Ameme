package com.ameme.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.components.EmptyMessage
import com.ameme.android.ui.components.EventRow
import com.ameme.android.ui.components.StateNotice
import com.ameme.android.ui.displayDate
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    events: List<MemoryEvent>,
    experienceMode: ExperienceMode,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onEvent: (String) -> Unit,
    onCapture: (CaptureKind, String) -> Unit,
) {
    var showCapture by remember { mutableStateOf(false) }
    val today = LocalDate.now()
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
                onClick = { showCapture = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("记录") },
                modifier = Modifier.semantics { contentDescription = "记录一件事" },
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
                        VerificationPrompt(eventTitle = event.title)
                    }
                }
                if (visibleToday.count { it.factStatus != FactStatus.Processing } >= 2) {
                    item { SyntheticSummary(experienceMode) }
                }
            }
        }
    }

    if (showCapture) {
        CaptureBottomSheet(
            onDismiss = { showCapture = false },
            onSave = { kind, text ->
                onCapture(kind, text)
                showCapture = false
            },
        )
    }
}

@Composable
private fun VerificationPrompt(eventTitle: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要核验", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("“$eventTitle”实际发生了吗？", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {}) { Text("发生了") }
                OutlinedButton(onClick = {}) { Text("没发生") }
                OutlinedButton(onClick = {}) { Text("稍后") }
            }
        }
    }
}

@Composable
private fun SyntheticSummary(mode: ExperienceMode) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp)) {
        HorizontalDivider()
        Text("今日小结", modifier = Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (mode == ExperienceMode.Partial || mode == ExperienceMode.Offline) {
                "今天推进了移动端体验骨架，也留出了一段生活记录。当前只包含本机合成内容，范围可能不完整。"
            } else {
                "今天推进了移动端体验骨架，也留出了一段生活记录。存在一项计划仍待核验。"
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("合成规则摘要 · 15:10", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureBottomSheet(
    onDismiss: () -> Unit,
    onSave: (CaptureKind, String) -> Unit,
) {
    var selectedKind by remember { mutableStateOf<CaptureKind?>(null) }
    var text by remember { mutableStateOf("") }
    var voiceActive by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("记录一件事", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "所有入口都是 mock；不会打开系统能力或申请权限。",
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedKind == null) {
                CaptureChoice(Icons.Outlined.EditNote, CaptureKind.Text, "输入一句话") { selectedKind = it }
                CaptureChoice(Icons.Outlined.MicNone, CaptureKind.Voice, "模拟点击开始与结束") { selectedKind = it }
                CaptureChoice(Icons.Outlined.PhotoCamera, CaptureKind.Photo, "模拟选择当前照片") { selectedKind = it }
                CaptureChoice(Icons.Outlined.FileOpen, CaptureKind.Import, "模拟导入当前文件") { selectedKind = it }
            } else {
                val kind = requireNotNull(selectedKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Text(kind.label, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                }
                if (kind == CaptureKind.Voice) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { voiceActive = !voiceActive }) {
                            Text(if (voiceActive) "结束模拟录音 · 00:08" else "开始模拟录音")
                        }
                    }
                }
                if (kind == CaptureKind.Photo || kind == CaptureKind.Import) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        Text(
                            if (kind == CaptureKind.Photo) "已选择 synthetic-photo-01（合成占位）" else "已选择 synthetic-note.txt（合成占位）",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (kind == CaptureKind.Text) "写下一句话" else "补充描述（可选）") },
                    minLines = 2,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSave(kind, text) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = kind != CaptureKind.Voice || !voiceActive,
                ) {
                    Text("保存到本机")
                }
                OutlinedButton(
                    onClick = { selectedKind = null; voiceActive = false },
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
        modifier = Modifier.clickable { onClick(kind) },
    )
}
