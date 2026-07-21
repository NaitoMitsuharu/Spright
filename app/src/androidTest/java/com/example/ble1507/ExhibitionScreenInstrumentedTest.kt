package com.example.ble1507

import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ExhibitionScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Before
    fun resetResults() {
        ComposeTestActivity.resetResults()
    }

    @Test
    fun colorDialogCanSelectBlackWithBrightnessSlider() {
        composeRule.onNodeWithText("Open color test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CURRENT  #FFFFFF").assertIsDisplayed()
        composeRule.onNodeWithText("TARGET  #FFFFFF").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("LIGHT COLOR").fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodesWithContentDescription("グラデーション時間")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithContentDescription("明るさ")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TARGET  #000000").assertIsDisplayed()
        composeRule.onNodeWithText("消灯").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        composeRule.runOnIdle {
            assertEquals(Color.BLACK, ComposeTestActivity.lastConfirmedColor)
        }
    }

    @Test
    fun midBrightnessKeepsHueAndProducesADarkChromaticTarget() {
        composeRule.onNodeWithText("Open chromatic color test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CURRENT  #FF7300").assertIsDisplayed()
        composeRule.onNodeWithText("TARGET  #FF7300").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("明るさ")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.5f)
            }
        composeRule.waitForIdle()

        val hsv = FloatArray(3)
        Color.colorToHSV(Color.parseColor(readTargetHex()), hsv)
        assertTrue("value ${hsv[2]} must be dimmed", hsv[2] < 0.7f)
        assertTrue("saturation ${hsv[1]} must stay chromatic", hsv[1] > 0.2f)
        assertTrue("hue ${hsv[0]} must be preserved", kotlin.math.abs(hsv[0] - 27.06f) <= 5f)
    }

    @Test
    fun cancelKeepsCurrentColorAndSkipsConfirmation() {
        composeRule.onNodeWithText("Open chromatic color test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("明るさ")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.25f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(Color.TRANSPARENT, ComposeTestActivity.lastConfirmedColor)
        }

        // Reopening must show the untouched current color, proving the cancelled
        // brightness edit was discarded.
        composeRule.onNodeWithText("Open chromatic color test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CURRENT  #FF7300").assertIsDisplayed()
        composeRule.onNodeWithText("TARGET  #FF7300").assertIsDisplayed()
    }

    private fun readTargetHex(): String =
        composeRule.onNode(hasText("TARGET", substring = true))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .text
            .substringAfter("TARGET  ")
            .trim()

    @Test
    fun calibrationPromptRequiresExplicitConfirmation() {
        composeRule.onNodeWithText("Open calibration test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ペンライトを机に置いてください").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle {
            assertTrue(ComposeTestActivity.calibrationConfirmed)
            assertFalse(ComposeTestActivity.calibrationDismissed)
        }
    }

    @Test
    fun calibrationCountdownShowsAccelAndGyroMagnitudeGraphs() {
        composeRule.onNodeWithText("Open calibration countdown test").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("IMU加速度・ジャイロ大きさグラフ")
            .assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("加速度").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("ジャイロ").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("m/s²", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("rad/s", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("IMUサンプル", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun glbFailureShowsComposeFallbackAndReason() {
        composeRule.onNodeWithText("Open fallback test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("3D fallback: test GLB failure").assertIsDisplayed()
    }

    @Test
    fun listeningStateShowsVoicePrompt() {
        composeRule.onNodeWithText("Open listening test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("色のイメージを話してください").assertIsDisplayed()
        composeRule.onNodeWithText("入力中…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("音声入力レベル").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.runOnIdle {
            assertTrue(ComposeTestActivity.listeningCancelled)
        }
        assertTrue(
            composeRule.onAllNodesWithText("聞き取った言葉がここに表示されます")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun voiceResultUsesPlainTranscriptWithoutBrackets() {
        composeRule.onNodeWithText("Open voice result test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("夜明け前の静かな青").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("「夜明け前の静かな青」")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithText("#536F9A").assertIsDisplayed()
        composeRule.onNodeWithText("AI").assertIsDisplayed()
    }

    @Test
    fun inferenceProgressUsesReservedColorSlotBelowTranscript() {
        composeRule.onNodeWithText("Open voice inferring test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        val transcript = composeRule.onNodeWithText("夜明け前の静かな青")
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val progress = composeRule.onNodeWithContentDescription("カラー推論中")
            .assertIsDisplayed()
            .fetchSemanticsNode()
        composeRule.onNodeWithText("色を考えています…").assertIsDisplayed()
        assertTrue(progress.boundsInRoot.center.y > transcript.boundsInRoot.center.y)
        assertTrue(composeRule.onAllNodesWithText("#536F9A").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun voiceButtonShowsProgressOnlyDuringRecognitionButStaysLockedWhileSending() {
        composeRule.onNodeWithText("Open recognizing button test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("音声認識中").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("音声認識処理中").assertIsDisplayed()

        composeRule.onNodeWithText("Open sending button test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("色を送信中").assertIsNotEnabled()
        composeRule.onNodeWithText("送信中…").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithContentDescription("音声認識処理中")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun voiceFailureShowsCauseAndOfflinePackSettings() {
        composeRule.onNodeWithText("Open voice error test")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("日本語オフライン音声パックがインストールされていません")
            .assertIsDisplayed()
        composeRule.onNodeWithText("日本語音声パック取得").performClick()
        composeRule.runOnIdle {
            assertTrue(ComposeTestActivity.speechSettingsRequested)
        }
    }

    @Test
    fun settingsPopupEditsTouchDesignerEndpointAndConnects() {
        composeRule.onNodeWithText("Open settings test").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("共通グラデーション時間").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("TouchDesigner IP")
            .assertIsDisplayed()
            .performTextReplacement("192.168.10.20")
        composeRule.onNodeWithContentDescription("TouchDesigner Port")
            .assertIsDisplayed()
            .performTextReplacement("45678")
        composeRule.onNodeWithText("接続").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("192.168.10.20:45678 接続中").assertIsDisplayed()
        composeRule.onNodeWithText("切断").assertIsDisplayed()
    }

    @Test
    fun settingsPopupResetsDisplayedAttitude() {
        composeRule.onNodeWithText("Open settings test").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("姿勢をリセット").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(ComposeTestActivity.attitudeResetRequested)
        }
    }
}
