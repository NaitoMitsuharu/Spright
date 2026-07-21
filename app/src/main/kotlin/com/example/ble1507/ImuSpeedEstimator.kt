package com.example.ble1507

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ImuSpeedEstimatorConfig(
    val thresholdAcc: Float = 0.8f,
    val thresholdGyro: Float = 0.03f,
    val stationaryCountThreshold: Int = 12,
    val leakFactor: Float = 0.995f,
    val speedSmoothingAlpha: Float = 0.25f,
    val filterGain: Float = 0.65f,
    val gravity: Float = 9.80665f,
    val accelerometerUnitScale: Float = 1f,
    val gyroUnitScale: Float = 1f,
    val defaultDtSeconds: Float = 1f / 60f,
    val maxDtSeconds: Float = 0.08f,
    val motionIndexWindowSeconds: Float = 0.25f,
)

data class ImuVector3(
    val componentX: Float,
    val componentY: Float,
    val componentZ: Float,
) {
    operator fun plus(other: ImuVector3): ImuVector3 = ImuVector3(
        componentX + other.componentX,
        componentY + other.componentY,
        componentZ + other.componentZ,
    )

    operator fun minus(other: ImuVector3): ImuVector3 = ImuVector3(
        componentX - other.componentX,
        componentY - other.componentY,
        componentZ - other.componentZ,
    )

    operator fun times(scale: Float): ImuVector3 = ImuVector3(
        componentX * scale,
        componentY * scale,
        componentZ * scale,
    )

    fun norm(): Float = sqrt(componentX * componentX + componentY * componentY + componentZ * componentZ)

    fun normalized(): ImuVector3? {
        val magnitude = norm()
        if (magnitude <= 1e-6f || !magnitude.isFinite()) return null
        return this * (1f / magnitude)
    }

    fun cross(other: ImuVector3): ImuVector3 = ImuVector3(
        componentY * other.componentZ - componentZ * other.componentY,
        componentZ * other.componentX - componentX * other.componentZ,
        componentX * other.componentY - componentY * other.componentX,
    )
}

data class ImuQuaternion(
    val scalar: Float,
    val vectorX: Float,
    val vectorY: Float,
    val vectorZ: Float,
) {
    fun normalized(): ImuQuaternion {
        val magnitude = sqrt(
            scalar * scalar + vectorX * vectorX + vectorY * vectorY + vectorZ * vectorZ,
        )
        if (magnitude <= 1e-6f || !magnitude.isFinite()) return IDENTITY
        val inverse = 1f / magnitude
        return ImuQuaternion(scalar * inverse, vectorX * inverse, vectorY * inverse, vectorZ * inverse)
    }

    fun conjugate(): ImuQuaternion = ImuQuaternion(scalar, -vectorX, -vectorY, -vectorZ)

    operator fun times(other: ImuQuaternion): ImuQuaternion = ImuQuaternion(
        scalar * other.scalar - vectorX * other.vectorX - vectorY * other.vectorY - vectorZ * other.vectorZ,
        scalar * other.vectorX + vectorX * other.scalar + vectorY * other.vectorZ - vectorZ * other.vectorY,
        scalar * other.vectorY - vectorX * other.vectorZ + vectorY * other.scalar + vectorZ * other.vectorX,
        scalar * other.vectorZ + vectorX * other.vectorY - vectorY * other.vectorX + vectorZ * other.scalar,
    )

    companion object {
        val IDENTITY = ImuQuaternion(1f, 0f, 0f, 0f)
    }
}

data class ImuSpeedInput(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val timestampSeconds: Double? = null,
    val dtSeconds: Float? = null,
)

data class ImuSpeedEstimate(
    val speedFiltered: Float,
    val motionIndex: Float,
    val accNorm: Float,
    val gyroNorm: Float,
    val isStationary: Boolean,
    val linearAcceleration: ImuVector3,
    val velocity: ImuVector3,
    val orientation: ImuQuaternion,
)

