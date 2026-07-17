package com.example.ble1507

import kotlin.math.PI

data class AttitudeEstimate(
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
)

class ImuNativeAttitudeEstimator {
    private val motionLib = InertialMotionLib2Compat()
    private var initialized = false
    private var started = false
    private var calibrating = false
    private var lastRawTimestampTicks: Long? = null
    private var timestampWrapOffsetTicks = 0L
    private var lastNativeTimestampSeconds: Double? = null

    fun isAvailable(): Boolean = true

    fun start(): Boolean {
        stop()

        val ok = runCatching { motionLib.initialize(DEFAULT_LATITUDE_DEG, FIXED_IMU_RATE_HZ) }.getOrDefault(false)
        if (!ok) {
            initialized = false
            started = false
            calibrating = false
            resetTimestampState()
            return false
        }

        runCatching { motionLib.setMode(1) }
        runCatching { motionLib.startUpdatePositionAttitude() }
        initialized = true
        started = true
        calibrating = false
        resetTimestampState()
        return true
    }

    fun stop() {
        started = false
        if (!initialized) {
            resetTimestampState()
            return
        }
        runCatching { motionLib.finishUpdatePositionAttitude() }
        runCatching { motionLib.release() }
        initialized = false
        calibrating = false
        resetTimestampState()
    }

    fun startCalibration(kind: Int = DEFAULT_CALIBRATION_KIND): Boolean {
        if (!initialized) {
            return false
        }
        runCatching { motionLib.finishUpdatePositionAttitude() }
        val started = runCatching { motionLib.startCalib(kind) }.getOrDefault(false)
        calibrating = started
        return started
    }

    fun finishCalibration(restartEstimation: Boolean = true): Boolean {
        if (!initialized || !calibrating) {
            return false
        }
        val result = runCatching { motionLib.finishCalib() }.getOrNull()
        calibrating = false
        if (restartEstimation) {
            runCatching { motionLib.startUpdatePositionAttitude() }
        }
        return result != null
    }

    fun update(packet: ImuPacket): AttitudeEstimate? {
        if (!started || !initialized) {
            return null
        }

        val packetTimeSeconds = packet.toPacketTimeSeconds()
        val nativeTimestampSeconds = toNativeTimestampSeconds(packetTimeSeconds).toFloat()

        val updated = runCatching {
            motionLib.updateImu(
                floatArrayOf(packet.ax, packet.ay, packet.az),
                floatArrayOf(packet.gx, packet.gy, packet.gz),
                nativeTimestampSeconds,
                packet.temp,
            )
        }.getOrDefault(false)
        if (!updated) {
            return null
        }
        val euler = runCatching { motionLib.getEulerAngle() }.getOrNull()?.takeIf { it.size >= 3 } ?: return null
        return AttitudeEstimate(
            rollDeg = (euler[0] * RAD_TO_DEG).toFloat(),
            pitchDeg = (euler[1] * RAD_TO_DEG).toFloat(),
            yawDeg = (euler[2] * RAD_TO_DEG).toFloat(),
        )
    }

    fun isCalibrating(): Boolean = calibrating

    private fun ImuPacket.toPacketTimeSeconds(): Double {
        val current = timestamp
        val previous = lastRawTimestampTicks
        if (previous != null && current < previous) {
            timestampWrapOffsetTicks += UINT32_RANGE
        }
        lastRawTimestampTicks = current
        return (timestampWrapOffsetTicks + current).toDouble() / TIMESTAMP_TICKS_PER_SECOND
    }

    private fun toNativeTimestampSeconds(packetTimeSeconds: Double): Double {
        val sampleIntervalSeconds = 1.0 / FIXED_IMU_RATE_HZ.toDouble()
        var nativeTimeSeconds = packetTimeSeconds
        val last = lastNativeTimestampSeconds
        if (last != null && nativeTimeSeconds <= last) {
            nativeTimeSeconds = last + sampleIntervalSeconds
        }
        lastNativeTimestampSeconds = nativeTimeSeconds
        return nativeTimeSeconds
    }

    private fun resetTimestampState() {
        lastRawTimestampTicks = null
        timestampWrapOffsetTicks = 0L
        lastNativeTimestampSeconds = null
    }

    companion object {
        private const val FIXED_IMU_RATE_HZ = 60f
        private const val DEFAULT_LATITUDE_DEG = 35.6f
        private const val TIMESTAMP_TICKS_PER_SECOND = 19_200_000.0
        private const val UINT32_RANGE = 0x1_0000_0000L
        private const val DEFAULT_CALIBRATION_KIND = 0x01 or 0x02 or 0x08
        private const val RAD_TO_DEG = 180.0 / PI
    }
}
