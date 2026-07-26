package com.ameme.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.MemoryIoExecutor
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.ui.components.EmptyMessage
import com.ameme.android.ui.components.DemoModeNotice
import com.ameme.android.ui.components.EventRow
import com.ameme.android.ui.components.StateNotice
import com.ameme.android.ui.displayDate
import com.ameme.android.ui.icons.AmemeSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
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
    onSettings: () -> Unit,
    onEvent: (String) -> Unit,
    demoMode: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var selectedStartDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedEndDate by remember { mutableStateOf<LocalDate?>(null) }
    var calendarEndpoint by remember { mutableStateOf<CalendarEndpoint?>(null) }
    var page by remember(repository) { mutableStateOf<com.ameme.android.domain.MemoryPage?>(null) }
    var initialSearchInFlight by remember { mutableStateOf(false) }
    var loadMoreInFlight by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchAttempt by remember { mutableIntStateOf(0) }
    var loadMoreJob by remember { mutableStateOf<Job?>(null) }
    val coordinator = remember { SearchRequestCoordinator() }
    val latestRepository by rememberUpdatedState(repository)
    val latestQuery by rememberUpdatedState(query)
    val latestStartDate by rememberUpdatedState(selectedStartDate)
    val latestEndDate by rememberUpdatedState(selectedEndDate)
    val scope = rememberCoroutineScope()
    val searchInFlight = initialSearchInFlight || loadMoreInFlight

    fun latestIdentity() = SearchRequestIdentity(latestRepository, latestQuery, latestStartDate, latestEndDate)

    fun invalidateFor(identity: SearchRequestIdentity) {
        coordinator.begin(identity)
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreInFlight = false
        page = null
        searchFailed = false
    }

    fun retrySearch() {
        if (searchInFlight) return
        searchFailed = false
        page = null
        searchAttempt += 1
    }

    LaunchedEffect(repository, query, selectedStartDate, selectedEndDate, searchAttempt) {
        val identity = SearchRequestIdentity(repository, query, selectedStartDate, selectedEndDate)
        val token = coordinator.begin(identity)
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreInFlight = false
        page = null
        initialSearchInFlight = true
        searchFailed = false
        try {
            if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
            val result = ioExecutor.searchPage(
                repository,
                query,
                selectedStartDate,
                selectedEndDate,
                cursor = null,
                pageSize = PAGE_SIZE,
            )
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
                        Icon(AmemeSymbols.ArrowBack, contentDescription = "返回今天")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics { contentDescription = "打开设置" },
                    ) {
                        Icon(AmemeSymbols.MoreVert, contentDescription = null)
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
                        invalidateFor(SearchRequestIdentity(repository, updated, selectedStartDate, selectedEndDate))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索历史记录") },
                leadingIcon = { Icon(AmemeSymbols.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            invalidateFor(SearchRequestIdentity(repository, "", selectedStartDate, selectedEndDate))
                        }) {
                            Icon(AmemeSymbols.Close, contentDescription = "清除搜索词")
                        }
                    }
                },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { calendarEndpoint = CalendarEndpoint.Start }) {
                    Icon(AmemeSymbols.CalendarMonth, contentDescription = null)
                    Text(
                        selectedStartDate?.displayDate() ?: "开始日期",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = { calendarEndpoint = CalendarEndpoint.End },
                    enabled = selectedStartDate != null,
                ) {
                    Text(selectedEndDate?.displayDate() ?: "结束日期", modifier = Modifier.padding(start = 8.dp))
                }
                if (selectedStartDate != null || selectedEndDate != null) {
                    TextButton(onClick = {
                        selectedStartDate = null
                        selectedEndDate = null
                        invalidateFor(SearchRequestIdentity(repository, query, null, null))
                    }) { Text("清除日期") }
                }
            }
            Text(
                rangeDescription(experienceMode, selectedStartDate, selectedEndDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StateNotice(
                mode = experienceMode,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (demoMode) {
                DemoModeNotice(modifier = Modifier.padding(top = 10.dp))
            }
            if (searchFailed) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "搜索暂不可用",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "已有内容保持安全；可以重试当前搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = ::retrySearch,
                        enabled = !searchInFlight,
                    ) { Text("重试搜索") }
                }
            }
            Text(
                "按日期从新到旧浏览，底部可加载更早记录",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (groups.isEmpty()) {
                if (searchFailed) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                } else {
                    EmptyMessage(
                        title = if (searchInFlight) {
                            "正在读取本机索引"
                        } else if (query.isBlank() && selectedStartDate == null && selectedEndDate == null) {
                            "当前可见范围内没有记录"
                        } else {
                            "当前条件没有结果"
                        },
                        detail = if (query.isBlank()) {
                            "这只说明当前本机与获准范围没有可见事件。"
                        } else {
                            "未找到“$query”；可以清除搜索词或日期，不代表这件事从未发生。"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                                        val identity = SearchRequestIdentity(repository, query, selectedStartDate, selectedEndDate)
                                        val token = coordinator.current(identity) ?: return@OutlinedButton
                                        loadMoreInFlight = true
                                        loadMoreJob = scope.launch {
                                            try {
                                                val next = ioExecutor.searchPage(
                                                    repository,
                                                    query,
                                                    selectedStartDate,
                                                    selectedEndDate,
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

    calendarEndpoint?.let { endpoint ->
        CalendarDialog(
            selectedDate = if (endpoint == CalendarEndpoint.Start) selectedStartDate else selectedEndDate,
            title = if (endpoint == CalendarEndpoint.Start) "选择开始日期" else "选择结束日期",
            onDismiss = { calendarEndpoint = null },
            onSelected = {
                if (endpoint == CalendarEndpoint.Start) {
                    selectedStartDate = it
                    if (selectedEndDate != null && selectedEndDate!!.isBefore(it)) selectedEndDate = it
                } else {
                    if (selectedStartDate != null && it.isBefore(selectedStartDate)) {
                        selectedEndDate = selectedStartDate
                        selectedStartDate = it
                    } else {
                        selectedEndDate = it
                    }
                }
                invalidateFor(SearchRequestIdentity(repository, query, selectedStartDate, selectedEndDate))
                calendarEndpoint = null
            },
        )
    }
}

private const val PAGE_SIZE = 20
private const val SEARCH_DEBOUNCE_MS = 200L

private enum class CalendarEndpoint {
    Start,
    End,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(
    selectedDate: LocalDate?,
    title: String,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val initialSelectedDateMillis = selectedDate
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?.toEpochMilli()
    val state = remember(selectedDate) {
        DatePickerState(
            locale = Locale.SIMPLIFIED_CHINESE,
            initialSelectedDateMillis = initialSelectedDateMillis,
        )
    }
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
        Column {
            Text(title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            DatePicker(
                state = state,
                title = { Text("选择日期") },
                headline = {
                    Text(
                        state.selectedDateMillis
                            ?.let { millis ->
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().displayDate()
                            }
                            ?: "尚未选择日期",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                },
            )
        }
    }
}

private fun rangeDescription(mode: ExperienceMode, startDate: LocalDate?, endDate: LocalDate?): String = buildString {
    append(
        when {
            startDate == null && endDate == null -> "全部可见日期 · "
            startDate != null && endDate == null -> "从 ${startDate.displayDate()} 起 · "
            startDate == null && endDate != null -> "截至 ${endDate.displayDate()} · "
            startDate == endDate -> "${startDate!!.displayDate()} · "
            else -> "${startDate!!.displayDate()} 至 ${endDate!!.displayDate()} · "
        },
    )
    append("Personal 空间 · 仅本机")
    when (mode) {
        ExperienceMode.Partial -> append(" + 1 台设备未同步")
        ExperienceMode.Offline -> append(" · 离线结果")
        ExperienceMode.Loading -> append(" · 索引更新中，结果可能不完整")
        else -> Unit
    }
}
