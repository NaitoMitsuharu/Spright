package com.example.ble1507

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real InertialMotionLib2 Android native library with synthetic data.
 *
 * Each sample takes the same path and units as a parsed BLE packet:
 *  - unsigned 32-bit timestamp at 19.2 MHz
 *  - acceleration in m/s^2
 *  - angular velocity in rad/s
 *  - 60 samples per second
 */
@RunWith(AndroidJUnit4::class)
class InertialMotionLibSyntheticInstrumentedTest {
    @Test
    fun syntheticBleImuProducesPlausibleRollPitchYaw() {
        val results = listOf(
            runAxisScenario(Axis.ROLL),
            runAxisScenario(Axis.PITCH),
            runAxisScenario(Axis.YAW),
        )

        results.forEach { result ->
            Log.i(TAG, result.toLogString())
            assertNotNull("${result.axis} did not produce a level estimate", result.level)
            assertNotNull("${result.axis} did not produce a rotated estimate", result.rotated)
            val change = result.primaryChangeDeg()
            assertTrue(
                "${result.axis} should change by about +90 degrees, but changed $change: $result",
                change in 65f..115f,
            )
            if (result.axis != Axis.PITCH) {
                assertTrue(
                    "${result.axis} cross-axis error is too large: ${result.crossAxisChangeDeg()} degrees",
                    result.crossAxisChangeDeg() < 30f,
                )
            }
        }

        writeReport(results)
    }

    private fun runAxisScenario(axis: Axis): ScenarioResult {
        val estimator = ImuNativeAttitudeEstimator()
        assertTrue("Could not initialize native estimator for $axis: ${estimator.lastError}", estimator.start())

        var sampleIndex = 0L
        fun update(accel: FloatArray, gyro: FloatArray): AttitudeEstimate {
            val timestampTicks = ((sampleIndex * TICKS_PER_SECOND) / SAMPLE_RATE_HZ)
                .roundToLong() and UINT32_MASK
            sampleIndex++
            return estimator.update(
                ImuPacket(
                    timestamp = timestampTicks,
                    temp = TEMPERATURE_C,
                    gx = gyro[0],
                    gy = gyro[1],
                    gz = gyro[2],
                    ax = accel[0],
                    ay = accel[1],
                    az = accel[2],
                ),
            ) ?: error("Native update failed for $axis at sample $sampleIndex: ${estimator.lastError}")
        }

        return try {
            var level: AttitudeEstimate? = null
            repeat(LEVEL_SAMPLES) {
                level = update(
                    accel = floatArrayOf(0f, 0f, GRAVITY),
                    gyro = ZERO_VECTOR,
                )
            }

            var rotated: AttitudeEstimate? = null
            repeat(ROTATION_SAMPLES) { index ->
                val angleRad = HALF_PI * (index + 1).toDouble() / ROTATION_SAMPLES
                val accel = gravityFor(axis, angleRad)
                val gyro = when (axis) {
                    Axis.ROLL -> floatArrayOf(ANGULAR_RATE, 0f, 0f)
                    Axis.PITCH -> floatArrayOf(0f, ANGULAR_RATE, 0f)
                    Axis.YAW -> floatArrayOf(0f, 0f, ANGULAR_RATE)
                }
                rotated = update(accel, gyro)
            }
            repeat(HOLD_SAMPLES) {
                rotated = update(
                    accel = gravityFor(axis, HALF_PI),
                    gyro = ZERO_VECTOR,
                )
            }

            ScenarioResult(axis, level, rotated, estimator.lastError)
        } finally {
            estimator.stop()
        }
    }

    private fun gravityFor(axis: Axis, angleRad: Double): FloatArray = when (axis) {
        // R_body_to_world^T * [0, 0, g]. Yaw does not rotate the gravity vector.
        Axis.ROLL -> floatArrayOf(0f, (GRAVITY * sin(angleRad)).toFloat(), (GRAVITY * cos(angleRad)).toFloat())
        Axis.PITCH -> floatArrayOf((-GRAVITY * sin(angleRad)).toFloat(), 0f, (GRAVITY * cos(angleRad)).toFloat())
        Axis.YAW -> floatArrayOf(0f, 0f, GRAVITY)
    }

