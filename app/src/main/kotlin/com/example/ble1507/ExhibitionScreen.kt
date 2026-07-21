package com.example.ble1507

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.google.android.filament.Colors
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node as SceneNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import io.github.sceneview.math.Position
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SprightExhibitionScreen(
    connectionState: String,
    statusText: String,
    modelState: String,
    modelReady: Boolean,
    selectedColor: Int,
    gradientDurationSeconds: Float,
    touchDesignerHost: String,
    touchDesignerPort: String,
    touchDesignerState: TouchDesignerConnectionState,
    voiceState: VoiceUiState,
    voiceText: String,
    voiceColorSource: String,
    voiceErrorMessage: String?,
    visitorMessage: String?,
    speechPackActionText: String,
    speechPackActionEnabled: Boolean,
    voiceRms: Float,
    voicePartialText: String,
    syncState: SyncUiState,
    imuPoseHealthy: Boolean,
    calibrationCountdown: Int,
    calibrationMessage: String,
    calibrationGraphSamples: List<ImuMagnitudeSample>,
    imuRunning: Boolean,
    attitude: AttitudeEstimate,
    attitudeReference: AttitudeEstimate,
    imuText: String,
    imuLogPath: String,
    onBleToggle: () -> Unit,
    onSync: () -> Unit,
    onConfirmCalibration: () -> Unit,
    onRetryCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onGradientDurationChanged: (Float) -> Unit,
    onTouchDesignerHostChanged: (String) -> Unit,
    onTouchDesignerPortChanged: (String) -> Unit,
    onTouchDesignerToggle: () -> Unit,
    onApplyColor: (Int) -> Unit,
    onVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onResetDisplayedAttitude: () -> Unit,
    onStartScreenPinning: () -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    var showGradientSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapAtMs by remember { mutableLongStateOf(0L) }
    var emitterHintShown by remember { mutableStateOf(false) }
    var emitterHintVisible by remember { mutableStateOf(false) }
    val emitterHintAlpha by animateFloatAsState(
        targetValue = if (emitterHintVisible) 0.6f else 0f,
        animationSpec = tween(900),
        label = "emitterHintAlpha",
    )
    LaunchedEffect(voiceState, showColorPicker) {
        if (!emitterHintShown && voiceState == VoiceUiState.Idle && !showColorPicker) {
            delay(8_000)
            if (voiceState == VoiceUiState.Idle && !showColorPicker) {
                emitterHintShown = true
                emitterHintVisible = true
                delay(1_200)
                emitterHintVisible = false
            }
        }
    }
    MaterialTheme {
        Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                AnimatedStarfield()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    val heroWidth = if (landscape) maxWidth * 0.42f else maxWidth
                    val heroHeight = if (landscape) maxHeight * 0.74f else maxHeight * 0.58f

                    Text(
                        text = "SPRIGHT",
                        color = BRAND_TEXT_COLOR,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraLight,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 5.5.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 18.dp)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    val now = SystemClock.elapsedRealtime()
                                    logoTapCount = if (now - lastLogoTapAtMs <= LOGO_TAP_WINDOW_MS) {
                                        logoTapCount + 1
                                    } else {
                                        1
                                    }
                                    lastLogoTapAtMs = now
                                    if (logoTapCount >= DIAGNOSTIC_TAP_COUNT) {
                                        showDiagnostics = !showDiagnostics
                                        logoTapCount = 0
                                    }
                                }
                            },
                    )

                    BleStatusButton(
                        connectionState = connectionState,
                        onClick = onBleToggle,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp),
                    )
                    SyncStatusButton(
                        connectionState = connectionState,
                        imuPoseHealthy = imuPoseHealthy,
                        onClick = onSync,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(x = maxWidth * 0.25f - 22.dp, y = 15.dp),
                    )
                    SettingsStatusButton(
                        gradientDurationSeconds = gradientDurationSeconds,
                        touchDesignerState = touchDesignerState,
                        onClick = { showGradientSettings = !showGradientSettings },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 20.dp, top = 15.dp),
                    )

                    Box(
                        modifier = Modifier
                            .align(if (landscape) Alignment.Center else Alignment.Center)
                            .size(heroWidth, heroHeight),
                    ) {
                        PenlightHero(
                            color = selectedColor,
                            attitude = attitude,
                            attitudeReference = attitudeReference,
                            attitudeEnabled = imuPoseHealthy,
                            onEmitterTap = { showColorPicker = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = "タップして色を変更",
                            color = Color(0xFFD6E1EE),
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = 92.dp, y = (-96).dp)
                                .alpha(emitterHintAlpha),
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                            .then(
                                if (landscape) Modifier.padding(end = 28.dp)
                                else Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                            )
                            .width(if (landscape) 270.dp else maxWidth - 36.dp),
                    ) {
                        VoiceResult(
                            voiceState = voiceState,
                            voiceText = voiceText,
                            sourceText = voiceColorSource,
                            errorMessage = voiceErrorMessage,
                            visitorMessage = visitorMessage,
                            speechPackActionText = speechPackActionText,
                            speechPackActionEnabled = speechPackActionEnabled,
                            onOpenSpeechSettings = onOpenSpeechSettings,
                        )
                        Spacer(Modifier.height(18.dp))
                        VoiceButton(
                            state = voiceState,
                            enabled = modelReady,
                            onClick = onVoice,
                        )
                    }

                    if (showDiagnostics) {
                        DebugGlassPanel(
                            connectionState = connectionState,
                            selectedColor = selectedColor,
                            voiceState = voiceState,
                            voiceText = voiceText,
                            voiceColorSource = voiceColorSource,
                            syncState = syncState,
                            statusText = statusText,
                            modelState = modelState,
                            imuRunning = imuRunning,
                            imuText = imuText,
                            imuLogPath = imuLogPath,
                            speechPackActionText = speechPackActionText,
                            speechPackActionEnabled = speechPackActionEnabled,
                            onOpenSpeechSettings = onOpenSpeechSettings,
                            onResetDisplayedAttitude = onResetDisplayedAttitude,
                            onStartScreenPinning = onStartScreenPinning,
                            modifier = Modifier
                                .align(if (landscape) Alignment.BottomStart else Alignment.BottomStart)
                                .padding(
                                    start = 12.dp,
                                    bottom = if (landscape) 12.dp else 164.dp,
                                )
                                .width(if (landscape) 360.dp else 330.dp),
                        )
                    }
                }
            }
        }
    }

    if (showGradientSettings) {
        SettingsPopup(
            durationSeconds = gradientDurationSeconds,
            onDurationChanged = onGradientDurationChanged,
            poseResetEnabled = imuRunning && syncState == SyncUiState.Synced && imuPoseHealthy,
            onResetDisplayedAttitude = {
                onResetDisplayedAttitude()
                showGradientSettings = false
            },
            touchDesignerHost = touchDesignerHost,
            touchDesignerPort = touchDesignerPort,
            touchDesignerState = touchDesignerState,
            onTouchDesignerHostChanged = onTouchDesignerHostChanged,
            onTouchDesignerPortChanged = onTouchDesignerPortChanged,
            onTouchDesignerToggle = onTouchDesignerToggle,
            onDismiss = { showGradientSettings = false },
        )
    }
    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = selectedColor,
            gradientEnabled = gradientDurationSeconds > 0f,
            onDismiss = { showColorPicker = false },
            onConfirm = { target ->
                showColorPicker = false
                onApplyColor(target)
            },
        )
    }
    if (voiceState == VoiceUiState.Listening) {
        ListeningDialog(
            rms = voiceRms,
            partialText = voicePartialText,
            onCancel = onCancelVoice,
        )
    }
    when (syncState) {
        SyncUiState.Prompt -> CalibrationPrompt(
            onConfirm = onConfirmCalibration,
            onDismiss = onCancelCalibration,
        )
        SyncUiState.CountingDown -> CalibrationCountdownDialog(
            countdown = calibrationCountdown,
            message = calibrationMessage,
            samples = calibrationGraphSamples,
        )
        SyncUiState.MotionError -> CalibrationErrorDialog(
            message = calibrationMessage,
            onRetry = onRetryCalibration,
            onDismiss = onCancelCalibration,
        )
        else -> Unit
    }
}

