package com.ameme.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.ui.screens.SettingsScreen
import com.ameme.android.ui.theme.AmemeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLocalSpaceDeletionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun destructiveLocalSpaceActionRequiresExactTypedConfirmation() {
        var deleteRequests = 0
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    localSpaceDeletionAvailable = true,
                    onDeleteLocalSpace = { deleteRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("delete-local-space-button"))
        composeRule.onNodeWithTag("delete-local-space-button").performClick()
        composeRule.onNodeWithTag("delete-local-space-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-delete-local-space-button").assertIsNotEnabled()

        composeRule.onNodeWithTag("delete-local-space-confirmation").performTextInput("删除")
        composeRule.onNodeWithTag("confirm-delete-local-space-button").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(1, deleteRequests) }
    }

    @Test
    fun terminalDeletedStateDoesNotOfferAnotherDestructiveButton() {
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.RecoverableError,
                    onModeSelected = {},
                    onBack = {},
                    localSpaceDeletionAvailable = true,
                    localSpaceDeleted = true,
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("local-space-deleted-status"))
        composeRule.onNodeWithTag("local-space-deleted-status").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-local-space-button").assertDoesNotExist()
        composeRule.onNodeWithTag("retry-local-space-delete-button").assertDoesNotExist()
    }
}
