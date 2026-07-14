package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ameme.android.data.source.ReadableCalendar

@Composable
fun CalendarImportDialog(
    calendars: List<ReadableCalendar>,
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, Int) -> Unit,
) {
    var selected by remember(calendars) { mutableStateOf(emptySet<String>()) }
    var rangeDays by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入日历计划") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("只读取你本次选择的日历和日期范围；不会后台全量扫描。导入内容只表示计划。")
                if (calendars.isEmpty()) {
                    Text("当前权限范围内没有可读取的日历。")
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        calendars.forEach { calendar ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = calendar.id in selected,
                                    enabled = !importing,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + calendar.id else selected - calendar.id
                                    },
                                )
                                Text(calendar.displayName, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
                Text("日期范围")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "今天", 7 to "7 天", 31 to "31 天").forEach { (days, label) ->
                        FilterChip(
                            selected = rangeDays == days,
                            enabled = !importing,
                            onClick = { rangeDays = days },
                            label = { Text(label) },
                        )
                    }
                }
                Text("单次最多导入 200 条；可在完成前取消。")
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && !importing,
                onClick = { onConfirm(selected, rangeDays) },
            ) { Text("确认导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (importing) "取消导入" else "取消") }
        },
    )
}
