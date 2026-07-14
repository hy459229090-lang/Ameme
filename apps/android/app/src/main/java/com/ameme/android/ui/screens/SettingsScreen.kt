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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.ExperienceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedMode: ExperienceMode,
    onModeSelected: (ExperienceMode) -> Unit,
    onBack: () -> Unit,
) {
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
                    SettingRow("照片", "未连接 · mock，不申请权限")
                    HorizontalDivider()
                    SettingRow("语音", "按使用时申请 · 当前为 mock")
                    HorizontalDivider()
                    SettingRow("位置、日历、健康", "未接入 · 真实适配器受后续 Gate 约束")
                }
            }
            item { SectionTitle("空间、设备与 Agent") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("Personal 合成空间", "仅本机")
                    HorizontalDivider()
                    SettingRow("同步设备", "1 台合成离线设备")
                    HorizontalDivider()
                    SettingRow("Agent 连接", "没有活跃授权")
                }
            }
            item { SectionTitle("合成体验状态") }
            item {
                Text(
                    "切换后可在今天与搜索页复核状态语义。它不会改变系统权限、网络或真实数据。",
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
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("隐私、导出与删除") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SettingRow("结构化导出", "P1 · 此骨架不生成文件")
                    HorizontalDivider()
                    SettingRow("删除任务", "从事件详情查看合成传播状态")
                    HorizontalDivider()
                    SettingRow("诊断", "不记录正文、搜索词或敏感字段")
                }
            }
        }
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