@Composable
internal fun PenlightHero(
    color: Int,
    attitude: AttitudeEstimate,
    attitudeEnabled: Boolean,
    onEmitterTap: () -> Unit,
    modifier: Modifier = Modifier,
    attitudeReference: AttitudeEstimate = AttitudeEstimate(0f, 0f, 0f),
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = 0.78f)
        lookAt(Position(0f, 0f, 0f))
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    var spresensePivotNode by remember { mutableStateOf<SceneNode?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(view) {
        view.isTransparentPickingEnabled = true
        // SceneView applies its Cinematic preset in its own effect. Apply the
        // exhibition-specific softer bloom on the following frame.
        withFrameNanos { }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = true
            strength = 0.60f
            resolution = 512
            levels = 8
            threshold = true
            lensFlare = false
        }
    }
    LaunchedEffect(modelNode, color) {
        modelNode?.modelInstance?.materialInstances?.forEach { material ->
            val red = AndroidColor.red(color) / 255f
            val green = AndroidColor.green(color) / 255f
            val blue = AndroidColor.blue(color) / 255f
            when {
                material.name.contains("Emitter", ignoreCase = true) -> {
                    runCatching {
                        material.setParameter(
                            "baseColorFactor",
                            Colors.RgbaType.SRGB,
                            red,
                            green,
                            blue,
                            0.64f,
                        )
                    }.onFailure { Log.w(PENLIGHT_LOG_TAG, "Emitter base color update failed", it) }
                    runCatching {
                        material.setParameter("emissiveFactor", red * 1.4f, green * 1.4f, blue * 1.4f)
                    }.onFailure { Log.w(PENLIGHT_LOG_TAG, "Emitter emissive update failed", it) }
                }
                material.name.contains("Glow", ignoreCase = true) -> {
                    runCatching {
                        material.setParameter(
                            "baseColorFactor",
                            Colors.RgbaType.SRGB,
                            red,
                            green,
                            blue,
                            0.035f,
                        )
                    }.onFailure { Log.w(PENLIGHT_LOG_TAG, "Glow base color update failed", it) }
                    runCatching {
                        material.setParameter("emissiveFactor", red * 1.8f, green * 1.8f, blue * 1.8f)
                    }.onFailure { Log.w(PENLIGHT_LOG_TAG, "Glow emissive update failed", it) }
                }
            }
        }
    }
    val targetQuaternion = if (attitudeEnabled) {
        attitudeToSceneQuaternion(attitude, attitudeReference)
    } else {
        SceneQuaternion(0f, 0f, 0f, 1f)
    }
    val latestTargetQuaternion by rememberUpdatedState(targetQuaternion)
    LaunchedEffect(spresensePivotNode) {
        spresensePivotNode?.let { node ->
            var displayed = latestTargetQuaternion
            var previousFrameNanos = 0L
            while (true) {
                withFrameNanos { frameNanos ->
                    val deltaSeconds = if (previousFrameNanos == 0L) {
                        1f / 60f
                    } else {
                        ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    }
                    previousFrameNanos = frameNanos
                    val blend = (1f - exp(-deltaSeconds / ATTITUDE_SMOOTHING_SECONDS)).coerceIn(0f, 1f)
                    displayed = interpolateSceneQuaternion(displayed, latestTargetQuaternion, blend)
                    node.quaternion = Quaternion(displayed.x, displayed.y, displayed.z, displayed.w)
                }
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            view = view,
            isOpaque = false,
            renderQuality = RenderQuality.Cinematic,
            // The node hierarchy explicitly places the physical IMU pivot.
            // Automatic recentering would erase that child offset.
            autoCenterContent = false,
            autoFitContent = false,
            cameraNode = cameraNode,
            cameraManipulator = null,
            onTouchEvent = { event, _ ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val pickX = event.x.toInt().coerceAtLeast(0)
                    val pickY = (view.viewport.height - event.y.toInt()).coerceAtLeast(0)
                    view.pick(pickX, pickY, mainHandler) { result ->
                        val entityName = modelNode
                            ?.modelInstance
                            ?.asset
                            ?.getName(result.renderable)
                            .orEmpty()
                        if (BuildConfig.DEBUG) {
                            Log.d(PENLIGHT_LOG_TAG, "pick=$entityName entity=${result.renderable}")
                        }
                        if (entityName == "Emitter" || entityName == "GlowShell") {
                            onEmitterTap()
                        }
                    }
                    true
                } else {
                    false
                }
            },
        ) {
            Node(
                position = Position(y = SPRESENSE_PIVOT_WORLD_Y),
                isEditable = false,
                apply = {
                    name = "SpresensePivot"
                    isTouchable = false
                    isHittable = false
                    isSmoothTransformEnabled = false
                    spresensePivotNode = this
                },
            ) {
                rememberModelInstance(modelLoader, PENLIGHT_MODEL_PATH)?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        autoAnimate = false,
                        // Fixed camera framing keeps this physical size stable and
                        // preserves margins through combined roll/pitch/yaw.
                        scaleToUnits = PENLIGHT_SCALE_TO_UNITS,
                        // Center the GLB around its child origin, then place that
                        // center above the physical Spresense parent pivot.
                        centerOrigin = Position(),
                        position = Position(y = MODEL_CENTER_ABOVE_SPRESENSE_PIVOT_Y),
                        isEditable = false,
                        apply = {
                            name = "SprightRoot"
                            // Filament picking resolves the exact glTF entity name, so the
                            // broad SceneView collider must not accept handle taps.
                            isTouchable = false
                            isHittable = false
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    PENLIGHT_LOG_TAG,
                                    "renderables=${renderableNodes.joinToString { it.name.orEmpty() }}",
                                )
                            }
                            // Quaternion smoothing is performed once per render
                            // frame above; a second stage would add avoidable lag.
                            isSmoothTransformEnabled = false
                            onFrameError = { error -> loadError = error.message ?: "3D render error" }
                            modelNode = this
                        },
                    )
                }
            }
        }
        if (modelNode == null || loadError != null) {
            PenlightFallback(color = color, error = loadError)
        }
    }
}

