package com.example.ble1507

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelativeColorModelInstrumentedTest {
    private data class Case(
        val current: InterpretedColor,
        val instruction: String,
    )

    private val cases = listOf(
        Case(InterpretedColor(30, 60, 210, "current"), "もう少し赤っぽくして"),
        Case(InterpretedColor(30, 60, 210, "current"), "もっと赤っぽくして"),
        Case(InterpretedColor(180, 70, 40, "current"), "青みを少し足して"),
        Case(InterpretedColor(80, 160, 70, "current"), "もう少し黄色寄りにして"),
        Case(InterpretedColor(120, 90, 160, "current"), "少し暖色寄りにして"),
        Case(InterpretedColor(130, 110, 100, "current"), "もっと濃い色にして"),
        Case(InterpretedColor(40, 80, 180, "current"), "少し明るくして"),
        Case(InterpretedColor(180, 120, 60, "current"), "もっと暗くして"),
        Case(InterpretedColor(220, 80, 80, "current"), "赤みを少し抑えて"),
        Case(InterpretedColor(80, 100, 150, "current"), "彩度を下げて"),
        Case(InterpretedColor(40, 80, 180, "current"), "赤を20%足して少し明るくして"),
        Case(InterpretedColor(160, 100, 70, "current"), "もう少し紫っぽくして"),
    )

    @Test
    fun relativeCommandsUseFastProductionRulePathAccurately() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val interpreter = QwenColorInterpreter(context)

        val rows = mutableListOf<JSONObject>()
        val latencies = mutableListOf<Long>()
        var ruleHandled = 0
        var directionHits = 0

        cases.forEach { case ->
            val expected = RuleColorInterpreter.interpretRelative(case.instruction, case.current)
                ?: error("Test case is not recognized as relative: ${case.instruction}")
            val started = SystemClock.elapsedRealtime()
            val result = interpreter.interpret(case.instruction, case.current)
                ?: error("No result for ${case.instruction}")
            val elapsed = SystemClock.elapsedRealtime() - started
            val isRule = result.source == "rule-relative"
            val directionOk = isPlausibleRelativeResult(case.current, expected, result.rgb)
            if (isRule) ruleHandled++
            if (directionOk) directionHits++
            latencies += elapsed
            rows += JSONObject()
                .put("instruction", case.instruction)
                .put("current", case.current.toHex())
                .put("expected", expected.toHex())
                .put("actual", result.rgb.toHex())
                .put("source", result.source)
                .put("ruleHandled", isRule)
                .put("directionOk", directionOk)
                .put("elapsedMs", elapsed)
        }

        val ruleRate = ruleHandled.toDouble() / cases.size
        val directionRate = directionHits.toDouble() / cases.size
        val p95 = percentile95(latencies)
        writeReport(context, rows, ruleRate, directionRate, p95)

        assertTrue("Relative direction accuracy must be 100%", directionRate == 1.0)
        assertTrue("Every relative case must use the rule path; was $ruleRate", ruleRate == 1.0)
        assertTrue("Relative resolution p95 must be <=50ms; was ${p95}ms", p95 <= 50L)
    }

    private fun writeReport(
        context: android.content.Context,
        rows: List<JSONObject>,
        ruleRate: Double,
        directionRate: Double,
        p95Ms: Long,
    ) {
        val dir = context.getExternalFilesDir("benchmarks") ?: error("No benchmark directory")
        val generatedAtUnixMs = System.currentTimeMillis()
        val root = JSONObject()
            .put("generatedAtUnixMs", generatedAtUnixMs)
            .put("model", BuildConfig.COLOR_MODEL_ID)
            .put("caseCount", rows.size)
            .put("ruleHandledRate", ruleRate)
            .put("directionAccuracy", directionRate)
            .put("p95Ms", p95Ms)
            .put("cases", JSONArray(rows))
        writeBenchmarkReport(dir, "relative-color-model-benchmark", generatedAtUnixMs, root)
    }

    private fun percentile95(values: List<Long>): Long {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun InterpretedColor.toHex(): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}
