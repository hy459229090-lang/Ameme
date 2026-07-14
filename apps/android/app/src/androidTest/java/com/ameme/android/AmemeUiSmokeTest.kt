package com.ameme.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class AmemeUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingTodayAndCaptureSheet_areReachableWithoutPermissions() {
        composeRule.onNodeWithText("自动整理你的一天").assertIsDisplayed()
        composeRule.onNodeWithText("进入合成的今天").performClick()

        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").assertIsDisplayed()
        composeRule.onNodeWithText("语音").assertIsDisplayed()
        composeRule.onNodeWithText("照片").assertIsDisplayed()
        composeRule.onNodeWithText("导入").assertIsDisplayed()
    }

    @Test
    fun searchSettingsAndPartialState_shareTheSameShell() {
        composeRule.onNodeWithText("进入合成的今天").performClick()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("部分范围"))
        composeRule.onNodeWithText("部分范围").performClick()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("部分范围").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").performClick()
        composeRule.onNodeWithText("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("从底部开始，向上滑加载更早日期").assertIsDisplayed()
    }

    @Test
    fun eventDetailAndDeleteImpact_areReachable() {
        composeRule.onNodeWithText("进入合成的今天").performClick()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").performClick()
        composeRule.onNodeWithText("写下一句话").performTextInput("用于详情测试的合成记录")
        composeRule.onNodeWithText("保存到本机").performClick()
        composeRule.onNodeWithText("用于详情测试的合成记录").performClick()
        composeRule.onNodeWithText("事件详情").assertIsDisplayed()
        composeRule.onNodeWithText("查看删除影响").performScrollTo().performClick()
        composeRule.onNodeWithText("删除影响与进度").assertIsDisplayed()
        composeRule.onNodeWithText("合成影响范围").assertIsDisplayed()
    }
}
