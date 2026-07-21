package com.example.ble1507

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private data class VoiceColorMetadata(
    val resolution: ColorResolution,
    val speechEndedAtMs: Long,
    val inferenceCompletedAtMs: Long,
)

private data class ColorSendOperation(
    val id: Long,
    val startColor: Int,
    val targetColor: Int,
    val gradientDurationMs: Long,
    val startedAtMs: Long,
    val voice: VoiceColorMetadata?,
    var nextStep: Int = 1,
) {
    val gradient: Boolean
        get() = gradientDurationMs > 0L
    val stepCount: Int
        get() = colorGradientStepCount(gradientDurationMs)
    val stepIntervalMs: Long
        get() = if (gradient) (gradientDurationMs / stepCount).coerceAtLeast(1L) else 0L
}

private data class PendingColorWrite(
    val operationId: Long,
    val color: Int,
    val step: Int,
)

class MainActivity : ComponentActivity(), Ble1507Client.Listener {
    private lateinit var bleClient: Ble1507Client
    private lateinit var colorInterpreter: QwenColorInterpreter
    private lateinit var imuEstimator: ImuNativeAttitudeEstimator
    private lateinit var attitudeTipSpeedEstimator: AttitudeTipSpeedEstimator
    private lateinit var touchDesignerClient: TouchDesignerTcpClient
    private var modelDirectoryOnly = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerGeneration = 0L
    private var speechRecognizerOnDevice = false
    private var offlineModelDownloadRecognizer: SpeechRecognizer? = null
    private var offlineSupportCheckRecognizer: SpeechRecognizer? = null
    private var isVoiceListening = false
    private var offlineRetryAttempted = false
    private var pendingVoiceInputStart = false
    private var offlineLanguagePackMissing = false
    private var offlineSpeechPackState by mutableStateOf(SPEECH_PACK_STATE_NONE)
    private var offlineSpeechPackRequestInProgress by mutableStateOf(false)
    private var currentRecognitionOffline = false
    private var lastPartialText = ""
    private var segmentedRecognitionText = ""
    private var segmentedResultApplied = false
    private var speechEndedAtMs: Long? = null
    private var voiceTimeoutJob: Job? = null
    private var voiceIdleRecoveryJob: Job? = null
    private var visitorMessageJob: Job? = null
    private var colorOperationTimeoutJob: Job? = null
    private var bleReconnectJob: Job? = null
    private var stationaryCalibrationJob: Job? = null
    private var voiceInferenceGeneration = 0L
    private var bleReconnectAttempt = 0
    private var shouldMaintainBleConnection = false
    private var shuttingDown = false

