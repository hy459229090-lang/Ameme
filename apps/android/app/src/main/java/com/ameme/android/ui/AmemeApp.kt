package com.ameme.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.MemoryIoExecutor
import com.ameme.android.data.UnavailableMemoryRepository
import com.ameme.android.data.runCatchingCancellable
import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.data.source.ContentUriGrantResolver
import com.ameme.android.data.source.AndroidCalendarProviderDataSource
import com.ameme.android.data.source.CalendarImportCancellation
import com.ameme.android.data.source.CalendarImportCancelled
import com.ameme.android.data.source.CalendarImportRequest
import com.ameme.android.data.source.CaptureResultDecision
import com.ameme.android.data.source.ExplicitCaptureGate
import com.ameme.android.data.source.PhotoCaptureCoordinator
import com.ameme.android.data.source.ReadableCalendar
import com.ameme.android.data.source.ScopedCalendarAdapter
import com.ameme.android.data.source.SourceGrantCleanupCoordinator
import com.ameme.android.data.source.VoiceCaptureCoordinator
import com.ameme.android.data.source.VoiceCaptureOrigin
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.ui.screens.DeleteScreen
import com.ameme.android.ui.screens.CalendarImportDialog
import com.ameme.android.ui.screens.EventDetailScreen
import com.ameme.android.ui.screens.OnboardingScreen
import com.ameme.android.ui.screens.SearchScreen
import com.ameme.android.ui.screens.SettingsScreen
import com.ameme.android.ui.screens.TodayScreen
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private object Routes {
    const val Onboarding = "onboarding"
    const val Today = "today"
    const val Search = "search"
    const val Settings = "settings"
    const val Event = "event/{eventId}"
    const val Delete = "delete/{eventId}"

    fun event(id: String) = "event/$id"
    fun delete(id: String) = "delete/$id"
}

