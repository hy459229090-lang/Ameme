package com.ameme.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class AmemeUiSmokeTest {
    @get:Rule(order = 0)
    val resetOnboarding = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("ameme_onboarding", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingTodayAndCaptureSheet_areReachableWithoutPermissions() {
        composeRule.onNodeWithText("自动整理你的一天").assertIsDisplayed()
        composeRule.onNodeWithText("查看今天").performClick()

        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").assertIsDisplayed()
        composeRule.onNodeWithText("语音").assertIsDisplayed()
        composeRule.onNodeWithText("照片").assertIsDisplayed()
    }

    @Test
    fun searchAndProductionSettings_shareTheSameShell() {
        composeRule.onNodeWithText("查看今天").performClick()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("AI 小结"))
        composeRule.onNodeWithText("AI 小结").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").performClick()
        composeRule.onNodeWithText("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("按日期从新到旧浏览，底部可加载更早记录").assertIsDisplayed()
    }

    @Test
    fun eventDetailAndDeleteImpact_areReachable() {
        val title = "合成详情-${System.nanoTime()}"
        composeRule.onNodeWithText("查看今天").performClick()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").performClick()
        composeRule.onNodeWithText("写下一句话").performTextInput(title)
        composeRule.onNodeWithText("保存到本机").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存到本机")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithContentDescription(title, substring = true).performClick()
        composeRule.onNodeWithText("事件详情").assertIsDisplayed()
        composeRule.onNodeWithText("查看删除影响").performScrollTo().performClick()
        composeRule.onNodeWithText("删除影响与进度").assertIsDisplayed()
        composeRule.onNodeWithText("合成影响范围").assertIsDisplayed()
    }
}