@Composable
internal fun PenlightFallback(color: Int, error: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(240.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(color).copy(alpha = 0.95f),
                            Color(color).copy(alpha = 0.72f),
                            Color(0xFF121A27),
                        ),
                    ),
                    RoundedCornerShape(24.dp),
                )
                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text("3D fallback: $error", color = Color(0xFFFFB4A9), fontSize = 10.sp)
        }
    }
}

@Composable
private fun BleStatusButton(connectionState: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ready = connectionState == "ready"
    val processing = connectionState in setOf("scanning", "connecting", "connected")
    val pulse = rememberInfiniteTransition(label = "blePulse").animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "blePulseAlpha",
    ).value
    val color = when {
        ready -> Color(0xFF4ADEDE)
        processing -> Color(0xFFFFB85C).copy(alpha = pulse)
        else -> Color(0xFF7B8497)
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(58.dp)
            .background(
                if (ready) color.copy(alpha = 0.16f) else Color(0xB5101726),
                CircleShape,
            )
            .border(1.dp, color.copy(alpha = 0.75f), CircleShape),
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = "BLE接続切替", tint = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SettingsStatusButton(
    gradientDurationSeconds: Float,
    touchDesignerState: TouchDesignerConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touchDesignerConnected = touchDesignerState.status == TouchDesignerConnectionStatus.Connected
    val active = gradientDurationSeconds > 0f || touchDesignerConnected
    val color = if (active) Color(0xFFC8D7E9) else Color(0xFF697386)
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(48.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA8101726), CircleShape)
                .border(1.dp, color.copy(alpha = 0.58f), CircleShape),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "設定",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-3).dp)
                    .size(4.dp)
                    .background(Color(0xFFC8D7E9), CircleShape),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsPopup(
    durationSeconds: Float,
    onDurationChanged: (Float) -> Unit,
    poseResetEnabled: Boolean,
    onResetDisplayedAttitude: () -> Unit,
    touchDesignerHost: String,
    touchDesignerPort: String,
    touchDesignerState: TouchDesignerConnectionState,
    onTouchDesignerHostChanged: (String) -> Unit,
    onTouchDesignerPortChanged: (String) -> Unit,
    onTouchDesignerToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val popupOffset = with(density) {
        IntOffset(x = (-12).dp.roundToPx(), y = 72.dp.roundToPx())
    }
    Popup(
        alignment = Alignment.TopEnd,
        offset = popupOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Color(0xF2131C2B),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(282.dp)
                .border(1.dp, Color(0xFF9AAEC7).copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "SETTINGS",
                    color = Color(0xFF92A4BC),
                    fontSize = 8.sp,
                    letterSpacing = 1.4.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "グラデーション時間",
                    color = Color(0xFFB8C6D8),
                    fontSize = 10.sp,
                )
                Slider(
                    value = durationSeconds,
                    onValueChange = { onDurationChanged(it.coerceIn(0f, MAX_GRADIENT_DURATION_SECONDS)) },
                    valueRange = 0f..MAX_GRADIENT_DURATION_SECONDS,
                    steps = 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFD9E3EF),
                        activeTrackColor = Color(0xFFC2D0E1),
                        inactiveTrackColor = Color.White.copy(alpha = 0.13f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .semantics { contentDescription = "共通グラデーション時間" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0秒", color = Color(0xFF7F8EA4), fontSize = 9.sp)
                    Text(
                        "${MAX_GRADIENT_DURATION_SECONDS.roundToInt()}秒",
                        color = Color(0xFF7F8EA4),
                        fontSize = 9.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "姿勢",
                    color = Color(0xFF92A4BC),
                    fontSize = 8.sp,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(7.dp))
                Button(
                    onClick = onResetDisplayedAttitude,
                    enabled = poseResetEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color(0xFFD9E3EF),
                        disabledContainerColor = Color.White.copy(alpha = 0.035f),
                        disabledContentColor = Color(0xFF66748A),
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                ) {
                    Text("姿勢をリセット", fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "TOUCHDESIGNER",
                    color = Color(0xFF92A4BC),
                    fontSize = 8.sp,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(7.dp))
                val fieldsEnabled = touchDesignerState.status !in setOf(
                    TouchDesignerConnectionStatus.Connecting,
                    TouchDesignerConnectionStatus.Connected,
                )
                TouchDesignerTextField(
                    value = touchDesignerHost,
                    onValueChange = onTouchDesignerHostChanged,
                    label = "IP",
                    contentDescription = "TouchDesigner IP",
                    keyboardType = KeyboardType.Decimal,
                    enabled = fieldsEnabled,
                )
                Spacer(Modifier.height(7.dp))
                TouchDesignerTextField(
                    value = touchDesignerPort,
                    onValueChange = onTouchDesignerPortChanged,
                    label = "PORT",
                    contentDescription = "TouchDesigner Port",
                    keyboardType = KeyboardType.Number,
                    enabled = fieldsEnabled,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val statusColor = when (touchDesignerState.status) {
                        TouchDesignerConnectionStatus.Connected -> Color(0xFF4ADEDE)
                        TouchDesignerConnectionStatus.Connecting -> Color(0xFFFFB85C)
                        TouchDesignerConnectionStatus.Error -> Color(0xFFFFA69A)
                        TouchDesignerConnectionStatus.Disconnected -> Color(0xFF7F8EA4)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            touchDesignerState.message,
                            color = statusColor,
                            fontSize = 9.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onTouchDesignerToggle,
                        enabled = touchDesignerState.status != TouchDesignerConnectionStatus.Connecting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.10f),
                            contentColor = Color(0xFFD9E3EF),
                            disabledContainerColor = Color.White.copy(alpha = 0.05f),
                            disabledContentColor = Color(0xFF68758A),
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 13.dp,
                            vertical = 4.dp,
                        ),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(
                            if (touchDesignerState.status == TouchDesignerConnectionStatus.Connected) {
                                "切断"
                            } else {
                                "接続"
                            },
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchDesignerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    contentDescription: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label, fontSize = 9.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFFDCE6F2),
            unfocusedTextColor = Color(0xFFC2CDDC),
            disabledTextColor = Color(0xFF8794A7),
            focusedBorderColor = Color(0xFF9FB2C9).copy(alpha = 0.65f),
            unfocusedBorderColor = Color(0xFF7D8CA1).copy(alpha = 0.34f),
            disabledBorderColor = Color(0xFF5D6879).copy(alpha = 0.28f),
            focusedLabelColor = Color(0xFFADBED2),
            unfocusedLabelColor = Color(0xFF7F8EA4),
        ),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier
            .fillMaxWidth()
            // Material text fields need at least 56 dp for the baseline and
            // floating label; the former 50 dp fixed height clipped IP/Port.
            .heightIn(min = 58.dp)
            .semantics { this.contentDescription = contentDescription },
    )
}

@Composable
private fun SyncStatusButton(
    connectionState: String,
    imuPoseHealthy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = connectionState == "ready"
    val color = if (imuPoseHealthy) Color(0xFF4ADEDE) else Color(0xFF697386)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .background(
                if (imuPoseHealthy) color.copy(alpha = 0.16f) else Color(0xB5101726),
                CircleShape,
            )
            .border(1.dp, color.copy(alpha = 0.72f), CircleShape),
    ) {
        Icon(Icons.Filled.Sync, contentDescription = "IMU同期", tint = color, modifier = Modifier.size(23.dp))
    }
}

@Composable
internal fun VoiceButton(state: VoiceUiState, enabled: Boolean, onClick: () -> Unit) {
    val inputLocked = state !in setOf(VoiceUiState.Idle, VoiceUiState.Error)
    val showRecognitionProgress = state in setOf(
        VoiceUiState.Listening,
        VoiceUiState.Recognizing,
    )
    val stateLabel = when {
        !enabled -> "モデル準備中"
        state == VoiceUiState.Listening -> "音声入力中"
        state == VoiceUiState.Recognizing -> "音声認識中"
        state == VoiceUiState.Inferring -> "色を推論中"
        state == VoiceUiState.Sending -> "色を送信中"
        else -> "音声入力"
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        if (showRecognitionProgress) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.42f),
                strokeWidth = 1.5.dp,
                modifier = Modifier
                    .size(58.dp)
                    .semantics { contentDescription = "音声認識処理中" },
            )
        }
        IconButton(
            onClick = onClick,
            // LLM inference and BLE/gradient transmission do not show a ring,
            // but a second voice request must wait for the current operation.
            enabled = enabled && !inputLocked,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = stateLabel,
                tint = if (enabled && !inputLocked) {
                    Color.White.copy(alpha = 0.92f)
                } else {
                    Color.White.copy(alpha = 0.28f)
                },
                modifier = Modifier
                    .size(34.dp)
                    .offset(y = if (state == VoiceUiState.Sending) (-5).dp else 0.dp),
            )
        }
        if (state == VoiceUiState.Sending) {
            Text(
                text = "送信中…",
                color = Color(0xFF8CA0B8),
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 1.dp),
            )
        }
    }
}

