package com.ameme.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.data.transport.PairingExperienceCandidate
import com.ameme.android.data.transport.PairingExperienceMethod
import com.ameme.android.data.transport.PairingQrScanFailure
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.ui.screens.SettingsScreen
import com.ameme.android.ui.theme.AmemeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPairingQrUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun productionQrEntryStartsSystemScannerWithoutResolvingAnUnauthenticatedCandidate() {
        var scanStarts = 0
        var legacyResolves = 0
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    pairingQrScannerAvailable = true,
                    onStartPairingQrScan = { scanStarts += 1 },
                    onResolvePairingCandidate = {
                        legacyResolves += 1
                        error("legacy resolution must not run for the production QR entry")
                    },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()
        composeRule.onNodeWithTag("pairing-method-qr").performClick()

        composeRule.runOnIdle {
            assertEquals(1, scanStarts)
            assertEquals(0, legacyResolves)
        }
        composeRule.onNodeWithTag("pairing-method-dialog").assertDoesNotExist()
    }

    @Test
    fun validScannerPayloadShowsEventOnlyAuthorizationBeforeAnyConnection() {
        val payload = "ameme-pairing-v2:bounded-test-payload"
        var resolvedPayload: String? = null
        var consumed = 0
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    pairingQrScannerAvailable = true,
                    pairingQrScanPayload = payload,
                    onPairingQrScanConsumed = { consumed += 1 },
                    onResolvePairingPayload = {
                        resolvedPayload = it
                        PairingExperienceCandidate(
                            id = "pair_ui_test",
                            deviceName = "Ameme 设备",
                            agentName = "Ameme Local Node",
                            method = PairingExperienceMethod.QrCode,
                            capabilities = listOf("写入结构化工作记录"),
                            simulated = false,
                            authorizationExpiresAt = Instant.now().plusSeconds(300),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag("pairing-authorization-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "拟授权范围：Personal 空间 · autonomous_memory · 仅写入结构化事件；" +
                "不会读取、修订、撤销或确认长期 Memory。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("允许并连接").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(payload, resolvedPayload)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun unavailableSystemScannerIsVisibleAndDoesNotCreateAConnection() {
        var consumed = 0
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    pairingQrScannerAvailable = true,
                    pairingQrScanFailure = PairingQrScanFailure.ModuleUnavailable,
                    onPairingQrScanConsumed = { consumed += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list").performScrollToNode(
            hasText("此设备的 Google Play 扫码服务不可用", substring = true),
        )
        composeRule.onNodeWithText(
            "此设备的 Google Play 扫码服务不可用",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("connected-device-card").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun manualPairingCodeUsesTheSameEventOnlyAuthorizationWithoutReadingClipboard() {
        val payload = "ameme-pairing-v2:manually-pasted-test-payload"
        var resolvedPayload: String? = null
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    pairingQrScannerAvailable = false,
                    onResolvePairingPayload = {
                        resolvedPayload = it
                        PairingExperienceCandidate(
                            id = "pair_manual_ui_test",
                            deviceName = "Ameme 设备",
                            agentName = "Ameme Local Node",
                            method = PairingExperienceMethod.QrCode,
                            capabilities = listOf("写入结构化工作记录"),
                            simulated = false,
                            authorizationExpiresAt = Instant.now().plusSeconds(300),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()
        composeRule.onNodeWithTag("pairing-method-manual").performClick()
        composeRule.onNodeWithText("Ameme 不会读取剪贴板", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("manual-pairing-code-input").performTextInput(payload)
        composeRule.onNodeWithTag("validate-manual-pairing-code-button").performClick()

        composeRule.onNodeWithTag("pairing-authorization-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "拟授权范围：Personal 空间 · autonomous_memory · 仅写入结构化事件；" +
                "不会读取、修订、撤销或确认长期 Memory。",
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(payload, resolvedPayload) }
    }

    @Test
    fun oversizedManualPairingCodeCannotBeResolvedOrConnected() {
        var resolves = 0
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    onResolvePairingPayload = {
                        resolves += 1
                        error("oversized payload must not reach the connector")
                    },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()
        composeRule.onNodeWithTag("pairing-method-manual").performClick()
        composeRule.onNodeWithTag("manual-pairing-code-input")
            .performTextInput("x".repeat(16_385))

        composeRule.onNodeWithText("配对码超过 16,384 字符", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("validate-manual-pairing-code-button")
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("connected-device-card").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, resolves) }
    }

    @Test
    fun invalidManualPairingCodeIsClearedImmediatelyAfterSubmission() {
        val payload = "ameme-pairing-v2:invalid-manual-payload"
        var resolvedPayload: String? = null
        composeRule.setContent {
            AmemeTheme {
                SettingsScreen(
                    selectedMode = ExperienceMode.Ready,
                    onModeSelected = {},
                    onBack = {},
                    pairingExperienceAvailable = true,
                    onResolvePairingPayload = {
                        resolvedPayload = it
                        error("配对码无效或已过期")
                    },
                )
            }
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("connect-device-button"))
        composeRule.onNodeWithTag("connect-device-button").performClick()
        composeRule.onNodeWithTag("pairing-method-manual").performClick()
        composeRule.onNodeWithTag("manual-pairing-code-input").performTextInput(payload)
        composeRule.onNodeWithTag("validate-manual-pairing-code-button").performClick()

        composeRule.onNodeWithTag("manual-pairing-code-input").assertTextEquals("")
        composeRule.onNodeWithText("配对码无效或已过期", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("connected-device-card").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(payload, resolvedPayload) }
    }
}
