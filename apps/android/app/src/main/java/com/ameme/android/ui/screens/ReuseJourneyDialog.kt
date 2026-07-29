package com.ameme.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.ResolvedReuseContext
import com.ameme.android.data.ResolvedReuseItem
import com.ameme.android.data.ReuseIntent
import com.ameme.android.data.ReuseOutcome
import com.ameme.android.data.ReuseRangeState
import com.ameme.android.data.ReuseSelectionReason
import com.ameme.android.ui.displayDate

@Composable
internal fun ReuseJourneyDialog(
    resolved: ResolvedReuseContext,
    feedbackInFlight: Boolean,
    feedbackRecorded: Boolean,
    onOpenEvent: (String) -> Unit,
    onFeedback: (ReuseOutcome) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!feedbackInFlight) onDismiss() },
        title = { Text(resolved.context.intent.userLabel()) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when (resolved.context.rangeState) {
                        ReuseRangeState.CompleteForLocalScope -> "当前本机获准范围内结果完整"
                        ReuseRangeState.PartialForLocalScope -> "结果已达上限，当前只显示一部分"
                        ReuseRangeState.Empty -> "当前本机获准范围没有可用结果"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                resolved.items.forEachIndexed { index, item ->
                    TextButton(
                        onClick = { onOpenEvent(item.sourceEvent.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reuse-result-$index"),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                item.memorySummary ?: item.sourceEvent.title,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                item.resultDetail(),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    if (feedbackRecorded) {
                        "反馈已保存；只记录结果类型和动作，不记录正文或搜索词。"
                    } else {
                        "这次找回有帮助吗？反馈不包含正文、搜索词或原始对象 ID。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    feedbackOptions.forEach { (label, outcome) ->
                        TextButton(
                            onClick = { onFeedback(outcome) },
                            enabled = !feedbackInFlight && !feedbackRecorded,
                            modifier = Modifier.testTag("reuse-feedback-${outcome.wireValue}"),
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !feedbackInFlight,
            ) {
                Text("关闭")
            }
        },
    )
}

private val feedbackOptions = listOf(
    "有帮助" to ReuseOutcome.Useful,
    "没帮助" to ReuseOutcome.NotUseful,
    "记错了" to ReuseOutcome.WrongMemory,
    "有遗漏" to ReuseOutcome.ImportantMiss,
    "已过期" to ReuseOutcome.Outdated,
)

private fun ReuseIntent.userLabel(): String = when (this) {
    ReuseIntent.HistoricalSearch -> "历史找回"
    ReuseIntent.ProjectResume -> "继续项目"
    ReuseIntent.PreMeetingContext -> "准备会面"
    ReuseIntent.DecisionCommitmentRecall -> "决定与承诺"
}

private fun ResolvedReuseItem.resultDetail(): String {
    val reason = when (reference.selectionReason) {
        ReuseSelectionReason.KeywordMatch -> "关键词匹配"
        ReuseSelectionReason.DateMatch -> "日期匹配"
        ReuseSelectionReason.MeetingAnchor -> "会面日期"
        ReuseSelectionReason.ActiveDecision -> "已确认决定"
        ReuseSelectionReason.ActiveCommitment -> "已确认承诺"
    }
    val source = if (memorySummary == null) sourceEvent.detail else "来自事件：${sourceEvent.title}"
    return "${sourceEvent.localDate.displayDate()} · $reason\n$source"
}
