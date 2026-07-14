package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.displayDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    event: MemoryEvent?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("事件详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (event == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("这条合成事件已不存在。")
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
                event.userWords?.let { words ->
                    DetailSection("我的补充") { Text(words) }
                }
                DetailSection("当前状态") {
                    Text(event.factStatus.label)
                    if (event.isLocalOnly) Text("仅本机；不代表其他设备没有记录。")
                }
                DetailSection("为什么这样记录") {
                    Text("来源：${event.sourceLabel}")
                    Text("所有内容均为合成数据；没有访问系统来源。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailSection("使用范围与修改历史") {
                    Text("Personal 合成空间 · revision 1")
                    Text("未授予任何 Agent 或跨设备内容访问。")
                }
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("补充或修正（mock）") }
                Button(onClick = { onDelete(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
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
