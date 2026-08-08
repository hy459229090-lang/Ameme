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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.MemoryIoExecutor
import com.ameme.android.data.AgentAccessAuditRecord
import com.ameme.android.data.AgentAccessAuditRepository
import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.LocalSpaceDeletionConvergenceCoordinator
import com.ameme.android.data.LocalSpaceDeletionConvergenceStatus
import com.ameme.android.data.LocalSpaceDeletionConvergenceStep
import com.ameme.android.data.StructuredExportWriter
import com.ameme.android.data.UnavailableMemoryRepository
import com.ameme.android.data.runCatchingCancellable
import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.data.local.AndroidKeystorePendingActionStore
import com.ameme.android.data.local.AndroidKeystoreDatabaseKeyProvider
import com.ameme.android.data.local.LocalRecoveryActivationCoordinator
import com.ameme.android.data.local.LocalRecoveryPointAvailability
import com.ameme.android.data.local.LocalRecoveryPointManager
import com.ameme.android.data.local.LocalRecoveryPointStatus
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
import com.ameme.android.data.transport.PairingQrScanFailure
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
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.ui.screens.DeleteScreen
import com.ameme.android.ui.screens.CalendarImportDialog
import com.ameme.android.ui.screens.EventDetailScreen
import com.ameme.android.ui.screens.OnboardingScreen
import com.ameme.android.ui.screens.SearchScreen
import com.ameme.android.ui.screens.SettingsScreen
import com.ameme.android.ui.screens.TodayScreen
import java.time.LocalDate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
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
    pairingQrScannerAvailable: Boolean = false,
    pairingQrScanPayload: String? = null,
    pairingQrScanFailure: PairingQrScanFailure? = null,
    onStartPairingQrScan: () -> Unit = {},
    onPairingQrScanConsumed: () -> Unit = {},
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
    val pendingActionStore = remember(appContext) { AndroidKeystorePendingActionStore(appContext) }
    val localRecoveryPointManager = remember(appContext) {
        LocalRecoveryPointManager(
            File(appContext.noBackupFilesDir, "ameme-local-recovery-v1"),
        )
    }
    val pairingExperienceConnector: PairingExperienceConnector? =
        remember(pairingExperienceConnectorOverride) {
            pairingExperienceConnectorOverride ?: PairingExperienceConnectorProvider.create(appContext)
        }
    val pairingExperienceStore = remember(appContext) { PairingExperienceStore(appContext) }
    var pairingExperienceConnection: PairingExperienceConnection? by remember(pairingExperienceStore) {
        mutableStateOf(pairingExperienceStore.load())
    }
    val unavailableRepository = remember { UnavailableMemoryRepository() }
    var repository by remember(repositoryOverride) { mutableStateOf(repositoryOverride) }
    var demoMode by rememberSaveable(repositoryOverride) { mutableStateOf(false) }
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
    var createdAgentPairingQrPayload by remember { mutableStateOf<String?>(null) }
    var createdAgentPairingQrExpiresAt by remember { mutableStateOf<Instant?>(null) }
    var agentRuntime by remember { mutableStateOf<AgentLocalNodeRuntime?>(null) }
    var agentRuntimeState by remember { mutableStateOf(AgentLocalNodeRuntimeState.Stopped) }
    var pairingInFlight by remember { mutableStateOf(false) }
    var pairingGeneration by remember { mutableIntStateOf(0) }
    var localSpaceDeleted by rememberSaveable(repositoryOverride) { mutableStateOf(false) }
    var localSpaceDeletionInFlight by remember { mutableStateOf(false) }
    var localSpaceDeletionNeedsRetry by remember { mutableStateOf(false) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    var exportInFlight by remember { mutableStateOf(false) }
    var localRecoveryPointStatus by remember {
        mutableStateOf(LocalRecoveryPointStatus.None)
    }
    var localRecoveryInFlight by remember { mutableStateOf(false) }
    var localRecoveryNotice by remember { mutableStateOf<String?>(null) }
    val manuallyClosedRepositories = remember {
        Collections.newSetFromMap(
            IdentityHashMap<MemoryRepository, Boolean>(),
        )
    }
    var pendingIncomingShare by remember { mutableStateOf<SourceCaptureRequest?>(null) }
    var incomingShareInFlight by remember { mutableStateOf(false) }
    var experienceModeName by rememberSaveable {
        mutableStateOf(if (repositoryOverride == null) ExperienceMode.Loading.name else ExperienceMode.Ready.name)
    }

    suspend fun convergeLocalSpaceDeletion(
        localRepository: LocalMemoryRepository,
        requestedAt: Instant,
    ) = LocalSpaceDeletionConvergenceCoordinator(
        spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
        stopIncomingAgentRuntime = {
            agentRuntime?.let { runtime ->
                check(ioExecutor.runSourceIo(runtime::closeAndAwait)) {
                    "Agent runtime did not stop before the local-space deletion boundary"
                }
            }
            agentRuntime = null
            agentRuntimeState = AgentLocalNodeRuntimeState.Stopped
        },
        deleteLocalSpace = { at ->
            ioExecutor.runSourceIo { localRepository.deleteLocalSpace(at) }
        },
        revokeStoredAgentPairing = {
            ioExecutor.runSourceIo { pairingManager.revoke() }
        },
        closeOutgoingAgentTransport = {
            val connected = pairingExperienceConnection
            if (connected != null) {
                requireNotNull(pairingExperienceConnector).disconnect(connected)
            } else {
                pairingExperienceConnector?.clearLocalCredentials()
            }
        },
        clearStoredConnectionMetadata = {
            ioExecutor.runSourceIo { pairingExperienceStore.clear() }
        },
        freezePendingActionResurrection = {
            ioExecutor.runSourceIo { pendingActionStore.freezeForDeletedSpace() }
        },
        clearPendingActionSnapshot = {
            ioExecutor.runSourceIo { pendingActionStore.clear() }
        },
    ).delete(requestedAt)

    fun applyLocalSpaceDeletionConvergence(
        result: com.ameme.android.data.LocalSpaceDeletionConvergenceResult,
    ) {
        if (LocalSpaceDeletionConvergenceStep.StoredAgentPairingRevocation in result.completedSteps) {
            agentPairingMaterial = null
            createdAgentPairing = null
            createdAgentPairingQrPayload = null
            createdAgentPairingQrExpiresAt = null
            pairingGeneration += 1
        }
        if (LocalSpaceDeletionConvergenceStep.StoredConnectionMetadataClear in result.completedSteps) {
            pairingExperienceConnection = null
        }
        if (LocalSpaceDeletionConvergenceStep.PendingActionSnapshotClear in result.completedSteps) {
            pendingIncomingShare = null
            pendingExportContent = null
        }
        val spaceRemainsFrozen = localSpaceDeleted || result.localSpaceFrozen
        localSpaceDeleted = spaceRemainsFrozen
        localSpaceDeletionNeedsRetry =
            result.status == LocalSpaceDeletionConvergenceStatus.PendingLocalRetry
        if (spaceRemainsFrozen) {
            events.clear()
            daySummary = DaySummarySnapshot(
                LocalDate.now(),
                0,
                emptyList(),
                DaySummaryState.Insufficient,
            )
            demoMode = false
            experienceModeName = ExperienceMode.RecoverableError.name
        }
        persistenceError = when (result.status) {
            LocalSpaceDeletionConvergenceStatus.CompletedLocalOnly ->
                "本机 Personal 空间已删除；本机 Agent、连接状态和待处理快照已收敛。账号、系统原件和其他设备不在此次删除范围内。"
            LocalSpaceDeletionConvergenceStatus.PendingExternalCleanup ->
                "本机 Personal 空间已冻结且本机授权已收敛；仍有系统来源授权等待释放，外部原件不会由 Ameme 删除。"
            LocalSpaceDeletionConvergenceStatus.PendingLocalRetry -> if (spaceRemainsFrozen) {
                "本机 Personal 空间已冻结；仍有 ${result.pendingRetrySteps.size} 个本机清理步骤待重试。账号和对端删除未被宣称完成。"
            } else {
                "本机 Space 删除尚未持久化；已尝试停止本机 Agent 与清理待处理快照，请重试并核对未完成步骤。"
            }
        }
    }

    LaunchedEffect(pendingActionStore) {
        runCatchingCancellable {
            ioExecutor.runSourceIo { pendingActionStore.load() }
        }.onSuccess { snapshot ->
            if (snapshot == null) return@onSuccess
            if (pendingIncomingShare == null) pendingIncomingShare = snapshot.incomingShare
            if (pendingExportContent == null) pendingExportContent = snapshot.exportContent
            if (snapshot.incomingShare != null || snapshot.exportContent != null) {
                persistenceError = "已恢复上次未完成操作；确认或重试前不会修改本机事件。"
            }
        }.onFailure {
            persistenceError = "待处理操作恢复失败；本机事件没有改变，请检查设备安全状态后重试。"
        }
    }

    LaunchedEffect(repositoryOverride, appContext, demoMode) {
        var unclaimedRepository: MemoryRepository? = null
        var primaryFailure: Throwable? = null
        try {
            val readyRepository = when {
                repositoryOverride != null -> repositoryOverride
                demoMode -> FakeMemoryRepository()
                else -> ioExecutor.open {
                    LocalMemoryRepository.open(
                        context = appContext,
                        spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
                    )
                }.also { unclaimedRepository = it }
            }
            val deletedLocalRepository = (readyRepository as? LocalMemoryRepository)
                ?.takeIf { ioExecutor.runSourceIo(it::isLocalSpaceDeleted) }
            val restored = if (deletedLocalRepository == null) {
                ioExecutor.loadActiveEvents(readyRepository)
            } else {
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            events.clear()
            events.addAll(restored)
            daySummary = if (deletedLocalRepository == null) {
                ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
            } else {
                DaySummarySnapshot(
                    LocalDate.now(),
                    0,
                    emptyList(),
                    DaySummaryState.Insufficient,
                )
            }
            deletedLocalRepository?.let {
                localSpaceDeleted = true
                applyLocalSpaceDeletionConvergence(
                    convergeLocalSpaceDeletion(it, Instant.now()),
                )
            }
            unclaimedRepository = null
            repository = readyRepository
            if (deletedLocalRepository == null) {
                localSpaceDeleted = false
                localSpaceDeletionNeedsRetry = false
                persistenceError = null
                localRecoveryPointStatus = (readyRepository as? LocalMemoryRepository)
                    ?.let { localRepository ->
                        ioExecutor.runSourceIo {
                            localRecoveryPointManager.refresh(localRepository)
                        }
                    }
                    ?: LocalRecoveryPointStatus.None
            } else {
                localRecoveryPointStatus = runCatchingCancellable {
                    ioExecutor.runSourceIo {
                        localRecoveryPointManager.clear()
                        LocalRecoveryPointStatus.None
                    }
                }.getOrElse {
                    LocalRecoveryPointStatus.Unavailable
                }
            }
            if (
                deletedLocalRepository == null &&
                experienceModeName == ExperienceMode.Loading.name
            ) {
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
            ownedRepository?.let { owned ->
                if (!manuallyClosedRepositories.remove(owned)) {
                    ioExecutor.closeInBackground(owned)
                }
            }
        }
    }
    LaunchedEffect(repository, pairingExperienceConnector, localSpaceDeleted) {
        if (
            repository == null ||
            localSpaceDeleted ||
            pairingExperienceConnector == null ||
            pairingExperienceConnector.simulated ||
            pairingExperienceConnection != null
        ) {
            return@LaunchedEffect
        }
        runCatchingCancellable {
            pairingExperienceConnector.restoreConnection()
        }.onSuccess { restored ->
            if (restored != null) {
                ioExecutor.runSourceIo { pairingExperienceStore.save(restored) }
                pairingExperienceConnection = restored
                persistenceError = null
            }
        }.onFailure {
            ioExecutor.runSourceIo { pairingExperienceStore.clear() }
            pairingExperienceConnection = null
            persistenceError =
                "设备授权连接未能恢复；没有回退为未认证连接，请重新扫描二维码。"
        }
    }
    LaunchedEffect(repository, pairingGeneration, localSpaceDeleted) {
        agentRuntime?.close()
        agentRuntime = null
        agentRuntimeState = AgentLocalNodeRuntimeState.Stopped
        val localRepository = repository as? LocalMemoryRepository
        if (localRepository == null || localSpaceDeleted) {
            agentPairingMaterial = null
            return@LaunchedEffect
        }
        val materialAndFactory = ioExecutor.runSourceIo {
            val active = pairingManager.loadActive() ?: return@runSourceIo null
            try {
                active.material to pairingManager.sslServerSocketFactory()
            } finally {
                active.close()
            }
        }
        if (materialAndFactory == null) {
            agentPairingMaterial = null
            return@LaunchedEffect
        }
        val (material, socketFactory) = materialAndFactory
        agentPairingMaterial = material
        agentRuntime = try {
            AgentLocalNodeRuntime.launch(
                repository = localRepository,
                pairingManager = pairingManager,
                sslServerSocketFactory = socketFactory,
                allowDeveloperCredential = com.ameme.android.BuildConfig.DEBUG,
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
            agentRuntimeState = AgentLocalNodeRuntimeState.RecoverableError
            persistenceError = "Agent 配对已保存，但本机监听暂时无法启动。"
            null
        }
    }
    DisposableEffect(agentRuntime) {
        val ownedRuntime = agentRuntime
        onDispose { ownedRuntime?.close() }
    }
    val baseExperienceMode = if (localSpaceDeleted) {
        ExperienceMode.RecoverableError
    } else {
        ExperienceMode.valueOf(experienceModeName)
    }
    val experienceMode = baseExperienceMode.resolvedFor(
        eventCount = events.size,
        deriveFromEvents = repositoryOverride == null,
    )
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
    var sourcePermissionRevision by remember { mutableIntStateOf(0) }
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
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = pendingExportContent
        if (uri == null || content == null) {
            exportInFlight = false
            if (uri == null) persistenceError = "导出已取消；本机数据没有改变，可以重试保存。"
        } else {
            scope.launch {
                val writeResult = runCatchingCancellable {
                    ioExecutor.runSourceIo {
                        val output = requireNotNull(appContext.contentResolver.openOutputStream(uri))
                        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                }
                if (writeResult.isFailure) {
                    exportInFlight = false
                    persistenceError = "导出文件写入失败；本机数据没有改变，请重试。"
                } else {
                    val clearResult = runCatchingCancellable {
                        ioExecutor.runSourceIo { pendingActionStore.clearExport() }
                    }
                    exportInFlight = false
                    if (clearResult.isSuccess) {
                        pendingExportContent = null
                        persistenceError = "结构化导出已保存；不包含受限事件或原始媒体文件。"
                    } else {
                        persistenceError = "结构化导出已保存；恢复快照清理失败，请稍后重试清理。"
                    }
                }
            }
        }
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
            sourcePermissionRevision += 1
            openCalendarDialog()
        } else {
            sourcePermissionRevision += 1
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

    LaunchedEffect(incomingShare) {
        if (incomingShare != null) {
            pendingIncomingShare = incomingShare
            runCatchingCancellable {
                ioExecutor.runSourceIo { pendingActionStore.saveIncomingShare(incomingShare) }
            }.onFailure {
                persistenceError = "分享内容已暂存于当前会话，但持久恢复快照不可用；请尽快确认或取消。"
            }
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
                        // This flag determines the first screen after process/activity
                        // recreation, so make the navigation decision durable before
                        // replacing the onboarding destination.
                        check(onboardingPreferences.edit().putBoolean("completed", true).commit()) {
                            "Could not persist onboarding completion"
                        }
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
                demoMode = demoMode,
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
                onSettings = { navController.navigate(Routes.Settings) },
                onEvent = { navController.navigate(Routes.event(it)) },
                demoMode = demoMode,
            )
        }
        composable(Routes.Settings) {
            val auditRepository = repository as? AgentAccessAuditRepository
            var agentAccessAuditRecords by remember(auditRepository) {
                mutableStateOf(emptyList<AgentAccessAuditRecord>())
            }
            var agentAccessAuditLoadFailed by remember(auditRepository) {
                mutableStateOf(false)
            }
            LaunchedEffect(auditRepository, agentRuntimeState) {
                if (auditRepository == null) {
                    agentAccessAuditRecords = emptyList()
                    agentAccessAuditLoadFailed = false
                    return@LaunchedEffect
                }
                val at = Instant.now()
                runCatchingCancellable {
                    ioExecutor.runSourceIo {
                        auditRepository.pruneExpiredAgentAccessAudit(at)
                        auditRepository.recentAgentAccessAudit(limit = 20, at = at)
                    }
                }.onSuccess { records ->
                    agentAccessAuditRecords = records
                    agentAccessAuditLoadFailed = false
                }.onFailure {
                    agentAccessAuditRecords = emptyList()
                    agentAccessAuditLoadFailed = true
                }
            }
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
                pairingQrScannerAvailable =
                    pairingQrScannerAvailable &&
                        pairingExperienceConnector?.simulated == false,
                pairingQrScanPayload = pairingQrScanPayload,
                pairingQrScanFailure = pairingQrScanFailure,
                onStartPairingQrScan = onStartPairingQrScan,
                onPairingQrScanConsumed = onPairingQrScanConsumed,
                onResolvePairingCandidate = { method ->
                    requireNotNull(pairingExperienceConnector).resolve(method)
                },
                onResolvePairingPayload = { payload ->
                    requireNotNull(pairingExperienceConnector)
                        .resolvePairingPayload(payload)
                },
                onConnectPairingCandidate = { candidate ->
                    val connected = requireNotNull(pairingExperienceConnector).connect(candidate)
                    ioExecutor.runSourceIo { pairingExperienceStore.save(connected) }
                    pairingExperienceConnection = connected
                    connected
                },
                onDisconnectPairingExperience = {
                    pairingExperienceConnection?.let { connected ->
                        requireNotNull(pairingExperienceConnector).disconnect(connected)
                    }
                    ioExecutor.runSourceIo { pairingExperienceStore.clear() }
                    pairingExperienceConnection = null
                    true
                },
                showDeveloperPairingControls = com.ameme.android.BuildConfig.DEBUG,
                agentPairingAvailable = repository is LocalMemoryRepository,
                developerAgentPairingDetail = developerAgentPairingDetail,
                developerPairingInFlight = pairingInFlight,
                developerPairingQrPayload = createdAgentPairingQrPayload,
                developerPairingQrExpiresAt = createdAgentPairingQrExpiresAt,
                developerPairingJson = createdAgentPairing?.pairingJson(),
                developerPairingSecret = createdAgentPairing?.developerChannelSecret,
                onCreateDeveloperAgentPairing = {
                    if (!pairingInFlight) {
                        pairingInFlight = true
                        scope.launch {
                            runCatchingCancellable {
                                ioExecutor.runSourceIo { pairingManager.create() }
                            }.onSuccess { created ->
                                runCatching { created.pairingQrPayload() }
                                    .onSuccess { qrPayload ->
                                        createdAgentPairing = created
                                        createdAgentPairingQrPayload = qrPayload
                                        createdAgentPairingQrExpiresAt = created.bootstrapExpiresAt
                                        agentPairingMaterial = created.material
                                        pairingGeneration += 1
                                        persistenceError = null
                                    }
                                    .onFailure {
                                        createdAgentPairing = null
                                        createdAgentPairingQrPayload = null
                                        createdAgentPairingQrExpiresAt = null
                                        agentPairingMaterial = created.material
                                        pairingGeneration += 1
                                        persistenceError = "配对已创建，但二维码生成失败；请重新生成。"
                                    }
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
                                    createdAgentPairingQrPayload = null
                                    createdAgentPairingQrExpiresAt = null
                                    pairingGeneration += 1
                                    persistenceError = null
                                }
                                .onFailure { persistenceError = "Agent 配对撤销尚未完成，请重试。" }
                            pairingInFlight = false
                        }
                    }
                },
                onDismissDeveloperPairingSecret = {
                    createdAgentPairing = null
                    createdAgentPairingQrPayload = null
                    createdAgentPairingQrExpiresAt = null
                },
                demoMode = demoMode,
                onExport = {
                    if (!exportInFlight) {
                        val readyRepository = repository
                        if (readyRepository == null) {
                            persistenceError = "本机加密节点仍在打开，请稍后重试。"
                        } else {
                            exportInFlight = true
                            scope.launch {
                                val generated = runCatchingCancellable {
                                    val currentEvents = ioExecutor.loadActiveEvents(readyRepository)
                                    StructuredExportWriter.encode(
                                        events = currentEvents,
                                        space = LocalEventDatabase.DEFAULT_SPACE_ID,
                                    )
                                }
                                if (generated.isFailure) {
                                    exportInFlight = false
                                    persistenceError = "导出尚未生成；本机数据保持不变，请重试。"
                                } else {
                                    val content = generated.getOrThrow()
                                    pendingExportContent = content
                                    val persisted = runCatchingCancellable {
                                        ioExecutor.runSourceIo { pendingActionStore.saveExport(content) }
                                    }
                                    if (persisted.isFailure) {
                                        persistenceError = "导出已准备好，但恢复快照不可用；请尽快保存。"
                                    }
                                    runCatching {
                                        exportDocument.launch("ameme-export-${LocalDate.now()}.json")
                                    }.onFailure {
                                        exportInFlight = false
                                        persistenceError = "导出入口暂不可用；本机数据没有改变，请重试。"
                                    }
                                }
                            }
                        }
                    }
                },
                exportPending = pendingExportContent != null,
                exportInFlight = exportInFlight,
                onRetryExport = {
                    if (!exportInFlight && pendingExportContent != null) {
                        exportInFlight = true
                        runCatching {
                            exportDocument.launch("ameme-export-${LocalDate.now()}.json")
                        }.onFailure {
                            exportInFlight = false
                            persistenceError = "导出入口暂不可用；本机数据没有改变，请重试。"
                        }
                    }
                },
                onClearExport = {
                    if (!exportInFlight) {
                        exportInFlight = true
                        scope.launch {
                            runCatchingCancellable {
                                ioExecutor.runSourceIo { pendingActionStore.clearExport() }
                            }.onSuccess {
                                pendingExportContent = null
                                persistenceError = "已清除未完成导出；本机事件没有改变。"
                            }.onFailure {
                                persistenceError = "未完成导出尚未清除，请稍后重试。"
                            }
                            exportInFlight = false
                        }
                    }
                },
                localRecoveryAvailable =
                    repository is LocalMemoryRepository &&
                        repositoryOverride == null &&
                        !demoMode &&
                        !localSpaceDeleted,
                localRecoveryPointStatus = localRecoveryPointStatus,
                localRecoveryInFlight = localRecoveryInFlight,
                localRecoveryNotice = localRecoveryNotice,
                onCreateLocalRecoveryPoint = {
                    val localRepository = repository as? LocalMemoryRepository
                    if (localRepository != null && !localRecoveryInFlight) {
                        localRecoveryInFlight = true
                        localRecoveryNotice = null
                        scope.launch {
                            val result = runCatchingCancellable {
                                agentRuntime?.let { runtime ->
                                    check(ioExecutor.runSourceIo(runtime::closeAndAwait)) {
                                        "Agent runtime did not stop before recovery-point creation"
                                    }
                                }
                                agentRuntime = null
                                agentRuntimeState = AgentLocalNodeRuntimeState.Stopped
                                ioExecutor.runSourceIo {
                                    localRecoveryPointManager.create(
                                        repository = localRepository,
                                        createdAt = Instant.now(),
                                    )
                                }
                            }
                            result.onSuccess { status ->
                                localRecoveryPointStatus = status
                                localRecoveryNotice =
                                    "同安装恢复点已创建，并完成完整性校验与隔离候选恢复。"
                            }.onFailure {
                                localRecoveryPointStatus = ioExecutor.runSourceIo {
                                    localRecoveryPointManager.refresh(localRepository)
                                }
                                localRecoveryNotice =
                                    "恢复点没有创建完成；旧恢复点和本机记录保持不变，请重试。"
                            }
                            persistenceError = localRecoveryNotice
                            pairingGeneration += 1
                            localRecoveryInFlight = false
                        }
                    }
                },
                onActivateLocalRecoveryPoint = {
                    val localRepository = repository as? LocalMemoryRepository
                    if (localRepository != null && !localRecoveryInFlight) {
                        localRecoveryInFlight = true
                        localRecoveryNotice = null
                        scope.launch {
                            var repositoryDetached = false
                            var activationCommitted = false
                            var receiptRecorded = false
                            var operationFailure: Throwable? = null
                            try {
                                agentRuntime?.let { runtime ->
                                    check(ioExecutor.runSourceIo(runtime::closeAndAwait)) {
                                        "Agent runtime did not stop before recovery activation"
                                    }
                                }
                                agentRuntime = null
                                agentRuntimeState = AgentLocalNodeRuntimeState.Stopped
                                val plan = ioExecutor.runSourceIo {
                                    localRecoveryPointManager.prepareActivation(
                                        repository = localRepository,
                                        confirmedAt = Instant.now(),
                                    )
                                }
                                val databaseFile = appContext.getDatabasePath(
                                    LocalMemoryRepository.DATABASE_NAME,
                                )
                                val keyProvider = AndroidKeystoreDatabaseKeyProvider(
                                    appContext,
                                    databaseFile,
                                )
                                manuallyClosedRepositories += localRepository
                                repository = null
                                repositoryDetached = true
                                experienceModeName = ExperienceMode.Loading.name
                                ioExecutor.close(localRepository)
                                val receipt = ioExecutor.runSourceIo {
                                    LocalRecoveryActivationCoordinator(keyProvider).activate(
                                        candidate = plan.candidate,
                                        liveDatabaseFile = databaseFile,
                                        authorization = plan.authorization,
                                        authoritativeWatermarks = plan.authoritativeWatermarks,
                                    )
                                }
                                activationCommitted = true
                                receiptRecorded = ioExecutor.runSourceIo {
                                    localRecoveryPointManager.recordSuccessfulActivation(receipt)
                                }
                            } catch (failure: Throwable) {
                                operationFailure = failure
                            }

                            if (repositoryDetached) {
                                val reopened = runCatchingCancellable {
                                    ioExecutor.open {
                                        LocalMemoryRepository.open(
                                            context = appContext,
                                            spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
                                        )
                                    } as LocalMemoryRepository
                                }
                                reopened.onSuccess { readyRepository ->
                                    val restored = runCatchingCancellable {
                                        ioExecutor.loadActiveEvents(readyRepository) to
                                            ioExecutor.loadDaySummary(
                                                readyRepository,
                                                LocalDate.now(),
                                            )
                                    }
                                    restored.onSuccess { (restoredEvents, restoredSummary) ->
                                        events.clear()
                                        events.addAll(restoredEvents)
                                        daySummary = restoredSummary
                                        repository = readyRepository
                                        localSpaceDeleted =
                                            ioExecutor.runSourceIo(
                                                readyRepository::isLocalSpaceDeleted,
                                            )
                                        localRecoveryPointStatus =
                                            ioExecutor.runSourceIo {
                                                localRecoveryPointManager.refresh(readyRepository)
                                            }
                                        experienceModeName = if (localSpaceDeleted) {
                                            ExperienceMode.RecoverableError.name
                                        } else {
                                            ExperienceMode.Ready.name
                                        }
                                    }.onFailure { reloadFailure ->
                                        operationFailure?.addSuppressed(reloadFailure)
                                            ?: run { operationFailure = reloadFailure }
                                        ioExecutor.close(readyRepository)
                                    }
                                }.onFailure { reopenFailure ->
                                    operationFailure?.addSuppressed(reopenFailure)
                                        ?: run { operationFailure = reopenFailure }
                                }
                            } else {
                                localRecoveryPointStatus = ioExecutor.runSourceIo {
                                    localRecoveryPointManager.refresh(localRepository)
                                }
                            }

                            localRecoveryNotice = when {
                                repository == null ->
                                    "恢复后本机加密节点未能重新打开；没有改用明文或合成数据，请重启后重试。"
                                activationCommitted && receiptRecorded ->
                                    "本机记录已恢复到恢复点，并重新完成完整性校验。"
                                activationCommitted ->
                                    "本机记录已恢复并重新校验；最近成功恢复记录未能写入，请保留当前状态并重试创建恢复点。"
                                operationFailure != null ->
                                    "恢复没有完成；已保留或回滚到切换前的本机记录，请重试。"
                                else ->
                                    "恢复没有完成；本机记录保持不变，请重试。"
                            }
                            persistenceError = localRecoveryNotice
                            if (repository == null) {
                                experienceModeName = ExperienceMode.RecoverableError.name
                            } else {
                                pairingGeneration += 1
                            }
                            operationFailure?.let { failure ->
                                Log.w(
                                    "AmemeRecovery",
                                    "activation_failed type=${failure::class.java.simpleName}",
                                )
                            }
                            Log.i(
                                "AmemeRecovery",
                                "activation_result committed=$activationCommitted " +
                                    "receipt_recorded=$receiptRecorded " +
                                    "repository_open=${repository != null} " +
                                    "point=${localRecoveryPointStatus.availability.name}",
                            )
                            localRecoveryInFlight = false
                        }
                    }
                },
                localSpaceDeletionAvailable =
                    repository is LocalMemoryRepository && repositoryOverride == null && !demoMode,
                localSpaceDeleted = localSpaceDeleted,
                localSpaceDeletionInFlight = localSpaceDeletionInFlight,
                localSpaceDeletionNeedsRetry = localSpaceDeletionNeedsRetry,
                onDeleteLocalSpace = {
                    val localRepository = repository as? LocalMemoryRepository
                    if (localRepository != null && !localSpaceDeletionInFlight) {
                        localSpaceDeletionInFlight = true
                        scope.launch {
                            val convergence = runCatchingCancellable {
                                convergeLocalSpaceDeletion(localRepository, Instant.now())
                            }
                            convergence.onSuccess { result ->
                                applyLocalSpaceDeletionConvergence(result)
                                if (result.localSpaceFrozen) {
                                    runCatchingCancellable {
                                        ioExecutor.runSourceIo {
                                            localRecoveryPointManager.clear()
                                        }
                                    }.onSuccess {
                                        localRecoveryPointStatus = LocalRecoveryPointStatus.None
                                        localRecoveryNotice = null
                                    }.onFailure {
                                        localRecoveryPointStatus =
                                            LocalRecoveryPointStatus.Unavailable
                                        localSpaceDeletionNeedsRetry = true
                                        localRecoveryNotice =
                                            "本机 Personal 空间已冻结；旧恢复点清理待重试，根删除水位会阻止其激活。"
                                        persistenceError = localRecoveryNotice
                                    }
                                }
                            }
                                .onFailure {
                                    localSpaceDeletionNeedsRetry = true
                                    persistenceError =
                                        "本机 Space 删除协调器未完成；没有宣称删除成功，请重试。"
                                }
                            localSpaceDeletionInFlight = false
                        }
                    }
                },
                onDemoModeChanged = { enabled ->
                    if (repositoryOverride == null && enabled != demoMode) {
                        experienceModeName = ExperienceMode.Loading.name
                        demoMode = enabled
                    }
                },
                calendarReadPermissionGranted = sourcePermissionRevision.let {
                    appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                        PackageManager.PERMISSION_GRANTED
                },
                voiceCaptureAvailable = canRecordVoice,
                agentAccessAuditAvailable = auditRepository != null,
                agentAccessAuditLoadFailed = agentAccessAuditLoadFailed,
                agentAccessAuditRecords = agentAccessAuditRecords,
            )
        }
        composable(
            route = Routes.Event,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { entry ->
            val eventId = entry.arguments?.getString("eventId").orEmpty()
            val event = events.firstOrNull { it.id == eventId }
            EventDetailScreen(
                event = event,
                onBack = navController::popBackStack,
                onDelete = { id -> navController.navigate(Routes.delete(id)) },
                onSaveAddendum = { words ->
                    val readyRepository = repository
                    if (readyRepository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                        false
                    } else {
                        runCatchingCancellable {
                            ioExecutor.updateEvent(readyRepository, eventId, userWords = words)
                        }.onSuccess { updated ->
                            if (updated != null) {
                                val index = events.indexOfFirst { it.id == updated.id }
                                if (index >= 0) events[index] = updated
                                daySummary = ioExecutor.loadDaySummary(readyRepository, updated.localDate)
                                persistenceError = null
                            }
                        }.onFailure {
                            persistenceError = "补充尚未保存；本机加密节点写入失败，请重试。"
                        }.getOrNull() != null
                    }
                },
                onUpdateFactStatus = { status ->
                    val readyRepository = repository
                    if (readyRepository == null) {
                        persistenceError = "本机加密节点仍在打开，请稍后重试。"
                        false
                    } else {
                        runCatchingCancellable {
                            ioExecutor.updateEvent(readyRepository, eventId, factStatus = status)
                        }.onSuccess { updated ->
                            if (updated != null) {
                                val index = events.indexOfFirst { it.id == updated.id }
                                if (index >= 0) events[index] = updated
                                daySummary = ioExecutor.loadDaySummary(readyRepository, updated.localDate)
                                persistenceError = null
                            }
                        }.onFailure {
                            persistenceError = "核验尚未保存；本机加密节点写入失败，请重试。"
                        }.getOrNull() != null
                    }
                },
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
                        val refreshRepository = requireNotNull(readyRepository)
                        scope.launch {
                            val summaryRefresh = runCatchingCancellable {
                                ioExecutor.loadDaySummary(refreshRepository, LocalDate.now())
                            }
                            summaryRefresh.onSuccess { daySummary = it }
                            val cleanupResult = runCatchingCancellable {
                                ioExecutor.retrySourceGrantCleanup(grantCleanupCoordinator)
                            }
                            cleanupResult
                                .onSuccess { cleanup ->
                                    persistenceError = when {
                                        cleanup.remaining > 0 ->
                                            "事件已删除；仍有 ${cleanup.remaining} 个系统来源授权等待释放，稍后会重试。"
                                        summaryRefresh.isFailure ->
                                            "事件已删除；日流与小结将在稍后重新载入。"
                                        else -> null
                                    }
                                }
                                .onFailure {
                                    persistenceError = when {
                                        summaryRefresh.isFailure ->
                                            "事件已删除；日流、小结与系统来源授权将在稍后重试。"
                                        else ->
                                            "事件已删除；系统来源授权清理待稍后重试。"
                                    }
                                }
                        }
                    } else {
                        persistenceError = "删除尚未持久化；本机事件仍保持可见。"
                    }
                    deleted
                },
                onDeleteComplete = { navController.popBackStack(Routes.Today, false) },
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

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(pendingIncomingShare, currentRoute) {
        // A completed user who was interrupted while a share was waiting should
        // return to the review surface even if the launch-time preference read
        // briefly selected onboarding during Activity recreation.
        if (
            pendingIncomingShare != null &&
            currentRoute == Routes.Onboarding &&
            onboardingPreferences.getBoolean("completed", false)
        ) {
            navController.navigate(Routes.Today) {
                popUpTo(Routes.Onboarding) { inclusive = true }
            }
        }
    }
    pendingIncomingShare?.let { request ->
        if (currentRoute == Routes.Today) {
            IncomingShareReviewDialog(
                request = request,
                inFlight = incomingShareInFlight,
                canConfirm = repository != null,
                onDismiss = {
                    if (!incomingShareInFlight) {
                        pendingIncomingShare = null
                        onIncomingShareConsumed()
                        scope.launch {
                            val cleared = runCatchingCancellable {
                                ioExecutor.runSourceIo { pendingActionStore.clearIncomingShare() }
                            }
                            if (cleared.isFailure) {
                                persistenceError = "已取消当前分享，但恢复快照清理失败；下次启动仍会提醒处理。"
                            }
                        }
                    }
                },
                onConfirm = {
                    if (!incomingShareInFlight) {
                        val readyRepository = repository
                        if (readyRepository == null) {
                            persistenceError = "本机加密节点仍在打开；分享内容尚未保存，请稍后重试。"
                        } else {
                            incomingShareInFlight = true
                            scope.launch {
                                val captured = runCatchingCancellable {
                                    val event = ioExecutor.captureSource(readyRepository, request)
                                    val summary = ioExecutor.loadDaySummary(readyRepository, LocalDate.now())
                                    event to summary
                                }
                                if (captured.isSuccess) {
                                    val (event, summary) = captured.getOrThrow()
                                    events.add(event)
                                    daySummary = summary
                                    pendingIncomingShare = null
                                    onIncomingShareConsumed()
                                    val cleared = runCatchingCancellable {
                                        ioExecutor.runSourceIo { pendingActionStore.clearIncomingShare() }
                                    }
                                    persistenceError = if (cleared.isSuccess) {
                                        null
                                    } else {
                                        "分享内容已保存，但恢复快照清理失败；请稍后重试清理。"
                                    }
                                } else {
                                    persistenceError = "分享内容尚未保存；本机数据没有改变，请重试。"
                                }
                                incomingShareInFlight = false
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun IncomingShareReviewDialog(
    request: SourceCaptureRequest,
    inFlight: Boolean,
    canConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = request.userWords?.trim()?.takeIf(String::isNotEmpty) ?: request.title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存分享内容？") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.take(1_024))
                Text(
                    request.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "确认后才会写入 Personal 空间；取消不会创建事件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canConfirm) {
                    Text(
                        "本机加密节点正在恢复；恢复完成后才可以保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = canConfirm && !inFlight) {
                Text(if (inFlight) "正在保存" else "保存到本机")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !inFlight) { Text("取消") }
        },
    )
}
