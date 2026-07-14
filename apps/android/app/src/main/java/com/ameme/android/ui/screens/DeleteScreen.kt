package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.DeleteStep
import com.ameme.android.domain.MemoryEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteScreen(
    event: MemoryEvent?,
    onBack: () -> Unit,
    onDeleteLocally: suspend () -> Boolean,
) {
    var stepName by rememberSaveable { mutableStateOf(DeleteStep.Queued.name) }
    var deleteInFlight by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val step = DeleteStep.valueOf(stepName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("删除影响与进度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(event?.title ?: "事件已不存在", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("合成影响范围", fontWeight = FontWeight.SemiBold)
                    Text("• 当前 Event 与 DayLedger 条目")
                    Text("• 今日小结和本地搜索索引")
                    Text("• 1 台合成离线设备的待确认副本")
                    Text("• 不会删除任何系统照片、文件或真实来源")
                }
            }
            ProgressCard(step)
            when (step) {
                DeleteStep.Queued -> Button(
                    onClick = { stepName = DeleteStep.LocalDeleting.name },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = event != null,
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                    Text("确认删除合成事件", modifier = Modifier.padding(start = 8.dp))
                }
                DeleteStep.LocalDeleting -> Button(
                    onClick = { stepName = DeleteStep.SyncPropagating.name },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("模拟完成本机清理") }
                DeleteStep.SyncPropagating -> {
                    Button(
                        onClick = { stepName = DeleteStep.Recomputing.name },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("模拟设备确认") }
                    OutlinedButton(
                        onClick = { stepName = DeleteStep.PartialFailed.name },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("模拟离线设备失败") }
                }
                DeleteStep.Recomputing -> Button(
                    onClick = { stepName = DeleteStep.Completed.name },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("模拟完成重算") }
                DeleteStep.PartialFailed -> Button(
                    onClick = { stepName = DeleteStep.Recomputing.name },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试失败步骤") }
                DeleteStep.Completed -> Button(
                    onClick = {
                        if (!deleteInFlight) {
                            deleteInFlight = true
                            scope.launch {
                                if (!onDeleteLocally()) stepName = DeleteStep.PartialFailed.name
                                deleteInFlight = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleteInFlight,
                ) { Text(if (deleteInFlight) "正在提交删除…" else "返回今天") }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("离开，任务状态保留") }
        }
    }
}

@Composable
private fun ProgressCard(step: DeleteStep) {
    val icon: ImageVector = when (step) {
        DeleteStep.Completed -> Icons.Outlined.CheckCircle
        DeleteStep.PartialFailed -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.HourglassTop
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(step.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (step) {
                    DeleteStep.Queued -> "尚未开始传播。确认后才会修改合成 repository。"
                    DeleteStep.LocalDeleting -> "本机对象进入不可见与清理步骤。"
                    DeleteStep.SyncPropagating -> "未收到全部副本确认前不会显示全部完成。"
                    DeleteStep.Recomputing -> "正在重算日流、小结和索引。"
                    DeleteStep.PartialFailed -> "本机内容已安全处理，但副本证明不完整。"
                    DeleteStep.Completed -> "所有合成步骤已完成，可从当前会话移除事件。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
