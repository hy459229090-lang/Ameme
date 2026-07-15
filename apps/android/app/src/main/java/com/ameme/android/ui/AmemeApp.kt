package com.ameme.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.ameme.android.data.summary.DaySummaryClient
import com.ameme.android.data.summary.DaySummaryClientException
import com.ameme.android.data.summary.HttpDaySummaryClient
import com.ameme.android.data.summary.InstallationSubjectRef
import com.ameme.android.data.transport.AgentLocalNodeRuntime
import com.ameme.android.data.transport.AgentLocalNodeRuntimeState
import com.ameme.android.data.transport.AgentPairingManager
import com.ameme.android.data.transport.AgentPairingMaterial
import com.ameme.android.data.transport.CreatedAgentPairing
import com.ameme.android.data.transport.PairingExperienceConnection
import com.ameme.android.data.transport.PairingExperienceConnector
import com.ameme.android.data.transport.PairingExperienceConnectorProvider
import com.ameme.android.data.transport.PairingExperienceStore
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
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
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
    summaryClientOverride: DaySummaryClient? = null,
    pairingExperienceConnectorOverride: PairingExperienceConnector? = null,
    incomingShare: SourceCaptureRequest? = null,
    onIncomingShareConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val onboardingPreferences = remember(appContext) {
        appContext.getSharedPreferences("ameme_onboarding", android.content.Context.MODE_PRIVATE)
    }
    val startDestination = remember(repositoryOverride, onboardingPreferences) {
        if (repositoryOverride == null && onboardingPreferences.getBoolean("completed", false)) {
            Routes.Today
        } else {
            Routes.Onboarding
        }
    }
    val scope = rememberCoroutineScope()
    val ioExecutor = remember { MemoryIoExecutor() }
    val pairingManager = remember(appContext) { AgentPairingManager(appContext) }
    val pairingExperienceConnector: PairingExperienceConnector? =
        remember(pairingExperienceConnectorOverride) {
            pairingExperienceConnectorOverride ?: PairingExperienceConnectorProvider.create()
        }
    val pairingExperienceStore = remember(appContext) { PairingExperienceStore(appContext) }
    var pairingExperienceConnection: PairingExperienceConnection? by remember(pairingExperienceStore) {
        mutableStateOf(pairingExperienceStore.load())
    }
    val unavailableRepository = remember { UnavailableMemoryRepository() }
    var repository by remember(repositoryOverride) { mutableStateOf(repositoryOverride) }
    val uiRepository = repository ?: unavailableRepository
    val events = remember { mutableStateListOf<com.ameme.android.domain.MemoryEvent>() }
    var daySummary by remember {
        mutableStateOf(
            DaySummarySnapshot(LocalDate.now(), 0, emptyList(), DaySummaryState.Insufficient),
        )
    }
    var summaryInFlight by remember { mutableStateOf(false) }
    val summaryClient = remember(summaryClientOverride) {
        summaryClientOverride ?: com.ameme.android.BuildConfig.AMEME_INFERENCE_BASE_URL
            .takeIf(String::isNotBlank)
            ?.let(::HttpDaySummaryClient)
    }
    var persistenceError by remember { mutableStateOf<String?>(null) }
    var agentPairingMaterial by remember { mutableStateOf<AgentPairingMaterial?>(null) }
    var createdAgentPairing by remember { mutableStateOf<CreatedAgentPairing?>(null) }
    var agentRuntime by remember { mutableStateOf<AgentLocalNodeRuntime?>(null) }
    var agentRuntimeState by remember { mutableStateOf(AgentLocalNodeRuntimeState.Stopped) }
    var pairingInFlight by remember { mutableStateOf(false) }
    var pairingGeneration by remember { mutableIntStateOf(0) }
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
            daySummary = ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
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
        val ownedRepository = repository.takeIf { repositoryOverride == null }
        onDispose {
            ownedRepository?.let(ioExecutor::closeInBackground)
        }
    }
    LaunchedEffect(repository, pairingGeneration) {
        agentRuntime?.close()
        agentRuntime = null
        agentRuntimeState = AgentLocalNodeRuntimeState.Stopped
        val localRepository = repository as? LocalMemoryRepository
        if (localRepository == null) {
            agentPairingMaterial = null
            return@LaunchedEffect
        }
        val activeAndFactory = ioExecutor.runSourceIo {
            val active = pairingManager.loadActive() ?: return@runSourceIo null
            try {
                active to pairingManager.sslServerSocketFactory()
            } catch (failure: Throwable) {
                active.close()
                throw failure
            }
        }
        if (activeAndFactory == null) {
            agentPairingMaterial = null
            return@LaunchedEffect
        }
        val (active, socketFactory) = activeAndFactory
        agentPairingMaterial = active.material
        agentRuntime = try {
            AgentLocalNodeRuntime.launch(
                repository = localRepository,
                pairing = active,
                sslServerSocketFactory = socketFactory,
                onStateChanged = { state -> scope.launch { agentRuntimeState = state } },
                onEventPersisted = {
                    scope.launch {
                        val restored = ioExecutor.loadActiveEvents(localRepository)
                        events.clear()
                        events.addAll(restored)
                        daySummary = ioExecutor.loadDaySummary(localRepository, LocalDate.now())
                    }
                },
            )
        } catch (failure: Throwable) {
            active.close()
            agentRuntimeState = AgentLocalNodeRuntimeState.RecoverableError
            persistenceError = "Agent 配对已保存，但本机监听暂时无法启动。"
            null
        }
    }
    DisposableEffect(agentRuntime) {
        val ownedRuntime = agentRuntime
        onDispose { ownedRuntime?.close() }
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
                if (event != null) {
                    events.add(event)
                    repository?.let { ready ->
                        daySummary = ioExecutor.loadDaySummary(ready, LocalDate.now())
                    }
                }
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
                        if (event != null) {
                            events.add(event)
                            repository?.let { ready ->
                                daySummary = ioExecutor.loadDaySummary(ready, LocalDate.now())
                            }
                        }
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
                    daySummary = ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
                    persistenceError = null
                }
                .onFailure { persistenceError = "分享内容尚未保存；请返回来源后重新分享。" }
            onIncomingShareConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                onContinue = {
                    if (repositoryOverride == null) {
                        onboardingPreferences.edit().putBoolean("completed", true).apply()
                    }
                    navController.navigate(Routes.Today) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Today) {
            TodayScreen(
                events = events,
                daySummary = daySummary,
                summaryInFlight = summaryInFlight,
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
                                daySummary = ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
                                persistenceError = null
                            }
                            .onFailure {
                                persistenceError = "记录尚未保存；本机加密节点写入失败，请重试。"
                            }
                            .isSuccess
                    }
                },
                onGenerateSummary = {
                    val readyRepository = repository
                    val client = summaryClient
                    if (readyRepository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                        false
                    } else if (client == null) {
                        persistenceError = "AI 小结服务尚未配置；今天的事件仍完整保存在本机。"
                        false
                    } else if (summaryInFlight) {
                        false
                    } else {
                        summaryInFlight = true
                        val result = runCatchingCancellable {
                            val fresh = ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
                            val processing = ioExecutor.beginDaySummary(
                                readyRepository,
                                fresh.localDate,
                                fresh.ledgerRevision,
                            )
                            daySummary = processing
                            val subjectRef = ioExecutor.runSourceIo {
                                InstallationSubjectRef.getOrCreate(appContext)
                            }
                            val generated = ioExecutor.generateDaySummary(
                                client,
                                processing,
                                subjectRef,
                                ZoneId.systemDefault(),
                            )
                            ioExecutor.completeDaySummary(
                                readyRepository,
                                processing.localDate,
                                processing.ledgerRevision,
                                generated.text,
                                generated.modelOrRuleVersion,
                            )
                        }
                        if (result.isSuccess) {
                            daySummary = result.getOrThrow()
                            persistenceError = null
                        } else {
                            val safeCode = when (result.exceptionOrNull()) {
                                is DaySummaryClientException -> (result.exceptionOrNull() as DaySummaryClientException).code
                                is IllegalArgumentException -> "CLIENT_PRECONDITION"
                                is IllegalStateException -> "LOCAL_STATE"
                                else -> "UNEXPECTED"
                            }
                            Log.w("AmemeDaySummary", "generation_failed code=$safeCode")
                            daySummary = ioExecutor.failDaySummary(
                                readyRepository,
                                daySummary.localDate,
                                daySummary.ledgerRevision,
                            )
                            persistenceError = "AI 小结暂时没有生成；已记录事件未丢失，也没有改用模板冒充结果。"
                        }
                        summaryInFlight = false
                        result.isSuccess
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
            val developerAgentPairingDetail = when {
                agentPairingMaterial == null -> "未配对"
                agentRuntimeState == AgentLocalNodeRuntimeState.Listening -> "已配对 · 等待 Agent 连接"
                agentRuntimeState == AgentLocalNodeRuntimeState.ConnectionHandled -> "已配对 · 最近连接成功"
                agentRuntimeState == AgentLocalNodeRuntimeState.RecoverableError -> "已配对 · 监听暂不可用"
                else -> "已配对 · 正在启动"
            }
            SettingsScreen(
                selectedMode = experienceMode,
                onModeSelected = { experienceModeName = it.name },
                onBack = navController::popBackStack,
                showExperienceControls = repositoryOverride != null,
                pairingExperienceAvailable = pairingExperienceConnector != null,
                pairingExperienceConnection = pairingExperienceConnection,
                onResolvePairingCandidate = { method ->
                    requireNotNull(pairingExperienceConnector).resolve(method)
                },
                onConnectPairingCandidate = { candidate ->
                    val connected = requireNotNull(pairingExperienceConnector).connect(candidate)
                    ioExecutor.runSourceIo { pairingExperienceStore.save(connected) }
                    pairingExperienceConnection = connected
                    connected
                },
                onDisconnectPairingExperience = {
                    ioExecutor.runSourceIo { pairingExperienceStore.clear() }
                    pairingExperienceConnection = null
                    true
                },
                showDeveloperPairingControls = com.ameme.android.BuildConfig.DEBUG,
                developerAgentPairingDetail = developerAgentPairingDetail,
                developerPairingInFlight = pairingInFlight,
                developerPairingJson = createdAgentPairing?.pairingJson(),
                developerPairingSecret = createdAgentPairing?.oneTimeSecret,
                onCreateDeveloperAgentPairing = {
                    if (!pairingInFlight) {
                        pairingInFlight = true
                        scope.launch {
                            runCatchingCancellable {
                                ioExecutor.runSourceIo { pairingManager.create() }
                            }.onSuccess { created ->
                                createdAgentPairing = created
                                agentPairingMaterial = created.material
                                pairingGeneration += 1
                                persistenceError = null
                            }.onFailure {
                                persistenceError = "Agent 配对创建失败；没有生成或显示不完整的密钥。"
                            }
                            pairingInFlight = false
                        }
                    }
                },
                onRevokeDeveloperAgentPairing = {
                    if (!pairingInFlight) {
                        pairingInFlight = true
                        agentRuntime?.close()
                        agentRuntime = null
                        scope.launch {
                            runCatchingCancellable { ioExecutor.runSourceIo { pairingManager.revoke() } }
                                .onSuccess {
                                    agentPairingMaterial = null
                                    createdAgentPairing = null
                                    pairingGeneration += 1
                                    persistenceError = null
                                }
                                .onFailure { persistenceError = "Agent 配对撤销尚未完成，请重试。" }
                            pairingInFlight = false
                        }
                    }
                },
                onDismissDeveloperPairingSecret = { createdAgentPairing = null },
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
                        daySummary = ioExecutor.loadDaySummary(requireNotNull(readyRepository), LocalDate.now())
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
                            repository?.let { ready ->
                                daySummary = ioExecutor.loadDaySummary(ready, LocalDate.now())
                            }
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
