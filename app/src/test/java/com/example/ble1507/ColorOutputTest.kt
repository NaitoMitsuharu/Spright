package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOutputTest {
    @Test
    fun parsesConstrainedUppercaseHex() {
        val color = parseHexColor("#3C50B4", "test")
        assertEquals(60, color?.r)
        assertEquals(80, color?.g)
        assertEquals(180, color?.b)
    }

    @Test
    fun rejectsAnythingOutsideExactGrammar() {
        assertNull(parseHexColor("#3c50b4", "test"))
        assertNull(parseHexColor("result=#3C50B4", "test"))
        assertNull(parseHexColor("#12345", "test"))
        assertNull(parseHexColor("#GG0000", "test"))
    }

    @Test
    fun exactBlackFromSemanticLlmIsRejectedAsUnsafeOffCommand() {
        assertTrue(isUnsafeLlmBlack(InterpretedColor(0, 0, 0, "llm")))
        assertFalse(isUnsafeLlmBlack(InterpretedColor(0, 0, 1, "llm")))
    }

    @Test
    fun additiveGuardCorrectsAContradictoryMixedColorButKeepsAValidOne() {
        val wrong = applyAdditiveMixGuard(
            "緑と青の間の色",
            InterpretedColor(255, 215, 0, "qwen"),
        )
        assertEquals(0, wrong.r)
        assertEquals(255, wrong.g)
        assertEquals(255, wrong.b)
        assertTrue(wrong.source.endsWith("additive-guard"))

        val valid = InterpretedColor(0, 210, 255, "qwen")
        assertEquals(valid, applyAdditiveMixGuard("緑と青の間の色", valid))
    }
}