    private fun writeReport(results: List<ScenarioResult>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.getExternalFilesDir("benchmarks")
            ?: error("External benchmark directory is unavailable")
        directory.mkdirs()
        val json = JSONObject()
            .put("sampleRateHz", SAMPLE_RATE_HZ)
            .put("timestampTicksPerSecond", TICKS_PER_SECOND)
            .put("accelerationUnit", "m/s^2")
            .put("gyroUnit", "rad/s")
            .put("eulerOutputUnit", "degree (converted from native radians)")
            .put("scenarios", JSONArray(results.map(ScenarioResult::toJson)))
        File(directory, REPORT_FILE).writeText(json.toString(2))
    }

    private enum class Axis {
        ROLL,
        PITCH,
        YAW,
    }

    private data class ScenarioResult(
        val axis: Axis,
        val level: AttitudeEstimate?,
        val rotated: AttitudeEstimate?,
        val error: String?,
    ) {
        fun primaryChangeDeg(): Float {
            val start = level ?: return Float.NaN
            val end = rotated ?: return Float.NaN
            return when (axis) {
                Axis.ROLL -> angleDifference(end.rollDeg, start.rollDeg)
                Axis.PITCH -> angleDifference(end.pitchDeg, start.pitchDeg)
                Axis.YAW -> angleDifference(end.yawDeg, start.yawDeg)
            }
        }

        fun crossAxisChangeDeg(): Float {
            val start = level ?: return Float.POSITIVE_INFINITY
            val end = rotated ?: return Float.POSITIVE_INFINITY
            val changes = listOf(
                kotlin.math.abs(angleDifference(end.rollDeg, start.rollDeg)),
                kotlin.math.abs(angleDifference(end.pitchDeg, start.pitchDeg)),
                kotlin.math.abs(angleDifference(end.yawDeg, start.yawDeg)),
            )
            val primaryIndex = when (axis) {
                Axis.ROLL -> 0
                Axis.PITCH -> 1
                Axis.YAW -> 2
            }
            return changes.filterIndexed { index, _ -> index != primaryIndex }.max()
        }

        fun toJson(): JSONObject = JSONObject()
            .put("axis", axis.name.lowercase())
            .put("level", level.toJson())
            .put("rotated", rotated.toJson())
            .put("primaryChangeDeg", primaryChangeDeg())
            .put("crossAxisChangeDeg", crossAxisChangeDeg())
            .put("error", error)

        fun toLogString(): String =
            "$axis level=$level rotated=$rotated primaryChange=${primaryChangeDeg()} " +
                "crossAxisChange=${crossAxisChangeDeg()} error=$error"
    }

    private companion object {
        const val TAG = "SprightImuSynthetic"
        const val REPORT_FILE = "inertial-motion-lib2-synthetic.json"
        const val SAMPLE_RATE_HZ = 60.0
        const val TICKS_PER_SECOND = 19_200_000.0
        const val UINT32_MASK = 0xFFFF_FFFFL
        const val GRAVITY = 9.7975962f
        const val TEMPERATURE_C = 25f
        const val LEVEL_SAMPLES = 120
        const val ROTATION_SAMPLES = 60
        const val HOLD_SAMPLES = 60
        const val HALF_PI = PI / 2.0
        const val ANGULAR_RATE = (PI / 2.0).toFloat()
        val ZERO_VECTOR = floatArrayOf(0f, 0f, 0f)

        fun angleDifference(endDeg: Float, startDeg: Float): Float =
            ImuNativeAttitudeEstimator.normalizeYawDeg(endDeg - startDeg)

        fun AttitudeEstimate?.toJson(): Any = this?.let {
            JSONObject()
                .put("rollDeg", it.rollDeg)
                .put("pitchDeg", it.pitchDeg)
                .put("yawDeg", it.yawDeg)
        } ?: JSONObject.NULL
    }
}