class ImuSpeedEstimator(
    private val config: ImuSpeedEstimatorConfig = ImuSpeedEstimatorConfig(),
) {
    private var orientation = ImuQuaternion.IDENTITY
    private var velocity = ImuVector3(0f, 0f, 0f)
    private var gyroBias = ImuVector3(0f, 0f, 0f)
    private var stationaryCount = 0
    private var speedFiltered = 0f
    private var previousTimestampSeconds: Double? = null
    private val motionWindow = ArrayDeque<Float>()
    private var motionWindowSumSquares = 0f

    fun reset() {
        orientation = ImuQuaternion.IDENTITY
        velocity = ImuVector3(0f, 0f, 0f)
        gyroBias = ImuVector3(0f, 0f, 0f)
        stationaryCount = 0
        speedFiltered = 0f
        previousTimestampSeconds = null
        motionWindow.clear()
        motionWindowSumSquares = 0f
    }

    fun update(packet: ImuPacket, receivedAtMs: Long): ImuSpeedEstimate {
        val input = ImuSpeedInput(
            ax = packet.ax,
            ay = packet.ay,
            az = packet.az,
            gx = packet.gx,
            gy = packet.gy,
            gz = packet.gz,
            timestampSeconds = receivedAtMs / 1_000.0,
        )
        return update(input)
    }

    fun update(input: ImuSpeedInput): ImuSpeedEstimate {
        val dtSeconds = resolveDtSeconds(input)
        val acceleration = ImuVector3(
            input.ax * config.accelerometerUnitScale,
            input.ay * config.accelerometerUnitScale,
            input.az * config.accelerometerUnitScale,
        )
        val rawGyro = ImuVector3(
            input.gx * config.gyroUnitScale,
            input.gy * config.gyroUnitScale,
            input.gz * config.gyroUnitScale,
        )
        val gyro = rawGyro - gyroBias
        val accNorm = acceleration.norm()
        val gyroNorm = gyro.norm()
        val sampleStationary = detect_stationary(accNorm, gyroNorm, config)
        stationaryCount = if (sampleStationary) stationaryCount + 1 else 0
        val isStationary = stationaryCount >= config.stationaryCountThreshold

        if (isStationary) {
            gyroBias = gyroBias * 0.995f + rawGyro * 0.005f
        }

        orientation = update_attitude_filter(orientation, acceleration, gyro, dtSeconds, config)
        val worldAcceleration = rotate_vector_by_quaternion(acceleration, orientation)
        val linearAcceleration = worldAcceleration - ImuVector3(0f, 0f, config.gravity)
        val motionIndex = updateMotionIndex(linearAcceleration.norm(), dtSeconds)

        val speed = if (isStationary) {
            velocity = ImuVector3(0f, 0f, 0f)
            0f
        } else {
            velocity = (velocity + linearAcceleration * dtSeconds) * config.leakFactor.coerceIn(0f, 1f)
            velocity.norm()
        }
        speedFiltered = if (isStationary) {
            0f
        } else {
            val alpha = config.speedSmoothingAlpha.coerceIn(0f, 1f)
            alpha * speed + (1f - alpha) * speedFiltered
        }

        return ImuSpeedEstimate(
            speedFiltered = speedFiltered,
            motionIndex = motionIndex,
            accNorm = accNorm,
            gyroNorm = gyroNorm,
            isStationary = isStationary,
            linearAcceleration = linearAcceleration,
            velocity = velocity,
            orientation = orientation,
        )
    }

    private fun resolveDtSeconds(input: ImuSpeedInput): Float {
        input.dtSeconds?.takeIf { it.isFinite() && it > 0f }?.let {
            return it.coerceAtMost(config.maxDtSeconds)
        }
        val timestamp = input.timestampSeconds
        val previous = previousTimestampSeconds
        previousTimestampSeconds = timestamp
        val dt = if (timestamp != null && previous != null) {
            (timestamp - previous).toFloat()
        } else {
            config.defaultDtSeconds
        }
        return dt.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(1e-4f, config.maxDtSeconds)
            ?: config.defaultDtSeconds
    }

    private fun updateMotionIndex(linearAccelerationNorm: Float, dtSeconds: Float): Float {
        val maxSamples = max(
            1,
            (config.motionIndexWindowSeconds / max(dtSeconds, 1e-4f)).toInt(),
        )
        val sample = linearAccelerationNorm.coerceAtLeast(0f)
        motionWindow.addLast(sample)
        motionWindowSumSquares += sample * sample
        while (motionWindow.size > maxSamples) {
            val removed = motionWindow.removeFirst()
            motionWindowSumSquares -= removed * removed
        }
        return sqrt(max(0f, motionWindowSumSquares) / motionWindow.size.coerceAtLeast(1))
    }
}