    private var connectionState by mutableStateOf("disconnected")
    private var bondState by mutableStateOf("unknown")
    private var mtuState by mutableStateOf("--")
    private var statusText by mutableStateOf("ready")
    private var imuText by mutableStateOf("roll: --\npitch: --\nyaw: --\naccel: --")
    private var imuLogPath by mutableStateOf("log: --")
    private var selectedColor by mutableIntStateOf(AndroidColor.WHITE)
    private var gradientDurationSeconds by mutableFloatStateOf(0f)
    private var touchDesignerHost by mutableStateOf(DEFAULT_TOUCHDESIGNER_HOST)
    private var touchDesignerPort by mutableStateOf(DEFAULT_TOUCHDESIGNER_PORT.toString())
    private var touchDesignerState by mutableStateOf(TouchDesignerConnectionState())
    private var imuRunning by mutableStateOf(false)
    private var imuCalibrating by mutableStateOf(false)
    private var shouldMaintainImuStream = false
    @Volatile
    private var imuEstimatorStarted = false
    private var voiceText by mutableStateOf("voice: --")
    private var voiceColorSource by mutableStateOf("color resolver: --")
    private var voiceErrorMessage by mutableStateOf<String?>(null)
    private var visitorMessage by mutableStateOf<String?>(null)
    private var modelState by mutableStateOf("Qwen model: checking")
    private var modelReady by mutableStateOf(false)
    private var voiceUiState by mutableStateOf(VoiceUiState.Idle)
    private var voiceRms by mutableFloatStateOf(0f)
    private var voicePartialText by mutableStateOf("")
    private var syncUiState by mutableStateOf(SyncUiState.Idle)
    private var imuPoseHealthy by mutableStateOf(false)
    private var calibrationCountdown by mutableIntStateOf(5)
    private var calibrationMessage by mutableStateOf("")
    private var calibrationGraphSamples by mutableStateOf<List<ImuMagnitudeSample>>(emptyList())
    private var latestAttitude by mutableStateOf(AttitudeEstimate(0f, 0f, 0f))
    private var sceneAttitudeReference by mutableStateOf(AttitudeEstimate(0f, 0f, 0f))
    @Volatile
    private var latestRawAttitude = AttitudeEstimate(0f, 0f, 0f)
    @Volatile
    private var latestTouchDesignerColor = AndroidColor.WHITE
    private val messageLog = mutableStateListOf<String>()
    private var showDiagBondRecovery by mutableStateOf(false)
    private var showBondHint by mutableStateOf(false)
    private var imuLogWriter: BufferedWriter? = null
    private var lastImuLogFlushAtMs = 0L
    private var lastImuUiUpdateAtMs = 0L
    private var lastImuPoseUiUpdateAtMs = 0L
    private var imuRateWindowStartedAtMs = 0L
    private var imuSamplesInRateWindow = 0
    private var imuSampleRateHz = 0f
    @Volatile
    private var lastValidImuEstimateAtMs = 0L
    private val calibrationSamples = mutableListOf<ImuPacket>()
    private val calibrationMagnitudeSamples = mutableListOf<ImuMagnitudeSample>()
    private var collectingStationaryCalibration = false
    @Volatile
    private var stationaryCalibrationStartedAtMs = 0L
    @Volatile
    private var lastStationaryCalibrationSampleAtMs = 0L
    private var lastCalibrationGraphUpdateAtMs = 0L
    private var forceImmediateBleReconnect = false
    private var nextColorOperationId = 1L
    private var activeColorOperation: ColorSendOperation? = null
    private val pendingColorWrites = mutableMapOf<Long, PendingColorWrite>()
    private val imuExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spright-imu").apply { priority = Thread.NORM_PRIORITY }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        modelState = QwenModelStore.displayState(this)
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (pendingVoiceInputStart) {
            pendingVoiceInputStart = false
            if (audioGranted) {
                statusText = "Starting voice input..."
                startVoiceInput()
            } else {
                statusText = "Microphone permission is required for voice input"
            }
        }
        if (hasRequiredPermissions(includeAudio = false) && shouldMaintainBleConnection) {
            scheduleBleReconnect(immediate = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QwenModelStore.ensureExternalModelDirectory(this)
        if (intent.getBooleanExtra(EXTRA_PREPARE_MODEL_DIRECTORY, false)) {
            modelDirectoryOnly = true
            finish()
            return
        }
        bleClient = Ble1507Client(this, this)
        colorInterpreter = QwenColorInterpreter(this)
        imuEstimator = ImuNativeAttitudeEstimator()
        attitudeTipSpeedEstimator = AttitudeTipSpeedEstimator()
        val exhibitionPreferences = getSharedPreferences(EXHIBITION_PREFS, MODE_PRIVATE)
        touchDesignerHost = exhibitionPreferences
            .getString(PREF_TOUCHDESIGNER_HOST, DEFAULT_TOUCHDESIGNER_HOST)
            ?: DEFAULT_TOUCHDESIGNER_HOST
        touchDesignerPort = exhibitionPreferences
            .getInt(PREF_TOUCHDESIGNER_PORT, DEFAULT_TOUCHDESIGNER_PORT)
            .toString()
        touchDesignerClient = TouchDesignerTcpClient { state ->
            runOnUiThread { touchDesignerState = state }
        }
        latestTouchDesignerColor = selectedColor
        touchDesignerClient.updateColor(selectedColor)
        bleClient.register()
        shouldMaintainBleConnection = exhibitionPreferences
            .getBoolean(PREF_MAINTAIN_BLE, false)
        offlineSpeechPackState = exhibitionPreferences
            .getString(PREF_OFFLINE_SPEECH_PACK_STATE, SPEECH_PACK_STATE_NONE)
            ?: SPEECH_PACK_STATE_NONE
        refreshOfflineSpeechPackState()
        modelState = QwenModelStore.displayState(this)
        // Pre-load the Qwen model in the background so the first voice inference is fast.
        lifecycleScope.launch {
            modelState = "Model: warming up..."
            val ready = withContext(Dispatchers.IO) { colorInterpreter.warmup() }
            modelReady = ready
            modelState = if (ready) {
                "${QwenModelStore.displayState(this@MainActivity)} / ready"
            } else {
                "${QwenModelStore.displayState(this@MainActivity)} / unavailable"
            }
        }
        setContent {
            SprightExhibitionScreen(
                connectionState = connectionState,
                statusText = statusText,
                modelState = modelState,
                modelReady = modelReady,
                selectedColor = selectedColor,
                gradientDurationSeconds = gradientDurationSeconds,
                touchDesignerHost = touchDesignerHost,
                touchDesignerPort = touchDesignerPort,
                touchDesignerState = touchDesignerState,
                voiceState = voiceUiState,
                voiceText = voiceText,
                voiceColorSource = voiceColorSource,
                voiceErrorMessage = voiceErrorMessage,
                visitorMessage = visitorMessage,
                speechPackActionText = speechPackActionText(),
                speechPackActionEnabled = speechPackActionEnabled(),
                voiceRms = voiceRms,
                voicePartialText = voicePartialText,
                syncState = syncUiState,
                imuPoseHealthy = imuPoseHealthy,
                calibrationCountdown = calibrationCountdown,
                calibrationMessage = calibrationMessage,
                calibrationGraphSamples = calibrationGraphSamples,
                imuRunning = imuRunning,
                attitude = latestAttitude,
                attitudeReference = sceneAttitudeReference,
                imuText = imuText,
                imuLogPath = imuLogPath,
                onBleToggle = ::toggleBleConnection,
                onSync = ::requestImuSync,
                onConfirmCalibration = ::beginStationaryCalibration,
                onRetryCalibration = ::beginStationaryCalibration,
                onCancelCalibration = ::cancelStationaryCalibration,
                onGradientDurationChanged = {
                    gradientDurationSeconds = it.coerceIn(0f, MAX_EXHIBITION_GRADIENT_SECONDS)
                },
                onTouchDesignerHostChanged = ::updateTouchDesignerHost,
                onTouchDesignerPortChanged = ::updateTouchDesignerPort,
                onTouchDesignerConnectionTest = ::runTouchDesignerConnectionTest,
                onTouchDesignerDisconnect = ::disconnectTouchDesigner,
                onApplyColor = { color -> sendColor(color, gradientDurationSeconds) },
                onVoice = ::startVoiceInput,
                onCancelVoice = ::cancelVoiceInput,
                onOpenSpeechSettings = ::openOfflineSpeechSettings,
                onResetDisplayedAttitude = ::resetDisplayedAttitude,
                onStartScreenPinning = ::startExhibitionScreenPinning,
            )
        }
        configureExhibitionWindow()
        requestRequiredPermissions()
        if (shouldMaintainBleConnection && hasRequiredPermissions(includeAudio = false)) {
            scheduleBleReconnect(immediate = true)
        }
        lifecycleScope.launch {
            while (true) {
                delay(IMU_HEALTH_CHECK_INTERVAL_MS)
                val stale = SystemClock.elapsedRealtime() - lastValidImuEstimateAtMs > IMU_HEALTH_TIMEOUT_MS
                if (imuPoseHealthy && stale) {
                    imuPoseHealthy = false
                }
            }
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        if (modelDirectoryOnly) {
            super.onDestroy()
            return
        }
        cancelSpeechTimeout()
        voiceIdleRecoveryJob?.cancel()
        visitorMessageJob?.cancel()
        colorOperationTimeoutJob?.cancel()
        bleReconnectJob?.cancel()
        stationaryCalibrationJob?.cancel()
        releaseSpeechRecognizer()
        offlineModelDownloadRecognizer?.destroy()
        offlineModelDownloadRecognizer = null
        offlineSupportCheckRecognizer?.destroy()
        offlineSupportCheckRecognizer = null
        touchDesignerClient.close()
        bleClient.unregister()
        runCatching {
            imuExecutor.submit {
                imuEstimator.stop()
                imuEstimatorStarted = false
                closeImuLog()
            }.get(2, TimeUnit.SECONDS)
        }
        imuExecutor.shutdown()
        runCatching { imuExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        modelState = QwenModelStore.displayState(this)
        configureExhibitionWindow()
        if (shouldMaintainBleConnection && connectionState == "disconnected") {
            scheduleBleReconnect(immediate = true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            modelState = QwenModelStore.displayState(this)
            hideSystemBars()
            window.decorView.postDelayed(::hideSystemBars, SYSTEM_UI_REHIDE_DELAY_MS)
        }
    }

    private fun updateTouchDesignerHost(host: String) {
        touchDesignerHost = host
        getSharedPreferences(EXHIBITION_PREFS, MODE_PRIVATE).edit {
            putString(PREF_TOUCHDESIGNER_HOST, host)
        }
    }

    private fun updateTouchDesignerPort(port: String) {
        touchDesignerPort = port.filter(Char::isDigit).take(5)
        touchDesignerPort.toIntOrNull()?.let { value ->
            if (value in 1..65535) {
                getSharedPreferences(EXHIBITION_PREFS, MODE_PRIVATE).edit {
                    putInt(PREF_TOUCHDESIGNER_PORT, value)
                }
            }
        }
    }

    private fun runTouchDesignerConnectionTest() {
        if (touchDesignerState.status == TouchDesignerConnectionStatus.Connecting) {
            return
        }
        val port = touchDesignerPort.toIntOrNull() ?: 0
        touchDesignerClient.connect(touchDesignerHost, port)
    }

    private fun disconnectTouchDesigner() {
        if (touchDesignerState.status != TouchDesignerConnectionStatus.Connected) {
            return
        }
        touchDesignerClient.disconnect()
    }

    private fun connect() {
        shouldMaintainBleConnection = true
        persistBleMaintenancePreference()
        if (!hasRequiredPermissions(includeAudio = false)) {
            requestRequiredPermissions()
            return
        }
        bleReconnectJob?.cancel()
        bleClient.scanPairAndConnect()
    }

    private fun reconnectBle() {
        if (!shouldMaintainBleConnection || shuttingDown) return
        if (!hasRequiredPermissions(includeAudio = false)) {
            statusText = "BLE自動再接続にはBluetooth権限が必要です"
            return
        }
        bleClient.reconnectLastDeviceOrScan()
    }

    private fun toggleBleConnection() {
        if (connectionState == "ready" || connectionState in setOf("connected", "connecting", "scanning")) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun disconnect() {
        shouldMaintainBleConnection = false
        shouldMaintainImuStream = false
        persistBleMaintenancePreference()
        bleReconnectJob?.cancel()
        stationaryCalibrationJob?.cancel()
        bleReconnectAttempt = 0
        bleClient.disconnect()
        imuRunning = false
        imuCalibrating = false
        syncUiState = SyncUiState.Idle
        collectingStationaryCalibration = false
        calibrationGraphSamples = emptyList()
        activeColorOperation = null
        pendingColorWrites.clear()
        executeImu {
            imuEstimator.stop()
            imuEstimatorStarted = false
            closeImuLog()
        }
    }

    private fun sendLedCommand() {
        sendColor(selectedColor, 0f)
    }

    private fun synchronizeDisplayedColorAfterConnection() {
        if (!bleClient.isReady()) return
        if (activeColorOperation != null) {
            android.util.Log.w("SprightColor", "Skipped ready color sync because another color operation is active")
            return
        }
        val colorAtConnection = selectedColor
        android.util.Log.i(
            "SprightColor",
            "Synchronizing BLE1507 to displayed color ${colorAtConnection.hexCode} after GATT ready",
        )
        sendColor(colorAtConnection, 0f)
    }

    private fun sendColor(targetColor: Int, gradientDurationSeconds: Float) {
        sendColor(targetColor, gradientDurationSeconds, voice = null)
    }

    private fun sendColor(targetColor: Int, gradientDurationSeconds: Float, voice: VoiceColorMetadata?) {
        if (!bleClient.isReady()) {
            statusText = "BLE未接続のため色を送信できません"
            if (voice != null) {
                reportVoiceError("BLE未接続のため色を送信できません", keepRecognizedText = true)
            } else {
                showVisitorMessage(statusText)
            }
            return
        }
        if (activeColorOperation != null) {
            statusText = "前の色変更を送信中です"
            if (voice != null) {
                reportVoiceError("前の色変更を送信中です", keepRecognizedText = true)
            } else {
                showVisitorMessage(statusText)
            }
            return
        }
        val operation = ColorSendOperation(
            id = nextColorOperationId++,
            startColor = selectedColor,
            targetColor = targetColor,
            gradientDurationMs = (gradientDurationSeconds.coerceAtLeast(0f) * 1_000f).roundToInt().toLong(),
            startedAtMs = SystemClock.elapsedRealtime(),
            voice = voice,
        )
        activeColorOperation = operation
        armColorOperationTimeout(operation)
        if (voice != null) voiceUiState = VoiceUiState.Sending
        statusText = if (operation.gradient) {
            String.format(Locale.US, "%.1f秒グラデーションを送信中", operation.gradientDurationMs / 1_000f)
        } else {
            "色を送信中"
        }
        scheduleNextColorStep(operation)
    }

    private fun scheduleNextColorStep(operation: ColorSendOperation) {
        if (activeColorOperation?.id != operation.id) return
        val stepCount = operation.stepCount
        if (operation.nextStep > stepCount) {
            finishColorOperation(operation)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (operation.gradient) {
            val elapsedStep = (now - operation.startedAtMs).coerceAtLeast(0L)
                .div(operation.stepIntervalMs)
                .toInt()
                .coerceIn(0, stepCount)
            operation.nextStep = maxOf(operation.nextStep, elapsedStep)
                .coerceIn(1, stepCount)
            val dueAt = operation.startedAtMs + operation.nextStep * operation.stepIntervalMs
            if (now < dueAt) {
                lifecycleScope.launch {
                    delay(dueAt - now)
                    dispatchColorStep(operation)
                }
                return
            }
        }
        dispatchColorStep(operation)
    }

    private fun dispatchColorStep(operation: ColorSendOperation) {
        if (activeColorOperation?.id != operation.id) return
        val stepCount = operation.stepCount
        val step = operation.nextStep.coerceIn(1, stepCount)
        val color = if (operation.gradient) {
            interpolateHsvShortest(operation.startColor, operation.targetColor, step.toFloat() / stepCount)
        } else {
            operation.targetColor
        }
        // On the final step, guarantee a non-black color still emits light after
        // gamma correction. Gradient intermediate steps are left untouched so a
        // fade toward black stays smooth; the last step lands on the target and
        // must therefore also receive the visibility floor.
        val rawDuty = color.toPwmDuty()
        val duty = if (!operation.gradient || step >= stepCount) {
            val (r, g, b) = ensureVisibleDuty(
                AndroidColor.red(color),
                AndroidColor.green(color),
                AndroidColor.blue(color),
                rawDuty.r,
                rawDuty.g,
                rawDuty.b,
            )
            Duty(r, g, b)
        } else {
            rawDuty
        }
        val requestId = bleClient.sendCommand(
            String.format(Locale.US, "led_pwm start -d %d,%d,%d", duty.r, duty.g, duty.b),
        )
        if (requestId == null) {
            failColorOperation(operation, "BLEコマンドをキューへ追加できませんでした")
            return
        }
        pendingColorWrites[requestId] = PendingColorWrite(operation.id, color, step)
    }

    private fun finishColorOperation(operation: ColorSendOperation) {
        if (activeColorOperation?.id != operation.id) return
        colorOperationTimeoutJob?.cancel()
        activeColorOperation = null
        val voice = operation.voice
        if (voice == null) {
            statusText = if (operation.gradient) "グラデーション完了" else "色を変更しました"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val bleMs = (now - voice.inferenceCompletedAtMs).coerceAtLeast(0L)
        val totalMs = (now - voice.speechEndedAtMs).coerceAtLeast(0L)
        val finalResolution = voice.resolution.copy(bleMs = bleMs, totalMs = totalMs)
        val hex = finalResolution.rgb.toColorInt().hexCode
        voiceColorSource = "$hex ${finalResolution.source} / speech:${finalResolution.speechMs}ms " +
            "infer:${finalResolution.inferenceMs}ms ble:${finalResolution.bleMs}ms total:${finalResolution.totalMs}ms"
        voiceUiState = VoiceUiState.Idle
        voiceErrorMessage = null
        statusText = "音声カラーを${finalResolution.totalMs}msで反映"
        android.util.Log.i("SprightLatency", finalResolution.toString())
        appendVoiceLatencyLog(finalResolution, android.bluetooth.BluetoothGatt.GATT_SUCCESS)
    }

    private fun failColorOperation(operation: ColorSendOperation, reason: String, gattStatus: Int? = null) {
        if (activeColorOperation?.id != operation.id) return
        colorOperationTimeoutJob?.cancel()
        activeColorOperation = null
        pendingColorWrites.entries.removeAll { it.value.operationId == operation.id }
        statusText = reason
        operation.voice?.let {
            voiceColorSource = "${it.resolution.rgb.toColorInt().hexCode} $reason"
            if (gattStatus != null) appendVoiceLatencyLog(it.resolution, gattStatus)
            reportVoiceError(reason, keepRecognizedText = true)
        } ?: showVisitorMessage(reason)
    }

    private fun showVisitorMessage(message: String) {
        visitorMessageJob?.cancel()
        visitorMessage = message
        visitorMessageJob = lifecycleScope.launch {
            delay(VISITOR_MESSAGE_DURATION_MS)
            if (visitorMessage == message) {
                visitorMessage = null
            }
        }
    }

    private fun armColorOperationTimeout(operation: ColorSendOperation) {
        colorOperationTimeoutJob?.cancel()
        val timeoutMs = if (operation.gradient) {
            maxOf(
                ExhibitionRecoveryPolicy.GRADIENT_OPERATION_TIMEOUT_MS,
                operation.gradientDurationMs + ExhibitionRecoveryPolicy.BLE_WRITE_TIMEOUT_MS,
            )
        } else {
            ExhibitionRecoveryPolicy.BLE_WRITE_TIMEOUT_MS
        }
        colorOperationTimeoutJob = lifecycleScope.launch {
            delay(timeoutMs)
            if (activeColorOperation?.id == operation.id) {
                failColorOperation(operation, "BLE送信が${timeoutMs / 1_000}秒以内に完了しませんでした")
                bleClient.disconnect()
            }
        }
    }

    private fun sendDiagBond() {
        bleClient.sendCommand(DIAG_BOND_COMMAND)
        statusText = "diag bond sent"
    }

    private fun startImu() {
        if (imuRunning) {
            statusText = "IMU already running"
            return
        }
        shouldMaintainImuStream = true
        if (!restartRemoteImuStream()) {
            statusText = "IMU開始コマンドを送信できませんでした"
            return
        }
        imuPoseHealthy = false
        statusText = "Starting IMU..."
        executeImu {
            attitudeTipSpeedEstimator.reset()
            val started = imuEstimator.start()
            imuEstimatorStarted = started
            if (started) openImuLog() else closeImuLog()
            runOnUiThread {
                imuRunning = started
                imuCalibrating = false
                statusText = if (started) {
                    "IMU started (JNI attitude enabled)"
                } else {
                    "IMU start failed: ${imuEstimator.lastError ?: "unknown error"}"
                }
            }
        }
    }

    private fun stopImu() {
        shouldMaintainImuStream = false
        bleClient.sendCommand("imu stop")
        imuRunning = false
        imuPoseHealthy = false
        imuCalibrating = false
        statusText = "IMU stopped"
        executeImu {
            imuEstimator.stop()
            attitudeTipSpeedEstimator.reset()
            imuEstimatorStarted = false
            closeImuLog()
        }
    }

    private fun resumeImuAfterReconnect() {
        if (!shouldMaintainImuStream || !bleClient.isReady() || imuRunning) return
        if (!imuEstimatorStarted) {
            startImu()
            return
        }
        if (!restartRemoteImuStream()) {
            statusText = "再接続後のIMU開始コマンドを送信できませんでした"
            return
        }
        imuRunning = true
        statusText = "BLE再接続後にIMU取得を再開しました"
        android.util.Log.i("SprightImu", "IMU stream resumed after BLE reconnect")
    }

    private fun restartRemoteImuStream(): Boolean {
        // BLE1507 can retain its streaming flag across a broken GATT link while
        // the old notification route is already gone. A lone "imu start" is
        // then acknowledged but produces no samples. Reset the peripheral
        // stream explicitly; Ble1507Client serializes both writes.
        val stopRequest = bleClient.sendCommand("imu stop") ?: return false
        val startRequest = bleClient.sendCommand("imu start") ?: return false
        android.util.Log.i(
            "SprightImu",
            "Queued remote IMU restart stopId=$stopRequest startId=$startRequest",
        )
        return true
    }

    private fun toggleCalibration() {
        if (!imuRunning) {
            statusText = "Start IMU first"
            return
        }
        if (imuCalibrating) {
            statusText = "Finishing calibration..."
            executeImu {
                val ok = imuEstimator.finishCalibration(restartEstimation = true)
                runOnUiThread {
                    imuCalibrating = false
                    statusText = if (ok) {
                        "Calibration completed; relative yaw reset"
                    } else {
                        "Calibration finish failed: ${imuEstimator.lastError ?: "unknown error"}"
                    }
                }
            }
        } else {
            statusText = "Starting calibration..."
            executeImu {
                val started = imuEstimator.startCalibration()
                runOnUiThread {
                    imuCalibrating = started
                    statusText = if (started) {
                        "Calibration started; keep device still"
                    } else {
                        "Calibration start failed: ${imuEstimator.lastError ?: "unknown error"}"
                    }
                }
            }
        }
    }

    private fun requestImuSync() {
        if (!bleClient.isReady()) {
            statusText = "IMU同期にはBLE接続が必要です"
            return
        }
        syncUiState = SyncUiState.Prompt
        imuPoseHealthy = false
        calibrationMessage = ""
        if (!imuRunning) {
            startImu()
        }
    }

    private fun beginStationaryCalibration() {
        if (!imuRunning) {
            calibrationMessage = "IMUの開始を待っています。もう一度お試しください"
            syncUiState = SyncUiState.MotionError
            return
        }
        stationaryCalibrationJob?.cancel()
        val startedAtMs = SystemClock.elapsedRealtime()
        stationaryCalibrationStartedAtMs = startedAtMs
        lastStationaryCalibrationSampleAtMs = 0L
        lastCalibrationGraphUpdateAtMs = 0L
        calibrationCountdown = STATIONARY_CALIBRATION_SECONDS
        calibrationMessage = "IMUサンプルを待っています"
        calibrationGraphSamples = emptyList()
        syncUiState = SyncUiState.CountingDown
        imuPoseHealthy = false
        imuCalibrating = true
        executeImu {
            calibrationSamples.clear()
            calibrationMagnitudeSamples.clear()
            collectingStationaryCalibration = true
        }
        stationaryCalibrationJob = lifecycleScope.launch {
            val durationMs = STATIONARY_CALIBRATION_SECONDS * 1_000L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - startedAtMs
                val remainingMs = (durationMs - elapsedMs).coerceAtLeast(0L)
                calibrationCountdown = ((remainingMs + 999L) / 1_000L).toInt()
                val lastSampleAt = lastStationaryCalibrationSampleAtMs
                val imuUnavailable = isCalibrationImuUnavailable(
                    nowMs = now,
                    startedAtMs = startedAtMs,
                    lastSampleAtMs = lastSampleAt,
                    firstSampleTimeoutMs = CALIBRATION_FIRST_SAMPLE_TIMEOUT_MS,
                    staleSampleTimeoutMs = CALIBRATION_SAMPLE_STALE_TIMEOUT_MS,
                )
                if (imuUnavailable) {
                    abortStationaryCalibrationAndReconnect(
                        if (lastSampleAt == 0L) {
                            "IMUデータを受信できませんでした。BLEを再接続します"
                        } else {
                            "IMUデータの更新が停止しました。BLEを再接続します"
                        },
                    )
                    return@launch
                }
                if (remainingMs == 0L) break
                delay(CALIBRATION_MONITOR_INTERVAL_MS)
            }
            calibrationCountdown = 0
            executeImu {
                collectingStationaryCalibration = false
                val samples = calibrationSamples.toList()
                if (samples.size < StationaryCalibration.MIN_SAMPLES) {
                    runOnUiThread {
                        abortStationaryCalibrationAndReconnect(
                            "IMUサンプルが不足しました（${samples.size}/${StationaryCalibration.MIN_SAMPLES}）。" +
                                "BLEを再接続します",
                        )
                    }
                    return@executeImu
                }
                val result = StationaryCalibration.analyze(samples)
                if (result.accepted) {
                    // The bundled native library frequently rejects calibration
                    // setters despite producing valid Euler estimates. Exhibition
                    // sync therefore uses the relaxed stationary check and makes
                    // the complete stationary pose the scene's zero orientation.
                    // This removes mounting/table tilt without guessing a diagonal
                    // sensor-axis transform.
                    imuEstimator.resetRelativeYaw()
                }
                val stationaryReference = latestRawAttitude
                runOnUiThread {
                    stationaryCalibrationJob = null
                    imuCalibrating = false
                    calibrationMessage = "${result.reason}\n${result.summary}"
                    if (result.accepted) {
                        syncUiState = SyncUiState.Synced
                        imuPoseHealthy = false
                        latestAttitude = AttitudeEstimate(0f, 0f, 0f)
                        sceneAttitudeReference = AttitudeEstimate(
                            rollDeg = stationaryReference.rollDeg,
                            pitchDeg = stationaryReference.pitchDeg,
                            // resetRelativeYaw() makes the next valid yaw zero.
                            yawDeg = 0f,
                        )
                        statusText = "IMU同期完了（静止確認）: ${result.summary}"
                    } else {
                        syncUiState = SyncUiState.MotionError
                        statusText = result.reason
                    }
                }
            }
        }
    }

    private fun abortStationaryCalibrationAndReconnect(reason: String) {
        executeImu {
            collectingStationaryCalibration = false
            calibrationSamples.clear()
            calibrationMagnitudeSamples.clear()
        }
        stationaryCalibrationJob = null
        imuCalibrating = false
        imuPoseHealthy = false
        syncUiState = SyncUiState.Idle
        calibrationCountdown = 0
        calibrationMessage = reason
        calibrationGraphSamples = emptyList()
        statusText = reason
        showVisitorMessage(reason)
        shouldMaintainBleConnection = true
        shouldMaintainImuStream = true
        persistBleMaintenancePreference()
        bleReconnectJob?.cancel()
        bleReconnectJob = null
        bleReconnectAttempt = 0
        forceImmediateBleReconnect = true
        bleClient.disconnect()
    }

    private fun cancelStationaryCalibration() {
        stationaryCalibrationJob?.cancel()
        stationaryCalibrationJob = null
        executeImu {
            collectingStationaryCalibration = false
            calibrationSamples.clear()
            calibrationMagnitudeSamples.clear()
        }
        imuCalibrating = false
        syncUiState = SyncUiState.Idle
        imuPoseHealthy = false
        calibrationMessage = ""
        calibrationGraphSamples = emptyList()
        statusText = "IMU同期をキャンセルしました"
    }

    private fun resetDisplayedAttitude() {
        if (!imuRunning || syncUiState != SyncUiState.Synced || !imuPoseHealthy) {
            statusText = "有効なIMU姿勢がないため表示をリセットできません"
            return
        }
        val current = latestRawAttitude
        sceneAttitudeReference = current
        latestAttitude = current
        statusText = "現在の姿勢を3D表示の基準にしました"
        android.util.Log.i(
            "SprightImu",
            "Display attitude reset at roll=${current.rollDeg} pitch=${current.pitchDeg} yaw=${current.yawDeg}",
        )
    }

    private fun startVoiceInput() {
        voiceInferenceGeneration++
        voiceIdleRecoveryJob?.cancel()
        voiceErrorMessage = null
        voiceColorSource = "color resolver: --"
        if (!hasRequiredPermissions(includeBle = false)) {
            pendingVoiceInputStart = true
            statusText = "マイク権限を確認しています"
            requestRequiredPermissions()
            return
        }
        pendingVoiceInputStart = false
        if (!modelReady) {
            reportVoiceError("ローカル色モデルの準備が完了していません")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reportVoiceError("この端末で音声認識サービスを利用できません")
            return
        }
        offlineRetryAttempted = false
        offlineLanguagePackMissing = false
        val preferOffline = shouldPreferOfflineRecognition()
        ensureSpeechRecognizer(preferOffline = preferOffline)
        if (isVoiceListening) {
            cancelSpeechTimeout()
            speechRecognizer?.cancel()
            isVoiceListening = false
        }
        startListeningWithRecovery(preferOffline = preferOffline)
    }

    private fun cancelVoiceInput() {
        voiceInferenceGeneration++
        releaseSpeechRecognizer()
        voiceUiState = VoiceUiState.Idle
        voiceErrorMessage = null
        voicePartialText = ""
        voiceText = "voice: --"
        voiceColorSource = "color resolver: --"
        statusText = "音声入力をキャンセルしました"
    }

    private fun ensureSpeechRecognizer(preferOffline: Boolean = false) {
        val useOnDeviceRecognizer = preferOffline && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (speechRecognizer != null && speechRecognizerOnDevice == useOnDeviceRecognizer) {
            return
        }
        if (speechRecognizer != null) {
            releaseSpeechRecognizer()
        }
        val listenerGeneration = ++speechRecognizerGeneration
        speechRecognizerOnDevice = useOnDeviceRecognizer
        val recognizer = if (useOnDeviceRecognizer) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer = recognizer.also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (listenerGeneration != speechRecognizerGeneration) return
                    isVoiceListening = true
                    voiceUiState = VoiceUiState.Listening
                    voiceErrorMessage = null
                    voiceRms = 0f
                    voicePartialText = ""
                    statusText = "音声入力中"
                    voiceText = "voice: listening"
                    scheduleSpeechTimeout()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (listenerGeneration != speechRecognizerGeneration) return
                    voiceRms = rmsdB.coerceAtLeast(0f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (listenerGeneration != speechRecognizerGeneration) return
                    cancelSpeechTimeout()
                    isVoiceListening = false
                    speechEndedAtMs = SystemClock.elapsedRealtime()
                    voiceUiState = VoiceUiState.Recognizing
                    statusText = "音声を認識しています"
                    scheduleRecognitionTimeout()
                }

                override fun onError(error: Int) {
                    if (shuttingDown || listenerGeneration != speechRecognizerGeneration) return
                    // A segmented recognizer can still deliver its end callback after an
                    // error. Mark this session complete so it cannot submit stale text.
                    segmentedResultApplied = true
                    isVoiceListening = false
                    voiceRms = 0f
                    cancelSpeechTimeout()
                    if (
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        currentRecognitionOffline && error in setOf(
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        )
                    ) {
                        offlineLanguagePackMissing = true
                        updateOfflineSpeechPackState(SPEECH_PACK_STATE_NONE)
                    }
                    val onlineServiceUnavailable = error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    if (!offlineRetryAttempted && onlineServiceUnavailable) {
                        offlineRetryAttempted = true
                        statusText = "オンライン認識に失敗したため日本語オフライン認識へ切り替えます"
                        recreateSpeechRecognizer(preferOffline = true)
                        lifecycleScope.launch {
                            delay(250L)
                            startListeningWithRecovery(preferOffline = true)
                        }
                        return
                    }
                    voiceColorSource = "color resolver: --"
                    reportVoiceError(
                        describeSpeechRecognizerError(error, offlineLanguagePackMissing),
                        keepRecognizedText = false,
                    )
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        recreateSpeechRecognizer(preferOffline = currentRecognitionOffline)
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (segmentedResultApplied || listenerGeneration != speechRecognizerGeneration) return
                    // Some providers finish a segmented request through onResults while
                    // others subsequently call onEndOfSegmentedSession. Apply it once.
                    segmentedResultApplied = true
                    isVoiceListening = false
                    voiceRms = 0f
                    cancelSpeechTimeout()
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    android.util.Log.d("BLE1507V", "onResults bundle keys=${results?.keySet()}")
                    android.util.Log.d("BLE1507V", "onResults list=$list size=${list?.size}")
                    val text = list?.firstOrNull().orEmpty().ifBlank { lastPartialText }
                    lastPartialText = ""
                    voicePartialText = ""
                    android.util.Log.d("BLE1507V", "onResults text='$text' blank=${text.isBlank()}")
                    applyVoiceColor(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (listenerGeneration != speechRecognizerGeneration) return
                    updateLiveRecognitionText(partialResults, appendSegment = false, source = "partial")
                }

                override fun onSegmentResults(segmentResults: Bundle) {
                    if (listenerGeneration != speechRecognizerGeneration) return
                    updateLiveRecognitionText(segmentResults, appendSegment = true, source = "segment")
                }

                override fun onEndOfSegmentedSession() {
                    if (
                        segmentedResultApplied ||
                        shuttingDown ||
                        listenerGeneration != speechRecognizerGeneration
                    ) return
                    segmentedResultApplied = true
                    cancelSpeechTimeout()
                    isVoiceListening = false
                    voiceRms = 0f
                    speechEndedAtMs = speechEndedAtMs ?: SystemClock.elapsedRealtime()
                    voiceUiState = VoiceUiState.Recognizing
                    val text = segmentedRecognitionText.ifBlank { lastPartialText }
                    android.util.Log.d("BLE1507V", "onEndOfSegmentedSession text='$text'")
                    applyVoiceColor(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun updateLiveRecognitionText(results: Bundle?, appendSegment: Boolean, source: String) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (text.isBlank()) return
        val liveText = if (appendSegment) {
            when {
                segmentedRecognitionText.isBlank() -> text
                text.startsWith(segmentedRecognitionText) -> text
                segmentedRecognitionText.endsWith(text) -> segmentedRecognitionText
                else -> segmentedRecognitionText + text
            }.also { segmentedRecognitionText = it }
        } else {
            text
        }
        lastPartialText = liveText
        voicePartialText = liveText
        android.util.Log.d("BLE1507V", "$source result='$liveText'")
    }

    private fun recreateSpeechRecognizer(preferOffline: Boolean = currentRecognitionOffline) {
        releaseSpeechRecognizer()
        ensureSpeechRecognizer(preferOffline = preferOffline)
    }

    private fun releaseSpeechRecognizer() {
        speechRecognizerGeneration++
        segmentedResultApplied = true
        isVoiceListening = false
        voiceRms = 0f
        cancelSpeechTimeout()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechRecognizerOnDevice = false
    }

    private fun startListeningWithRecovery() {
        startListeningWithRecovery(preferOffline = shouldPreferOfflineRecognition())
    }

    private fun startListeningWithRecovery(preferOffline: Boolean) {
        val recognizer = speechRecognizer ?: run {
            ensureSpeechRecognizer(preferOffline = preferOffline)
            speechRecognizer
        } ?: return
        speechEndedAtMs = null
        lastPartialText = ""
        segmentedRecognitionText = ""
        segmentedResultApplied = false
        voicePartialText = ""
        currentRecognitionOffline = preferOffline
        voiceUiState = VoiceUiState.Listening
        statusText = if (preferOffline) "日本語オフライン音声認識を開始しています" else "音声認識を開始しています"
        scheduleSpeechTimeout()
        val intent = buildSpeechRecognizerIntent(preferOffline)
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            recreateSpeechRecognizer(preferOffline = preferOffline)
            runCatching {
                speechRecognizer?.startListening(intent)
            }.onFailure {
                isVoiceListening = false
                reportVoiceError("音声認識サービスを開始できませんでした")
            }
        }
    }

    private fun shouldPreferOfflineRecognition(): Boolean =
        offlineSpeechPackState == SPEECH_PACK_STATE_READY && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun buildSpeechRecognizerIntent(
        preferOffline: Boolean,
        enableSegmentedResults: Boolean = true,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (enableSegmentedResults) {
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
        }
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_200)
    }

    private fun scheduleSpeechTimeout() {
        cancelSpeechTimeout()
        voiceTimeoutJob = lifecycleScope.launch {
            delay(ExhibitionRecoveryPolicy.SPEECH_LISTENING_TIMEOUT_MS)
            if (voiceUiState == VoiceUiState.Listening) {
                isVoiceListening = false
                runCatching { speechRecognizer?.cancel() }
                recreateSpeechRecognizer()
                reportVoiceError(
                    "音声入力が${ExhibitionRecoveryPolicy.SPEECH_LISTENING_TIMEOUT_MS / 1_000}秒でタイムアウトしました",
                    keepRecognizedText = false,
                )
            }
        }
    }

    private fun scheduleRecognitionTimeout() {
        cancelSpeechTimeout()
        voiceTimeoutJob = lifecycleScope.launch {
            delay(ExhibitionRecoveryPolicy.SPEECH_RESULTS_TIMEOUT_MS)
            if (voiceUiState == VoiceUiState.Recognizing) {
                recreateSpeechRecognizer()
                reportVoiceError(
                    "音声認識結果が${ExhibitionRecoveryPolicy.SPEECH_RESULTS_TIMEOUT_MS / 1_000}秒以内に返りませんでした",
                    keepRecognizedText = false,
                )
            }
        }
    }

    private fun cancelSpeechTimeout() {
        voiceTimeoutJob?.cancel()
        voiceTimeoutJob = null
    }

    private fun reportVoiceError(message: String, keepRecognizedText: Boolean = false) {
        cancelSpeechTimeout()
        isVoiceListening = false
        voiceRms = 0f
        voicePartialText = ""
        voiceUiState = VoiceUiState.Error
        voiceErrorMessage = message
        statusText = message
        if (!keepRecognizedText) {
            voiceText = "voice: --"
        }
        voiceIdleRecoveryJob?.cancel()
        voiceIdleRecoveryJob = lifecycleScope.launch {
            delay(ExhibitionRecoveryPolicy.ERROR_TO_IDLE_DELAY_MS)
            if (voiceUiState == VoiceUiState.Error) {
                voiceUiState = VoiceUiState.Idle
            }
        }
    }

    private fun refreshOfflineSpeechPackState() {
        if (offlineSpeechPackState == SPEECH_PACK_STATE_READY) return
        offlineSupportCheckRecognizer?.destroy()
        val recognizer = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        }.getOrNull() ?: return
        offlineSupportCheckRecognizer = recognizer
        runCatching {
            recognizer.checkRecognitionSupport(
                buildSpeechRecognizerIntent(preferOffline = true, enableSegmentedResults = false),
                mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        when {
                            recognitionSupport.installedOnDeviceLanguages.any(::isJapaneseLanguageTag) ->
                                updateOfflineSpeechPackState(SPEECH_PACK_STATE_READY)

                            recognitionSupport.pendingOnDeviceLanguages.any(::isJapaneseLanguageTag) ->
                                updateOfflineSpeechPackState(SPEECH_PACK_STATE_REQUESTED)
                        }
                        finishOfflineSupportCheck()
                    }

                    override fun onError(error: Int) {
                        android.util.Log.i(
                            "SprightSpeech",
                            "Could not query offline speech pack state: $error",
                        )
                        finishOfflineSupportCheck()
                    }
                },
            )
        }.onFailure {
            finishOfflineSupportCheck()
        }
    }

    private fun finishOfflineSupportCheck() {
        offlineSupportCheckRecognizer?.destroy()
        offlineSupportCheckRecognizer = null
    }

    private fun isJapaneseLanguageTag(tag: String): Boolean =
        Locale.forLanguageTag(tag.replace('_', '-')).language.equals("ja", ignoreCase = true)

    private fun openOfflineSpeechSettings() {
        if (offlineSpeechPackRequestInProgress) return
        if (offlineSpeechPackState != SPEECH_PACK_STATE_NONE) {
            statusText = if (offlineSpeechPackState == SPEECH_PACK_STATE_READY) {
                "日本語オフライン音声パックは準備済みです"
            } else {
                "日本語オフライン音声パックは取得要求済みです"
            }
            return
        }
        offlineModelDownloadRecognizer?.destroy()
        val recognizer = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        }.getOrElse {
            openVoiceInputSettings("オフライン音声サービスを起動できませんでした")
            return
        }
        offlineModelDownloadRecognizer = recognizer
        offlineSpeechPackRequestInProgress = true
        statusText = "日本語オフライン音声パックの取得を要求しています"
        runCatching {
            recognizer.triggerModelDownload(
                buildSpeechRecognizerIntent(preferOffline = true, enableSegmentedResults = false),
                mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {
                        statusText = "日本語音声パックを取得中: ${completedPercent.coerceIn(0, 100)}%"
                    }

                    override fun onSuccess() {
                        updateOfflineSpeechPackState(SPEECH_PACK_STATE_READY)
                        statusText = "日本語オフライン音声パックの準備が完了しました"
                        voiceErrorMessage = null
                        finishOfflineModelDownload()
                    }

                    override fun onScheduled() {
                        updateOfflineSpeechPackState(SPEECH_PACK_STATE_REQUESTED)
                        statusText = "日本語音声パックのダウンロードを予約しました"
                        finishOfflineModelDownload()
                    }

                    override fun onError(error: Int) {
                        finishOfflineModelDownload()
                        openVoiceInputSettings("日本語音声パックを取得できませんでした（エラー$error）")
                    }
                },
            )
        }.onFailure {
            finishOfflineModelDownload()
            openVoiceInputSettings("日本語音声パックの取得要求に失敗しました")
        }
    }

    private fun finishOfflineModelDownload() {
        offlineSpeechPackRequestInProgress = false
        offlineModelDownloadRecognizer?.destroy()
        offlineModelDownloadRecognizer = null
    }

    private fun updateOfflineSpeechPackState(state: String) {
        offlineSpeechPackState = state
        getSharedPreferences(EXHIBITION_PREFS, MODE_PRIVATE).edit {
            putString(PREF_OFFLINE_SPEECH_PACK_STATE, state)
        }
    }

    private fun speechPackActionText(): String = when {
        offlineSpeechPackRequestInProgress -> "日本語音声パック取得要求中…"
        offlineSpeechPackState == SPEECH_PACK_STATE_READY -> "日本語音声パック準備済み"
        offlineSpeechPackState == SPEECH_PACK_STATE_REQUESTED -> "日本語音声パック要求済み"
        else -> "日本語音声パック取得"
    }

    private fun speechPackActionEnabled(): Boolean =
        !offlineSpeechPackRequestInProgress && offlineSpeechPackState == SPEECH_PACK_STATE_NONE

    private fun openVoiceInputSettings(reason: String) {
        voiceErrorMessage = "$reason。音声入力設定を確認してください"
        statusText = voiceErrorMessage.orEmpty()
        val voiceInputSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        val settingsIntent = if (voiceInputSettings.resolveActivity(packageManager) != null) {
            voiceInputSettings
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { startActivity(settingsIntent) }
            .onFailure { reportVoiceError("音声サービスの設定画面を開けませんでした") }
    }

    private fun applyVoiceColor(text: String) {
        if (text.isBlank()) {
            voiceColorSource = "color resolver: --"
            reportVoiceError("音声を認識できませんでした。もう一度ゆっくり話してください", keepRecognizedText = false)
            return
        }
        voiceText = "voice: $text"
        voiceUiState = VoiceUiState.Inferring
        voiceErrorMessage = null
        statusText = "Resolving color..."
        voiceColorSource = "color resolver: ..."
        val current = selectedColor.toInterpretedColor()
        val recognitionCompletedAtMs = SystemClock.elapsedRealtime()
        val endAtMs = speechEndedAtMs ?: recognitionCompletedAtMs
        val inferenceGeneration = ++voiceInferenceGeneration
        lifecycleScope.launch {
            // Let Compose commit the transcript and inference indicator before
            // native LLM work starts competing for CPU time.
            delay(VOICE_TRANSCRIPT_RENDER_DELAY_MS)
            if (inferenceGeneration != voiceInferenceGeneration) return@launch
            val inference = async(Dispatchers.IO) {
                colorInterpreter.interpret(text, current)
            }
            val resolution = withTimeoutOrNull(ExhibitionRecoveryPolicy.INFERENCE_TIMEOUT_MS) {
                inference.await()
            }
            if (inferenceGeneration != voiceInferenceGeneration) {
                inference.cancel()
                return@launch
            }
            if (resolution == null && !inference.isCompleted) {
                inference.cancel()
                voiceColorSource = "color resolver: --"
                reportVoiceError(
                    "色推論が${ExhibitionRecoveryPolicy.INFERENCE_TIMEOUT_MS / 1_000}秒でタイムアウトしました",
                    keepRecognizedText = true,
                )
                return@launch
            }
            val completedResolution = resolution ?: run {
                statusText = "Color not recognized"
                voiceColorSource = "Fail: model did not return HEX"
                reportVoiceError("色を判断できませんでした。別の表現をお試しください", keepRecognizedText = true)
                return@launch
            }
            val interpretedColor = completedResolution.rgb
            val pwmDuty = interpretedColor.toPwmDuty()
            val newColor = interpretedColor.toColorInt()
            val hexCode = String.format(java.util.Locale.US, "#%02X%02X%02X",
                interpretedColor.r, interpretedColor.g, interpretedColor.b)
            val src = describeColorSource(interpretedColor.source)
            val speechMs = (recognitionCompletedAtMs - endAtMs).coerceAtLeast(0L)
            val completedAtMs = SystemClock.elapsedRealtime()
            val withSpeech = completedResolution.copy(speechMs = speechMs)
            voiceColorSource = "$hexCode $src / speech:${speechMs}ms infer:${completedResolution.inferenceMs}ms"
            statusText = "Sending RGB ${interpretedColor.label} -> PWM ${pwmDuty.label}"
            sendColor(
                targetColor = newColor,
                gradientDurationSeconds = gradientDurationSeconds,
                voice = VoiceColorMetadata(withSpeech, endAtMs, completedAtMs),
            )
        }
    }

    override fun onStatus(status: String) {
        statusText = status
    }

    override fun onBondState(status: String) {
        bondState = status
    }

    override fun onConnectionState(status: String) {
        connectionState = status
        when (status) {
            "ready" -> {
                bleReconnectJob?.cancel()
                bleReconnectAttempt = 0
                // Queue the visible color first. Ble1507Client serializes this
                // write with the following IMU stop/start recovery commands.
                synchronizeDisplayedColorAfterConnection()
                resumeImuAfterReconnect()
            }
            "scanning", "connecting", "connected" -> bleReconnectJob?.cancel()
        }
        if (status == "disconnected") {
            val calibrationWasActive = syncUiState == SyncUiState.CountingDown
            val reconnectImmediately = forceImmediateBleReconnect || calibrationWasActive
            forceImmediateBleReconnect = false
            stationaryCalibrationJob?.cancel()
            stationaryCalibrationJob = null
            val preserveImuSession = shouldMaintainBleConnection &&
                shouldMaintainImuStream &&
                imuEstimatorStarted &&
                !shuttingDown
            imuRunning = false
            imuCalibrating = false
            imuPoseHealthy = false
            collectingStationaryCalibration = false
            calibrationGraphSamples = emptyList()
            if (calibrationWasActive) {
                calibrationMessage = "BLE接続が切れたため同期を中断し、再接続します"
                showVisitorMessage(calibrationMessage)
            }
            if (!preserveImuSession || syncUiState != SyncUiState.Synced) {
                syncUiState = SyncUiState.Idle
            }
            activeColorOperation?.let {
                failColorOperation(it, "BLE接続が切れたため送信を中止しました")
            }
            pendingColorWrites.clear()
            showDiagBondRecovery = false
            showBondHint = false
            if (!preserveImuSession) {
                executeImu {
                    imuEstimator.stop()
                    attitudeTipSpeedEstimator.reset()
                    imuEstimatorStarted = false
                    closeImuLog()
                }
            } else {
                android.util.Log.i("SprightImu", "Preserving IMU estimator across transient BLE disconnect")
            }
            if (shouldMaintainBleConnection && !shuttingDown) {
                scheduleBleReconnect(immediate = reconnectImmediately)
            }
        }
    }

    override fun onMtuChanged(mtu: Int) {
        mtuState = mtu.toString()
    }

    override fun onImuSample(packet: ImuPacket) {
        executeImu {
            val sampleReceivedAtMs = SystemClock.elapsedRealtime()
            if (collectingStationaryCalibration) {
                calibrationSamples += packet
                lastStationaryCalibrationSampleAtMs = sampleReceivedAtMs
                calibrationMagnitudeSamples += packet.toMagnitudeSample(
                    sampleReceivedAtMs - stationaryCalibrationStartedAtMs,
                )
                if (
                    sampleReceivedAtMs - lastCalibrationGraphUpdateAtMs >=
                    CALIBRATION_GRAPH_UPDATE_INTERVAL_MS
                ) {
                    lastCalibrationGraphUpdateAtMs = sampleReceivedAtMs
                    val graphSnapshot = calibrationMagnitudeSamples
                        .takeLast(MAX_CALIBRATION_GRAPH_SAMPLES)
                    runOnUiThread {
                        if (syncUiState == SyncUiState.CountingDown) {
                            calibrationGraphSamples = graphSnapshot
                            // The live overlaid graph is sufficient feedback;
                            // keep internal sample counts out of exhibition UI.
                            calibrationMessage = ""
                        }
                    }
                }
            }
            val attitude = imuEstimator.update(packet)
            var tipSpeedEstimate: AttitudeTipSpeedEstimate? = null
            if (attitude != null) {
                latestRawAttitude = attitude
                tipSpeedEstimate = attitudeTipSpeedEstimator.update(attitude, sampleReceivedAtMs)
                if (syncUiState == SyncUiState.Synced) {
                    touchDesignerClient.updateMotion(
                        TouchDesignerMotionFrame(
                            attitude = attitude,
                            speed = tipSpeedEstimate.speedFiltered,
                            color = latestTouchDesignerColor,
                        ),
                    )
                }
            }
            appendImuLog(packet, attitude, tipSpeedEstimate)
            val now = sampleReceivedAtMs
            if (imuRateWindowStartedAtMs == 0L) {
                imuRateWindowStartedAtMs = now
            }
            imuSamplesInRateWindow++
            val rateWindowMs = now - imuRateWindowStartedAtMs
            if (rateWindowMs >= IMU_RATE_WINDOW_MS) {
                imuSampleRateHz = imuSamplesInRateWindow * 1_000f / rateWindowMs.coerceAtLeast(1L)
                imuSamplesInRateWindow = 0
                imuRateWindowStartedAtMs = now
            }
            val synced = syncUiState == SyncUiState.Synced
            val poseUpdateDue = attitude != null &&
                synced &&
                now - lastImuPoseUiUpdateAtMs >= IMU_POSE_UI_INTERVAL_MS
            if (poseUpdateDue) {
                lastImuPoseUiUpdateAtMs = now
            }
            val debugUpdateDue = now - lastImuUiUpdateAtMs >= IMU_DEBUG_UI_INTERVAL_MS
            if (debugUpdateDue) {
                lastImuUiUpdateAtMs = now
            }
            if (!poseUpdateDue && !debugUpdateDue && !(attitude == null && synced)) {
                return@executeImu
            }
            val display = if (!debugUpdateDue) {
                null
            } else if (attitude == null) {
                String.format(
                    Locale.US,
                    "sample: %.1f Hz\nroll: --\npitch: --\nyaw: --\nerror: %s\ntemp: %.2f C\ngyro:  x %.3f  y %.3f  z %.3f\naccel: x %.3f  y %.3f  z %.3f",
                    imuSampleRateHz,
                    imuEstimator.lastError ?: "waiting for estimate",
                    packet.temp,
                    packet.gx,
                    packet.gy,
                    packet.gz,
                    packet.ax,
                    packet.ay,
                    packet.az,
                )
            } else {
                String.format(
                    Locale.US,
                    "sample: %.1f Hz  render: smooth\nroll: %.2f deg\npitch: %.2f deg\nyaw(rel): %.2f deg\ntip speed: %.3f m/s raw: %.3f m/s omega: %.3f rad/s\ntemp: %.2f C\ngyro:  x %.3f  y %.3f  z %.3f\naccel: x %.3f  y %.3f  z %.3f",
                    imuSampleRateHz,
                    attitude.rollDeg,
                    attitude.pitchDeg,
                    attitude.yawDeg,
                    tipSpeedEstimate?.speedFiltered ?: 0f,
                    tipSpeedEstimate?.speedRaw ?: 0f,
                    tipSpeedEstimate?.angularSpeedRadPerSecond ?: 0f,
                    packet.temp,
                    packet.gx,
                    packet.gy,
                    packet.gz,
                    packet.ax,
                    packet.ay,
                    packet.az,
                )
            }
            runOnUiThread {
                if (display != null) {
                    imuText = display
                }
                if (poseUpdateDue && syncUiState == SyncUiState.Synced) {
                    lastValidImuEstimateAtMs = now
                    imuPoseHealthy = true
                    latestAttitude = attitude
                } else if (attitude == null && syncUiState == SyncUiState.Synced) {
                    imuPoseHealthy = false
                }
            }
        }
    }

    override fun onMessage(message: String) {
        statusText = message
        appendMessageLog(message)
        when (message) {
            WARN_REPEATED_0X1F -> showDiagBondRecovery = true
            BOND_HINT_MESSAGE -> showBondHint = true
        }
    }

    override fun onCommandWrite(requestId: Long, status: Int) {
        val pending = pendingColorWrites.remove(requestId) ?: return
        val operation = activeColorOperation?.takeIf { it.id == pending.operationId } ?: return
        if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
            failColorOperation(operation, "BLE書き込み失敗: $status", status)
            return
        }
        // The screen and GLB only commit colors that the penlight acknowledged.
        selectedColor = pending.color
        latestTouchDesignerColor = selectedColor
        touchDesignerClient.updateColor(selectedColor)
        val stepCount = operation.stepCount
        if (pending.step >= stepCount) {
            operation.nextStep = stepCount + 1
            finishColorOperation(operation)
        } else {
            operation.nextStep = pending.step + 1
            scheduleNextColorStep(operation)
        }
    }

    private fun appendMessageLog(message: String) {
        if (message.isBlank()) {
            return
        }
        messageLog += message
        if (messageLog.size > MAX_LOG_MESSAGES) {
            messageLog.removeRange(0, messageLog.size - MAX_LOG_MESSAGES)
        }
    }

    private fun executeImu(block: () -> Unit) {
        if (imuExecutor.isShutdown) return
        runCatching { imuExecutor.execute(block) }
            .onFailure { android.util.Log.w("SprightImu", "IMU executor rejected work", it) }
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasRequiredPermissions(includeBle: Boolean = true, includeAudio: Boolean = true): Boolean {
        val hasBle = if (!includeBle) {
            true
        } else {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        val hasAudio = !includeAudio || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasBle && hasAudio
    }

    private fun openImuLog() {
        closeImuLog()
        val baseDir = getExternalFilesDir("imu-logs") ?: File(filesDir, "imu-logs")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val logFile = File(baseDir, IMU_LOG_FILE_NAME)
        imuLogWriter = runCatching { BufferedWriter(FileWriter(logFile, false)) }.getOrNull()
        if (imuLogWriter == null) {
            imuLogPath = "log: failed to open"
            return
        }
        imuLogPath = "log: ${logFile.absolutePath}"
        imuLogWriter?.apply {
            write(
                "ts_unix_ms,timestamp_ticks,temp,gx,gy,gz,ax,ay,az,roll,pitch,yaw," +
                    "tip_speed_filtered,tip_speed_raw,angular_speed_rad_s," +
                    "delta_roll_rad,delta_pitch_rad,delta_yaw_rad,dt_seconds\n",
            )
            flush()
        }
        lastImuLogFlushAtMs = SystemClock.elapsedRealtime()
    }

    private fun appendImuLog(packet: ImuPacket, attitude: AttitudeEstimate?, speed: AttitudeTipSpeedEstimate?) {
        val writer = imuLogWriter ?: return
        runCatching {
            val line = String.format(
                Locale.US,
                "%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s,%s," +
                    "%s,%s,%s,%s,%s,%s,%s\n",
                System.currentTimeMillis(),
                packet.timestamp,
                packet.temp,
                packet.gx,
                packet.gy,
                packet.gz,
                packet.ax,
                packet.ay,
                packet.az,
                attitude?.rollDeg?.let { "%.6f".format(Locale.US, it) } ?: "",
                attitude?.pitchDeg?.let { "%.6f".format(Locale.US, it) } ?: "",
                attitude?.yawDeg?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.speedFiltered?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.speedRaw?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.angularSpeedRadPerSecond?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.deltaRollRad?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.deltaPitchRad?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.deltaYawRad?.let { "%.6f".format(Locale.US, it) } ?: "",
                speed?.dtSeconds?.let { "%.6f".format(Locale.US, it) } ?: "",
            )
            writer.write(line)
            val now = SystemClock.elapsedRealtime()
            if (now - lastImuLogFlushAtMs >= IMU_LOG_FLUSH_INTERVAL_MS) {
                writer.flush()
                lastImuLogFlushAtMs = now
            }
        }
    }

    private fun closeImuLog() {
        runCatching { imuLogWriter?.flush() }
        runCatching { imuLogWriter?.close() }
        imuLogWriter = null
    }

    private fun appendVoiceLatencyLog(resolution: ColorResolution, gattStatus: Int) {
        runCatching {
            val baseDir = getExternalFilesDir("benchmarks") ?: File(filesDir, "benchmarks")
            baseDir.mkdirs()
            val file = File(baseDir, VOICE_LATENCY_FILE_NAME)
            val needsHeader = !file.exists() || file.length() == 0L
            BufferedWriter(FileWriter(file, true)).use { writer ->
                if (needsHeader) {
                    writer.write("ts_unix_ms,gatt_success,gatt_status,rgb,source,speech_ms,inference_ms,ble_ms,total_ms\n")
                }
                val rgb = String.format(
                    Locale.US,
                    "#%02X%02X%02X",
                    resolution.rgb.r,
                    resolution.rgb.g,
                    resolution.rgb.b,
                )
                val source = resolution.source.replace("\"", "\"\"")
                writer.write(
                    "${System.currentTimeMillis()}," +
                        "${gattStatus == android.bluetooth.BluetoothGatt.GATT_SUCCESS},$gattStatus," +
                        "$rgb,\"$source\",${resolution.speechMs},${resolution.inferenceMs}," +
                        "${resolution.bleMs},${resolution.totalMs}\n",
                )
            }
        }.onFailure {
            android.util.Log.w("SprightLatency", "Could not append voice E2E report", it)
        }
    }

    private fun scheduleBleReconnect(immediate: Boolean = false) {
        if (!shouldMaintainBleConnection || shuttingDown || bleClient.isReady()) return
        if (bleReconnectJob?.isActive == true) return
        val attempt = bleReconnectAttempt++
        val delayMs = if (immediate) 0L else ExhibitionRecoveryPolicy.reconnectDelayMs(attempt)
        statusText = if (delayMs == 0L) {
            "BLE1507へ自動接続します"
        } else {
            "BLE切断: ${delayMs / 1_000}秒後に自動再接続します"
        }
        bleReconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (shouldMaintainBleConnection && connectionState == "disconnected" && !shuttingDown) {
                reconnectBle()
            }
        }
    }

    private fun persistBleMaintenancePreference() {
        getSharedPreferences(EXHIBITION_PREFS, MODE_PRIVATE).edit {
            putBoolean(PREF_MAINTAIN_BLE, shouldMaintainBleConnection)
        }
    }

    private fun configureExhibitionWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        enterManagedKioskIfAllowed()
    }

    private fun enterManagedKioskIfAllowed() {
        val devicePolicyManager = getSystemService(DevicePolicyManager::class.java) ?: return
        val activityManager = getSystemService(ActivityManager::class.java) ?: return
        if (
            devicePolicyManager.isLockTaskPermitted(packageName) &&
            activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
        ) {
            runCatching { startLockTask() }
                .onFailure { android.util.Log.w("SprightExhibition", "Could not enter managed kiosk mode", it) }
        }
    }

    private fun startExhibitionScreenPinning() {
        runCatching { startLockTask() }
            .onSuccess {
                statusText = "画面固定を開始しました"
                hideSystemBars()
            }
            .onFailure {
                statusText = "画面固定を開始できませんでした: ${it.message ?: it.javaClass.simpleName}"
            }
    }

    private fun hideSystemBars() {
        window.decorView.windowInsetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun Ble1507Screen(
    connectionState: String,
    bondState: String,
    mtuState: String,
    statusText: String,
    modelState: String,
    modelReady: Boolean,
    voiceText: String,
    voiceColorSource: String,
    selectedColor: Int,
    imuRunning: Boolean,
    imuCalibrating: Boolean,
    imuText: String,
    imuLogPath: String,
    showDiagBondRecovery: Boolean,
    showBondHint: Boolean,
    messageLog: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onColorChanged: (Int) -> Unit,
    onSendColor: () -> Unit,
    onSendDiagBond: () -> Unit,
    onVoice: () -> Unit,
    onStartImu: () -> Unit,
    onStopImu: () -> Unit,
    onCalibrateImu: () -> Unit,
    onBluetoothSettings: () -> Unit,
) {
    MaterialTheme {
        Surface(color = Color(0xFF080E1B), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                StageBackground()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                ) {
                    val landscape = maxWidth > maxHeight
                    if (landscape) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                            ControlPanel(
                                connectionState,
                                bondState,
                                mtuState,
                                statusText,
                                modelState,
                                modelReady,
                                voiceText,
                                voiceColorSource,
                                selectedColor,
                                showDiagBondRecovery,
                                showBondHint,
                                messageLog,
                                colorMapHeight = 92.dp,
                                onConnect,
                                onDisconnect,
                                onColorChanged,
                                onSendColor,
                                onSendDiagBond,
                                onVoice,
                                onBluetoothSettings,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight(),
                            )
                            ImuPanel(
                                imuRunning,
                                imuCalibrating,
                                imuText,
                                imuLogPath,
                                onStartImu,
                                onStopImu,
                                onCalibrateImu,
                                onBluetoothSettings,
                                modifier = Modifier
                                    .weight(0.8f)
                                    .fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            ControlPanel(
                                connectionState,
                                bondState,
                                mtuState,
                                statusText,
                                modelState,
                                modelReady,
                                voiceText,
                                voiceColorSource,
                                selectedColor,
                                showDiagBondRecovery,
                                showBondHint,
                                messageLog,
                                colorMapHeight = 86.dp,
                                onConnect,
                                onDisconnect,
                                onColorChanged,
                                onSendColor,
                                onSendDiagBond,
                                onVoice,
                                onBluetoothSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1.15f),
                            )
                            ImuPanel(
                                imuRunning,
                                imuCalibrating,
                                imuText,
                                imuLogPath,
                                onStartImu,
                                onStopImu,
                                onCalibrateImu,
                                onBluetoothSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    connectionState: String,
    bondState: String,
    mtuState: String,
    statusText: String,
    modelState: String,
    modelReady: Boolean,
    voiceText: String,
    voiceColorSource: String,
    selectedColor: Int,
    showDiagBondRecovery: Boolean,
    showBondHint: Boolean,
    messageLog: List<String>,
    colorMapHeight: Dp,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onColorChanged: (Int) -> Unit,
    onSendColor: () -> Unit,
    onSendDiagBond: () -> Unit,
    onVoice: () -> Unit,
    onBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("BLE1507", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Spresense light + motion", color = Color(0xFFB9C4DD), fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Connect", onClick = onConnect, modifier = Modifier.width(96.dp))
                SecondaryButton("Disconnect", onClick = onDisconnect, modifier = Modifier.width(96.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            InfoChip(connectionState, Modifier.weight(1f))
            InfoChip("pairing: $bondState", Modifier.weight(1f))
            InfoChip("mtu: $mtuState", Modifier.weight(0.72f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            // (diag bond button removed – available via Bond Recovery panel when needed)
        }
        Spacer(Modifier.height(8.dp))
        if (showDiagBondRecovery || showBondHint) {
            BondRecoveryPanel(
                showDiagBondRecovery = showDiagBondRecovery,
                showBondHint = showBondHint,
                onSendDiagBond = onSendDiagBond,
                onBluetoothSettings = onBluetoothSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text("LED PWM", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 36.dp)
                    .background(Color(selectedColor), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(8.dp))
            InfoChip("RGB: ${selectedColor.toRgbCodeLabel()}", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSendColor,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF3F68FF), RoundedCornerShape(14.dp)),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "送信",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoChip("PWM duty: ${selectedColor.toPwmDuty().label}", Modifier.fillMaxWidth(), fontSize = 11)
        Spacer(Modifier.height(6.dp))
        ColorMap(selectedColor = selectedColor, onColorChanged = onColorChanged, mapHeight = colorMapHeight)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MicButton(onClick = onVoice, enabled = modelReady)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(modelState, Modifier.fillMaxWidth(), fontSize = 11)
                InfoChip(voiceColorSource, Modifier.fillMaxWidth(), fontSize = 11)
                InfoChip(voiceText, Modifier.fillMaxWidth(), fontSize = 11)
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoChip("status: $statusText", Modifier.fillMaxWidth(), fontSize = 11)
    }
}

@Composable
private fun BondRecoveryPanel(
    showDiagBondRecovery: Boolean,
    showBondHint: Boolean,
    onSendDiagBond: () -> Unit,
    onBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0x33FFB347), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x66FFB347), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Bond Recovery", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (showDiagBondRecovery) {
            InfoChip("WARN repeated 0x1f を受信: diag bond 実行とペアリング解除 + 再接続を推奨", Modifier.fillMaxWidth(), fontSize = 11)
        }
        if (showBondHint) {
            InfoChip("手順 1: Android 側で BLE1507 のペアリング削除", Modifier.fillMaxWidth(), fontSize = 11)
            InfoChip("手順 2: 再接続", Modifier.fillMaxWidth(), fontSize = 11)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton("diag bond 実行", onClick = onSendDiagBond, modifier = Modifier.weight(1f))
            SecondaryButton("BT Settings", onClick = onBluetoothSettings, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MessageLogPanel(messageLog: List<String>, modifier: Modifier = Modifier) {
    val logText = if (messageLog.isEmpty()) {
        "notify log: --"
    } else {
        buildString {
            append("notify log:\n")
            messageLog.asReversed().forEach { entry ->
                append(entry)
                append('\n')
            }
        }.trimEnd()
    }
    val scrollState = rememberScrollState()
    InfoChip(
        text = logText,
        modifier = modifier
            .heightIn(min = 96.dp, max = 156.dp)
            .verticalScroll(scrollState),
        fontSize = 11,
    )
}

@Composable
private fun ImuPanel(
    imuRunning: Boolean,
    imuCalibrating: Boolean,
    imuText: String,
    imuLogPath: String,
    onStartImu: () -> Unit,
    onStopImu: () -> Unit,
    onCalibrateImu: () -> Unit,
    onBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier = modifier) {
        Text("IMU Attitude (JNI)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton("IMU Start", onClick = onStartImu, modifier = Modifier.weight(1f))
            SecondaryButton("IMU Stop", onClick = onStopImu, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        SecondaryButton(
            if (imuCalibrating) "Finish Calibration" else "Start Calibration",
            onClick = onCalibrateImu,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        InfoChip(if (imuRunning) "state: running" else "state: stopped", Modifier.fillMaxWidth(), fontSize = 11)
        Spacer(Modifier.height(6.dp))
        InfoChip(imuLogPath, Modifier.fillMaxWidth(), fontSize = 11)
        Spacer(Modifier.height(8.dp))
        InfoChip(
            text = imuText,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            fontSize = 13,
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Bluetooth Settings", onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(Color(0x9910192E), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
            .padding(10.dp),
        content = content,
    )
}

@Composable
private fun InfoChip(text: String, modifier: Modifier = Modifier, fontSize: Int = 13) {
    Text(
        text = text,
        color = Color(0xFFE2E9F4),
        fontSize = fontSize.sp,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F68FF), contentColor = Color.White),
        modifier = modifier.height(40.dp),
    ) {
        Text(text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MicButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2C86FF),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF465167),
            disabledContentColor = Color(0xFFB9C4DD),
        ),
        modifier = modifier
            .width(110.dp)
            .height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        )
        {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(if (enabled) "Voice" else "Loading", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color(0xFFE4EAF8)),
        modifier = modifier.height(40.dp),
    ) {
        Text(text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColorMap(selectedColor: Int, onColorChanged: (Int) -> Unit, mapHeight: Dp) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(size) { buildColorMapBitmap(size.width, size.height) }

    fun colorAt(offset: Offset): Int {
        val width = (size.width - 1).coerceAtLeast(1)
        val height = (size.height - 1).coerceAtLeast(1)
        val x = offset.x.coerceIn(0f, width.toFloat())
        val y = offset.y.coerceIn(0f, height.toFloat())
        val hue = 360f * x / width
        val saturation = y / height
        return AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(mapHeight)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .onSizeChanged { size = it }
            .pointerInput(size) { detectTapGestures { onColorChanged(colorAt(it)) } }
            .pointerInput(size) {
                detectDragGestures(
                    onDragStart = { onColorChanged(colorAt(it)) },
                    onDrag = { change, _ -> onColorChanged(colorAt(change.position)) },
                )
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(selectedColor, hsv)
            val marker = Offset(
                x = size.width * hsv[0] / 360f,
                y = size.height * hsv[1],
            )
            drawCircle(Color.Black, radius = 13.dp.toPx(), center = marker, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.White, radius = 10.dp.toPx(), center = marker, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
private fun StageBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF090E1C), Color(0xFF191D3A), Color(0xFF0B262D)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        val stars = listOf(
            Offset(0.08f, 0.12f),
            Offset(0.18f, 0.28f),
            Offset(0.32f, 0.09f),
            Offset(0.55f, 0.19f),
            Offset(0.72f, 0.10f),
            Offset(0.88f, 0.24f),
            Offset(0.94f, 0.42f),
            Offset(0.44f, 0.34f),
        )
        stars.forEachIndexed { index, star ->
            drawCircle(
                Color.White.copy(alpha = 0.34f),
                radius = if (index % 3 == 0) 2.2.dp.toPx() else 1.4.dp.toPx(),
                center = Offset(size.width * star.x, size.height * star.y),
            )
        }
        drawCircle(Color(0x6650BEFF), radius = size.width * 0.36f, center = Offset(size.width * 0.18f, size.height * 0.92f))
        drawCircle(Color(0x55FF49A8), radius = size.width * 0.32f, center = Offset(size.width * 0.84f, size.height * 0.88f))
    }
}

private data class Duty(val r: Int, val g: Int, val b: Int) {
    val label: String = "$r,$g,$b"
}

private val Int.hexCode: String
    get() = String.format(Locale.US, "#%02X%02X%02X", AndroidColor.red(this), AndroidColor.green(this), AndroidColor.blue(this))

private fun Int.toRgbCodeLabel(): String = "${AndroidColor.red(this)},${AndroidColor.green(this)},${AndroidColor.blue(this)} ($hexCode)"

private fun Int.toPwmDuty(): Duty = Duty(
    r = channelToDuty(AndroidColor.red(this)),
    g = channelToDuty(AndroidColor.green(this)),
    b = channelToDuty(AndroidColor.blue(this)),
)

private fun Int.toInterpretedColor(): InterpretedColor = InterpretedColor(
    r = AndroidColor.red(this),
    g = AndroidColor.green(this),
    b = AndroidColor.blue(this),
    source = "current",
)

private fun InterpretedColor.toPwmDuty(): Duty = Duty(
    r = channelToDuty(r),
    g = channelToDuty(g),
    b = channelToDuty(b),
)

private val InterpretedColor.label: String
    get() = "$r,$g,$b (${String.format(Locale.US, "#%02X%02X%02X", r, g, b)})"

private fun describeColorSource(source: String): String {
    val parenPart = Regex("\\(.*\\)").find(source)?.value?.let { " $it" } ?: ""
    return when {
        source.startsWith("rule") -> "rule-based"
        source.startsWith("qwen-failed") -> "Qwen failed$parenPart"
        source.startsWith("qwen-retry") -> "Qwen retry$parenPart"
        source.startsWith("qwen") -> "Qwen$parenPart"
        else -> source
    }
}

private fun describeSpeechRecognizerError(error: Int, offlineLanguagePackMissing: Boolean): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "音声エラー3: マイク録音に失敗しました"
    SpeechRecognizer.ERROR_CLIENT -> "音声エラー5: 音声認識クライアントを再起動してください"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "音声エラー9: マイク権限がありません"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "音声エラー12: 日本語オフライン音声パックが対応していません"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "音声エラー13: 日本語オフライン音声パックがインストールされていません"
    SpeechRecognizer.ERROR_NETWORK -> if (offlineLanguagePackMissing) {
        "音声エラー2: ネットワークを利用できず、日本語オフライン音声パックも見つかりません"
    } else {
        "音声エラー2: ネットワークに接続できません"
    }
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (offlineLanguagePackMissing) {
        "音声エラー1: オンライン認識がタイムアウトし、日本語オフライン音声パックも見つかりません"
    } else {
        "音声エラー1: オンライン音声認識がタイムアウトしました"
    }
    SpeechRecognizer.ERROR_NO_MATCH -> "音声エラー7: 発話を認識できませんでした"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声エラー8: 音声認識サービスが使用中です"
    SpeechRecognizer.ERROR_SERVER -> "音声エラー4: 音声認識サーバーでエラーが発生しました"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "音声エラー11: 音声認識サービスとの接続が切れました"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声エラー6: 発話を検出できませんでした"
    else -> "音声認識エラー: コード$error"
}

private fun buildColorMapBitmap(width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) {
        return null
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    val hsv = floatArrayOf(0f, 0f, 1f)
    for (y in 0 until height) {
        hsv[1] = y.toFloat() / (height - 1).coerceAtLeast(1)
        for (x in 0 until width) {
            hsv[0] = 360f * x.toFloat() / (width - 1).coerceAtLeast(1)
            pixels[y * width + x] = AndroidColor.HSVToColor(hsv)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

private const val DIAG_BOND_COMMAND = "diag bond"
private const val WARN_REPEATED_0X1F = "WARN repeated 0x1f: run diag bond"
private const val BOND_HINT_MESSAGE = "HINT clear Android bond + /mnt/spif/BLE1507_BONDINFO"
private const val MAX_LOG_MESSAGES = 24
private const val IMU_LOG_FILE_NAME = "imu-attitude-latest.csv"
private const val VOICE_LATENCY_FILE_NAME = "voice-e2e.csv"
private const val EXTRA_PREPARE_MODEL_DIRECTORY = "prepare_model_directory"
private const val EXHIBITION_PREFS = "spright_exhibition"
private const val PREF_MAINTAIN_BLE = "maintain_ble_connection"
private const val PREF_OFFLINE_SPEECH_PACK_STATE = "offline_speech_pack_state"
private const val PREF_TOUCHDESIGNER_HOST = "touchdesigner_host"
private const val PREF_TOUCHDESIGNER_PORT = "touchdesigner_port"
private const val DEFAULT_TOUCHDESIGNER_HOST = "192.168.1.100"
private const val DEFAULT_TOUCHDESIGNER_PORT = 12345
private const val SPEECH_PACK_STATE_NONE = "none"
private const val SPEECH_PACK_STATE_REQUESTED = "requested"
private const val SPEECH_PACK_STATE_READY = "ready"
private const val SYSTEM_UI_REHIDE_DELAY_MS = 500L
private const val IMU_POSE_UI_INTERVAL_MS = 16L
private const val IMU_DEBUG_UI_INTERVAL_MS = 100L
private const val IMU_RATE_WINDOW_MS = 1_000L
private const val MAX_EXHIBITION_GRADIENT_SECONDS = 5f
private const val VOICE_TRANSCRIPT_RENDER_DELAY_MS = 32L
private const val VISITOR_MESSAGE_DURATION_MS = 4_000L
private const val IMU_HEALTH_CHECK_INTERVAL_MS = 500L
private const val IMU_HEALTH_TIMEOUT_MS = 1_000L
private const val IMU_LOG_FLUSH_INTERVAL_MS = 1_000L
private const val STATIONARY_CALIBRATION_SECONDS = 5
private const val CALIBRATION_FIRST_SAMPLE_TIMEOUT_MS = 1_200L
private const val CALIBRATION_SAMPLE_STALE_TIMEOUT_MS = 750L
private const val CALIBRATION_MONITOR_INTERVAL_MS = 100L
private const val CALIBRATION_GRAPH_UPDATE_INTERVAL_MS = 50L
private const val MAX_CALIBRATION_GRAPH_SAMPLES = 300
