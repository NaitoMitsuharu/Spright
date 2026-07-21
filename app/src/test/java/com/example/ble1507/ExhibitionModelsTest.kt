package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExhibitionModelsTest {
    @Test
    fun imuGraphMagnitudesUseEuclideanNormOfXyzComponents() {
        val point = ImuPacket(
            timestamp = 0L,
            temp = 25f,
            gx = 1f,
            gy = 2f,
            gz = 2f,
            ax = 3f,
            ay = 4f,
            az = 12f,
        ).toMagnitudeSample(elapsedMs = 250L)

        assertEquals(250L, point.elapsedMs)
        assertEquals(13f, point.accelMagnitude, 0.0001f)
        assertEquals(3f, point.gyroMagnitude, 0.0001f)
    }

    @Test
    fun calibrationAbortsWhenFirstSampleNeverArrivesOrStreamBecomesStale() {
        assertFalse(isCalibrationImuUnavailable(1_199L, 0L, 0L, 1_200L, 750L))
        assertTrue(isCalibrationImuUnavailable(1_200L, 0L, 0L, 1_200L, 750L))
        assertFalse(isCalibrationImuUnavailable(2_000L, 0L, 1_251L, 1_200L, 750L))
        assertTrue(isCalibrationImuUnavailable(2_001L, 0L, 1_251L, 1_200L, 750L))
    }

    @Test
    fun longerGradientAddsStepsAtTwentyHertz() {
        assertEquals(1, colorGradientStepCount(0L))
        assertEquals(1, colorGradientStepCount(1L))
        assertEquals(10, colorGradientStepCount(500L))
        assertEquals(20, colorGradientStepCount(1_000L))
        assertEquals(50, colorGradientStepCount(2_500L))
        assertEquals(100, colorGradientStepCount(5_000L))
        assertEquals(100, colorGradientStepCount(10_000L))
    }

    @Test
    fun hsvInterpolationUsesShortestHuePathAndKeepsEndpoints() {
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        assertEquals(red, interpolateHsvShortest(red, blue, 0f))
        assertEquals(blue, interpolateHsvShortest(red, blue, 1f))

        val midpoint = interpolateHsvShortest(red, blue, 0.5f)
        assertTrue((midpoint ushr 16 and 0xff) > 240)
        assertTrue((midpoint and 0xff) > 120)
        assertTrue((midpoint ushr 8 and 0xff) < 10)
    }

    @Test
    fun stationaryCalibrationAcceptsStableSiSamples() {
        val samples = List(300) { index ->
            ImuPacket(
                timestamp = index * 320_000L,
                temp = 24f,
                gx = 0.002f,
                gy = -0.001f,
                gz = 0.0015f,
                ax = if (index % 2 == 0) 0.01f else -0.01f,
                ay = 0.02f,
                az = if (index % 3 == 0) 9.79f else 9.81f,
            )
        }
        val result = StationaryCalibration.analyze(samples)
        assertTrue(result.reason, result.accepted)
        assertEquals(300, result.sampleCount)
        assertTrue(result.accelNormMean in 9.7..9.9)
    }

    @Test
    fun stationaryCalibrationRejectsMotion() {
        val samples = List(300) { index ->
            ImuPacket(
                timestamp = index * 320_000L,
                temp = 24f,
                gx = if (index > 270) 0.4f else 0f,
                gy = 0f,
                gz = 0f,
                ax = 0f,
                ay = 0f,
                az = if (index % 2 == 0) 9.2f else 10.4f,
            )
        }
        val result = StationaryCalibration.analyze(samples)
        assertFalse(result.accepted)
        assertTrue(result.reason.contains("回転") || result.reason.contains("振動"))
    }

    @Test
    fun exhibitionStationaryCheckAllowsSmallHandAndTableNoise() {
        val samples = List(210) { index ->
            ImuPacket(
                timestamp = index * 320_000L,
                temp = 24f,
                gx = 0.10f,
                gy = -0.04f,
                gz = 0.03f,
                ax = if (index % 2 == 0) 0.25f else -0.25f,
                ay = 0.12f,
                az = if (index % 3 == 0) 9.35f else 9.95f,
            )
        }
        val result = StationaryCalibration.analyze(samples)
        assertTrue(result.reason, result.accepted)
    }

    @Test
    fun zeroEulerProducesIdentitySceneQuaternion() {
        val quaternion = attitudeToSceneQuaternion(AttitudeEstimate(0f, 0f, 0f))
        assertEquals(0f, quaternion.x, 0.0001f)
        assertEquals(0f, quaternion.y, 0.0001f)
        assertEquals(0f, quaternion.z, 0.0001f)
        assertEquals(1f, quaternion.w, 0.0001f)
    }

    @Test
    fun stationaryReferenceProducesIdentityEvenWhenSensorIsMountedTilted() {
        val mountedPose = AttitudeEstimate(23f, -17f, 31f)
        val quaternion = attitudeToSceneQuaternion(mountedPose, mountedPose)
        assertEquals(0f, quaternion.x, 0.0001f)
        assertEquals(0f, quaternion.y, 0.0001f)
        assertEquals(0f, quaternion.z, 0.0001f)
        assertEquals(1f, quaternion.w, 0.0001f)
    }

    @Test
    fun identityAxisMappingKeepsNinetyDegreeRollOnSceneX() {
        val quaternion = attitudeToSceneQuaternion(
            attitude = AttitudeEstimate(90f, 0f, 0f),
            reference = AttitudeEstimate(0f, 0f, 0f),
        )
        val halfRoot = kotlin.math.sqrt(0.5f)
        assertEquals(halfRoot, quaternion.x, 0.0001f)
        assertEquals(0f, quaternion.y, 0.0001f)
        assertEquals(0f, quaternion.z, 0.0001f)
        assertEquals(halfRoot, quaternion.w, 0.0001f)
    }

    @Test
    fun referenceYawDoesNotTurnLocalRollIntoADiagonalSceneAxis() {
        val quaternion = attitudeToSceneQuaternion(
            attitude = AttitudeEstimate(90f, 0f, 30f),
            reference = AttitudeEstimate(0f, 0f, 30f),
        )
        val halfRoot = kotlin.math.sqrt(0.5f)
        assertEquals(halfRoot, quaternion.x, 0.0001f)
        assertEquals(0f, quaternion.y, 0.0001f)
        assertEquals(0f, quaternion.z, 0.0001f)
        assertEquals(halfRoot, quaternion.w, 0.0001f)
    }

    @Test
    fun quaternionInterpolationUsesEquivalentShortestHemisphere() {
        val identity = SceneQuaternion(0f, 0f, 0f, 1f)
        val sameRotationWithOppositeSign = SceneQuaternion(0f, 0f, 0f, -1f)
        val midpoint = interpolateSceneQuaternion(identity, sameRotationWithOppositeSign, 0.5f)
        assertEquals(0f, midpoint.x, 0.0001f)
        assertEquals(0f, midpoint.y, 0.0001f)
        assertEquals(0f, midpoint.z, 0.0001f)
        assertEquals(1f, midpoint.w, 0.0001f)
    }

    @Test
    fun quaternionInterpolationKeepsNormalizedEndpoints() {
        val from = SceneQuaternion(0f, 0f, 0f, 1f)
        val to = SceneQuaternion(0f, 0f, 1f, 0f)
        val midpoint = interpolateSceneQuaternion(from, to, 0.5f)
        val halfRoot = kotlin.math.sqrt(0.5f)
        assertEquals(halfRoot, midpoint.z, 0.0001f)
        assertEquals(halfRoot, midpoint.w, 0.0001f)
    }

    @Test
    fun glbContainsRequiredNodeAndMaterialContract() {
        val file = listOf(
            File("app/src/main/assets/models/spright_penlight.glb"),
            File("src/main/assets/models/spright_penlight.glb"),
        ).firstOrNull(File::isFile) ?: File("app/src/main/assets/models/spright_penlight.glb")
        assertTrue("Generated GLB is missing", file.isFile)
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67, header.int)
        assertEquals(2, header.int)
        assertEquals(bytes.size, header.int)
        val jsonLength = header.int
        assertEquals(0x4e4f534a, header.int)
        val json = bytes.copyOfRange(20, 20 + jsonLength).toString(Charsets.UTF_8)

        listOf(
            "SprightRoot",
            "Handle",
            "Collar",
            "Button",
            "EndCap",
            "Emitter",
            "GlowShell",
            "YawMarker",
            "YawMarkerLeft",
            "YawMarkerRight",
            "AccentRing",
            "ButtonRing",
            "EmitterMaterial",
            "GlowMaterial",
            "YawMarkerMaterial",
        ).forEach { required ->
            assertTrue("GLB contract missing $required", json.contains("\"name\":\"$required"))
        }
        assertTrue("Model must declare glTF 2.0", json.contains("\"version\":\"2.0\""))
        assertTrue("Emitter should stay in sub-meter authored units", json.contains("\"translation\":[0.0,0.061,0.0]"))
        assertTrue("Emitter must use blended transparency", json.contains("\"alphaMode\":\"BLEND\""))
        assertTrue("The model must keep a single authored root", json.contains("\"scenes\":[{\"name\":\"SprightScene\",\"nodes\":[0]}]"))
    }
}