@Composable
internal fun VoiceResult(
    voiceState: VoiceUiState,
    voiceText: String,
    sourceText: String,
    errorMessage: String?,
    visitorMessage: String?,
    speechPackActionText: String,
    speechPackActionEnabled: Boolean,
    onOpenSpeechSettings: () -> Unit,
) {
    val visibleMessage = errorMessage?.takeIf { it.isNotBlank() }
        ?: visitorMessage?.takeIf { it.isNotBlank() }
    val recognized = voiceText.removePrefix("voice: ")
        .takeIf { it !in setOf("--", "listening") && it.isNotBlank() }
    val details = sourceText.removePrefix("color resolver: ")
    val resolvedColor = remember(details) {
        Regex("#[0-9A-Fa-f]{6}").find(details)?.value?.let { code ->
            runCatching { AndroidColor.parseColor(code) }.getOrNull()
        }
    }
    val transcriptFontSize = when {
        recognized == null -> 17.sp
        recognized.length <= 16 -> 20.sp
        recognized.length <= 26 -> 18.sp
        else -> 16.sp
    }
    val transcriptMaxLines = if ((recognized?.length ?: 0) <= 24) 1 else 2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (
            !errorMessage.isNullOrBlank() &&
            (errorMessage.contains("オフライン") || errorMessage.contains("音声パック"))
        ) {
            GlassButton(
                text = speechPackActionText,
                onClick = onOpenSpeechSettings,
                enabled = speechPackActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                when {
                    visibleMessage != null -> Text(
                        text = visibleMessage,
                        color = Color(0xFFFFD1C7),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    recognized != null -> Text(
                        text = recognized,
                        color = VOICE_TEXT_COLOR,
                        fontSize = transcriptFontSize,
                        lineHeight = 23.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = transcriptMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )

                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
            ) {
                if (visibleMessage == null && resolvedColor != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(21.dp)
                                .background(Color(resolvedColor), RoundedCornerShape(5.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.48f), RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = Regex("#[0-9A-Fa-f]{6}")
                                .find(details)
                                ?.value
                                ?.uppercase(Locale.US)
                                .orEmpty(),
                            color = Color(0xFFD3DCEA),
                            fontSize = 13.sp,
                            letterSpacing = 1.15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                        val sourceBadge = when {
                            details.contains("rule", ignoreCase = true) -> "RULE"
                            details.contains("qwen", ignoreCase = true) ||
                                details.contains("llm", ignoreCase = true) -> "AI"
                            else -> null
                        }
                        sourceBadge?.let { ResolverBadge(it) }
                    }
                } else if (visibleMessage == null && voiceState == VoiceUiState.Inferring && recognized != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .semantics { contentDescription = "カラー推論中" },
                            strokeWidth = 1.8.dp,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "色を考えています…",
                            color = Color(0xFF9DABC0),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolverBadge(label: String) {
    Text(
        text = label,
        color = Color(0xFF92A4BC),
        fontSize = 7.sp,
        letterSpacing = 1.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(
                width = 1.dp,
                color = Color(0xFF92A4BC).copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ColorPickerDialog(
    currentColor: Int,
    gradientEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // The hue/saturation map stays value=1, so brightness is tracked separately
    // and recombined into the final target color. This lets the picker reach
    // black and dark colors that a value=1 map alone cannot express.
    val startHsv = remember(currentColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(currentColor, it) }
    }
    var mapHue by remember(currentColor) { mutableFloatStateOf(startHsv[0]) }
    var mapSaturation by remember(currentColor) { mutableFloatStateOf(startHsv[1]) }
    var brightness by remember(currentColor) { mutableFloatStateOf(startHsv[2]) }
    val targetColor = AndroidColor.HSVToColor(floatArrayOf(mapHue, mapSaturation, brightness))
    val mapColor = AndroidColor.HSVToColor(floatArrayOf(mapHue, mapSaturation, 1f))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = Color(0xF5121B2C),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp)),
        ) {
            Column(Modifier.padding(20.dp)) {
                ColorComparison(currentColor, targetColor, gradientEnabled)
                Spacer(Modifier.height(16.dp))
                ExhibitionColorMap(mapColor, brightness) { picked ->
                    val hsv = FloatArray(3)
                    AndroidColor.colorToHSV(picked, hsv)
                    mapHue = hsv[0]
                    mapSaturation = hsv[1]
                }
                Spacer(Modifier.height(10.dp))
                BrightnessSlider(brightness) { brightness = it }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = "キャンセル",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    BlueButton(
                        text = "OK",
                        onClick = { onConfirm(targetColor) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrightnessSlider(brightness: Float, onBrightnessChanged: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("明るさ", color = Color(0xFF9DAAC0), fontSize = 10.sp)
            if (brightness <= 0f) {
                Text("消灯", color = Color(0xFF7F8EA4), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text(
                    String.format(Locale.US, "%d%%", (brightness * 100f).roundToInt()),
                    color = Color(0xFF9DAAC0),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Slider(
            value = brightness,
            onValueChange = { onBrightnessChanged(it.coerceIn(0f, 1f)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFD9E3EF),
                activeTrackColor = Color(0xFFC2D0E1),
                inactiveTrackColor = Color.White.copy(alpha = 0.13f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .semantics { contentDescription = "明るさ" },
        )
    }
}

@Composable
private fun ColorComparison(current: Int, target: Int, gradient: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CURRENT  ${current.hexLabel}", color = Color(0xFF9DAAC0), fontSize = 10.sp)
            Text("TARGET  ${target.hexLabel}", color = Color(0xFF9DAAC0), fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (gradient) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.horizontalGradient(
                            (0..12).map { interpolateHsvShortest(current, target, it / 12f).let(::Color) },
                        ),
                        RoundedCornerShape(14.dp),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(current, Modifier.weight(1f))
                Text("→", color = Color(0xFFD7E0F0), fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
                ColorSwatch(target, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .background(Color(color), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
    )
}

@Composable
private fun ExhibitionColorMap(
    selectedColor: Int,
    brightness: Float,
    onColorChanged: (Int) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(size) { buildExhibitionColorMap(size.width, size.height) }
    fun colorAt(offset: Offset): Int {
        val width = (size.width - 1).coerceAtLeast(1)
        val height = (size.height - 1).coerceAtLeast(1)
        return AndroidColor.HSVToColor(
            floatArrayOf(
                360f * offset.x.coerceIn(0f, width.toFloat()) / width,
                offset.y.coerceIn(0f, height.toFloat()) / height,
                1f,
            ),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .onSizeChanged { size = it }
            .pointerInput(size) { detectTapGestures { onColorChanged(colorAt(it)) } }
            .pointerInput(size) {
                detectDragGestures(
                    onDragStart = { onColorChanged(colorAt(it)) },
                    onDrag = { change, _ -> onColorChanged(colorAt(change.position)) },
                )
            },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(selectedColor, hsv)
            // The bitmap is generated at value=1, so the brightness slider is
            // reflected as a scrim instead of regenerating the map.
            drawRect(Color.Black.copy(alpha = (1f - brightness) * 0.82f))
            // The marker is drawn after the scrim so it stays visible on dark
            // selections, and its center is clamped inside the map so the ring is
            // never clipped at the edges (for example white, where saturation=0).
            val inset = 13.dp.toPx()
            val marker = Offset(
                (size.width * hsv[0] / 360f)
                    .coerceIn(inset, (size.width - inset).coerceAtLeast(inset)),
                (size.height * hsv[1])
                    .coerceIn(inset, (size.height - inset).coerceAtLeast(inset)),
            )
            drawCircle(Color.Black, 12.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 9.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        }
    }
}

@Composable
internal fun ListeningDialog(
    rms: Float,
    partialText: String = "",
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val level = (rms.coerceIn(0f, 12f) / 12f)
        val equalizerPhase by rememberInfiniteTransition(label = "voiceEqualizer")
            .animateFloat(
                initialValue = 0f,
                targetValue = (2f * PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(1_150, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "voiceEqualizerPhase",
            )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .background(Color(0xEF111B2D), RoundedCornerShape(28.dp))
                .border(1.dp, Color(0x665AA7FF), RoundedCornerShape(28.dp))
                .padding(28.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // The equalizer always draws inside this fixed canvas, so RMS
                    // changes never resize the dialog or move its text.
                    .size(112.dp)
                    .semantics { contentDescription = "音声入力レベル" },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = this.center
                    val ringRadius = 39.dp.toPx()
                    val quietLength = 3.dp.toPx()
                    val activeLength = 10.dp.toPx() * level
                    val barCount = 40
                    repeat(barCount) { index ->
                        val angle = 2.0 * PI * index / barCount
                        val wave = 0.5f + 0.5f * sin((angle * 3.0 + equalizerPhase).toFloat())
                        val length = quietLength + activeLength * (0.35f + 0.65f * wave)
                        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(
                            color = Color.White.copy(alpha = 0.35f + level * 0.55f),
                            start = center + direction * ringRadius,
                            end = center + direction * (ringRadius + length),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(
                        color = Color.White.copy(alpha = 0.34f),
                        radius = ringRadius - 4.dp.toPx(),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text("色のイメージを話してください", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("入力中…", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                if (partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        color = VOICE_TEXT_COLOR,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("例：夕焼けのような色、もう少し赤っぽく", color = Color(0xFF9DABC0), fontSize = 11.sp)
            Spacer(Modifier.height(16.dp))
            GlassButton(
                text = "キャンセル",
                onClick = onCancel,
                modifier = Modifier
                    .width(120.dp)
                    .height(40.dp),
            )
        }
    }
}

@Composable
internal fun CalibrationPrompt(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CalibrationDialogShell {
        PenlightPlacementAnimation()
        Spacer(Modifier.height(10.dp))
        Text("ペンライトを机に置いてください", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "水平な場所で動かさず、5秒間静止させます。",
            color = Color(0xFFB6C2D5),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("キャンセル", onDismiss, modifier = Modifier.weight(1f))
            BlueButton("OK", onConfirm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PenlightPlacementAnimation() {
    val placement by rememberInfiniteTransition(label = "penlightPlacement")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "penlightPlacementProgress",
        )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .semantics { contentDescription = "ペンライトを水平な机へ置くアニメーション" },
    ) {
        val tableY = size.height * 0.78f
        val penlightCenter = Offset(
            x = size.width * 0.5f,
            y = tableY - 10.dp.toPx() - (1f - placement) * 12.dp.toPx(),
        )
        drawLine(
            color = Color(0xFF8190A8).copy(alpha = 0.42f),
            start = Offset(size.width * 0.08f, tableY),
            end = Offset(size.width * 0.92f, tableY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.06f),
            start = Offset(size.width * 0.18f, tableY + 4.dp.toPx()),
            end = Offset(size.width * 0.82f, tableY + 4.dp.toPx()),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        rotate(
            degrees = -27f * (1f - placement),
            pivot = penlightCenter,
        ) {
            val left = size.width * 0.15f
            val right = size.width * 0.85f
            val handleRight = size.width * 0.36f
            val tubeLeft = size.width * 0.33f
            val bodyTop = penlightCenter.y - 7.dp.toPx()
            val bodyHeight = 14.dp.toPx()
            val glowInset = 7.dp.toPx()
            drawRoundRect(
                color = Color(0xFFBBD8FF).copy(alpha = 0.06f),
                topLeft = Offset(tubeLeft - glowInset, bodyTop - glowInset),
                size = Size(right - tubeLeft + glowInset * 2f, bodyHeight + glowInset * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFFDAE9FF).copy(alpha = 0.16f),
                topLeft = Offset(tubeLeft - 3.dp.toPx(), bodyTop - 3.dp.toPx()),
                size = Size(right - tubeLeft + 6.dp.toPx(), bodyHeight + 6.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF202A3A),
                topLeft = Offset(left, bodyTop - 1.dp.toPx()),
                size = Size(handleRight - left, bodyHeight + 2.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFFE5EDF8),
                topLeft = Offset(tubeLeft, bodyTop),
                size = Size(right - tubeLeft, bodyHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.72f),
                start = Offset(tubeLeft + 10.dp.toPx(), bodyTop + 3.dp.toPx()),
                end = Offset(right - 9.dp.toPx(), bodyTop + 3.dp.toPx()),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF92A2B8),
                start = Offset(handleRight, bodyTop),
                end = Offset(handleRight, bodyTop + bodyHeight),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

@Composable
internal fun CalibrationCountdownDialog(
    countdown: Int,
    message: String,
    samples: List<ImuMagnitudeSample>,
) {
    CalibrationDialogShell(dismissible = false) {
        Text(countdown.coerceAtLeast(0).toString(), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text("そのまま動かさないでください", color = Color(0xFFBDD0E8), fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        CalibrationMagnitudeGraph(samples)
        if (message.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(message, color = Color(0xFF7F91AA), fontSize = 10.sp)
        }
    }
}

@Composable
internal fun CalibrationMagnitudeGraph(samples: List<ImuMagnitudeSample>) {
    val accelColor = Color(0xFFB7D9FF)
    val gyroColor = Color(0xFFD3C7FF)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "IMU加速度・ジャイロ大きさグラフ" },
    ) {
        val corner = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
        drawRoundRect(
            color = Color.White.copy(alpha = 0.035f),
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = corner,
        )
        repeat(3) { index ->
            val y = size.height * index / 2f
            drawLine(
                color = Color.White.copy(alpha = 0.055f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        if (samples.size < 2) return@Canvas
        // The two signals use different physical units and ranges. Scale each
        // independently so both remain visible in the same compact plot.
        val accelMax = maxOf(12f, samples.maxOf { it.accelMagnitude } * 1.08f)
        val gyroMax = maxOf(0.20f, samples.maxOf { it.gyroMagnitude } * 1.08f)

        fun drawMagnitudePath(
            value: (ImuMagnitudeSample) -> Float,
            maximum: Float,
            color: Color,
        ) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = (sample.elapsedMs.toFloat() / 5_000f)
                    .coerceIn(0f, 1f) * size.width
                val normalized = (value(sample) / maximum).coerceIn(0f, 1f)
                val y = size.height - normalized * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        drawMagnitudePath({ it.accelMagnitude }, accelMax, accelColor)
        drawMagnitudePath({ it.gyroMagnitude }, gyroMax, gyroColor)
    }
}

@Composable
private fun CalibrationErrorDialog(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    CalibrationDialogShell {
        Text(
            "動きを検出しました。もう一度置いてください",
            color = Color(0xFFFF9A91),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFFBDC8D8), fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("キャンセル", onDismiss, modifier = Modifier.weight(1f))
            BlueButton("再試行", onRetry, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CalibrationDialogShell(
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
                .background(Color(0xF5121B2C), RoundedCornerShape(28.dp))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            content = content,
        )
    }
}

@Composable
private fun BlueButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF316DFF)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(46.dp),
    ) { Text(text) }
}

@Composable
private fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(46.dp),
    ) { Text(text) }
}

@Composable
private fun DebugGlassPanel(
    connectionState: String,
    selectedColor: Int,
    voiceState: VoiceUiState,
    voiceText: String,
    voiceColorSource: String,
    syncState: SyncUiState,
    statusText: String,
    modelState: String,
    imuRunning: Boolean,
    imuText: String,
    imuLogPath: String,
    speechPackActionText: String,
    speechPackActionEnabled: Boolean,
    onOpenSpeechSettings: () -> Unit,
    onResetDisplayedAttitude: () -> Unit,
    onStartScreenPinning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recognized = voiceText.removePrefix("voice: ")
        .takeUnless { it in setOf("--", "listening") || it.isBlank() }
    Column(
        modifier = modifier
            .background(Color(0xE6101828), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFF9BB0CB).copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "DEVELOPER",
                color = Color(0xFFD6E0EE),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("LIVE", color = Color(0xFF58E391), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DiagnosticStatusChip(
                label = "BLE",
                value = connectionState.uppercase(Locale.US),
                active = connectionState == "ready",
                modifier = Modifier.weight(1f),
            )
            DiagnosticStatusChip(
                label = "IMU",
                value = if (imuRunning) "RUN" else "OFF",
                active = imuRunning,
                modifier = Modifier.weight(1f),
            )
            DiagnosticStatusChip(
                label = "POSE",
                value = syncState.name.uppercase(Locale.US),
                active = syncState == SyncUiState.Synced,
                modifier = Modifier.weight(1f),
            )
        }

        DiagnosticSectionTitle("COLOR")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(Color(selectedColor), RoundedCornerShape(7.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                selectedColor.hexLabel,
                color = Color(0xFFE0E7F1),
                fontSize = 13.sp,
                letterSpacing = 1.3.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        DiagnosticSectionTitle("VOICE / COLOR RESOLUTION")
        Text(
            text = "STATE  $voiceState",
            color = Color(0xFF91A3BB),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = recognized?.let { "「$it」" } ?: "認識結果なし",
            color = if (recognized == null) Color(0xFF718098) else VOICE_TEXT_COLOR,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 2,
        )
        Text(
            text = voiceColorSource,
            color = Color(0xFFB8C6D9),
            fontSize = 9.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )

        DiagnosticSectionTitle("IMU STREAM")
        Text(
            text = imuText,
            color = Color(0xFFC8D4E5),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.heightIn(max = 118.dp),
        )
        Text(
            text = imuLogPath,
            color = Color(0xFF708198),
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )

        DiagnosticSectionTitle("SYSTEM")
        Text(statusText, color = Color(0xFFB7C5D7), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
        Text(modelState, color = Color(0xFF8191A8), fontSize = 8.sp, lineHeight = 11.sp, maxLines = 2)

        Spacer(Modifier.height(10.dp))
        DiagnosticAction(
            text = speechPackActionText,
            onClick = onOpenSpeechSettings,
            enabled = speechPackActionEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        DiagnosticAction(
            text = "現在の姿勢を表示基準にする",
            onClick = onResetDisplayedAttitude,
            enabled = imuRunning && syncState == SyncUiState.Synced,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        DiagnosticAction(
            text = "画面固定を開始",
            onClick = onStartScreenPinning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticSectionTitle(text: String) {
    Spacer(Modifier.height(11.dp))
    Text(
        text = text,
        color = Color(0xFF6F829D),
        fontSize = 8.sp,
        letterSpacing = 1.2.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun DiagnosticStatusChip(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (active) Color(0xFF58E391) else Color(0xFF77859A)
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color(0xFF71829A), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
        Text(
            value,
            color = accent,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiagnosticAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            disabledContainerColor = Color.White.copy(alpha = 0.045f),
            disabledContentColor = Color(0xFF75849A),
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(36.dp),
    ) {
        Text(text, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun AnimatedStarfield() {
    val transition = rememberInfiniteTransition(label = "starfield")
    val twinklePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Restart),
        label = "starTwinkle",
    )
    val farStars = remember { seededStars(46, 0x51A2) }
    val nearStars = remember { seededStars(24, 0xB1507) }
    Canvas(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050811), Color(0xFF0A1020), Color(0xFF11172A)),
                ),
            ),
    ) {
        farStars.forEachIndexed { index, star ->
            drawCircle(
                Color.White.copy(alpha = 0.22f + (index % 4) * 0.05f),
                0.75.dp.toPx(),
                Offset(star.x * size.width, star.y * size.height),
            )
        }
        nearStars.forEachIndexed { index, star ->
            val position = Offset(
                star.x * size.width,
                star.y * size.height,
            )
            val twinkle = if (index % 4 == 0) {
                ((sin(twinklePhase + index * 1.73f) + 1f) * 0.5f).pow(10f)
            } else {
                0f
            }
            if (twinkle > 0.01f) {
                drawCircle(
                    Color.White.copy(alpha = 0.025f * twinkle),
                    (9f + twinkle * 5f).dp.toPx(),
                    position,
                )
                drawCircle(
                    Color(0xFFDCEBFF).copy(alpha = 0.08f * twinkle),
                    (4f + twinkle * 3f).dp.toPx(),
                    position,
                )
            }
            drawCircle(
                Color.White.copy(alpha = 0.48f + (index % 3) * 0.12f + twinkle * 0.22f),
                if (index % 5 == 0) {
                    (1.65f + twinkle * 0.8f).dp.toPx()
                } else {
                    (1.05f + twinkle * 0.5f).dp.toPx()
                },
                position,
            )
        }
    }
}

private fun seededStars(count: Int, seed: Int): List<Offset> {
    var state = seed.toLong()
    fun next(): Float {
        state = (state * 1_103_515_245L + 12_345L) and 0x7fff_ffff
        return state.toFloat() / 0x7fff_ffff
    }
    return List(count) { Offset(next(), next()) }
}

private fun buildExhibitionColorMap(width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    val hsv = floatArrayOf(0f, 0f, 1f)
    for (y in 0 until height) {
        hsv[1] = y.toFloat() / (height - 1).coerceAtLeast(1)
        for (x in 0 until width) {
            hsv[0] = 360f * x / (width - 1).coerceAtLeast(1)
            pixels[y * width + x] = AndroidColor.HSVToColor(hsv)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

private val Int.hexLabel: String
    get() = String.format(
        Locale.US,
        "#%02X%02X%02X",
        AndroidColor.red(this),
        AndroidColor.green(this),
        AndroidColor.blue(this),
    )

private const val PENLIGHT_MODEL_PATH = "models/spright_penlight.glb"
private const val PENLIGHT_LOG_TAG = "Spright3D"
// 0.31 is slightly larger than the original 0.30 presentation while keeping
// the tip inside the full-width portrait viewport when the handle-mounted IMU
// pivot rotates the model close to 90 degrees.
private const val PENLIGHT_SCALE_TO_UNITS = 0.31f
// Keep the scaled model center at world Y=0 while the parent transform sits
// inside the lower-middle handle. Rotating the parent now hinges around the
// physical IMU position instead of the GLB root at the model center.
private const val SPRESENSE_PIVOT_WORLD_Y = -0.112f
private const val MODEL_CENTER_ABOVE_SPRESENSE_PIVOT_Y = 0.112f
private val BRAND_TEXT_COLOR = Color(0xFFB9C7D9)
private val VOICE_TEXT_COLOR = Color(0xFFAAB9CD)
private const val ATTITUDE_SMOOTHING_SECONDS = 0.045f
private const val DIAGNOSTIC_TAP_COUNT = 5
private const val LOGO_TAP_WINDOW_MS = 700L
private const val MAX_GRADIENT_DURATION_SECONDS = 5f
