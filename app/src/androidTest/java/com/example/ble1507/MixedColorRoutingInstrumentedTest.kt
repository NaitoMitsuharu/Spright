package com.example.ble1507

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MixedColorRoutingInstrumentedTest {
    @Test
    fun greenBlueIntermediateBypassesRulesAndProducesCyanFamilyColor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val interpreter = QwenColorInterpreter(context)
        assertTrue("Model warmup failed", interpreter.warmup())

        val result = interpreter.interpret(
            "緑と青の間の色",
            InterpretedColor(255, 255, 255, "current"),
        )
        assertNotNull("Mixed color inference failed", result)
        assertTrue("Mixed color must not use a rule: ${result?.source}", result?.source?.contains("rule") == false)

        val hsv = FloatArray(3)
        Color.colorToHSV(result!!.rgb.toColorInt(), hsv)
        assertTrue("Expected green-blue hue, got ${hsv[0]} from ${result.rgb}", hsv[0] in 145f..215f)
        assertTrue("Expected a visible chromatic color, got S=${hsv[1]} V=${hsv[2]}", hsv[1] >= 0.35f && hsv[2] >= 0.25f)
    }
}
