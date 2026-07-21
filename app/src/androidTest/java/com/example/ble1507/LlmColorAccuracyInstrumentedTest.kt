package com.example.ble1507

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent evaluation set for phrases that no rule can answer. Every case is
 * expected to reach the local LLM, so a rule hit means the set itself leaked into
 * the rule vocabulary and must be fixed rather than tolerated.
 */
@RunWith(AndroidJUnit4::class)
class LlmColorAccuracyInstrumentedTest {
    private data class Case(
        val suite: String,
        val category: String,
        val text: String,
        val hMin: Float,
        val hMax: Float,
        val sMin: Float,
        val sMax: Float,
        val vMin: Float,
        val vMax: Float,
    ) {
        fun accepts(rgb: Int): Boolean {
            val hsv = FloatArray(3)
            Color.colorToHSV(rgb, hsv)
            val hueOk = if (hMin <= hMax) {
                hsv[0] in hMin..hMax
            } else {
                hsv[0] >= hMin || hsv[0] <= hMax
            }
            return hueOk && hsv[1] in sMin..sMax && hsv[2] in vMin..vMax
        }
    }

    @Test
    fun evaluatesLocalLlmOnRuleFreePhrases() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val interpreter = QwenColorInterpreter(context)
        assertTrue("Model warmup failed", interpreter.warmup())
        val cases = loadCases(InstrumentationRegistry.getInstrumentation().context)
        val minLlmAccuracy = InstrumentationRegistry.getArguments()
            .getString("minLlmAccuracy")
            ?.toDoubleOrNull()
            ?: 0.0

        // Production always supplies the current color; reproducing that here keeps
        // the measured path identical to MainActivity.applyVoiceColor.
        val current = InterpretedColor(180, 120, 60, "current")
        val rows = mutableListOf<JSONObject>()
        cases.forEach { case ->
            val startedAt = SystemClock.elapsedRealtime()
            val result = interpreter.interpret(case.text, current)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val hex = result?.rgb?.toHex().orEmpty()
            rows += JSONObject()
                .put("suite", case.suite)
                .put("category", case.category)
                .put("text", case.text)
                .put("hex", hex)
                .put("source", result?.source.orEmpty())
                .put("ruleLeak", result?.source?.startsWith("rule") == true)
                .put("hexOk", HEX_PATTERN.matches(hex))
                .put("accepted", result?.let { case.accepts(it.rgb.toColorInt()) } == true)
                .put("elapsedMs", elapsedMs)
        }

        val leakedTexts = rows.filter { it.getBoolean("ruleLeak") }.map { it.getString("text") }
        val ruleLeakCount = leakedTexts.size
        val generatedAtUnixMs = System.currentTimeMillis()
        val report = JSONObject()
            .put("generatedAtUnixMs", generatedAtUnixMs)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put("model", BuildConfig.COLOR_MODEL_ID)
            .put("minLlmAccuracy", minLlmAccuracy)
            .put("ruleLeakCount", ruleLeakCount)
            .put("ruleLeakTexts", JSONArray(leakedTexts))
            .putMetrics(rows)
            .put("suiteResults", groupedMetrics(rows) { it.getString("suite") })
            .put("categoryResults", groupedMetrics(rows) { it.getString("category") })
            .put("cases", JSONArray(rows))
        val dir = context.getExternalFilesDir("benchmarks") ?: error("No benchmark directory")
        writeBenchmarkReport(dir, "llm-color-accuracy", generatedAtUnixMs, report)

        assertEquals(
            "Every LLM case must bypass the rule path; leaked=${leakedTexts.joinToString()}",
            0,
            ruleLeakCount,
        )
        val accuracy = report.getDouble("accuracy")
        assertTrue(
            "LLM color accuracy must be >=$minLlmAccuracy; was $accuracy",
            accuracy >= minLlmAccuracy,
        )
    }

    private fun JSONObject.putMetrics(rows: List<JSONObject>): JSONObject {
        val latencies = rows.map { it.getLong("elapsedMs") }
        return put("caseCount", rows.size)
            .put("accuracy", rows.rate("accepted"))
            .put("hexSuccessRate", rows.rate("hexOk"))
            .put("p50Ms", percentile(latencies, 0.50))
            .put("p95Ms", percentile(latencies, 0.95))
    }

    private fun groupedMetrics(
        rows: List<JSONObject>,
        key: (JSONObject) -> String,
    ): JSONObject {
        val grouped = JSONObject()
        rows.groupBy(key).forEach { (name, groupRows) ->
            grouped.put(name, JSONObject().putMetrics(groupRows))
        }
        return grouped
    }

    private fun loadCases(context: android.content.Context): List<Case> {
        val raw = context.assets.open("llm_color_cases.json")
            .bufferedReader()
            .use { it.readText() }
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Case(
                suite = item.optString("suite", "development"),
                category = item.getString("category"),
                text = item.getString("text"),
                hMin = item.getDouble("hMin").toFloat(),
                hMax = item.getDouble("hMax").toFloat(),
                sMin = item.getDouble("sMin").toFloat(),
                sMax = item.getDouble("sMax").toFloat(),
                vMin = item.getDouble("vMin").toFloat(),
                vMax = item.getDouble("vMax").toFloat(),
            )
        }
    }

    private fun List<JSONObject>.rate(field: String): Double =
        if (isEmpty()) 0.0 else count { it.getBoolean(field) }.toDouble() / size

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun InterpretedColor.toHex(): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    private companion object {
        val HEX_PATTERN = Regex("^#[0-9A-F]{6}$")
    }
}
