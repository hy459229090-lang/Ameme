package com.ameme.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.UnavailableMemoryRepository
import com.ameme.android.data.local.LocalEventDatabase
import com.ameme.android.data.local.LocalMemoryRepository
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.ui.screens.DeleteScreen
import com.ameme.android.ui.screens.EventDetailScreen
import com.ameme.android.ui.screens.OnboardingScreen
import com.ameme.android.ui.screens.SearchScreen
import com.ameme.android.ui.screens.SettingsScreen
import com.ameme.android.ui.screens.TodayScreen

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
fun AmemeApp(repositoryOverride: MemoryRepository? = null) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val repositoryResult = remember(repositoryOverride, appContext) {
        runCatching {
            repositoryOverride ?: LocalMemoryRepository.open(
                context = appContext,
                spaceId = LocalEventDatabase.DEFAULT_SPACE_ID,
            )
        }
    }
    val repository = remember(repositoryResult) {
        repositoryResult.getOrElse { UnavailableMemoryRepository() }
    }
    DisposableEffect(repository, repositoryOverride) {
        onDispose {
            if (repositoryOverride == null) repository.close()
        }
    }
    val events = remember(repository) {
        mutableStateListOf(*repository.loadActiveEvents().toTypedArray())
    }
    var persistenceError by remember {
        mutableStateOf(
            repositoryResult.exceptionOrNull()?.let {
                "本机加密节点暂不可用；没有改用明文存储，请检查设备安全状态后重试。"
            },
        )
    }
    var experienceModeName by rememberSaveable {
        mutableStateOf(
            if (repositoryResult.isSuccess) ExperienceMode.Ready.name else ExperienceMode.RecoverableError.name,
        )
    }
    val experienceMode = ExperienceMode.valueOf(experienceModeName)

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
                onCapture = { kind: CaptureKind, text: String ->
                    runCatching { repository.capture(kind, text) }
                        .onSuccess {
                            events.add(it)
                            persistenceError = null
                        }
                        .onFailure {
                            persistenceError = "记录尚未保存；本机加密节点写入失败，请重试。"
                        }
                        .isSuccess
                },
            )
        }
        composable(Routes.Search) {
            SearchScreen(
                repository = repository,
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
                    val deleted = runCatching { repository.deleteEvent(eventId) }.getOrDefault(false)
                    if (deleted) {
                        events.removeAll { it.id == eventId }
                        persistenceError = null
                        navController.popBackStack(Routes.Today, false)
                    } else {
                        persistenceError = "删除尚未持久化；本机事件仍保持可见。"
                    }
                    deleted
                },
            )
        }
    }
}
