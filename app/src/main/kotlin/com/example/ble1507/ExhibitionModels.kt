package com.example.ble1507

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class VoiceUiState {
    Idle,
    Listening,
    Recognizing,
    Inferring,
    Sending,
    Error,
}

enum class SyncUiState {
    Idle,
    Prompt,
    CountingDown,
    Synced,
    MotionError,
}

data class ImuMagnitudeSample(
    val elapsedMs: Long,
    val accelMagnitude: Float,
    val gyroMagnitude: Float,
)

internal fun ImuPacket.toMagnitudeSample(elapsedMs: Long): ImuMagnitudeSample = ImuMagnitudeSample(
    elapsedMs = elapsedMs.coerceAtLeast(0L),
    accelMagnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat(),
    gyroMagnitude = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat(),
)

internal fun isCalibrationImuUnavailable(
    nowMs: Long,
    startedAtMs: Long,
    lastSampleAtMs: Long,
    firstSampleTimeoutMs: Long,
    staleSampleTimeoutMs: Long,
): Boolean = if (lastSampleAtMs <= 0L) {
    nowMs - startedAtMs >= firstSampleTimeoutMs
} else {
    nowMs - lastSampleAtMs >= staleSampleTimeoutMs
}

internal const val COLOR_GRADIENT_STEP_INTERVAL_MS = 50L
internal const val MAX_COLOR_GRADIENT_STEPS = 100

/**
 * Uses a 20 Hz color cadence so a longer transition also gains more intermediate
 * colors: 0.5 s = 10, 1 s = 20, and 5 s = 100 steps.
 */
internal fun colorGradientStepCount(durationMs: Long): Int {
    if (durationMs <= 0L) return 1
    return ((durationMs + COLOR_GRADIENT_STEP_INTERVAL_MS - 1L) / COLOR_GRADIENT_STEP_INTERVAL_MS)
        .toInt()
        .coerceIn(1, MAX_COLOR_GRADIENT_STEPS)
}

data class SceneQuaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

data class StationaryCalibrationResult(
    val sampleCount: Int,
    val gyroMagnitudeP95: Double,
    val accelNormMean: Double,
    val accelNormStdDev: Double,
    val gravity: FloatArray,
    val gyroBias: FloatArray,
    val accepted: Boolean,
    val reason: String,
) {
    val summary: String
        get() = "n=$sampleCount gyro p95=%.3f rad/s accel=%.3f±%.3f m/s²".format(
            gyroMagnitudeP95,
            accelNormMean,
            accelNormStdDev,
        )
}

object StationaryCalibration {
    // Exhibition calibration only verifies that the penlight is reasonably
    // stationary. Native calibration is intentionally not a success condition.
    const val MIN_SAMPLES = 180
    const val MAX_GYRO_P95_RAD_S = 0.20
    const val MIN_ACCEL_NORM_M_S2 = 7.0
    const val MAX_ACCEL_NORM_M_S2 = 12.5
    const val MAX_ACCEL_STD_DEV_M_S2 = 0.75

