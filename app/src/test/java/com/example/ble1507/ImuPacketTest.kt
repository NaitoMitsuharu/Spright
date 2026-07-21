package com.example.ble1507

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuPacketTest {
    @Test
    fun parsesLittleEndianPacket() {
        val raw = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ImuPacket.TYPE_IMU_SAMPLE.toByte())
            putInt(0xF1234567.toInt())
            putFloat(24.5f)
            putFloat(0.1f)
            putFloat(-0.2f)
            putFloat(0.3f)
            putFloat(1.0f)
            putFloat(2.0f)
            putFloat(9.7f)
        }.array()

        val packet = ImuPacket.parse(raw)

        assertEquals(0xF1234567L, packet.timestamp)
        assertEquals(24.5f, packet.temp, 0.0001f)
        assertEquals(-0.2f, packet.gy, 0.0001f)
        assertEquals(9.7f, packet.az, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongPacketType() {
        ImuPacket.parse(ByteArray(33))
    }

    @Test
    fun timestampRemainsMonotonicAcrossWrapAndDuplicates() {
        val normalizer = ImuTimestampNormalizer()
        val beforeWrap = normalizer.next(0xFFFF_FFF0L)
        val afterWrap = normalizer.next(0x0000_0020L)
        val duplicate = normalizer.next(0x0000_0020L)

        assertTrue(afterWrap > beforeWrap)
        assertTrue(duplicate > afterWrap)
        assertEquals(1.0 / 60.0, duplicate - afterWrap, 1e-8)
    }

    @Test
    fun normalizesRelativeYaw() {
        assertEquals(-170f, ImuNativeAttitudeEstimator.normalizeYawDeg(190f), 0.0001f)
        assertEquals(170f, ImuNativeAttitudeEstimator.normalizeYawDeg(-190f), 0.0001f)
        assertEquals(-180f, ImuNativeAttitudeEstimator.normalizeYawDeg(180f), 0.0001f)
    }

    @Test
    fun convertsSdkEulerRadiansToDegrees() {
        assertEquals(
            90f,
            ImuNativeAttitudeEstimator.radiansToDegrees((PI / 2.0).toFloat()),
            0.0001f,
        )
    }
}
