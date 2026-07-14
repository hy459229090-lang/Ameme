package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.MemoryRepository
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.ui.components.EmptyMessage
import com.ameme.android.ui.components.EventRow
import com.ameme.android.ui.components.StateNotice
import com.ameme.android.ui.displayDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: MemoryRepository,
    experienceMode: ExperienceMode,
    onBack: () -> Unit,
    onEvent: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    val rawGroups = repository.search(query, selectedDate)
    val groups = when (experienceMode) {
        ExperienceMode.Empty -> emptyList()
        ExperienceMode.Sparse -> rawGroups.take(1).map { it.copy(events = it.events.take(1)) }
        else -> rawGroups
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回今天")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索历史记录") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除搜索词")
                        }
                    }
                },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showCalendar = true }) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                    Text(selectedDate?.displayDate() ?: "选择日期", modifier = Modifier.padding(start = 8.dp))
                }
                if (selectedDate != null) {
                    TextButton(onClick = { selectedDate = null }) { Text("清除日期") }
                }
            }
            Text(
                rangeDescription(experienceMode, selectedDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StateNotice(
                mode = experienceMode,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (groups.isEmpty()) {
                EmptyMessage(
                    title = if (query.isBlank() && selectedDate == null) "当前可见范围内没有记录" else "当前条件没有结果",
                    detail = if (query.isBlank()) {
                        "这只说明当前本机与获准范围没有可见事件。"
                    } else {
                        "未找到“$query”；可以清除搜索词或日期，不代表这件事从未发生。"
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "从底部开始，向上滑加载更早日期",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "day-${group.date}") {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    group.date.displayDate(),
                                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                group.events.forEach { event ->
                                    EventRow(event = event, onClick = { onEvent(event.id) })
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "更早的合成日期已加载完毕",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showCalendar) {
        CalendarDialog(
            selectedDate = selectedDate,
            onDismiss = { showCalendar = false },
            onSelected = {
                selectedDate = it
                showCalendar = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(
    selectedDate: LocalDate?,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

private fun rangeDescription(mode: ExperienceMode, date: LocalDate?): String = buildString {
    append(date?.let { "${it.displayDate()} · " } ?: "全部可见日期 · ")
    append("Personal 合成空间 · 本机")
    when (mode) {
        ExperienceMode.Partial -> append(" + 1 台设备未同步")
        ExperienceMode.Offline -> append(" · 离线结果")
        ExperienceMode.Loading -> append(" · 索引更新中，结果可能不完整")
        else -> Unit
    }
}
