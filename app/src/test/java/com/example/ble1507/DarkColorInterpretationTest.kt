package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies dark and deep color handling on the pure-JVM rule path
 * ([RuleColorInterpreter.interpret] / [RuleColorInterpreter.interpretRelative]).
 * Colors are checked in HSV ranges so the intent survives small rounding.
 */
class DarkColorInterpretationTest {
    private fun hsv(color: InterpretedColor): FloatArray {
        val r = color.r / 255f
        val g = color.g / 255f
        val b = color.b / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
    }

    private fun assertHueInRange(hue: Float, minHue: Float, maxHue: Float) {
        val inRange = if (minHue <= maxHue) hue in minHue..maxHue else hue >= minHue || hue <= maxHue
        assertTrue("hue $hue not in [$minHue,$maxHue]", inRange)
    }

    @Test
    fun blackNearBlueIsADeepBlue() {
        val result = RuleColorInterpreter.interpret(
            "黒に近い青",
            InterpretedColor(255, 0, 0, "current"),
        )
        assertNotNull(result)
        val h = hsv(result!!)
        assertHueInRange(h[0], 220f, 260f)
        assertTrue("saturation ${h[1]}", h[1] >= 0.7f)
        assertTrue("value ${h[2]}", h[2] in 0.06f..0.20f)
        assertEquals("rule", result.source)
    }

    @Test
    fun blackNearBlueIsIndependentOfCurrentColor() {
        val neutral = RuleColorInterpreter.interpret("黒に近い青")
        val withRedCurrent = RuleColorInterpreter.interpret(
            "黒に近い青",
            InterpretedColor(255, 0, 0, "current"),
        )
        assertNotNull(withRedCurrent)
        assertEquals(neutral, withRedCurrent)
    }

    @Test
    fun midnightBlueStaysDarkAndBlue() {
        val h = hsv(
            RuleColorInterpreter.interpret(
                "深夜の青",
                InterpretedColor(255, 0, 0, "current"),
            )!!,
        )
        assertHueInRange(h[0], 220f, 260f)
        assertTrue("value ${h[2]}", h[2] in 0.08f..0.25f)
    }

    @Test
    fun darkForestGreenIsSynthesizedBeforeForestColorMap() {
        val h = hsv(
            RuleColorInterpreter.interpret(
                "暗い森の緑",
                InterpretedColor(255, 0, 0, "current"),
            )!!,
        )
        assertHueInRange(h[0], 100f, 140f)
        assertTrue("value ${h[2]}", h[2] in 0.25f..0.45f)
    }

    @Test
    fun vividBlueIsNearlyPureBlue() {
        val h = hsv(
            RuleColorInterpreter.interpret(
                "鮮やかな青",
                InterpretedColor(255, 0, 0, "current"),
            )!!,
        )
        assertHueInRange(h[0], 230f, 250f)
        assertTrue("saturation ${h[1]}", h[1] >= 0.95f)
        assertTrue("value ${h[2]}", h[2] >= 0.95f)
    }

    @Test
    fun paleBlueIsDesaturatedButBright() {
        val h = hsv(
            RuleColorInterpreter.interpret(
                "淡い青",
                InterpretedColor(255, 0, 0, "current"),
            )!!,
        )
        assertTrue("saturation ${h[1]}", h[1] in 0.2f..0.5f)
        assertTrue("value ${h[2]}", h[2] >= 0.9f)
    }

    @Test
    fun deepNavyIsDarkBlue() {
        val h = hsv(
            RuleColorInterpreter.interpret(
                "濃紺",
                InterpretedColor(255, 0, 0, "current"),
            )!!,
        )
        assertHueInRange(h[0], 220f, 260f)
        assertTrue("value ${h[2]}", h[2] in 0.2f..0.45f)
    }

    @Test
    fun sumiIsNearBlack() {
        val h = hsv(RuleColorInterpreter.interpret("墨色")!!)
        assertTrue("saturation ${h[1]}", h[1] <= 0.1f)
        assertTrue("value ${h[2]}", h[2] in 0.05f..0.15f)
    }

    @Test
    fun jetBlackIsBlack() {
        val result = RuleColorInterpreter.interpret("漆黒")!!
        assertEquals(0, result.r)
        assertEquals(0, result.g)
        assertEquals(0, result.b)
    }

    @Test
    fun almostVanishingRedIsBarelyLit() {
        val result = RuleColorInterpreter.interpret(
            "ほとんど消えそうな赤",
            InterpretedColor(255, 0, 0, "current"),
        )!!
        val h = hsv(result)
        assertHueInRange(h[0], 350f, 10f)
        assertTrue("value ${h[2]}", h[2] in 0.03f..0.12f)
    }

