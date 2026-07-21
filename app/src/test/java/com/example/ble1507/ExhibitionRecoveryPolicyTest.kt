package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Test

class ExhibitionRecoveryPolicyTest {
    @Test
    fun reconnectBackoffIsBounded() {
        assertEquals(1_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(0))
        assertEquals(2_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(1))
        assertEquals(4_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(2))
        assertEquals(8_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(3))
        assertEquals(15_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(4))
        assertEquals(15_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(100))
    }

    @Test
    fun reconnectBackoffHandlesNegativeAttempt() {
        assertEquals(1_000L, ExhibitionRecoveryPolicy.reconnectDelayMs(-1))
    }
}
