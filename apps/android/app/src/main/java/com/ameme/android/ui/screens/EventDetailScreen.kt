package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.icons.AmemeSymbols
import com.ameme.android.domain.FactStatus
import com.ameme.android.ui.displayDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    event: MemoryEvent?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onSaveAddendum: suspend (String) -> Boolean,
    onUpdateFactStatus: suspend (FactStatus) -> Boolean,
) {
    var addendum by remember(event?.id) { mutableStateOf(event?.userWords.orEmpty()) }
    var savingAddendum by remember { mutableStateOf(false) }
    var savingStatus by remember { mutableStateOf(false) }
    var addendumError by remember(event?.id) { mutableStateOf<String?>(null) }
    var statusError by remember(event?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("事件详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AmemeSymbols.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (event == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("这条事件已不存在。")
                OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("返回") }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DetailSection("发生了什么") {
                    Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${event.localDate.displayDate()} · ${event.time ?: "时间待确认"}")
                    Text(event.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailSection("我的补充") {
                    event.userWords?.takeIf { it.isNotBlank() && addendum == it && !savingAddendum }?.let { words -> Text(words) }
                    OutlinedTextField(
                        value = addendum,
                        onValueChange = {
                            addendum = it
                            addendumError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("补充一句原话或说明") },
                        minLines = 2,
                    )
                    OutlinedButton(
                        onClick = {
                            if (!savingAddendum) {
                                savingAddendum = true
                                scope.launch {
                                    addendumError = if (onSaveAddendum(addendum)) null else "补充没有保存；请检查本机存储状态后重试。"
                                    savingAddendum = false
                                }
                            }
                        },
                        enabled = !savingAddendum && addendum.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (savingAddendum) "正在保存…" else "保存补充") }
                    addendumError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                DetailSection("当前状态") {
                    Text(event.factStatus.label)
                    if (event.isLocalOnly) Text("仅本机；不代表其他设备没有记录。")
                }
                if (event.factStatus == FactStatus.NeedsReview || event.factStatus == FactStatus.Planned) {
                    DetailSection("核验这件事") {
                        Text(
                            if (event.factStatus == FactStatus.Planned) {
                                "日历或来源只说明计划；请选择它是否实际发生。"
                            } else {
                                "当前来源不足以确认事实；你的选择会形成一条新的 Revision。"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (!savingStatus) {
                                    savingStatus = true
                                    scope.launch {
                                        statusError = if (onUpdateFactStatus(FactStatus.Confirmed)) null else "状态没有保存；请检查本机存储状态后重试。"
                                        savingStatus = false
                                    }
                                }
                            },
                            enabled = !savingStatus,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (savingStatus) "正在保存…" else "确认已发生") }
                        OutlinedButton(
                            onClick = {
                                if (!savingStatus) {
                                    savingStatus = true
                                    scope.launch {
                                        statusError = if (onUpdateFactStatus(FactStatus.Planned)) null else "状态没有保存；请检查本机存储状态后重试。"
                                        savingStatus = false
                                    }
                                }
                            },
                            enabled = !savingStatus,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("仍是计划") }
                        statusError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                DetailSection("为什么这样记录") {
                    Text("来源：${event.sourceLabel}")
                    Text(
                        "来源摘要只展示当前获准信息；它支持的字段与事实状态分别说明。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DetailSection("使用范围与修改历史") {
                    Text("Personal 空间 · revision ${event.revision}")
                    Text("未授予任何 Agent 或跨设备内容访问。")
                }
                Button(onClick = { onDelete(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AmemeSymbols.Delete, contentDescription = null)
                    Text("查看删除影响", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
