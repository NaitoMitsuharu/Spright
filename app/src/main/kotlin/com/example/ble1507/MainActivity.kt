package com.example.ble1507

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Ble1507Client.Listener {
    private lateinit var bleClient: Ble1507Client
    private lateinit var colorInterpreter: QwenColorInterpreter
    private lateinit var imuEstimator: ImuNativeAttitudeEstimator
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceListening = false
    private var offlineRetryAttempted = false
    private var pendingVoiceInputStart = false
    private var offlineLanguagePackMissing = false
    private var lastPartialText = ""
    private val speechTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var connectionState by mutableStateOf("disconnected")
    private var bondState by mutableStateOf("unknown")
    private var mtuState by mutableStateOf("--")
    private var statusText by mutableStateOf("ready")
    private var imuText by mutableStateOf("roll: --\npitch: --\nyaw: --\naccel: --")
    private var imuLogPath by mutableStateOf("log: --")
    private var selectedColor by mutableIntStateOf(AndroidColor.WHITE)
    private var imuRunning by mutableStateOf(false)
    private var imuCalibrating by mutableStateOf(false)
    private var voiceText by mutableStateOf("voice: --")
    private var voiceColorSource by mutableStateOf("color resolver: --")
    private var modelState by mutableStateOf("Qwen model: checking")
    private val messageLog = mutableStateListOf<String>()
    private var showDiagBondRecovery by mutableStateOf(false)
    private var showBondHint by mutableStateOf(false)
    private var imuLogWriter: BufferedWriter? = null

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleClient = Ble1507Client(this, this)
        colorInterpreter = QwenColorInterpreter(this)
        imuEstimator = ImuNativeAttitudeEstimator()
        bleClient.register()
        modelState = QwenModelStore.displayState(this)
        // Pre-load the Qwen model in the background so the first voice inference is fast.
        lifecycleScope.launch(Dispatchers.IO) { colorInterpreter.warmup() }
        setContent {
            Ble1507Screen(
                connectionState = connectionState,
                bondState = bondState,
                mtuState = mtuState,
                statusText = statusText,
                modelState = modelState,
                voiceText = voiceText,
                voiceColorSource = voiceColorSource,
                selectedColor = selectedColor,
                imuRunning = imuRunning,
                imuCalibrating = imuCalibrating,
                imuText = imuText,
                imuLogPath = imuLogPath,
                showDiagBondRecovery = showDiagBondRecovery,
                showBondHint = showBondHint,
                messageLog = messageLog,
                onConnect = ::connect,
                onDisconnect = ::disconnect,
                onColorChanged = { selectedColor = it },
                onSendColor = ::sendLedCommand,
                onSendDiagBond = ::sendDiagBond,
                onVoice = ::startVoiceInput,
                onStartImu = ::startImu,
                onStopImu = ::stopImu,
                onCalibrateImu = ::toggleCalibration,
                onBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        }
        hideSystemBars()
        requestRequiredPermissions()
    }

    override fun onDestroy() {
        cancelSpeechTimeout()
        releaseSpeechRecognizer()
        imuEstimator.stop()
        closeImuLog()
        bleClient.unregister()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        modelState = QwenModelStore.displayState(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            modelState = QwenModelStore.displayState(this)
            hideSystemBars()
        }
    }

    private fun connect() {
        if (!hasRequiredPermissions(includeAudio = false)) {
            requestRequiredPermissions()
            return
        }
        bleClient.scanPairAndConnect()
    }

    private fun disconnect() {
        bleClient.disconnect()
        imuEstimator.stop()
        closeImuLog()
        imuRunning = false
        imuCalibrating = false
    }

    private fun sendLedCommand() {
        val duty = selectedColor.toPwmDuty()
        bleClient.sendCommand(String.format(Locale.US, "led_pwm start -d %d,%d,%d", duty.r, duty.g, duty.b))
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
        bleClient.sendCommand("imu start")
        val started = imuEstimator.start()
        imuRunning = started
        imuCalibrating = false
        if (started) {
            openImuLog()
            statusText = "IMU started (JNI attitude enabled)"
        } else {
            closeImuLog()
            statusText = "IMU start failed (JNI unavailable)"
        }
    }

    private fun stopImu() {
        bleClient.sendCommand("imu stop")
        imuEstimator.stop()
        closeImuLog()
        imuRunning = false
        imuCalibrating = false
        statusText = "IMU stopped"
    }

    private fun toggleCalibration() {
        if (!imuRunning) {
            statusText = "Start IMU first"
            return
        }
        if (imuCalibrating) {
            val ok = imuEstimator.finishCalibration(restartEstimation = true)
            imuCalibrating = false
            statusText = if (ok) "Calibration completed" else "Calibration finish failed"
        } else {
            val started = imuEstimator.startCalibration()
            imuCalibrating = started
            statusText = if (started) "Calibration started; keep device still" else "Calibration start failed"
        }
    }

    private fun startVoiceInput() {
        if (!hasRequiredPermissions(includeBle = false)) {
            pendingVoiceInputStart = true
            statusText = "Requesting microphone permission..."
            requestRequiredPermissions()
            return
        }
        pendingVoiceInputStart = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText = "Speech recognition is unavailable"
            return
        }
        offlineRetryAttempted = false
        offlineLanguagePackMissing = false
        ensureSpeechRecognizer()
        if (isVoiceListening) {
            cancelSpeechTimeout()
            speechRecognizer?.cancel()
            isVoiceListening = false
        }
        startListeningWithRecovery()
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) {
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isVoiceListening = true
                    statusText = "Listening..."
                    voiceText = "voice: listening"
                    scheduleSpeechTimeout()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    cancelSpeechTimeout()
                    statusText = "Recognizing..."
                }

                override fun onError(error: Int) {
                    isVoiceListening = false
                    cancelSpeechTimeout()
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        offlineLanguagePackMissing = true
                    }
                    // Offline language pack is missing (error 13) — never retry with offline.
                    val isHardError = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    if (!offlineRetryAttempted && !isHardError) {
                        offlineRetryAttempted = true
                        startListeningWithRecovery(preferOffline = true)
                        return
                    }
                    statusText = describeSpeechRecognizerError(error, offlineLanguagePackMissing)
                    voiceText = "voice: --"
                    voiceColorSource = "color resolver: --"
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        recreateSpeechRecognizer()
                    }
                }

                override fun onResults(results: Bundle?) {
                    isVoiceListening = false
                    cancelSpeechTimeout()
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    android.util.Log.d("BLE1507V", "onResults bundle keys=${results?.keySet()}")
                    android.util.Log.d("BLE1507V", "onResults list=$list size=${list?.size}")
                    val text = list?.firstOrNull().orEmpty().ifBlank { lastPartialText }
                    lastPartialText = ""
                    android.util.Log.d("BLE1507V", "onResults text='$text' blank=${text.isBlank()}")
                    applyVoiceColor(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) lastPartialText = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun recreateSpeechRecognizer() {
        releaseSpeechRecognizer()
        ensureSpeechRecognizer()
    }

    private fun releaseSpeechRecognizer() {
        isVoiceListening = false
        cancelSpeechTimeout()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun startListeningWithRecovery() {
        startListeningWithRecovery(preferOffline = false)
    }

    private fun startListeningWithRecovery(preferOffline: Boolean) {
        val recognizer = speechRecognizer ?: run {
            ensureSpeechRecognizer()
            speechRecognizer
        } ?: return
        val intent = buildSpeechRecognizerIntent(preferOffline)
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            recreateSpeechRecognizer()
            runCatching {
                speechRecognizer?.startListening(intent)
            }.onFailure {
                isVoiceListening = false
                statusText = "Speech recognition could not start"
            }
        }
    }

    private fun buildSpeechRecognizerIntent(preferOffline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_200)
    }

    private fun scheduleSpeechTimeout() {
        cancelSpeechTimeout()
        speechTimeoutHandler.postDelayed({
            if (!isVoiceListening) {
                return@postDelayed
            }
            runCatching {
                speechRecognizer?.stopListening()
                statusText = "Recognizing..."
            }.onFailure {
                isVoiceListening = false
                statusText = "Voice input timed out"
                voiceText = "voice: --"
                voiceColorSource = "color resolver: --"
            }
        }, VOICE_FORCE_STOP_MS)
    }

    private fun cancelSpeechTimeout() {
        speechTimeoutHandler.removeCallbacksAndMessages(null)
    }

    private fun applyVoiceColor(text: String) {
        if (text.isBlank()) {
            statusText = "Voice result is empty"
            voiceText = "voice: --"
            voiceColorSource = "color resolver: --"
            return
        }
        voiceText = "voice: $text"
        statusText = "Resolving color..."
        voiceColorSource = "color resolver: ..."
        val current = selectedColor.toInterpretedColor()
        lifecycleScope.launch {
            val interpretedColor = withContext(Dispatchers.IO) {
                colorInterpreter.interpret(text, current)
            }
            if (interpretedColor == null) {
                statusText = "Color not recognized"
                voiceColorSource = "color resolver: --"
                return@launch
            }
            if (interpretedColor.source.startsWith("qwen-failed")) {
                val timing = interpretedColor.source.removePrefix("qwen-failed").trim()
                statusText = "Qwen: color recognition failed $timing"
                voiceColorSource = "Fail Qwen$timing"
                return@launch
            }
            val pwmDuty = interpretedColor.toPwmDuty()
            val newColor = interpretedColor.toColorInt()
            val unchanged = newColor == selectedColor
            selectedColor = newColor
            val hexCode = String.format(java.util.Locale.US, "#%02X%02X%02X",
                interpretedColor.r, interpretedColor.g, interpretedColor.b)
            val src = describeColorSource(interpretedColor.source)
            voiceColorSource = "$hexCode $src"
            statusText = if (unchanged) {
                "Color from ${interpretedColor.source}: RGB ${interpretedColor.label} (color unchanged)"
            } else {
                "Color from ${interpretedColor.source}: RGB ${interpretedColor.label} -> PWM ${pwmDuty.label}"
            }
            if (!unchanged) {
                bleClient.sendCommand(String.format(Locale.US, "led_pwm start -d %d,%d,%d", pwmDuty.r, pwmDuty.g, pwmDuty.b))
            }
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
        if (status == "disconnected") {
            imuEstimator.stop()
            closeImuLog()
            imuRunning = false
            imuCalibrating = false
            showDiagBondRecovery = false
            showBondHint = false
        }
    }

    override fun onMtuChanged(mtu: Int) {
        mtuState = mtu.toString()
    }

    override fun onImuSample(packet: ImuPacket) {
        val attitude = imuEstimator.update(packet)
        appendImuLog(packet, attitude)
        imuText = if (attitude == null) {
            String.format(
                Locale.US,
                "roll: --\npitch: --\nyaw: --\ntemp: %.2f C\ngyro:  x %.3f  y %.3f  z %.3f\naccel: x %.3f  y %.3f  z %.3f",
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
                "roll: %.2f deg\npitch: %.2f deg\nyaw: %.2f deg\ntemp: %.2f C\ngyro:  x %.3f  y %.3f  z %.3f\naccel: x %.3f  y %.3f  z %.3f",
                attitude.rollDeg,
                attitude.pitchDeg,
                attitude.yawDeg,
                packet.temp,
                packet.gx,
                packet.gy,
                packet.gz,
                packet.ax,
                packet.ay,
                packet.az,
            )
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

    private fun appendMessageLog(message: String) {
        if (message.isBlank()) {
            return
        }
        messageLog += message
        if (messageLog.size > MAX_LOG_MESSAGES) {
            messageLog.removeRange(0, messageLog.size - MAX_LOG_MESSAGES)
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
            write("ts_unix_ms,timestamp_ticks,temp,gx,gy,gz,ax,ay,az,roll,pitch,yaw\n")
            flush()
        }
    }

    private fun appendImuLog(packet: ImuPacket, attitude: AttitudeEstimate?) {
        val writer = imuLogWriter ?: return
        runCatching {
            val line = String.format(
                Locale.US,
                "%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s,%s\n",
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
            )
            writer.write(line)
            writer.flush()
        }
    }

    private fun closeImuLog() {
        runCatching { imuLogWriter?.flush() }
        runCatching { imuLogWriter?.close() }
        imuLogWriter = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
            MicButton(onClick = onVoice)
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
private fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C86FF), contentColor = Color.White),
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
            Text("Voice", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    r = (AndroidColor.red(this) * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
    g = (AndroidColor.green(this) * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
    b = (AndroidColor.blue(this) * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
)

private fun Int.toInterpretedColor(): InterpretedColor = InterpretedColor(
    r = AndroidColor.red(this),
    g = AndroidColor.green(this),
    b = AndroidColor.blue(this),
    source = "current",
)

private fun InterpretedColor.toPwmDuty(): Duty = Duty(
    r = (r * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
    g = (g * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
    b = (b * 100.0f / 255.0f).roundToInt().coerceIn(0, 100),
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
    SpeechRecognizer.ERROR_AUDIO -> "Voice error 3: audio recording error"
    SpeechRecognizer.ERROR_CLIENT -> "Voice error 5: client-side recognition error"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Voice error 9: microphone permission is missing"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Voice error 12: language not supported (offline model unavailable)"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Voice error 13: Japanese offline speech pack is unavailable"
    SpeechRecognizer.ERROR_NETWORK -> if (offlineLanguagePackMissing) {
        "Voice error 2: Japanese offline speech pack is missing and online speech is unavailable"
    } else {
        "Voice error 2: network error"
    }
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (offlineLanguagePackMissing) {
        "Voice error 1: Japanese offline speech pack is missing and online speech timed out"
    } else {
        "Voice error 1: network timeout"
    }
    SpeechRecognizer.ERROR_NO_MATCH -> "Voice error 7: no speech matched a recognizable command"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice error 8: recognizer is busy"
    SpeechRecognizer.ERROR_SERVER -> "Voice error 4: speech server error"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Voice error 11: speech recognition service disconnected"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Voice error 6: no speech input detected"
    else -> "Voice error $error"
}

private fun buildColorMapBitmap(width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) {
        return null
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val hsv = floatArrayOf(0f, 0f, 1f)
    for (y in 0 until height) {
        hsv[1] = y.toFloat() / (height - 1).coerceAtLeast(1)
        for (x in 0 until width) {
            hsv[0] = 360f * x.toFloat() / (width - 1).coerceAtLeast(1)
            bitmap.setPixel(x, y, AndroidColor.HSVToColor(hsv))
        }
    }
    return bitmap
}

private const val DIAG_BOND_COMMAND = "diag bond"
private const val WARN_REPEATED_0X1F = "WARN repeated 0x1f: run diag bond"
private const val BOND_HINT_MESSAGE = "HINT clear Android bond + /mnt/spif/BLE1507_BONDINFO"
private const val MAX_LOG_MESSAGES = 24
private const val VOICE_FORCE_STOP_MS = 7_000L
private const val IMU_LOG_FILE_NAME = "imu-attitude-latest.csv"