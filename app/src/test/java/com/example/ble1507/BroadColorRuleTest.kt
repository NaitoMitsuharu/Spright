package com.example.ble1507

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadColorRuleTest {
    @Test
    fun resolvesSpecificTraditionalColorsBeforeGenericColorWords() {
        assertHex("#F8B500", RuleColorInterpreter.interpret("山吹色"))
        assertHex("#4C6CB3", RuleColorInterpreter.interpret("群青色"))
        assertHex("#A8C97F", RuleColorInterpreter.interpret("萌黄色"))
    }

    @Test
    fun resolvesCanonicalBrandAndCharacterReferences() {
        assertHex("#5865F2", RuleColorInterpreter.interpret("Discordのブランドカラー"))
        assertHex("#000000", RuleColorInterpreter.interpret("Xのブランドカラー"))
        assertHex("#00B7C2", RuleColorInterpreter.interpret("初音ミクの代表色"))
    }

    @Test
    fun shortBloodReadingDoesNotMatchUnrelatedWords() {
        assertHex("#E67E14", RuleColorInterpreter.interpret("かぼちゃの果肉"))
        assertHex("#506080", RuleColorInterpreter.interpret("ひとりぼっちの孤独"))
    }

    @Test
    fun resolvesExplicitEmotionsButLeavesUnknownMetaphorsForLlm() {
        assertHex("#FF4020", RuleColorInterpreter.interpret("抑えきれない怒り"))
        assertHex("#301050", RuleColorInterpreter.interpret("背筋が凍る恐怖"))
        assertEquals(null, RuleColorInterpreter.interpret("秘密を隠した微笑み"))
    }

    @Test
    fun mixedAndIntermediateBasicColorsBypassSubstringRules() {
        assertNull(RuleColorInterpreter.interpret("緑と青の間の色"))
        assertNull(RuleColorInterpreter.interpret("赤と青を混ぜた色"))
        assertNull(RuleColorInterpreter.interpret("暗い緑と青の間の色"))
        assertNull(RuleColorInterpreter.interpret("青緑"))
    }

    @Test
    fun directBasicColorRequestsStillUseTheFastRulePath() {
        assertHex("#0000FF", RuleColorInterpreter.interpret("青"))
        assertHex("#0000FF", RuleColorInterpreter.interpret("青色にしてください"))
        assertHex("#00FF00", RuleColorInterpreter.interpret("緑にして"))
        assertHex("#000073", RuleColorInterpreter.interpret("深い青"))
        assertHex("#4C6CB3", RuleColorInterpreter.interpret("群青色"))
    }

    private fun assertHex(expected: String, actual: InterpretedColor?) {
        val hex = actual?.let { "#%02X%02X%02X".format(it.r, it.g, it.b) }
        assertEquals(expected, hex)
        assertEquals("rule", actual?.source)
    }
}
