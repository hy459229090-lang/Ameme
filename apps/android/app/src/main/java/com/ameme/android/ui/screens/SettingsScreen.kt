package com.ameme.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.transport.PairingExperienceCandidate
import com.ameme.android.data.transport.PairingExperienceConnection
import com.ameme.android.data.transport.PairingExperienceMethod
import com.ameme.android.domain.ExperienceMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedMode: ExperienceMode,
    onModeSelected: (ExperienceMode) -> Unit,
    onBack: () -> Unit,
    showExperienceControls: Boolean = false,
    pairingExperienceAvailable: Boolean = false,
    pairingExperienceConnection: PairingExperienceConnection? = null,
    onResolvePairingCandidate: suspend (PairingExperienceMethod) -> PairingExperienceCandidate = {
        error("Pairing experience is unavailable")
    },
    onConnectPairingCandidate: suspend (PairingExperienceCandidate) -> PairingExperienceConnection = {
        error("Pairing experience is unavailable")
    },
    onDisconnectPairingExperience: suspend () -> Boolean = { false },
    showDeveloperPairingControls: Boolean = false,
    developerAgentPairingDetail: String = "未配对",
    developerPairingInFlight: Boolean = false,
    developerPairingJson: String? = null,
    developerPairingSecret: String? = null,
    onCreateDeveloperAgentPairing: () -> Unit = {},
    onRevokeDeveloperAgentPairing: () -> Unit = {},
    onDismissDeveloperPairingSecret: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showPairingChooser by remember { mutableStateOf(false) }
    var pairingCandidate by remember { mutableStateOf<PairingExperienceCandidate?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingSuccess by remember { mutableStateOf<PairingExperienceConnection?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var disconnecting by remember { mutableStateOf(false) }

    fun resolve(method: PairingExperienceMethod) {
        showPairingChooser = false
        pairingBusy = true
        pairingError = null
        scope.launch {
            runCatching { onResolvePairingCandidate(method) }
                .onSuccess { pairingCandidate = it }
                .onFailure { pairingError = "连接体验暂时不可用，请重试。" }
            pairingBusy = false
        }
    }

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
                Card(Modifier.fillMaxWidth().testTag("device-connection-card")) {
                    SettingRow("Personal 空间", "本机 SQLCipher 加密存储")
                    HorizontalDivider()
                    SettingRow("设备同步", "云端未启用 · 局域网同步待后续版本")
                    HorizontalDivider()
                    if (pairingExperienceConnection == null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("设备连接", fontWeight = FontWeight.Medium)
                            Text(
                                if (pairingExperienceAvailable) {
                                    "连接 Codex、Claude Code 等 Agent"
                                } else {
                                    "当前版本尚未开放普通用户连接"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { showPairingChooser = true },
                                enabled = pairingExperienceAvailable && !pairingBusy,
                                modifier = Modifier.fillMaxWidth().testTag("connect-device-button"),
                            ) {
                                if (pairingBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(if (pairingBusy) "正在查找" else "连接设备")
                            }
                            pairingError?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("connected-device-card"),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(pairingExperienceConnection.deviceName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${pairingExperienceConnection.agentName} · ${if (pairingExperienceConnection.simulated) "体验连接" else "已连接"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "方式：${pairingExperienceConnection.method.label()} · 权限：${pairingExperienceConnection.capabilities.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    disconnecting = true
                                    pairingError = null
                                    scope.launch {
                                        val disconnected = runCatching { onDisconnectPairingExperience() }
                                            .getOrDefault(false)
                                        if (!disconnected) {
                                            pairingError = "断开连接尚未完成，请重试。"
                                        }
                                        disconnecting = false
                                    }
                                },
                                enabled = !disconnecting,
                                modifier = Modifier.fillMaxWidth().testTag("disconnect-device-button"),
                            ) { Text(if (disconnecting) "正在断开" else "断开连接") }
                            pairingError?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
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
            if (showDeveloperPairingControls) {
                item { SectionTitle("开发者选项") }
                item {
                    Card(Modifier.fillMaxWidth().testTag("developer-pairing-card")) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("开发者 TLS 配对", fontWeight = FontWeight.Medium)
                            Text(
                                developerAgentPairingDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "用于验证真实 Host→Android 本地通路；需要手动复制一次性密钥和 JSON。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = onCreateDeveloperAgentPairing,
                                    enabled = !developerPairingInFlight,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (developerAgentPairingDetail == "未配对") "创建开发者配对" else "重新配对")
                                }
                                if (developerAgentPairingDetail != "未配对") {
                                    OutlinedButton(
                                        onClick = onRevokeDeveloperAgentPairing,
                                        enabled = !developerPairingInFlight,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("撤销") }
                                }
                            }
                        }
                    }
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

    if (showPairingChooser) {
        AlertDialog(
            modifier = Modifier.testTag("pairing-method-dialog"),
            onDismissRequest = { showPairingChooser = false },
            title = { Text("连接设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "选择最适合当前环境的方式。三种入口都会在连接前显示设备和授权范围。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PairingMethodRow("自动发现电脑", "适合同一局域网", "pairing-method-lan") {
                        resolve(PairingExperienceMethod.LanDiscovery)
                    }
                    PairingMethodRow("扫描二维码", "适合电脑已显示配对码", "pairing-method-qr") {
                        resolve(PairingExperienceMethod.QrCode)
                    }
                    PairingMethodRow("账户设备", "适合同一账户下的已登录设备", "pairing-method-account") {
                        resolve(PairingExperienceMethod.AccountDevice)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPairingChooser = false }) { Text("取消") } },
        )
    }

    pairingCandidate?.let { candidate ->
        AlertDialog(
            modifier = Modifier.testTag("pairing-authorization-dialog"),
            onDismissRequest = { if (!pairingBusy) pairingCandidate = null },
            title = { Text("允许 Agent 连接？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(candidate.deviceName, fontWeight = FontWeight.SemiBold)
                    Text("Agent：${candidate.agentName}")
                    Text("连接方式：${candidate.method.label()}")
                    Text("允许：${candidate.capabilities.joinToString("、")}")
                    if (candidate.simulated) {
                        Text(
                            "体验模式 · 不建立真实网络连接",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    pairingError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !pairingBusy,
                    onClick = {
                        pairingBusy = true
                        pairingError = null
                        scope.launch {
                            runCatching { onConnectPairingCandidate(candidate) }
                                .onSuccess {
                                    pairingCandidate = null
                                    pairingError = null
                                    pairingSuccess = it
                                }
                                .onFailure { pairingError = "连接体验暂时不可用，请重试。" }
                            pairingBusy = false
                        }
                    },
                ) { Text(if (pairingBusy) "正在连接" else "允许并连接") }
            },
            dismissButton = {
                TextButton(enabled = !pairingBusy, onClick = { pairingCandidate = null }) { Text("取消") }
            },
        )
    }

    pairingSuccess?.let { connected ->
        AlertDialog(
            modifier = Modifier.testTag("pairing-success-dialog"),
            onDismissRequest = { pairingSuccess = null },
            title = { Text("连接成功") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (connected.simulated) {
                            "${connected.deviceName} 已标记为体验连接。"
                        } else {
                            "${connected.deviceName} 已可在授权范围内向 Ameme 写入记录。"
                        },
                    )
                    if (connected.simulated) {
                        Text(
                            "当前是体验连接，不会建立真实网络连接或传输数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pairingSuccess = null }) { Text("完成") } },
        )
    }

    if (showDeveloperPairingControls && developerPairingJson != null && developerPairingSecret != null) {
        AlertDialog(
            onDismissRequest = onDismissDeveloperPairingSecret,
            title = { Text("开发者配对已创建") },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("配对密钥只显示这一次。先复制密钥，再复制配对 JSON 到电脑端。")
                        Text("密钥", fontWeight = FontWeight.SemiBold)
                        Text(developerPairingSecret, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(developerPairingSecret)) }) {
                            Text("复制密钥")
                        }
                        Text("配对 JSON", fontWeight = FontWeight.SemiBold)
                        Text(developerPairingJson, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(developerPairingJson)) }) {
                            Text("复制配对 JSON")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissDeveloperPairingSecret) { Text("完成") } },
        )
    }
}

@Composable
private fun PairingMethodRow(title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    }
}

private fun PairingExperienceMethod.label(): String = when (this) {
    PairingExperienceMethod.LanDiscovery -> "同一局域网"
    PairingExperienceMethod.QrCode -> "二维码"
    PairingExperienceMethod.AccountDevice -> "账户设备"
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
