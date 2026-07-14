package com.ameme.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.ExperienceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedMode: ExperienceMode,
    onModeSelected: (ExperienceMode) -> Unit,
    onBack: () -> Unit,
    showExperienceControls: Boolean = false,
    agentPairingDetail: String = "未配对",
    pairingInFlight: Boolean = false,
    pairingJson: String? = null,
    pairingSecret: String? = null,
    onCreateAgentPairing: () -> Unit = {},
    onRevokeAgentPairing: () -> Unit = {},
    onDismissPairingSecret: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("来源与权限") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("照片", "系统照片选择器 · 每次由你选择")
                    HorizontalDivider()
                    SettingRow("语音", "系统录音或音频选择器 · 每次由你触发")
                    HorizontalDivider()
                    SettingRow("日历", "用户触发后只读导入 · 保留计划状态")
                    HorizontalDivider()
                    SettingRow("位置与健康", "尚未接入公开 MVP")
                }
            }
            item { SectionTitle("空间、设备与 Agent") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("Personal 空间", "本机 SQLCipher 加密存储")
                    HorizontalDivider()
                    SettingRow("设备同步", "云端未启用 · 局域网同步待后续版本")
                    HorizontalDivider()
                    SettingRow("Agent 连接", agentPairingDetail)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onCreateAgentPairing,
                            enabled = !pairingInFlight,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (agentPairingDetail == "未配对") "创建配对" else "重新配对") }
                        if (agentPairingDetail != "未配对") {
                            OutlinedButton(
                                onClick = onRevokeAgentPairing,
                                enabled = !pairingInFlight,
                                modifier = Modifier.weight(1f),
                            ) { Text("撤销") }
                        }
                    }
                }
            }
            item { SectionTitle("AI 小结") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("发送范围", "仅当天可用的结构化事件；不发送照片、音频原文件和来源定位")
                    HorizontalDivider()
                    SettingRow("生成方式", "每次在今天页明确确认后调用；失败不生成模板替代")
                }
            }
            if (showExperienceControls) {
                item { SectionTitle("合成体验状态") }
                item {
                    Text(
                        "仅供自动化测试切换状态，不改变系统权限、网络或真实数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(ExperienceMode.entries, key = { it.name }) { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = { onModeSelected(mode) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(mode.label, fontWeight = FontWeight.Medium)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { SectionTitle("隐私、导出与删除") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("结构化导出", "已进入公开 MVP 范围 · 交互入口待完成")
                    HorizontalDivider()
                    SettingRow("删除", "删除事件时立即清除对应 AI 小结")
                    HorizontalDivider()
                    SettingRow("诊断", "不记录正文、搜索词或配对密钥")
                }
            }
        }
    }

    if (pairingJson != null && pairingSecret != null) {
        AlertDialog(
            onDismissRequest = onDismissPairingSecret,
            title = { Text("Agent 配对已创建") },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("配对密钥只显示这一次。先复制密钥，再复制配对 JSON 到电脑端。")
                        Text("密钥", fontWeight = FontWeight.SemiBold)
                        Text(pairingSecret, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(pairingSecret)) }) {
                            Text("复制密钥")
                        }
                        Text("配对 JSON", fontWeight = FontWeight.SemiBold)
                        Text(pairingJson, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(pairingJson)) }) {
                            Text("复制配对 JSON")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissPairingSecret) { Text("完成") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingRow(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    }
}
