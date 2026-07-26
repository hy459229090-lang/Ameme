package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.ameme.android.ui.icons.AmemeSymbols
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteScreen(
    event: MemoryEvent?,
    onBack: () -> Unit,
    onDeleteLocally: suspend () -> Boolean,
    onDeleteComplete: () -> Unit,
) {
    var stepName by rememberSaveable { mutableStateOf(DeleteStep.Queued.name) }
    var deleteInFlight by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val step = DeleteStep.valueOf(stepName)

    fun startDeletion() {
        if (deleteInFlight || event == null) return
        deleteInFlight = true
        stepName = DeleteStep.LocalDeleting.name
        scope.launch {
            val deleted = onDeleteLocally()
            stepName = if (deleted) DeleteStep.Completed.name else DeleteStep.PartialFailed.name
            deleteInFlight = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("删除影响与进度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AmemeSymbols.ArrowBack, contentDescription = "返回")
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
                    Text("影响范围", fontWeight = FontWeight.SemiBold)
                    Text("• 当前 Event 与 DayLedger 条目")
                    Text("• 今日小结和本地搜索索引")
                    Text("• 其他已授权设备的删除传播状态（若存在）")
                    Text("• 不会删除任何系统照片、文件或真实来源")
                }
            }
            ProgressCard(step)
            when (step) {
                DeleteStep.Queued -> Button(
                    onClick = ::startDeletion,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = event != null && !deleteInFlight,
                ) {
                    Icon(AmemeSymbols.DeleteForever, contentDescription = null)
                    Text("确认删除", modifier = Modifier.padding(start = 8.dp))
                }
                DeleteStep.LocalDeleting,
                DeleteStep.SyncPropagating,
                DeleteStep.Recomputing -> {
                    Text("正在处理删除任务…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeleteStep.PartialFailed -> Button(
                    onClick = ::startDeletion,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleteInFlight,
                ) { Text("重试删除") }
                DeleteStep.Completed -> Button(
                    onClick = onDeleteComplete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回今天") }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("离开，任务状态保留") }
        }
    }
}

@Composable
private fun ProgressCard(step: DeleteStep) {
    val icon: ImageVector = when (step) {
        DeleteStep.Completed -> AmemeSymbols.CheckCircle
        DeleteStep.PartialFailed -> AmemeSymbols.Error
        else -> AmemeSymbols.HourglassTop
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(step.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (step) {
                    DeleteStep.Queued -> "尚未开始传播。确认后才会修改本机加密节点。"
                    DeleteStep.LocalDeleting -> "本机对象进入不可见与清理步骤。"
                    DeleteStep.SyncPropagating -> "未收到全部副本确认前不会显示全部完成。"
                    DeleteStep.Recomputing -> "正在重算日流、小结和索引。"
                    DeleteStep.PartialFailed -> "本机内容已安全处理，但其他设备的删除证明不完整。"
                    DeleteStep.Completed -> "所有已知步骤已完成，可从当前会话移除事件。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