    @Test
    fun deepSeaIsAbsoluteDeepBlue() {
        val current = InterpretedColor(255, 0, 0, "current")
        val result = RuleColorInterpreter.interpret("深海", current)!!
        assertEquals("rule", result.source)
        val h = hsv(result)
        assertHueInRange(h[0], 180f, 240f)
        assertTrue("value ${h[2]}", h[2] < 0.8f)
    }

    @Test
    fun moonlightSimileBlueGoesToLlm() {
        val current = InterpretedColor(255, 0, 0, "current")
        assertNull(RuleColorInterpreter.interpret("月明かりのような青", current))
    }

    @Test
    fun sunsetSimileWithoutBasicColorWordStaysRule() {
        val current = InterpretedColor(255, 0, 0, "current")
        val result = RuleColorInterpreter.interpret("夕焼けのような色", current)
        assertNotNull(result)
        assertEquals("rule", result!!.source)
    }

    @Test
    fun freshnessDoesNotMeanVividColor() {
        val result = RuleColorInterpreter.interpret("新鮮なミルク")!!
        val h = hsv(result)
        assertTrue("saturation ${h[1]}", h[1] <= 0.12f)
        assertTrue("value ${h[2]}", h[2] >= 0.85f)
    }

    @Test
    fun keepBlueButDarkerStaysRelative() {
        val current = InterpretedColor(255, 0, 0, "current")
        val result = RuleColorInterpreter.interpret("青みを保ったまま暗く", current)!!
        assertEquals("rule-relative", result.source)
    }

    @Test
    fun slightlyMoreReddishRaisesRed() {
        val current = InterpretedColor(30, 60, 210, "current")
        val result = RuleColorInterpreter.interpret("もう少し赤っぽく", current)!!
        assertEquals("rule-relative", result.source)
        assertTrue("red ${result.r} vs ${current.r}", result.r > current.r)
    }

    private val current = InterpretedColor(30, 60, 210, "current")

    @Test
    fun slightlyDarkerReducesEveryChannelAndKeepsHue() {
        val before = hsv(current)
        val result = RuleColorInterpreter.interpret("もう少し暗く", current)!!
        assertTrue(result.r < current.r)
        assertTrue(result.g < current.g)
        assertTrue(result.b < current.b)
        val after = hsv(result)
        assertTrue("hue drift ${after[0]} vs ${before[0]}", kotlin.math.abs(after[0] - before[0]) <= 5f)
    }

    @Test
    fun stronglyDarkerIsDarkerThanSlightlyDarker() {
        val slight = RuleColorInterpreter.interpret("もう少し暗く", current)!!
        val strong = RuleColorInterpreter.interpret("かなり暗く", current)!!
        assertTrue(maxOf(strong.r, strong.g, strong.b) < maxOf(slight.r, slight.g, slight.b))
    }

    @Test
    fun towardBlackDimsButKeepsSomeLight() {
        val result = RuleColorInterpreter.interpret("黒に近づけて", current)!!
        assertTrue(result.r < current.r)
        assertTrue(result.g < current.g)
        assertTrue(result.b < current.b)
        assertTrue(result.r > 0 || result.g > 0 || result.b > 0)
    }

    @Test
    fun addingDepthLowersValueAndRaisesSaturation() {
        val before = hsv(current)
        val after = hsv(RuleColorInterpreter.interpret("深みを加えて", current)!!)
        assertTrue("value ${after[2]} vs ${before[2]}", after[2] < before[2])
        assertTrue("saturation ${after[1]} vs ${before[1]}", after[1] > before[1])
    }

    @Test
    fun brighterKeepingSaturationRaisesValueOnly() {
        val before = hsv(current)
        val after = hsv(RuleColorInterpreter.interpret("彩度はそのままで明るく", current)!!)
        assertTrue("value ${after[2]} vs ${before[2]}", after[2] > before[2])
        assertTrue("saturation ${after[1]} vs ${before[1]}", kotlin.math.abs(after[1] - before[1]) <= 0.05f)
    }

    @Test
    fun halfBrightnessHalvesValue() {
        val before = hsv(current)
        val after = hsv(RuleColorInterpreter.interpret("今の色を半分くらいの明るさに", current)!!)
        val ratio = after[2] / before[2]
        assertTrue("value ratio $ratio", ratio in 0.45f..0.55f)
    }

    @Test
    fun ruleResultIsStableAcrossRepeatedCalls() {
        val first = RuleColorInterpreter.interpret("黒に近い青")
        val second = RuleColorInterpreter.interpret("黒に近い青")
        assertEquals(first, second)
        val firstRelative = RuleColorInterpreter.interpret("もう少し暗く", current)
        val secondRelative = RuleColorInterpreter.interpret("もう少し暗く", current)
        assertEquals(firstRelative, secondRelative)
    }
}
