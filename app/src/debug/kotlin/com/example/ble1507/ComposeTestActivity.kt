package com.example.ble1507

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Empty host used only by debug Compose instrumentation tests. */
class ComposeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_PENLIGHT_PREVIEW, false)) {
            val previewColor = intent.getIntExtra(EXTRA_PREVIEW_COLOR, android.graphics.Color.WHITE)
            val staticRoll = intent.getIntExtra(EXTRA_PREVIEW_STATIC_ROLL, Int.MIN_VALUE)
            val staticYaw = intent.getIntExtra(EXTRA_PREVIEW_STATIC_YAW, Int.MIN_VALUE)
            val staticPose = staticRoll != Int.MIN_VALUE || staticYaw != Int.MIN_VALUE
            setContent {
                val angle = rememberInfiniteTransition(label = "previewMotion").animateFloat(
                    initialValue = -22f,
                    targetValue = 22f,
                    animationSpec = infiniteRepeatable(tween(4_000), RepeatMode.Reverse),
                    label = "previewAngle",
                ).value
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF050914)),
                ) {
                    PenlightHero(
                        color = previewColor,
                        attitude = AttitudeEstimate(
                            rollDeg = if (staticPose) staticRoll.coerceAtLeast(0).toFloat() else angle * 0.28f,
                            pitchDeg = if (staticPose) 0f else angle * 0.42f,
                            yawDeg = if (staticPose) staticYaw.coerceAtLeast(0).toFloat() else angle,
                        ),
                        attitudeEnabled = true,
                        onEmitterTap = {},
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.98f)
                            .fillMaxHeight(0.58f),
                    )
                }
            }
            return
        }
        setContent {
            var dialog by remember { mutableStateOf<TestDialog?>(null) }
            var touchDesignerHost by remember { mutableStateOf("192.168.1.100") }
            var touchDesignerPort by remember { mutableStateOf("12345") }
            var touchDesignerState by remember { mutableStateOf(TouchDesignerConnectionState()) }
            Column {
                Button(onClick = { dialog = TestDialog.Color }) {
                    Text("Open color test")
                }
                Button(onClick = { dialog = TestDialog.ColorChromatic }) {
                    Text("Open chromatic color test")
                }
                Button(onClick = { dialog = TestDialog.Calibration }) {
                    Text("Open calibration test")
                }
                Button(onClick = { dialog = TestDialog.CalibrationCountdown }) {
                    Text("Open calibration countdown test")
                }
                Button(onClick = { dialog = TestDialog.Fallback }) {
                    Text("Open fallback test")
                }
                Button(onClick = { dialog = TestDialog.Listening }) {
                    Text("Open listening test")
                }
                Button(onClick = { dialog = TestDialog.VoiceError }) {
                    Text("Open voice error test")
                }
                Button(onClick = { dialog = TestDialog.VoiceResult }) {
                    Text("Open voice result test")
                }
                Button(onClick = { dialog = TestDialog.VoiceInferring }) {
                    Text("Open voice inferring test")
                }
                Button(onClick = { dialog = TestDialog.VoiceRecognizingButton }) {
                    Text("Open recognizing button test")
                }
                Button(onClick = { dialog = TestDialog.VoiceSendingButton }) {
                    Text("Open sending button test")
                }
                Button(onClick = { dialog = TestDialog.Settings }) {
                    Text("Open settings test")
                }
            }
            when (dialog) {
                TestDialog.Color -> ColorPickerDialog(
                    currentColor = android.graphics.Color.WHITE,
                    gradientEnabled = true,
                    onDismiss = { dialog = null },
                    onConfirm = { color ->
                        lastConfirmedColor = color
                        dialog = null
                    },
                )

                // Chromatic start color so brightness-slider behaviour can be
                // observed on a saturated hue (the white host stays at S=0).
                TestDialog.ColorChromatic -> ColorPickerDialog(
                    currentColor = android.graphics.Color.rgb(255, 115, 0),
                    gradientEnabled = true,
                    onDismiss = { dialog = null },
                    onConfirm = { color ->
                        lastConfirmedColor = color
                        dialog = null
                    },
                )

                TestDialog.Calibration -> CalibrationPrompt(
                    onConfirm = {
                        calibrationConfirmed = true
                        dialog = null
                    },
                    onDismiss = {
                        calibrationDismissed = true
                        dialog = null
                    },
                )

                TestDialog.CalibrationCountdown -> CalibrationCountdownDialog(
                    countdown = 3,
                    message = "",
                    samples = listOf(
                        ImuMagnitudeSample(0L, 9.80f, 0.010f),
                        ImuMagnitudeSample(1_000L, 9.92f, 0.025f),
                        ImuMagnitudeSample(2_000L, 9.75f, 0.018f),
                    ),
                )

                TestDialog.Fallback -> PenlightFallback(
                    color = android.graphics.Color.WHITE,
                    error = "test GLB failure",
                )

                TestDialog.Listening -> ListeningDialog(
                    rms = 8f,
                    partialText = "夕焼けより、少し深い赤紫",
                    onCancel = {
                        listeningCancelled = true
                        dialog = null
                    },
                )

                TestDialog.VoiceError -> VoiceResult(
                    voiceState = VoiceUiState.Idle,
                    voiceText = "voice: --",
                    sourceText = "color resolver: --",
                    errorMessage = "日本語オフライン音声パックがインストールされていません",
                    visitorMessage = null,
                    speechPackActionText = "日本語音声パック取得",
                    speechPackActionEnabled = true,
                    onOpenSpeechSettings = { speechSettingsRequested = true },
                )

                TestDialog.VoiceResult -> VoiceResult(
                    voiceState = VoiceUiState.Idle,
                    voiceText = "voice: 夜明け前の静かな青",
                    sourceText = "color resolver: #536F9A LLM / speech:412ms infer:289ms ble:81ms total:782ms",
                    errorMessage = null,
                    visitorMessage = null,
                    speechPackActionText = "日本語音声パック準備済み",
                    speechPackActionEnabled = false,
                    onOpenSpeechSettings = {},
                )

                TestDialog.VoiceInferring -> VoiceResult(
                    voiceState = VoiceUiState.Inferring,
                    voiceText = "voice: 夜明け前の静かな青",
                    sourceText = "color resolver: ...",
                    errorMessage = null,
                    visitorMessage = null,
                    speechPackActionText = "日本語音声パック準備済み",
                    speechPackActionEnabled = false,
                    onOpenSpeechSettings = {},
                )

                TestDialog.VoiceRecognizingButton -> VoiceButton(
                    state = VoiceUiState.Recognizing,
                    enabled = true,
                    onClick = {},
                )

                TestDialog.VoiceSendingButton -> VoiceButton(
                    state = VoiceUiState.Sending,
                    enabled = true,
                    onClick = {},
                )

                TestDialog.Settings -> SettingsPopup(
                    durationSeconds = 1.25f,
                    onDurationChanged = {},
                    poseResetEnabled = true,
                    onResetDisplayedAttitude = {
                        attitudeResetRequested = true
                        dialog = null
                    },
                    touchDesignerHost = touchDesignerHost,
                    touchDesignerPort = touchDesignerPort,
                    touchDesignerState = touchDesignerState,
                    onTouchDesignerHostChanged = { touchDesignerHost = it },
                    onTouchDesignerPortChanged = { touchDesignerPort = it },
                    onTouchDesignerConnectionTest = {
                        touchDesignerState = TouchDesignerConnectionState(
                            TouchDesignerConnectionStatus.Connected,
                            "接続",
                        )
                    },
                    onDismiss = { dialog = null },
                )

                null -> Unit
            }
        }
    }

    private enum class TestDialog {
        Color,
        ColorChromatic,
        Calibration,
        CalibrationCountdown,
        Fallback,
        Listening,
        VoiceError,
        VoiceResult,
        VoiceInferring,
        VoiceRecognizingButton,
        VoiceSendingButton,
        Settings,
    }

    companion object {
        const val EXTRA_PENLIGHT_PREVIEW = "penlight_preview"
        const val EXTRA_PREVIEW_COLOR = "preview_color"
        const val EXTRA_PREVIEW_STATIC_ROLL = "preview_static_roll"
        const val EXTRA_PREVIEW_STATIC_YAW = "preview_static_yaw"
        var lastConfirmedColor: Int = android.graphics.Color.TRANSPARENT
        var calibrationConfirmed: Boolean = false
        var calibrationDismissed: Boolean = false
        var speechSettingsRequested: Boolean = false
        var listeningCancelled: Boolean = false
        var attitudeResetRequested: Boolean = false

        fun resetResults() {
            lastConfirmedColor = android.graphics.Color.TRANSPARENT
            calibrationConfirmed = false
            calibrationDismissed = false
            speechSettingsRequested = false
            listeningCancelled = false
            attitudeResetRequested = false
        }
    }
}
