package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeColorAdjustmentTest {
    private val currentBlue = InterpretedColor(30, 60, 210, "current")

    @Test
    fun makesCurrentColorMoreSaturated() {
        val adjusted = RuleColorInterpreter.interpret(
            "もっと濃い色にして",
            InterpretedColor(130, 110, 100, "current"),
        )

        assertNotNull(adjusted)
        val beforeRange = 130 - 100
        val afterRange = maxOf(adjusted!!.r, adjusted.g, adjusted.b) -
            minOf(adjusted.r, adjusted.g, adjusted.b)
        assertTrue(afterRange > beforeRange)
        assertEquals("rule-relative", adjusted.source)
    }

    @Test
    fun shiftsCurrentColorSlightlyTowardRed() {
        val adjusted = RuleColorInterpreter.interpret("もう少し赤っぽくして", currentBlue)

        assertNotNull(adjusted)
        assertTrue(adjusted!!.r > currentBlue.r)
        assertTrue(adjusted.b < currentBlue.b)
        assertEquals("rule-relative", adjusted.source)
    }

    @Test
    fun strongerDirectionCommandMovesFurtherTowardTarget() {
        val base = InterpretedColor(100, 100, 100, "current")
        val slight = RuleColorInterpreter.interpret("少し赤っぽく", base)!!
        val strong = RuleColorInterpreter.interpret("もっと赤っぽく", base)!!

        assertTrue(strong.r > slight.r)
        assertTrue(strong.g < slight.g)
        assertTrue(strong.b < slight.b)
    }

    @Test
    fun supportsExplicitPercentageAndCompoundBrightness() {
        val base = InterpretedColor(40, 80, 180, "current")
        val redder = RuleColorInterpreter.interpret("赤を20%足して", base)!!
        val redderAndBrighter = RuleColorInterpreter.interpret("赤を20%足して少し明るく", base)!!

        assertEquals(83, redder.r)
        assertEquals(64, redder.g)
        assertEquals(144, redder.b)
        assertTrue(redderAndBrighter.r > redder.r)
        assertTrue(redderAndBrighter.g > redder.g)
        assertTrue(redderAndBrighter.b > redder.b)
    }

    @Test
    fun exactColorCommandStillUsesAbsoluteRule() {
        val adjusted = RuleColorInterpreter.interpret("赤にして", currentBlue)

        assertEquals(255, adjusted?.r)
        assertEquals(0, adjusted?.g)
        assertEquals(0, adjusted?.b)
        assertEquals("rule", adjusted?.source)
    }

    @Test
    fun canReduceAColorComponentRelatively() {
        val adjusted = RuleColorInterpreter.interpret(
            "赤みを少し抑えて",
            InterpretedColor(220, 80, 80, "current"),
        )

        assertNotNull(adjusted)
        assertTrue(adjusted!!.r < 220)
        assertTrue(adjusted.g > 80)
        assertTrue(adjusted.b > 80)
    }

    @Test
    fun acceptsLlmResultThatMovesInExpectedDirection() {
        val current = InterpretedColor(30, 60, 210, "current")
        val expected = InterpretedColor(71, 49, 172, "rule-relative")
        val llm = InterpretedColor(66, 45, 180, "qwen")

        assertTrue(isPlausibleRelativeResult(current, expected, llm))
    }

    @Test
    fun rejectsUnchangedOrOppositeLlmResult() {
        val current = InterpretedColor(30, 60, 210, "current")
        val expected = InterpretedColor(71, 49, 172, "rule-relative")

        assertTrue(!isPlausibleRelativeResult(current, expected, current))
        assertTrue(
            !isPlausibleRelativeResult(
                current,
                expected,
                InterpretedColor(0, 80, 240, "qwen"),
            ),
        )
    }

    @Test
    fun appliesLlmRelativeControlCode() {
        val current = InterpretedColor(30, 60, 210, "current")
        val adjusted = RuleColorInterpreter.interpretRelativeControl(
            code = "RED,1,0,0;",
            originalText = "もう少し赤っぽくして",
            current = current,
        )

        assertEquals(71, adjusted?.r)
        assertEquals(49, adjusted?.g)
        assertEquals(172, adjusted?.b)
    }

    @Test
    fun appliesCompoundLlmControlAndExplicitPercentage() {
        val adjusted = RuleColorInterpreter.interpretRelativeControl(
            code = "RED,1,0,3;",
            originalText = "赤を20%足して少し明るくして",
            current = InterpretedColor(40, 80, 180, "current"),
        )

        assertEquals(93, adjusted?.r)
        assertEquals(72, adjusted?.g)
        assertEquals(161, adjusted?.b)
    }

    @Test
    fun guardsExplicitColorVocabularyWhileKeepingLlmAdjustments() {
        assertEquals(
            "BLUE,1,0,0;",
            RuleColorInterpreter.normalizeRelativeControl(
                code = "WARM,1,0,0;",
                originalText = "青みを少し足して",
            ),
        )
        assertEquals(
            "NONE,0,4,0;",
            RuleColorInterpreter.normalizeRelativeControl(
                code = "WARM,0,4,0;",
                originalText = "もっと濃い色にして",
            ),
        )
    }
}
