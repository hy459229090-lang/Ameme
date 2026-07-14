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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.MemoryIoExecutor
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.components.EmptyMessage
import com.ameme.android.ui.components.EventRow
import com.ameme.android.ui.components.StateNotice
import com.ameme.android.ui.displayDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: MemoryRepository,
    ioExecutor: MemoryIoExecutor,
    experienceMode: ExperienceMode,
    onBack: () -> Unit,
    onEvent: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var page by remember(repository) { mutableStateOf<com.ameme.android.domain.MemoryPage?>(null) }
    var initialSearchInFlight by remember { mutableStateOf(false) }
    var loadMoreInFlight by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var loadMoreJob by remember { mutableStateOf<Job?>(null) }
    val coordinator = remember { SearchRequestCoordinator() }
    val latestRepository by rememberUpdatedState(repository)
    val latestQuery by rememberUpdatedState(query)
    val latestDate by rememberUpdatedState(selectedDate)
    val scope = rememberCoroutineScope()
    val searchInFlight = initialSearchInFlight || loadMoreInFlight

    fun latestIdentity() = SearchRequestIdentity(latestRepository, latestQuery, latestDate)

    fun invalidateFor(identity: SearchRequestIdentity) {
        coordinator.begin(identity)
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreInFlight = false
        page = null
    }

    LaunchedEffect(repository, query, selectedDate) {
        val identity = SearchRequestIdentity(repository, query, selectedDate)
        val token = coordinator.begin(identity)
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreInFlight = false
        page = null
        initialSearchInFlight = true
        searchFailed = false
        try {
            if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
            val result = ioExecutor.searchPage(repository, query, selectedDate, cursor = null, pageSize = PAGE_SIZE)
            coordinator.commitIfCurrent(token, latestIdentity()) { page = result }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            coordinator.commitIfCurrent(token, latestIdentity()) {
                page = null
                searchFailed = true
            }
        } finally {
            if (coordinator.isCurrent(token, latestIdentity())) initialSearchInFlight = false
        }
    }
    val rawGroups = page?.events.orEmpty()
        .groupBy(MemoryEvent::localDate)
        .toSortedMap(compareByDescending { it })
        .map { (date, events) -> DayGroup(date, events) }
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
                onValueChange = { updated ->
                    if (updated != query) {
                        query = updated
                        invalidateFor(SearchRequestIdentity(repository, updated, selectedDate))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索历史记录") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            invalidateFor(SearchRequestIdentity(repository, "", selectedDate))
                        }) {
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
                    TextButton(onClick = {
                        selectedDate = null
                        invalidateFor(SearchRequestIdentity(repository, query, null))
                    }) { Text("清除日期") }
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
            Text(
                "按日期从新到旧浏览，底部可加载更早记录",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (groups.isEmpty()) {
                EmptyMessage(
                    title = when {
                        searchInFlight -> "正在读取本机索引"
                        searchFailed -> "搜索暂不可用"
                        query.isBlank() && selectedDate == null -> "当前可见范围内没有记录"
                        else -> "当前条件没有结果"
                    },
                    detail = if (searchFailed) {
                        "已有内容保持安全；可以稍后重试当前搜索。"
                    } else if (query.isBlank()) {
                        "这只说明当前本机与获准范围没有可见事件。"
                    } else {
                        "未找到“$query”；可以清除搜索词或日期，不代表这件事从未发生。"
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                    item(key = "load-more") {
                        if (page?.nextCursor != null) {
                            OutlinedButton(
                                onClick = {
                                    val current = page ?: return@OutlinedButton
                                    if (!initialSearchInFlight && !loadMoreInFlight) {
                                        val identity = SearchRequestIdentity(repository, query, selectedDate)
                                        val token = coordinator.current(identity) ?: return@OutlinedButton
                                        loadMoreInFlight = true
                                        loadMoreJob = scope.launch {
                                            try {
                                                val next = ioExecutor.searchPage(
                                                    repository,
                                                    query,
                                                    selectedDate,
                                                    current.nextCursor,
                                                    PAGE_SIZE,
                                                )
                                                coordinator.commitIfCurrent(token, latestIdentity()) {
                                                    page = current.copy(
                                                        events = current.events + next.events,
                                                        nextCursor = next.nextCursor,
                                                        searchBackend = next.searchBackend,
                                                    )
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Throwable) {
                                                coordinator.commitIfCurrent(token, latestIdentity()) {
                                                    searchFailed = true
                                                }
                                            } finally {
                                                if (coordinator.isCurrent(token, latestIdentity())) {
                                                    loadMoreInFlight = false
                                                    loadMoreJob = null
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                enabled = !searchInFlight,
                            ) { Text(if (searchInFlight) "正在加载…" else "加载更早") }
                        } else {
                            Text(
                                "当前范围已加载完毕",
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                invalidateFor(SearchRequestIdentity(repository, query, it))
                showCalendar = false
            },
        )
    }
}

private const val PAGE_SIZE = 20
private const val SEARCH_DEBOUNCE_MS = 200L

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