fun estimate_speed_from_imu(
    data: List<ImuSpeedInput>,
    config: ImuSpeedEstimatorConfig = ImuSpeedEstimatorConfig(),
): List<ImuSpeedEstimate> {
    val estimator = ImuSpeedEstimator(config)
    return data.map(estimator::update)
}

fun update_attitude_filter(
    orientation: ImuQuaternion,
    acceleration: ImuVector3,
    gyro: ImuVector3,
    dtSeconds: Float,
    config: ImuSpeedEstimatorConfig = ImuSpeedEstimatorConfig(),
): ImuQuaternion {
    val accelerationUnit = acceleration.normalized() ?: return orientation
    val estimatedGravitySensor = rotate_vector_by_quaternion(
        ImuVector3(0f, 0f, 1f),
        orientation.conjugate(),
    ).normalized() ?: ImuVector3(0f, 0f, 1f)
    val correction = accelerationUnit.cross(estimatedGravitySensor) * config.filterGain
    val correctedGyro = gyro + correction
    val gyroQuaternion = ImuQuaternion(
        0f,
        correctedGyro.componentX,
        correctedGyro.componentY,
        correctedGyro.componentZ,
    )
    val derivative = orientation * gyroQuaternion
    return ImuQuaternion(
        orientation.scalar + 0.5f * derivative.scalar * dtSeconds,
        orientation.vectorX + 0.5f * derivative.vectorX * dtSeconds,
        orientation.vectorY + 0.5f * derivative.vectorY * dtSeconds,
        orientation.vectorZ + 0.5f * derivative.vectorZ * dtSeconds,
    ).normalized()
}

fun rotate_vector_by_quaternion(vector: ImuVector3, orientation: ImuQuaternion): ImuVector3 {
    val rotated = orientation *
        ImuQuaternion(0f, vector.componentX, vector.componentY, vector.componentZ) *
        orientation.conjugate()
    return ImuVector3(rotated.vectorX, rotated.vectorY, rotated.vectorZ)
}

fun detect_stationary(
    accNorm: Float,
    gyroNorm: Float,
    config: ImuSpeedEstimatorConfig = ImuSpeedEstimatorConfig(),
): Boolean = abs(accNorm - config.gravity) < config.thresholdAcc && gyroNorm < config.thresholdGyro

fun estimate_speed_csv(
    inputCsv: String,
    config: ImuSpeedEstimatorConfig = ImuSpeedEstimatorConfig(),
): String {
    val lines = inputCsv.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return ""
    val header = lines.first().split(',').map { it.trim() }
    val column = header.withIndex().associate { it.value.lowercase(Locale.US) to it.index }
    fun value(row: List<String>, name: String): Float = row[column.getValue(name)].trim().toFloat()
    fun optionalValue(row: List<String>, name: String): Float? = column[name]?.let { row[it].trim().toFloatOrNull() }
    val inputs = lines.drop(1).map { line ->
        val row = line.split(',')
        ImuSpeedInput(
            ax = value(row, "ax"),
            ay = value(row, "ay"),
            az = value(row, "az"),
            gx = value(row, "gx"),
            gy = value(row, "gy"),
            gz = value(row, "gz"),
            timestampSeconds = optionalValue(row, "timestamp_seconds")?.toDouble(),
            dtSeconds = optionalValue(row, "dt"),
        )
    }
    val estimates = estimate_speed_from_imu(inputs, config)
    val output = StringBuilder()
    output.append(lines.first())
    output.append(
        ",speed_filtered,motion_index,acc_norm,gyro_norm,is_stationary," +
            "a_linear_x,a_linear_y,a_linear_z,vx,vy,vz\n",
    )
    lines.drop(1).zip(estimates).forEach { (line, estimate) ->
        output.append(line)
        output.append(',')
        output.append(
            String.format(
                Locale.US,
                "%.6f,%.6f,%.6f,%.6f,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                estimate.speedFiltered,
                estimate.motionIndex,
                estimate.accNorm,
                estimate.gyroNorm,
                estimate.isStationary,
                estimate.linearAcceleration.componentX,
                estimate.linearAcceleration.componentY,
                estimate.linearAcceleration.componentZ,
                estimate.velocity.componentX,
                estimate.velocity.componentY,
                estimate.velocity.componentZ,
            ),
        )
    }
    return output.toString()
}