    fun analyze(samples: List<ImuPacket>): StationaryCalibrationResult {
        if (samples.isEmpty()) {
            return rejected(samples, "IMUサンプルを取得できませんでした")
        }
        val gyroMagnitudes = samples.map {
            sqrt((it.gx * it.gx + it.gy * it.gy + it.gz * it.gz).toDouble())
        }.sorted()
        val accelNorms = samples.map {
            sqrt((it.ax * it.ax + it.ay * it.ay + it.az * it.az).toDouble())
        }
        val accelMean = accelNorms.average()
        val accelStdDev = sqrt(accelNorms.sumOf { (it - accelMean).pow(2) } / accelNorms.size)
        val p95Index = ((gyroMagnitudes.size - 1) * 0.95).toInt()
        val gyroP95 = gyroMagnitudes[p95Index]
        val gravity = floatArrayOf(
            samples.map { it.ax.toDouble() }.average().toFloat(),
            samples.map { it.ay.toDouble() }.average().toFloat(),
            samples.map { it.az.toDouble() }.average().toFloat(),
        )
        val gyroBias = floatArrayOf(
            samples.map { it.gx.toDouble() }.average().toFloat(),
            samples.map { it.gy.toDouble() }.average().toFloat(),
            samples.map { it.gz.toDouble() }.average().toFloat(),
        )
        val reason = when {
            samples.size < MIN_SAMPLES -> "サンプル不足です（${samples.size}/$MIN_SAMPLES）"
            gyroP95 >= MAX_GYRO_P95_RAD_S -> "回転を検出しました"
            accelMean !in MIN_ACCEL_NORM_M_S2..MAX_ACCEL_NORM_M_S2 -> "重力加速度の範囲が不正です"
            accelStdDev >= MAX_ACCEL_STD_DEV_M_S2 -> "振動または移動を検出しました"
            else -> "静止判定に合格しました"
        }
        return StationaryCalibrationResult(
            sampleCount = samples.size,
            gyroMagnitudeP95 = gyroP95,
            accelNormMean = accelMean,
            accelNormStdDev = accelStdDev,
            gravity = gravity,
            gyroBias = gyroBias,
            accepted = samples.size >= MIN_SAMPLES &&
                gyroP95 < MAX_GYRO_P95_RAD_S &&
                accelMean in MIN_ACCEL_NORM_M_S2..MAX_ACCEL_NORM_M_S2 &&
                accelStdDev < MAX_ACCEL_STD_DEV_M_S2,
            reason = reason,
        )
    }

    private fun rejected(samples: List<ImuPacket>, reason: String) = StationaryCalibrationResult(
        sampleCount = samples.size,
        gyroMagnitudeP95 = Double.POSITIVE_INFINITY,
        accelNormMean = Double.NaN,
        accelNormStdDev = Double.NaN,
        gravity = floatArrayOf(0f, 0f, 0f),
        gyroBias = floatArrayOf(0f, 0f, 0f),
        accepted = false,
        reason = reason,
    )

    /**
     * Native library quaternion order is WXYZ. This matches the SDK's gravity-to-level
     * conversion and conjugation used by ImuAdvController.
     */
    fun gravityToNativeLevelQuaternion(gravity: FloatArray): FloatArray {
        require(gravity.size >= 3)
        val norm = sqrt(
            gravity[0].toDouble().pow(2) +
                gravity[1].toDouble().pow(2) +
                gravity[2].toDouble().pow(2),
        ).toFloat()
        if (norm == 0f) return floatArrayOf(1f, 0f, 0f, 0f)
        val x = gravity[0] / norm
        val y = gravity[1] / norm
        val z = gravity[2] / norm
        val w = 1f + z
        val qx = y
        val qy = -x
        val qNorm = sqrt((w * w + qx * qx + qy * qy).toDouble()).toFloat()
        if (qNorm == 0f) return floatArrayOf(1f, 0f, 0f, 0f)
        return floatArrayOf(w / qNorm, -qx / qNorm, -qy / qNorm, 0f)
    }
}

/**
 * Converts the native sensor Euler angles to SceneView's quaternion.
 *
 * Axis contract (identity, with no diagonal mixing):
 * sensor X / roll  -> scene X
 * sensor Y / pitch -> scene Y (the GLB penlight's longitudinal axis)
 * sensor Z / yaw   -> scene Z
 *
 * When [reference] is supplied, the stationary synchronization pose becomes
 * identity and only motion relative to that physical mounting pose is shown.
 */
fun attitudeToSceneQuaternion(
    attitude: AttitudeEstimate,
    reference: AttitudeEstimate? = null,
): SceneQuaternion {
    val current = eulerZyxToQuaternion(attitude)
    val origin = reference ?: return current
    // Express motion in the stationary sensor frame. Reversing this order
    // expresses it in world coordinates and rotates the apparent control axes
    // when the sensor happened to be tilted during synchronization.
    return multiplySceneQuaternions(conjugateSceneQuaternion(eulerZyxToQuaternion(origin)), current)
        .normalized()
}

