package com.example.ble1507

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorModelBenchmarkInstrumentedTest {
    private data class Case(
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
            val hueOk = if (hMin <= hMax) hsv[0] in hMin..hMax else hsv[0] >= hMin || hsv[0] <= hMax
            return hueOk && hsv[1] in sMin..sMax && hsv[2] in vMin..vMax
        }
    }

    private data class ModelResult(
        val id: String,
        val fileName: String,
        val formatRate: Double,
        val semanticRate: Double,
        val p95Ms: Long,
        val caseColorDiversityRate: Double,
        val stableOutputRate: Double,
        val rows: List<String>,
    ) {
        val passes: Boolean
            get() = formatRate == 1.0 &&
                semanticRate >= 0.90 &&
                p95Ms <= 3_000L &&
                caseColorDiversityRate >= 0.75 &&
                stableOutputRate == 1.0
    }

    @Test
    fun benchmarkBothModels() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cases = loadCases(InstrumentationRegistry.getInstrumentation().context)
        val modelDir = File(context.getExternalFilesDir(null), "models")
        val arguments = InstrumentationRegistry.getArguments()
        val selectedModel = arguments.getString("colorModel")
        val repeats = arguments.getString("repeats")?.toIntOrNull()?.coerceIn(1, DEFAULT_REPEATS)
            ?: DEFAULT_REPEATS
        val specs = listOf(
            "qwen3-0.6b" to "Qwen3-0.6B-Q4_K_M.gguf",
            "qwen3.5-0.8b" to "Qwen3.5-0.8B-Q4_0.gguf",
        ).filter { selectedModel == null || it.first == selectedModel }
        require(specs.isNotEmpty()) { "Unknown instrumentation colorModel=$selectedModel" }
        val results = specs.map { (id, fileName) ->
            benchmarkModel(id, File(modelDir, fileName), cases, repeats)
        }
        val winner = chooseWinner(results)
        writeReports(context, results, winner, repeats, cases.size)

        assertTrue(
            "No candidate met format=100%, semantic>=90%, p95<=3000ms, diversity>=75%, stability=100%. See benchmark report.",
            winner != null,
        )
    }

    private fun benchmarkModel(id: String, model: File, cases: List<Case>, repeats: Int): ModelResult {
        require(model.isFile) { "Missing model: ${model.absolutePath}" }
        require(NativeQwenBridge.warmupModel(model.absolutePath)) { "Warmup failed for $id" }
        val latencies = mutableListOf<Long>()
        val rows = mutableListOf<String>()
        val colorsByCase = linkedMapOf<String, MutableSet<String>>()
        var formatHits = 0
        var semanticHits = 0
        val total = cases.size * repeats

        cases.forEach { case ->
            repeat(repeats) { run ->
                val started = SystemClock.elapsedRealtime()
                val raw = NativeQwenBridge.inferColorHex(
                    model.absolutePath,
                    "Description: ${case.text}\n" +
                        "Select the single closest semantic anchor, keep its hue, and output HEX:",
                )
                val elapsed = SystemClock.elapsedRealtime() - started
                latencies += elapsed
                val hex = raw?.substringBefore('|').orEmpty()
                val parsed = parseHexColor(hex, "benchmark")
                val formatOk = parsed != null
                val semanticOk = parsed?.let { case.accepts(it.toColorInt()) } == true
                if (formatOk) colorsByCase.getOrPut(case.text) { linkedSetOf() }.add(hex)
                if (formatOk) formatHits++
                if (semanticOk) semanticHits++
                rows += listOf(
                    csv(id),
                    csv(case.text),
                    run.toString(),
                    csv(hex),
                    formatOk.toString(),
                    semanticOk.toString(),
                    elapsed.toString(),
                ).joinToString(",")
            }
        }
        return ModelResult(
            id = id,
            fileName = model.name,
            formatRate = formatHits.toDouble() / total,
            semanticRate = semanticHits.toDouble() / total,
            p95Ms = percentile95(latencies),
            caseColorDiversityRate = colorsByCase.values
                .mapNotNull { it.firstOrNull() }
                .distinct()
                .size
                .toDouble() / cases.size,
            stableOutputRate = cases.count { colorsByCase[it.text]?.size == 1 }.toDouble() / cases.size,
            rows = rows,
        )
    }

    private fun chooseWinner(results: List<ModelResult>): ModelResult? {
        val passing = results.filter(ModelResult::passes)
        if (passing.isEmpty()) return null
        val fastest = passing.minBy(ModelResult::p95Ms)
        val nearFastest = passing.filter { it.p95Ms - fastest.p95Ms <= 200L }
        return nearFastest.sortedWith(
            compareByDescending<ModelResult> { it.semanticRate }
                .thenBy { if (it.id == "qwen3-0.6b") 0 else 1 },
        ).first()
    }

    private fun writeReports(
        context: android.content.Context,
        results: List<ModelResult>,
        winner: ModelResult?,
        repeats: Int,
        caseCount: Int,
    ) {
        val dir = context.getExternalFilesDir("benchmarks") ?: error("No external benchmark directory")
        dir.mkdirs()
        File(dir, "color-model-benchmark.csv").bufferedWriter().use { writer ->
            writer.write("model,text,run,hex,format_ok,semantic_ok,elapsed_ms\n")
            results.flatMap(ModelResult::rows).forEach {
                writer.write(it)
                writer.newLine()
            }
        }
        val generatedAtUnixMs = System.currentTimeMillis()
        val root = JSONObject()
            .put("generatedAtUnixMs", generatedAtUnixMs)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put("caseCount", caseCount)
            .put("repeats", repeats)
            .put("winner", winner?.id)
            .put(
                "criteria",
                JSONObject()
                    .put("formatRate", 1.0)
                    .put("semanticRate", 0.90)
                    .put("p95Ms", 3_000)
                    .put("caseColorDiversityRate", 0.75)
                    .put("stableOutputRate", 1.0),
            )
        val models = JSONArray()
        results.forEach {
            models.put(
                JSONObject()
                    .put("id", it.id)
                    .put("fileName", it.fileName)
                    .put("formatRate", it.formatRate)
                    .put("semanticRate", it.semanticRate)
                    .put("p95Ms", it.p95Ms)
                    .put("caseColorDiversityRate", it.caseColorDiversityRate)
                    .put("stableOutputRate", it.stableOutputRate)
                    .put("passes", it.passes),
            )
        }
        root.put("models", models)
        // An A/B run must never silently overwrite the previous model's report,
        // so keep a timestamped copy alongside the latest-result file.
        writeBenchmarkReport(dir, "color-model-benchmark", generatedAtUnixMs, root)
    }

    private fun loadCases(context: android.content.Context): List<Case> {
        val json = context.assets.open("color_benchmark_cases.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Case(
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

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private companion object {
        const val DEFAULT_REPEATS = 3
    }
}