@Composable
fun AmemeApp(
    repositoryOverride: MemoryRepository? = null,
    incomingShare: SourceCaptureRequest? = null,
    onIncomingShareConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ioExecutor = remember { MemoryIoExecutor() }
    val unavailableRepository = remember { UnavailableMemoryRepository() }
    var repository by remember(repositoryOverride) { mutableStateOf(repositoryOverride) }
    val uiRepository = repository ?: unavailableRepository
    val events = remember { mutableStateListOf<com.ameme.android.domain.MemoryEvent>() }
    var persistenceError by remember { mutableStateOf<String?>(null) }
    var experienceModeName by rememberSaveable {
        mutableStateOf(if (repositoryOverride == null) ExperienceMode.Loading.name else ExperienceMode.Ready.name)
    }

    LaunchedEffect(repositoryOverride, appContext) {
        var unclaimedRepository: MemoryRepository? = null
        var primaryFailure: Throwable? = null
        try {
            val readyRepository = repositoryOverride ?: ioExecutor.open {
                    LocalMemoryRepository.open(
                        context = appContext,
                        spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
                    )
                }.also { unclaimedRepository = it }
            val restored = ioExecutor.loadActiveEvents(readyRepository)
            currentCoroutineContext().ensureActive()
            events.clear()
            events.addAll(restored)
            unclaimedRepository = null
            repository = readyRepository
            persistenceError = null
            if (experienceModeName == ExperienceMode.Loading.name) {
                experienceModeName = ExperienceMode.Ready.name
            }
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (failure: Throwable) {
            primaryFailure = failure
            persistenceError = "本机加密节点暂不可用；没有改用明文存储，请检查设备安全状态后重试。"
            experienceModeName = ExperienceMode.RecoverableError.name
        } finally {
            unclaimedRepository?.let { unclaimed ->
                try {
                    ioExecutor.close(unclaimed)
                } catch (closeFailure: Throwable) {
                    primaryFailure?.addSuppressed(closeFailure) ?: throw closeFailure
                }
            }
        }
    }
    DisposableEffect(repository, repositoryOverride) {
        onDispose {
            if (repositoryOverride == null) repository?.let(ioExecutor::closeInBackground)
        }
    }
    val experienceMode = ExperienceMode.valueOf(experienceModeName)
    val uriGrantResolver = remember(appContext) { ContentUriGrantResolver(appContext.contentResolver) }
    val photoCoordinator = remember(uiRepository, uriGrantResolver) {
        PhotoCaptureCoordinator(uiRepository, uriGrantResolver)
    }
    val grantCleanupCoordinator = remember(uiRepository, uriGrantResolver) {
        SourceGrantCleanupCoordinator(uiRepository, uriGrantResolver)
    }
    val voiceCoordinator = remember(uiRepository, uriGrantResolver) {
        VoiceCaptureCoordinator(uiRepository, uriGrantResolver)
    }
    val calendarDataSource = remember(appContext) { AndroidCalendarProviderDataSource(appContext) }
    val calendarAdapter = remember(uiRepository, calendarDataSource) {
        ScopedCalendarAdapter(
            expectedSpaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
            repository = uiRepository,
            dataSource = calendarDataSource,
            zoneId = ZoneId.systemDefault(),
        )
    }
    var calendarDialogCalendars by remember { mutableStateOf<List<ReadableCalendar>?>(null) }
    var calendarImporting by remember { mutableStateOf(false) }
    var calendarCancellation by remember { mutableStateOf<CalendarImportCancellation?>(null) }
    val voiceCaptureGate = remember { ExplicitCaptureGate() }
    var voiceCaptureInFlight by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            calendarCancellation?.cancel()
        }
    }

    fun saveVoiceResult(
        uri: android.net.Uri?,
        flags: Int,
        origin: VoiceCaptureOrigin,
    ) {
        when (voiceCaptureGate.acceptResult(hasSource = uri != null)) {
            CaptureResultDecision.Ignore -> return
            CaptureResultDecision.Cancelled -> {
                voiceCaptureInFlight = false
                return
            }
            CaptureResultDecision.Process -> Unit
        }
        val sourceUri = requireNotNull(uri)
        scope.launch {
            val result = runCatchingCancellable {
                ioExecutor.runSourceIo {
                    val mime = appContext.contentResolver.getType(sourceUri) ?: "audio/*"
                    voiceCoordinator.capture(sourceUri, flags, origin, mime)
                }
            }
            voiceCaptureGate.complete()
            voiceCaptureInFlight = false
            result.onSuccess { event ->
                if (event != null) events.add(event)
                persistenceError = null
            }.onFailure {
                persistenceError = "语音引用尚未保存；系统未返回可读音频或本机写入失败。"
            }
        }
    }

    val voiceRecorder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            saveVoiceResult(
                uri = result.data?.data,
                flags = result.data?.flags ?: 0,
                origin = VoiceCaptureOrigin.SystemRecorder,
            )
        } else {
            saveVoiceResult(null, 0, VoiceCaptureOrigin.SystemRecorder)
        }
    }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        saveVoiceResult(
            uri = uri,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            origin = VoiceCaptureOrigin.SystemPicker,
        )
    }
    val canRecordVoice = remember(appContext) {
        Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).resolveActivity(appContext.packageManager) != null
    }

    val openCalendarDialog: () -> Unit = openCalendarDialog@{
        val previous = calendarCancellation
        if (previous != null && !previous.cancel()) return@openCalendarDialog
        val cancellation = CalendarImportCancellation()
        calendarCancellation = cancellation
        calendarDialogCalendars = emptyList()
        calendarImporting = true
        scope.launch {
            val result = runCatchingCancellable {
                ioExecutor.runSourceIo { calendarDataSource.listCalendars(cancellation) }
            }
            if (calendarCancellation === cancellation) {
                calendarImporting = false
                calendarCancellation = null
                result.onSuccess { calendarDialogCalendars = it }
                    .onFailure { error ->
                        calendarDialogCalendars = null
                        if (error !is CalendarImportCancelled) {
                            persistenceError = if (error is SecurityException) {
                                "日历只读权限未授予；未读取或保存任何日历内容。"
                            } else {
                                "日历列表暂时无法读取；未保存任何日历内容。"
                            }
                        }
                    }
            }
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            openCalendarDialog()
        } else {
            persistenceError = "日历只读权限未授予；未读取或保存任何日历内容。"
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatchingCancellable { ioExecutor.runSourceIo { photoCoordinator.capture(uri) } }
                    .onSuccess { event ->
                        if (event != null) events.add(event)
                        persistenceError = null
                    }
                    .onFailure { persistenceError = "照片引用尚未保存；本机加密节点写入失败，请重试。" }
            }
        }
    }

    LaunchedEffect(repository, grantCleanupCoordinator) {
        if (repository == null) return@LaunchedEffect
        runCatchingCancellable { ioExecutor.retrySourceGrantCleanup(grantCleanupCoordinator) }
            .onSuccess { cleanup ->
                if (cleanup.remaining > 0) {
                    persistenceError = "仍有 ${cleanup.remaining} 个系统来源授权等待释放；下次启动会继续重试。"
                }
            }
            .onFailure { persistenceError = "系统来源授权清理暂不可用；待处理记录仍保留在本机并会重试。" }
    }

    LaunchedEffect(incomingShare, repository) {
        val readyRepository = repository
        if (incomingShare != null && readyRepository != null) {
            runCatchingCancellable { ioExecutor.captureSource(readyRepository, incomingShare) }
                .onSuccess {
                    events.add(it)
                    persistenceError = null
                }
                .onFailure { persistenceError = "分享内容尚未保存；请返回来源后重新分享。" }
            onIncomingShareConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Onboarding,
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                onContinue = {
                    navController.navigate(Routes.Today) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Today) {
            TodayScreen(
                events = events,
                experienceMode = experienceMode,
                onSearch = { navController.navigate(Routes.Search) },
                onSettings = { navController.navigate(Routes.Settings) },
                onEvent = { navController.navigate(Routes.event(it)) },
                persistenceError = persistenceError,
                canRecordVoice = canRecordVoice,
                voiceCaptureInFlight = voiceCaptureInFlight,
                onRequestPhoto = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRequestVoiceRecording = {
                    if (repository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                    } else if (voiceCaptureGate.begin()) {
                        voiceCaptureInFlight = true
                        runCatching {
                            voiceRecorder.launch(
                                Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }.onFailure {
                            voiceCaptureGate.launchFailed()
                            voiceCaptureInFlight = false
                            persistenceError = "设备没有可用的系统录音入口；可改为选择已有音频。"
                        }
                    }
                },
                onRequestVoiceSelection = {
                    if (repository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                    } else if (voiceCaptureGate.begin()) {
                        voiceCaptureInFlight = true
                        runCatching { voicePicker.launch(arrayOf("audio/*")) }
                            .onFailure {
                                voiceCaptureGate.launchFailed()
                                voiceCaptureInFlight = false
                                persistenceError = "系统音频选择器暂不可用；未创建语音记录。"
                            }
                    }
                },
                onRequestCalendar = {
                    if (repository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                    } else if (appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        openCalendarDialog()
                    } else {
                        calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    }
                },
                onCapture = { kind: CaptureKind, text: String ->
                    val readyRepository = repository
                    if (readyRepository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                        false
                    } else {
                        runCatchingCancellable { ioExecutor.capture(readyRepository, kind, text) }
                            .onSuccess {
                                events.add(it)
                                persistenceError = null
                            }
                            .onFailure {
                                persistenceError = "记录尚未保存；本机加密节点写入失败，请重试。"
                            }
                            .isSuccess
                    }
                },
            )
        }
        composable(Routes.Search) {
            SearchScreen(
                repository = uiRepository,
                ioExecutor = ioExecutor,
                experienceMode = experienceMode,
                onBack = navController::popBackStack,
                onEvent = { navController.navigate(Routes.event(it)) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                selectedMode = experienceMode,
                onModeSelected = { experienceModeName = it.name },
                onBack = navController::popBackStack,
            )
        }
        composable(
            route = Routes.Event,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { entry ->
            val event = events.firstOrNull { it.id == entry.arguments?.getString("eventId") }
            EventDetailScreen(
                event = event,
                onBack = navController::popBackStack,
                onDelete = { id -> navController.navigate(Routes.delete(id)) },
            )
        }
        composable(
            route = Routes.Delete,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { entry ->
            val eventId = entry.arguments?.getString("eventId").orEmpty()
            val event = events.firstOrNull { it.id == eventId }
            DeleteScreen(
                event = event,
                onBack = navController::popBackStack,
                onDeleteLocally = {
                    val readyRepository = repository
                    val deleted = if (readyRepository == null) {
                        false
                    } else {
                        runCatchingCancellable { ioExecutor.deleteEvent(readyRepository, eventId) }.getOrDefault(false)
                    }
                    if (deleted) {
                        events.removeAll { it.id == eventId }
                        persistenceError = runCatchingCancellable {
                            ioExecutor.retrySourceGrantCleanup(grantCleanupCoordinator)
                        }
                            .fold(
                                onSuccess = { cleanup ->
                                    if (cleanup.remaining == 0) null else {
                                        "事件已删除；仍有 ${cleanup.remaining} 个系统来源授权等待释放，稍后会重试。"
                                    }
                                },
                                onFailure = { "事件已删除；系统来源授权清理待稍后重试。" },
                            )
                        navController.popBackStack(Routes.Today, false)
                    } else {
                        persistenceError = "删除尚未持久化；本机事件仍保持可见。"
                    }
                    deleted
                },
            )
        }
    }

    calendarDialogCalendars?.let { calendars ->
        CalendarImportDialog(
            calendars = calendars,
            importing = calendarImporting,
            onDismiss = {
                val activeCancellation = calendarCancellation
                val canClose = activeCancellation == null || activeCancellation.cancel()
                if (canClose) {
                    calendarCancellation = null
                    calendarDialogCalendars = null
                    calendarImporting = false
                }
            },
            onConfirm = { calendarIds, rangeDays ->
                val cancellation = CalendarImportCancellation()
                calendarCancellation = cancellation
                calendarImporting = true
                val zone = ZoneId.systemDefault()
                val startDate = LocalDate.now()
                val start = startDate.atStartOfDay(zone).toInstant()
                val request = CalendarImportRequest(
                    spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
                    initiatedByUser = true,
                    calendarIds = calendarIds,
                    startInclusive = start,
                    endExclusive = startDate.plusDays(rangeDays.toLong()).atStartOfDay(zone).toInstant(),
                    maxItems = 200,
                )
                scope.launch {
                    val result = runCatchingCancellable {
                        ioExecutor.runSourceIo { calendarAdapter.import(request, cancellation) }
                    }
                    if (calendarCancellation === cancellation) {
                        calendarImporting = false
                        calendarCancellation = null
                        result.onSuccess { imported ->
                            events.addAll(imported)
                            calendarDialogCalendars = null
                            persistenceError = null
                        }.onFailure { error ->
                            if (error is CalendarImportCancelled) {
                                calendarDialogCalendars = null
                            } else {
                                persistenceError = if (error is SecurityException) {
                                    "日历只读权限已失效；未继续导入。"
                                } else {
                                    "日历导入失败；请缩小范围或稍后重试。"
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
