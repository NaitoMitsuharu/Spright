package com.example.ble1507

import kotlin.math.pow
import kotlin.math.roundToInt

// sRGB channel values are gamma-encoded, but the LED PWM duty is linear in light
// output. Decoding with this gamma keeps the penlight's perceived brightness
// close to the on-screen color, which otherwise looks far too bright for dark
// colors when the channel is sent to duty linearly.
internal const val LED_PWM_GAMMA = 2.2f

/** Maps an 8-bit sRGB channel (0..255) to a gamma-corrected PWM duty (0..100). */
internal fun channelToDuty(channel: Int): Int =
    (100.0 * (channel.coerceIn(0, 255) / 255.0).pow(LED_PWM_GAMMA.toDouble()))
        .roundToInt()
        .coerceIn(0, 100)

/**
 * Guarantees a non-black color still emits light. When the source RGB is not
 * black yet gamma correction rounds every duty to zero, the brightest channel's
 * duty is raised to 1 so the penlight does not appear switched off. Pure black
 * (an intentional "off") and any already-visible duty are returned unchanged.
 */
internal fun ensureVisibleDuty(
    r: Int,
    g: Int,
    b: Int,
    dutyR: Int,
    dutyG: Int,
    dutyB: Int,
): Triple<Int, Int, Int> {
    val isBlack = r == 0 && g == 0 && b == 0
    if (isBlack || dutyR != 0 || dutyG != 0 || dutyB != 0) {
        return Triple(dutyR, dutyG, dutyB)
    }
    val maxChannel = maxOf(r, g, b)
    return Triple(
        if (r == maxChannel) 1 else dutyR,
        if (g == maxChannel && r != maxChannel) 1 else dutyG,
        if (b == maxChannel && r != maxChannel && g != maxChannel) 1 else dutyB,
    )
}