/**
 * Shortest-path normalized interpolation for display smoothing.
 *
 * q and -q describe the same rotation, so the target is flipped when needed
 * to prevent a visually long rotation across the quaternion hemisphere.
 */
fun interpolateSceneQuaternion(
    from: SceneQuaternion,
    to: SceneQuaternion,
    fraction: Float,
): SceneQuaternion {
    val t = fraction.coerceIn(0f, 1f)
    val dot = from.x * to.x + from.y * to.y + from.z * to.z + from.w * to.w
    val sign = if (dot < 0f) -1f else 1f
    return SceneQuaternion(
        x = from.x + (to.x * sign - from.x) * t,
        y = from.y + (to.y * sign - from.y) * t,
        z = from.z + (to.z * sign - from.z) * t,
        w = from.w + (to.w * sign - from.w) * t,
    ).normalized()
}

private fun eulerZyxToQuaternion(attitude: AttitudeEstimate): SceneQuaternion {
    val roll = attitude.rollDeg * PI / 180.0
    val pitch = attitude.pitchDeg * PI / 180.0
    val yaw = attitude.yawDeg * PI / 180.0
    val cr = cos(roll / 2.0)
    val sr = sin(roll / 2.0)
    val cp = cos(pitch / 2.0)
    val sp = sin(pitch / 2.0)
    val cy = cos(yaw / 2.0)
    val sy = sin(yaw / 2.0)
    return SceneQuaternion(
        x = (sr * cp * cy - cr * sp * sy).toFloat(),
        y = (cr * sp * cy + sr * cp * sy).toFloat(),
        z = (cr * cp * sy - sr * sp * cy).toFloat(),
        w = (cr * cp * cy + sr * sp * sy).toFloat(),
    )
}

private fun multiplySceneQuaternions(a: SceneQuaternion, b: SceneQuaternion) = SceneQuaternion(
    x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
)

private fun conjugateSceneQuaternion(q: SceneQuaternion) = SceneQuaternion(-q.x, -q.y, -q.z, q.w)

private fun SceneQuaternion.normalized(): SceneQuaternion {
    val magnitude = sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
    if (magnitude == 0f) return SceneQuaternion(0f, 0f, 0f, 1f)
    return SceneQuaternion(x / magnitude, y / magnitude, z / magnitude, w / magnitude)
}

fun interpolateHsvShortest(start: Int, end: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    val a = rgbToHsv(start)
    val b = rgbToHsv(end)
    var hueDelta = (b[0] - a[0]) % 360f
    if (hueDelta > 180f) hueDelta -= 360f
    if (hueDelta < -180f) hueDelta += 360f
    val hue = (a[0] + hueDelta * t + 360f) % 360f
    val saturation = a[1] + (b[1] - a[1]) * t
    val value = a[2] + (b[2] - a[2]) * t
    return hsvToRgb(hue, saturation, value)
}

private fun rgbToHsv(color: Int): FloatArray {
    val r = ((color ushr 16) and 0xff) / 255f
    val g = ((color ushr 8) and 0xff) / 255f
    val b = (color and 0xff) / 255f
    val maxValue = max(r, max(g, b))
    val minValue = min(r, min(g, b))
    val delta = maxValue - minValue
    val hue = when {
        delta == 0f -> 0f
        maxValue == r -> 60f * (((g - b) / delta) % 6f)
        maxValue == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(hue, if (maxValue == 0f) 0f else delta / maxValue, maxValue)
}

private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
    val c = value * saturation
    val h = hue / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (rp, gp, bp) = when (h.toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = value - c
    val r = ((rp + m) * 255f).toInt().coerceIn(0, 255)
    val g = ((gp + m) * 255f).toInt().coerceIn(0, 255)
    val b = ((bp + m) * 255f).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (r shl 16) or (g shl 8) or b
}
