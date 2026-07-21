package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuSpeedEstimatorTest {
    @Test
    fun stationarySamplesConvergeToZeroSpeed() {
        val config = ImuSpeedEstimatorConfig(stationaryCountThreshold = 3)
        val estimator = ImuSpeedEstimator(config)

        val estimates = (0 until 20).map {
            estimator.update(
                ImuSpeedInput(
                    ax = 0f,
                    ay = 0f,
                    az = config.gravity,
                    gx = 0f,
                    gy = 0f,
                    gz = 0f,
                    dtSeconds = 1f / 60f,
                ),
            )
        }

        val last = estimates.last()
        assertTrue(last.isStationary)
        assertEquals(0f, last.speedFiltered, 0.0001f)
        assertEquals(0f, last.velocity.norm(), 0.0001f)
    }

    @Test
    fun nonStationaryLinearAccelerationProducesPositiveMotionIndex() {
        val config = ImuSpeedEstimatorConfig(stationaryCountThreshold = 3, speedSmoothingAlpha = 1f)
        val estimator = ImuSpeedEstimator(config)

        repeat(6) {
            estimator.update(
                ImuSpeedInput(0f, 0f, config.gravity, 0f, 0f, 0f, dtSeconds = 1f / 60f),
            )
        }
        val moving = estimator.update(
            ImuSpeedInput(1.5f, 0f, config.gravity, 0.1f, 0f, 0f, dtSeconds = 1f / 60f),
        )

        assertTrue(!moving.isStationary)
        assertTrue(moving.speedFiltered > 0f)
        assertTrue(moving.motionIndex > 0f)
    }

    @Test
    fun csvOutputAppendsSpeedAndDebugColumns() {
        val csv = "timestamp_seconds,ax,ay,az,gx,gy,gz\n" +
            "0.0,0,0,9.80665,0,0,0\n" +
            "0.016,0,0,9.80665,0,0,0\n"

        val output = estimate_speed_csv(csv, ImuSpeedEstimatorConfig(stationaryCountThreshold = 1))

        assertTrue(output.lines().first().contains("speed_filtered"))
        assertTrue(output.lines().first().contains("motion_index"))
        assertTrue(output.lines().first().contains("is_stationary"))
    }
}