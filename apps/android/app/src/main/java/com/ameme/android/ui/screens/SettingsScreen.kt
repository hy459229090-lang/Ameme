package com.ameme.android.ui.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.transport.PairingExperienceCandidate
import com.ameme.android.data.transport.PairingExperienceConnection
import com.ameme.android.data.transport.PairingExperienceException
import com.ameme.android.data.transport.PairingExperienceFailure
import com.ameme.android.data.transport.PairingExperienceMethod
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.ui.components.PairingQrCode
import com.ameme.android.ui.icons.AmemeSymbols
import java.time.Instant
import kotlinx.coroutines.delay
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
    agentPairingAvailable: Boolean = false,
    developerAgentPairingDetail: String = "未配对",
    developerPairingInFlight: Boolean = false,
    developerPairingQrPayload: String? = null,
    developerPairingQrExpiresAt: Instant? = null,
    developerPairingJson: String? = null,
    developerPairingSecret: String? = null,
    onCreateDeveloperAgentPairing: () -> Unit = {},
    onRevokeDeveloperAgentPairing: () -> Unit = {},
    onDismissDeveloperPairingSecret: () -> Unit = {},
    demoMode: Boolean = false,
    onDemoModeChanged: (Boolean) -> Unit = {},
    onExport: () -> Unit = {},
    exportPending: Boolean = false,
    exportInFlight: Boolean = false,
    onRetryExport: () -> Unit = {},
    onClearExport: () -> Unit = {},
    calendarReadPermissionGranted: Boolean = false,
    voiceCaptureAvailable: Boolean = false,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showPairingChooser by remember { mutableStateOf(false) }
    var pairingCandidate by remember { mutableStateOf<PairingExperienceCandidate?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingSuccess by remember { mutableStateOf<PairingExperienceConnection?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var pairingQrExpired by remember(developerPairingQrPayload, developerPairingQrExpiresAt) {
        mutableStateOf(
            developerPairingQrExpiresAt == null ||
                !developerPairingQrExpiresAt.isAfter(Instant.now()),
        )
    }

    LaunchedEffect(developerPairingQrPayload, developerPairingQrExpiresAt) {
        val expiresAt = developerPairingQrExpiresAt ?: return@LaunchedEffect
        val remainingMillis = expiresAt.toEpochMilli() - System.currentTimeMillis()
        if (remainingMillis > 0) delay(remainingMillis)
        pairingQrExpired = true
    }

    fun resolve(method: PairingExperienceMethod) {
        showPairingChooser = false
        pairingBusy = true
        pairingError = null
        scope.launch {
            runCatching { onResolvePairingCandidate(method) }
                .onSuccess { pairingCandidate = it }
                .onFailure { pairingError = it.pairingExperienceMessage() }
            pairingBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AmemeSymbols.ArrowBack, contentDescription = "返回")
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
                    SettingRow("照片", "系统照片选择器 · 按次选择，不申请整库权限")
                    HorizontalDivider()
                    SettingRow(
                        "语音",
                        if (voiceCaptureAvailable) {
                            "系统录音入口可用 · 也可选择已有音频"
                        } else {
                            "设备未提供系统录音入口 · 可选择已有音频"
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        "日历",
                        if (calendarReadPermissionGranted) {
                            "只读权限已允许 · 仍须选择日历和日期范围"
                        } else {
                            "只读权限未允许 · 用户触发导入时申请"
                        },
                    )
                    HorizontalDivider()
                    SettingRow("位置与健康", "尚未接入公开 MVP")
                }
            }
            item { SectionTitle("空间、设备与 Agent") }
            item {
                Card(Modifier.fillMaxWidth().testTag("device-connection-card")) {
                    SettingRow("Personal 空间", "本机 SQLCipher 加密存储")
                    HorizontalDivider()
                    SettingRow("设备同步", "云端未启用 · 可在下方按次授权另一台 Ameme 设备写入")
                    HorizontalDivider()
                    if (pairingExperienceConnection == null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("主动连接 Agent", fontWeight = FontWeight.Medium)
                            Text(
                                if (pairingExperienceAvailable) {
                                    "连接 Codex、Claude Code 等 Agent"
                                } else {
                                    "当前 Android 版本只开放下方的安全接收连接"
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
                                Text(if (pairingBusy) "正在查找" else "主动连接 Agent")
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
                            Text(
                                "${if (pairingExperienceConnection.simulated) "体验期限至" else "有效期至"} " +
                                    pairingExperienceConnection.expiresAt
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDate(),
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
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag("receive-device-connection-card"),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text("让另一台 Ameme 设备连接", fontWeight = FontWeight.Medium)
                        Text(
                            developerAgentPairingDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "二维码只用于短时交换安全配对材料。对方扫描并确认后，只能在 Personal 空间写入结构化事件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (developerAgentPairingDetail != "未配对") {
                            Text(
                                "重新生成会撤销当前配对，并使旧二维码立即失效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = onCreateDeveloperAgentPairing,
                            enabled = agentPairingAvailable && !developerPairingInFlight,
                            modifier = Modifier.fillMaxWidth().testTag("create-pairing-qr-button"),
                        ) {
                            if (developerPairingInFlight) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                if (developerAgentPairingDetail == "未配对") {
                                    "生成配对二维码"
                                } else {
                                    "重新生成配对二维码"
                                },
                            )
                        }
                        if (!agentPairingAvailable) {
                            Text(
                                "本机加密节点就绪后才能生成配对二维码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (developerAgentPairingDetail != "未配对") {
                            OutlinedButton(
                                onClick = onRevokeDeveloperAgentPairing,
                                enabled = !developerPairingInFlight,
                                modifier = Modifier.fillMaxWidth().testTag("revoke-pairing-button"),
                            ) {
                                Text("撤销当前配对")
                            }
                        }
                    }
                }
            }
            if (!showExperienceControls) {
                item { SectionTitle("体验数据") }
                item {
                    Card(Modifier.fillMaxWidth().testTag("demo-data-card")) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (demoMode) "当前正在查看演示数据" else "使用演示数据", fontWeight = FontWeight.Medium)
                            Text(
                                "固定示例覆盖今天、搜索、详情、小结和删除流程，不写入真实本机数据库。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (demoMode) {
                                OutlinedButton(
                                    onClick = { onDemoModeChanged(false) },
                                    modifier = Modifier.fillMaxWidth().testTag("exit-demo-button"),
                                ) { Text("退出演示数据") }
                            } else {
                                Button(
                                    onClick = { onDemoModeChanged(true) },
                                    modifier = Modifier.fillMaxWidth().testTag("load-demo-button"),
                                ) { Text("载入演示数据") }
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
                                "创建时明确授权 Agent 在 Personal 空间写入结构化事件 30 天；需要手动复制一次性密钥和 JSON。",
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("结构化导出", fontWeight = FontWeight.Medium)
                        Text(
                            "固定 Personal 空间和当前可见事件范围；不包含受限事件、原始照片或音频文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onExport,
                            enabled = !exportInFlight,
                            modifier = Modifier.fillMaxWidth().testTag("export-json-button"),
                        ) {
                            Text(if (exportInFlight) "正在准备导出" else "生成并保存 JSON")
                        }
                        if (exportPending) {
                            Text(
                                "上次导出尚未保存；本机数据没有改变，可以继续保存同一份快照。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = onRetryExport,
                                enabled = !exportInFlight,
                                modifier = Modifier.fillMaxWidth().testTag("retry-export-button"),
                            ) {
                                Text("重试保存导出")
                            }
                            TextButton(
                                onClick = onClearExport,
                                enabled = !exportInFlight,
                                modifier = Modifier.fillMaxWidth().testTag("clear-export-button"),
                            ) {
                                Text("清除未完成导出")
                            }
                        }
                    }
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
                    Text("拟授权范围：Personal 空间 · autonomous_memory · 结构化 event · 30 天")
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
                                .onFailure { pairingError = it.pairingExperienceMessage() }
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

    if (
        developerPairingQrPayload != null &&
        developerPairingJson != null &&
        developerPairingSecret != null
    ) {
        AlertDialog(
            modifier = Modifier.testTag("pairing-qr-dialog"),
            onDismissRequest = onDismissDeveloperPairingSecret,
            title = { Text("设备配对二维码") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("请让另一台设备在 5 分钟内扫描。扫描后，对方仍需确认授权。")
                    if (pairingQrExpired) {
                        Text(
                            "此配对码已过期。请关闭窗口并重新生成。",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("pairing-qr-expired"),
                        )
                    } else {
                        PairingQrCode(
                            payload = developerPairingQrPayload,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "Ameme 设备配对码",
                                            developerPairingQrPayload,
                                        ),
                                    ),
                                )
                            }
                        },
                        enabled = !pairingQrExpired,
                        modifier = Modifier.fillMaxWidth().testTag("copy-pairing-code-button"),
                    ) {
                        Text("复制配对码")
                    }
                    Text(
                        "配对码包含短时密钥；请只发送给你信任的设备，完成后关闭此窗口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showDeveloperPairingControls) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("开发者调试材料", fontWeight = FontWeight.SemiBold)
                                Text("密钥", fontWeight = FontWeight.SemiBold)
                                Text(developerPairingSecret, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Ameme 开发者配对密钥",
                                                    developerPairingSecret,
                                                ),
                                            ),
                                        )
                                    }
                                }) {
                                    Text("复制密钥")
                                }
                                Text("配对 JSON", fontWeight = FontWeight.SemiBold)
                                Text(developerPairingJson, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Ameme 开发者配对 JSON",
                                                    developerPairingJson,
                                                ),
                                            ),
                                        )
                                    }
                                }) {
                                    Text("复制配对 JSON")
                                }
                            }
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
        Icon(AmemeSymbols.ChevronRight, contentDescription = null)
    }
}

private fun PairingExperienceMethod.label(): String = when (this) {
    PairingExperienceMethod.LanDiscovery -> "同一局域网"
    PairingExperienceMethod.QrCode -> "二维码"
    PairingExperienceMethod.AccountDevice -> "账户设备"
}

private fun Throwable.pairingExperienceMessage(): String = when {
    this is PairingExperienceException && failure == PairingExperienceFailure.NoDeviceFound ->
        "没有发现可用电脑；请确认 Agent 已启动并与本机处于同一局域网。"
    this is PairingExperienceException && failure == PairingExperienceFailure.AuthorizationRequired ->
        "已发现设备，但授权通道尚未完成；本机没有建立连接。"
    this is PairingExperienceException && failure == PairingExperienceFailure.QrScannerUnavailable ->
        "二维码入口尚未接入扫描器；本机没有建立连接。"
    this is PairingExperienceException && failure == PairingExperienceFailure.AccountSignInRequired ->
        "账户设备需要先完成账户授权；本机没有建立连接。"
    else -> "连接体验暂时不可用，请重试。"
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
    }
}
