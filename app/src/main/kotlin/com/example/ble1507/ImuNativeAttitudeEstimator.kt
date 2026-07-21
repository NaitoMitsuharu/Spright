package com.example.ble1507

import android.util.Log
import kotlin.math.PI
import kotlin.math.sqrt

data class AttitudeEstimate(
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
)

internal class ImuTimestampNormalizer(
    private val imuRateHz: Double = 60.0,
    private val ticksPerSecond: Double = 19_200_000.0,
) {
    private var lastRawTicks: Long? = null
    private var wrapOffsetTicks = 0L
    private var lastSeconds: Double? = null

    fun next(rawTicks: Long): Double {
        val previous = lastRawTicks
        if (previous != null && rawTicks < previous && previous - rawTicks > UINT32_RANGE / 2) {
            wrapOffsetTicks += UINT32_RANGE
        }
        lastRawTicks = rawTicks
        var seconds = (wrapOffsetTicks + rawTicks).toDouble() / ticksPerSecond
        val last = lastSeconds
        if (last != null && seconds <= last) {
            seconds = last + 1.0 / imuRateHz
        }
        lastSeconds = seconds
        return seconds
    }

    fun reset() {
        lastRawTicks = null
        wrapOffsetTicks = 0L
        lastSeconds = null
    }

    private companion object {
        const val UINT32_RANGE = 0x1_0000_0000L
    }
}

class ImuNativeAttitudeEstimator {
    private val motionLib = InertialMotionLib2Compat()
    private var initialized = false
    private var started = false
    private var calibrating = false
    private val timestampNormalizer = ImuTimestampNormalizer(FIXED_IMU_RATE_HZ.toDouble())
    private var yawReferenceDeg: Float? = null
    var lastError: String? = null
        private set

    fun isAvailable(): Boolean = true

    @Synchronized
    fun start(): Boolean {
        stop()

        val ok = motionLib.initialize(FIXED_IMU_RATE_HZ)
        if (!ok) {
            lastError = motionLib.lastError ?: "InertialMotionLib2 initialization failed"
            initialized = false
            started = false
            calibrating = false
            resetTimestampState()
            return false
        }

        if (!motionLib.setMode(MODE_DEFAULT)) {
            // The bundled singleton library starts in mode 1 but reports
            // INVALID_STATE when the same default is explicitly re-applied.
            Log.w(TAG, "Native default mode is already active: ${motionLib.lastError}")
        }
        if (!motionLib.setCalibParam(ZERO_VECTOR, CALIB_PARAM_ACCEL_BIAS)) {
            Log.w(TAG, "Native zero accelerometer bias was not applied: ${motionLib.lastError}")
        }
        if (!motionLib.setCalibParam(ZERO_VECTOR, CALIB_PARAM_GYRO_BIAS)) {
            Log.w(TAG, "Native zero gyroscope bias was not applied: ${motionLib.lastError}")
        }
        if (!motionLib.setQuaternion(floatArrayOf(1f, 0f, 0f, 0f))) {
            Log.w(TAG, "Native identity quaternion was not applied: ${motionLib.lastError}")
        }
        if (!motionLib.startUpdatePositionAttitude()) {
            lastError = motionLib.lastError ?: "Could not start attitude estimation"
            motionLib.release()
            initialized = false
            started = false
            resetTimestampState()
            return false
        }
        initialized = true
        started = true
        calibrating = false
        lastError = null
        resetTimestampState()
        return true
    }

    @Synchronized
    fun stop() {
        started = false
        if (!initialized) {
            resetTimestampState()
            return
        }
        motionLib.finishUpdatePositionAttitude()
        motionLib.release()
        initialized = false
        calibrating = false
        resetTimestampState()
    }

    @Synchronized
    fun startCalibration(kind: Int = DEFAULT_CALIBRATION_KIND): Boolean {
        if (!initialized) {
            return false
        }
        motionLib.finishUpdatePositionAttitude()
        val started = motionLib.startCalib(kind)
        if (!started) lastError = motionLib.lastError ?: "Calibration start failed"
        calibrating = started
        return started
    }

