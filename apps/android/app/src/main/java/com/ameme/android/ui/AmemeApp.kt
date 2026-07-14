package com.ameme.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ameme.android.data.FakeMemoryRepository
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
fun AmemeApp() {
    val navController = rememberNavController()
    val repository = remember { FakeMemoryRepository() }
    val events = remember { mutableStateListOf(*repository.seedEvents().toTypedArray()) }
    var experienceModeName by rememberSaveable { mutableStateOf(ExperienceMode.Ready.name) }
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
                onCapture = { kind: CaptureKind, text: String ->
                    events.add(repository.capture(kind, text))
                },
            )
        }
        composable(Routes.Search) {
            SearchScreen(
                repository = repository,
                events = events,
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
                    events.removeAll { it.id == eventId }
                    navController.popBackStack(Routes.Today, false)
                },
            )
        }
    }
}
