package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the gamma-corrected PWM duty helpers in LedOutput.kt. */
class PwmDutyGammaTest {
    @Test
    fun mapsBoundaryChannelsExactly() {
        assertEquals(0, channelToDuty(0))
        assertEquals(100, channelToDuty(255))
    }

    @Test
    fun midToneIsPulledDownByGamma() {
        assertTrue("128 -> ${channelToDuty(128)}", channelToDuty(128) in 21..23)
        assertTrue("51 -> ${channelToDuty(51)}", channelToDuty(51) in 2..4)
    }

    @Test
    fun clampsOutOfRangeInput() {
        assertEquals(0, channelToDuty(-10))
        assertEquals(100, channelToDuty(999))
    }

    @Test
    fun isMonotonicallyNonDecreasing() {
        for (channel in 1..255) {
            assertTrue(
                "channel $channel decreased",
                channelToDuty(channel) >= channelToDuty(channel - 1),
            )
        }
    }

    @Test
    fun nonBlackColorAlwaysEmitsLight() {
        val (r, g, b) = ensureVisibleDuty(2, 1, 0, 0, 0, 0)
        assertEquals(1, r)
        assertEquals(0, g)
        assertEquals(0, b)
    }

    @Test
    fun blackStaysOff() {
        val (r, g, b) = ensureVisibleDuty(0, 0, 0, 0, 0, 0)
        assertEquals(0, r)
        assertEquals(0, g)
        assertEquals(0, b)
    }

    @Test
    fun alreadyVisibleDutyIsUnchanged() {
        val (r, g, b) = ensureVisibleDuty(255, 0, 0, 100, 0, 0)
        assertEquals(100, r)
        assertEquals(0, g)
        assertEquals(0, b)
    }
}
