package com.ameme.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.ameme.android.data.local.AndroidKeystorePendingActionStore
import com.ameme.android.data.local.PendingActionSnapshot
import com.ameme.android.data.transport.PairingExperienceStore
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
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
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences(PairingExperienceStore.PREFERENCES, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                runCatching {
                    AndroidKeystorePendingActionStore(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                    ).clear()
                }
                try {
                    base.evaluate()
                } finally {
                    runCatching {
                        AndroidKeystorePendingActionStore(
                            InstrumentationRegistry.getInstrumentation().targetContext,
                        ).clear()
                    }
                }
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitForCaptureEntry() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("记录一件事").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    private fun waitForReuseJourneyLauncher() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("reuse-journey-launcher")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun onboardingTodayAndCaptureSheet_areReachableWithoutPermissions() {
        composeRule.onNodeWithText("自动整理你的一天").assertIsDisplayed()
        composeRule.onNodeWithText("查看今天").performClick()

        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").assertIsDisplayed()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").assertIsDisplayed()
        composeRule.onNodeWithText("语音").assertIsDisplayed()
        composeRule.onNodeWithTag("capture-photo").assertIsDisplayed()
    }

    @Test
    fun searchAndProductionSettings_shareTheSameShell() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("AI 小结"))
        composeRule.onNodeWithText("AI 小结").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").performClick()
        composeRule.onNodeWithText("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("按日期从新到旧浏览，底部可加载更早记录").assertIsDisplayed()
        waitForReuseJourneyLauncher()
        composeRule.onNodeWithTag("reuse-journey-launcher")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("历史找回").assertIsDisplayed()
        composeRule.onNodeWithText("继续项目").assertIsDisplayed()
        composeRule.onNodeWithText("准备会面").assertIsDisplayed()
        composeRule.onNodeWithText("决定与承诺").assertIsDisplayed()
    }

    @Test
    fun settingsSourceSection_explainsRuntimePermissionAndCapabilityStates() {
        composeRule.onNodeWithText("查看今天").performClick()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("来源与权限"))

        composeRule.onNodeWithText("按次选择，不申请整库权限", substring = true).assertIsDisplayed()
        assertTrue(
            "Calendar permission state is not exposed",
            composeRule.onAllNodesWithText("只读权限", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Voice capability state is not exposed",
            composeRule.onAllNodesWithText("系统录音入口", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("设备未提供系统录音入口", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun settingsAgentAudit_explainsContentFreeRetentionAndUsesProductionProjection() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("agent-access-audit-card"))

        composeRule.onNodeWithTag("agent-access-audit-card").assertIsDisplayed()
        composeRule.onNodeWithText("安全审计保留 180 天", substring = true).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("尚无 Agent 访问记录。").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("autonomous_memory", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        assertTrue(
            "Agent audit must expose a real empty or persisted production projection",
            composeRule.onAllNodesWithText("尚无 Agent 访问记录。").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("autonomous_memory", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
        )
    }

    @Test
    fun incomingTextShare_requiresConfirmationBeforeLocalSave() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        val sharedText = "外部分享需要先确认-${System.nanoTime()}"
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.receiveIncomingIntent(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, sharedText)
                },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("保存分享内容？").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Incoming share was saved before user confirmation",
            composeRule.onAllNodesWithContentDescription(sharedText, substring = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithText("保存到本机").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存分享内容？").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("today-list")
            .performScrollToNode(hasContentDescription(sharedText, substring = true))
        composeRule.onNodeWithContentDescription(sharedText, substring = true).assertIsDisplayed()
    }

    @Test
    fun pendingShare_recoversAfterActivityRecreationBeforeLocalSave() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        val sharedText = "进程恢复分享-${System.nanoTime()}"
        val request = SourceCaptureRequest(
            sourceKind = SourceKind.SharedText,
            title = sharedText,
            detail = "通过系统分享入口保存的用户文字。",
            factStatus = FactStatus.UserAsserted,
            localDate = LocalDate.now(),
            time = LocalTime.now().withSecond(0).withNano(0),
            userWords = sharedText,
            locatorPermissionState = LocatorPermissionState.NoLocator,
            sourceInstanceKey = "ui-recovery-$sharedText",
            eventType = EventType.Experience,
            evidenceState = EvidenceState.UserAsserted,
            sensitivity = Sensitivity.Personal,
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            AndroidKeystorePendingActionStore(activity).save(PendingActionSnapshot(incomingShare = request))
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertTrue(
                "Onboarding completion did not survive recreation",
                activity.getSharedPreferences("ameme_onboarding", android.content.Context.MODE_PRIVATE)
                    .getBoolean("completed", false),
            )
            assertTrue(
                "Pending share snapshot did not survive recreation",
                AndroidKeystorePendingActionStore(activity).load()?.incomingShare?.title == sharedText,
            )
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存分享内容？").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Recovered share was saved before confirmation",
            composeRule.onAllNodesWithContentDescription(sharedText, substring = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("保存到本机").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("保存到本机").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存分享内容？").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("today-list")
            .performScrollToNode(hasContentDescription(sharedText, substring = true))
        composeRule.onNodeWithContentDescription(sharedText, substring = true).assertIsDisplayed()
    }

    @Test
    fun pendingExport_canBeClearedWithoutTouchingLocalEvents() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.activityRule.scenario.onActivity { activity ->
            AndroidKeystorePendingActionStore(activity).save(
                PendingActionSnapshot(exportContent = "{\"synthetic\":\"private-export\"}"),
            )
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("结构化导出"))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("上次导出尚未保存", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("clear-export-button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("上次导出尚未保存", substring = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.activityRule.scenario.onActivity { activity ->
            assertTrue(
                "Clearing export snapshot must not leave encrypted pending content",
                AndroidKeystorePendingActionStore(activity).load()?.exportContent == null,
            )
        }
    }

    @Test
    fun eventDetailAndDeleteImpact_areReachable() {
        val title = "合成详情-${System.nanoTime()}"
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
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
        composeRule.onNodeWithText("影响范围").assertIsDisplayed()
        composeRule.onNodeWithText("确认删除")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("返回今天").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("重试删除").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("返回今天").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("搜索历史记录").performClick()
        composeRule.onNodeWithTag("search-query").performTextInput(title)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前条件没有结果").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun demoData_roundTripRestoresRealLocalEvents() {
        val realTitle = "真实本机-${System.nanoTime()}"
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("记录一件事").performClick()
        composeRule.onNodeWithText("文字").performClick()
        composeRule.onNodeWithText("写下一句话").performTextInput(realTitle)
        composeRule.onNodeWithText("保存到本机").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存到本机").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("load-demo-button"))
        composeRule.onNodeWithTag("load-demo-button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前正在查看演示数据").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("当前正在查看演示数据").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithContentDescription("演示数据。固定示例仅用于体验，不会写入真实本机记录。", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("exit-demo-button"))
        composeRule.onNodeWithTag("exit-demo-button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("使用演示数据").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithTag("today-list")
            .performScrollToNode(hasContentDescription(realTitle, substring = true))
        composeRule.onNodeWithContentDescription(realTitle, substring = true).assertIsDisplayed()
    }

    @Test
    fun visualEvidence_demoTodayUsesCurrentPlatformDesign() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("load-demo-button"))
        composeRule.onNodeWithTag("load-demo-button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前正在查看演示数据").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithContentDescription(
            "演示数据。固定示例仅用于体验，不会写入真实本机记录。",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索历史记录").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("打开设置").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("记录一件事").assertIsDisplayed()
        composeRule.waitForIdle()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fontScale = instrumentation.targetContext.resources.configuration.fontScale
        val scaleLabel = (fontScale * 100).roundToInt()
        val resolver = instrumentation.targetContext.contentResolver
        val displayName = "android-today-demo-font$scaleLabel.png"
        val relativePath = "${Environment.DIRECTORY_PICTURES}/AmemeTestEvidence/"
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
        )
        val screenshot = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        ) { "MediaStore could not create the Today screenshot entry" }
        try {
            checkNotNull(resolver.openOutputStream(screenshot)).use { output ->
                assertTrue(
                    "UIAutomation could not encode the current Today screenshot",
                    instrumentation.uiAutomation.takeScreenshot()
                        .compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
            resolver.update(
                screenshot,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(screenshot, null, null)
            throw error
        }
    }

    @Test
    fun agentConnection_offersThreeOrdinaryUserEntrances() {
        composeRule.onNodeWithText("查看今天").performClick()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()

        composeRule.onNodeWithText("自动发现电脑").assertIsDisplayed()
        composeRule.onNodeWithText("扫描二维码").assertIsDisplayed()
        composeRule.onNodeWithText("账户设备").assertIsDisplayed()
    }

    @Test
    fun receiveDeviceConnection_generatesAReadableShortLivedQrCode() {
        composeRule.onNodeWithText("查看今天").performClick()
        waitForCaptureEntry()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("create-pairing-qr-button"))
        composeRule.onNodeWithTag("create-pairing-qr-button").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("设备配对二维码").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("请让另一台设备在 5 分钟内扫描。扫描后，对方仍需确认授权。")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("设备配对二维码，五分钟内有效")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("copy-pairing-code-button").assertIsEnabled()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithTag("revoke-pairing-button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("未配对").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun agentConnection_requiresAuthorizationAndCanBeDisconnected() {
        composeRule.onNodeWithText("查看今天").performClick()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()
        composeRule.onNodeWithTag("pairing-method-lan").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("允许 Agent 连接？").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            "拟授权范围：Personal 空间 · autonomous_memory · " +
                "获准结构化事件读取、event/revision 写入与 10 分钟撤销 · 30 天",
        )
            .assertIsDisplayed()
        composeRule.onNodeWithText("体验模式 · 不建立真实网络连接").assertIsDisplayed()
        composeRule.onNodeWithText("允许并连接").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("连接成功").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("Ameme Desktop").assertIsDisplayed()
        composeRule.onNodeWithText("Codex · 体验连接").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("disconnect-device-button"))
        composeRule.onNodeWithTag("disconnect-device-button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("connected-device-card")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").assertIsDisplayed()
    }
}
