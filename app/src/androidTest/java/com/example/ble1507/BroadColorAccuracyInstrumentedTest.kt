package com.example.ble1507

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BroadColorAccuracyInstrumentedTest {
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
    fun evaluatesApplicationAcrossBroadColorCategories() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val interpreter = QwenColorInterpreter(context)
        assertTrue("Model warmup failed", interpreter.warmup())
        val cases = loadCases(InstrumentationRegistry.getInstrumentation().context)
        val minimumAccuracy = InstrumentationRegistry.getArguments()
            .getString("minAccuracy")
            ?.toDoubleOrNull()
            ?: 0.0

        val rows = mutableListOf<JSONObject>()
        val latencies = mutableListOf<Long>()
        // Production always calls interpret() with the current color, so every
        // absolute case is evaluated twice: standalone (current=null) and with a
        // saturated non-neutral current color that pushes any accidental relative
        // interpretation outside the accepted HSV window.
        MODES.forEach { (mode, current) ->
            cases.forEach { case ->
                val startedAt = SystemClock.elapsedRealtime()
                val result = interpreter.interpret(case.text, current)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val accepted = result?.let { case.accepts(it.rgb.toColorInt()) } == true
                latencies += elapsedMs
                rows += JSONObject()
                    .put("mode", mode)
                    .put("suite", case.suite)
                    .put("category", case.category)
                    .put("text", case.text)
                    .put("hex", result?.rgb?.toHex().orEmpty())
                    .put("source", result?.source.orEmpty())
                    .put("accepted", accepted)
                    .put("elapsedMs", elapsedMs)
            }
        }

        val accuracy = rows.accuracy()
        val categories = JSONObject()
        rows.groupBy { it.getString("category") }.forEach { (category, categoryRows) ->
            categories.put(
                category,
                JSONObject()
                    .put("caseCount", categoryRows.size)
                    .put("accuracy", categoryRows.accuracy()),
            )
        }
        val suites = JSONObject()
        rows.groupBy { it.getString("suite") }.forEach { (suite, suiteRows) ->
            suites.put(
                suite,
                JSONObject()
                    .put("caseCount", suiteRows.size)
                    .put("accuracy", suiteRows.accuracy()),
            )
        }
        val modes = JSONObject()
        val modeAccuracies = mutableMapOf<String, Double>()
        rows.groupBy { it.getString("mode") }.forEach { (mode, modeRows) ->
            val modeAccuracy = modeRows.accuracy()
            modeAccuracies[mode] = modeAccuracy
            val modeRuleRows = modeRows.filter { it.isRule() }
            val modeLlmRows = modeRows.filterNot { it.isRule() }
            modes.put(
                mode,
                JSONObject()
                    .put("caseCount", modeRows.size)
                    .put("accuracy", modeAccuracy)
                    .put("p95Ms", percentile95(modeRows.map { it.getLong("elapsedMs") }))
                    .put("ruleCaseCount", modeRuleRows.size)
                    .put("ruleAccuracy", modeRuleRows.takeIf { it.isNotEmpty() }?.accuracy())
                    .put(
                        "ruleP95Ms",
                        modeRuleRows.takeIf { it.isNotEmpty() }
                            ?.let { items -> percentile95(items.map { it.getLong("elapsedMs") }) },
                    )
                    .put("llmCaseCount", modeLlmRows.size)
                    .put("llmAccuracy", modeLlmRows.takeIf { it.isNotEmpty() }?.accuracy())
                    .put(
                        "llmP95Ms",
                        modeLlmRows.takeIf { it.isNotEmpty() }
                            ?.let { items -> percentile95(items.map { it.getLong("elapsedMs") }) },
                    ),
            )
        }
        val worstModeAccuracy = modeAccuracies.values.minOrNull() ?: 0.0
        val worstMode = modeAccuracies.entries.minByOrNull { it.value }?.key.orEmpty()
        val ruleRows = rows.filter { it.isRule() }
        val llmRows = rows.filterNot { it.isRule() }
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
            .put("caseCount", rows.size)
            .put("accuracy", accuracy)
            .put("minModeAccuracy", worstModeAccuracy)
            .put("p95Ms", percentile95(latencies))
            .put("categoryResults", categories)
            .put("suiteResults", suites)
            .put("modeResults", modes)
            .put("ruleCaseCount", ruleRows.size)
            .put("ruleAccuracy", ruleRows.takeIf { it.isNotEmpty() }?.accuracy())
            .put(
                "ruleP95Ms",
                ruleRows.takeIf { it.isNotEmpty() }
                    ?.let { items -> percentile95(items.map { it.getLong("elapsedMs") }) },
            )
            .put("llmCaseCount", llmRows.size)
            .put("llmAccuracy", llmRows.takeIf { it.isNotEmpty() }?.accuracy())
            .put(
                "llmP95Ms",
                llmRows.takeIf { it.isNotEmpty() }
                    ?.let { items -> percentile95(items.map { it.getLong("elapsedMs") }) },
            )
            .put("cases", JSONArray(rows))
        val dir = context.getExternalFilesDir("benchmarks") ?: error("No benchmark directory")
        writeBenchmarkReport(dir, "broad-color-accuracy", generatedAtUnixMs, report)

        assertTrue(
            "Broad color accuracy must be >=$minimumAccuracy in every mode; " +
                "worst was $worstModeAccuracy ($worstMode), overall $accuracy",
            worstModeAccuracy >= minimumAccuracy,
        )
    }

    private fun loadCases(context: android.content.Context): List<Case> {
        val raw = context.assets.open("broad_color_accuracy_cases.json")
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

    private fun percentile95(values: List<Long>): Long {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun JSONObject.isRule(): Boolean = getString("source") == "rule"

    private fun List<JSONObject>.accuracy(): Double =
        if (isEmpty()) 0.0 else count { it.getBoolean("accepted") }.toDouble() / size

    private fun InterpretedColor.toHex(): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    private companion object {
        val MODES = listOf(
            "standalone" to null,
            "withCurrent" to InterpretedColor(180, 120, 60, "current"),
        )
    }
}

/**
 * Writes the report under a model- and run-scoped name so A/B comparisons cannot
 * read a stale file, plus the historical stable name pointing at the latest run.
 */
internal fun writeBenchmarkReport(
    dir: File,
    name: String,
    generatedAtUnixMs: Long,
    report: JSONObject,
) {
    dir.mkdirs()
    val modelId = BuildConfig.COLOR_MODEL_ID.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val body = report.toString(2)
    File(dir, "$name-$modelId-$generatedAtUnixMs.json").writeText(body)
    File(dir, "$name.json").writeText(body)
}