    @Synchronized
    fun finishCalibration(restartEstimation: Boolean = true): Boolean {
        if (!initialized || !calibrating) {
            return false
        }
        val result = motionLib.finishCalib()
        calibrating = false
        if (restartEstimation) {
            if (!motionLib.startUpdatePositionAttitude()) {
                lastError = motionLib.lastError ?: "Estimator restart after calibration failed"
                return false
            }
        }
        if (result) {
            yawReferenceDeg = null
            lastError = null
        } else {
            lastError = motionLib.lastError ?: "Calibration finish failed"
        }
        return result
    }

    @Synchronized
    fun update(packet: ImuPacket): AttitudeEstimate? {
        if (!started || !initialized) {
            return null
        }

        val nativeTimestampSeconds = timestampNormalizer.next(packet.timestamp)

        val updated = motionLib.updateImu(
            floatArrayOf(packet.ax, packet.ay, packet.az),
            floatArrayOf(packet.gx, packet.gy, packet.gz),
            nativeTimestampSeconds,
            packet.temp,
        )
        if (!updated) {
            lastError = motionLib.lastError ?: "Native IMU update failed"
            return null
        }
        val euler = motionLib.getEulerAngle()?.takeIf { it.size >= 3 } ?: run {
            lastError = motionLib.lastError ?: "Euler angle is unavailable"
            return null
        }
        val absoluteYawDeg = radiansToDegrees(euler[2])
        val reference = yawReferenceDeg ?: absoluteYawDeg.also { yawReferenceDeg = it }
        lastError = null
        return AttitudeEstimate(
            rollDeg = radiansToDegrees(euler[0]),
            pitchDeg = radiansToDegrees(euler[1]),
            yawDeg = normalizeYawDeg(absoluteYawDeg - reference),
        )
    }

    fun isCalibrating(): Boolean = calibrating

    @Synchronized
    fun resetRelativeYaw() {
        yawReferenceDeg = null
        lastError = null
    }

    @Synchronized
    fun applyStationaryCalibration(result: StationaryCalibrationResult): Boolean {
        if (!initialized || !started) {
            lastError = "Estimator is not running"
            return false
        }
        if (!result.accepted) {
            lastError = result.reason
            return false
        }
        val gravityNorm = sqrt(result.gravity.sumOf { (it * it).toDouble() })
        val quaternion = StationaryCalibration.gravityToNativeLevelQuaternion(result.gravity)
        motionLib.finishUpdatePositionAttitude()
        val gyroApplied = motionLib.setCalibParam(result.gyroBias, CALIB_PARAM_GYRO_BIAS)
        val normApplied = motionLib.setGravityNorm(gravityNorm)
        val gravityApplied = motionLib.setGravityAttitude(result.gravity)
        val quaternionApplied = motionLib.setQuaternion(quaternion)
        val restarted = motionLib.startUpdatePositionAttitude()
        val success = gyroApplied && normApplied && gravityApplied && quaternionApplied && restarted
        if (success) {
            yawReferenceDeg = null
            lastError = null
        } else {
            lastError = motionLib.lastError ?: "Stationary calibration could not be applied"
        }
        started = restarted
        return success
    }

    private fun resetTimestampState() {
        timestampNormalizer.reset()
        yawReferenceDeg = null
    }

    companion object {
        private const val FIXED_IMU_RATE_HZ = 60f
        private const val MODE_DEFAULT = 1
        private const val CALIB_PARAM_ACCEL_BIAS = 0
        private const val CALIB_PARAM_GYRO_BIAS = 1
        private const val DEFAULT_CALIBRATION_KIND = 0x01 or 0x02 or 0x08
        private const val RAD_TO_DEG = 180.0 / PI
        private const val TAG = "SprightImuEstimator"
        private val ZERO_VECTOR = floatArrayOf(0f, 0f, 0f)

        internal fun radiansToDegrees(value: Float): Float = (value * RAD_TO_DEG).toFloat()

        internal fun normalizeYawDeg(value: Float): Float {
            var normalized = (value + 180f) % 360f
            if (normalized < 0f) normalized += 360f
            return normalized - 180f
        }
    }
}
