package com.example.ble1507

/**
 * Time limits are deliberately short enough for an exhibition visitor to recover
 * without staff intervention, while leaving headroom above measured device latency.
 */
internal object ExhibitionRecoveryPolicy {
    const val SPEECH_LISTENING_TIMEOUT_MS = 8_000L
    const val SPEECH_RESULTS_TIMEOUT_MS = 6_000L
    const val INFERENCE_TIMEOUT_MS = 3_000L
    const val BLE_WRITE_TIMEOUT_MS = 3_000L
    const val GRADIENT_OPERATION_TIMEOUT_MS = 5_000L
    const val ERROR_TO_IDLE_DELAY_MS = 2_500L

    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    fun reconnectDelayMs(attempt: Int): Long =
        reconnectDelaysMs[attempt.coerceIn(0, reconnectDelaysMs.lastIndex)]
}
