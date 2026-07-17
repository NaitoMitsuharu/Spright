package com.example.ble1507

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

data class InterpretedColor(val r: Int, val g: Int, val b: Int, val source: String) {
    fun toColorInt(): Int = android.graphics.Color.rgb(
        r.coerceIn(0, 255),
        g.coerceIn(0, 255),
        b.coerceIn(0, 255),
    )
}

class QwenColorInterpreter(private val context: Context) {
    /** Call once at startup (on a background thread) to pre-load the model. */
    fun warmup() {
        val modelFile = QwenModelStore.preferredModelFile(context) ?: return
        NativeQwenBridge.warmupModel(modelFile.absolutePath)
    }

    fun interpret(text: String, current: InterpretedColor? = null): InterpretedColor? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }

        val contextualRule = RuleColorInterpreter.interpret(normalized, current)
        if (contextualRule != null) {
            return contextualRule
        }

        val modelFile = QwenModelStore.preferredModelFile(context)
        if (modelFile != null) {
            // Balanced mode: one retry with a stricter, anchored prompt for stability.
            var lastElapsedMs = 0L
            for (attempt in 0..1) {
                val prompt = if (attempt == 0) {
                    buildPrompt(normalized, current)
                } else {
                    buildStabilizedRetryPrompt(normalized, current)
                }
                val inferStart = System.currentTimeMillis()
                val raw = NativeQwenBridge.inferColorJson(modelFile.absolutePath, prompt)
                val elapsed = System.currentTimeMillis() - inferStart
                lastElapsedMs = elapsed
                // raw format: {"r":…}|ctx:Xms,pf:Yms,g:Zms  – strip the timing suffix for JSON parsing
                val json = raw?.substringBefore('|')
                val timingDetail = raw?.substringAfter('|', "")?.takeIf { it.isNotEmpty() }
                    ?.let { " [$it]" } ?: ""
                val label = if (attempt == 0) "qwen" else "qwen-retry"
                val source = "$label (${elapsed}ms$timingDetail)"
                val qwenDuty = json?.let { parseColorOutput(it, source) }
                if (qwenDuty != null) {
                    return qwenDuty
                }
            }
            // Both Qwen attempts failed – return a failure marker so callers can show this
            // explicitly rather than silently leaving the previous color unchanged.
            val base = current ?: InterpretedColor(255, 255, 255, "")
            return base.copy(source = "qwen-failed (${lastElapsedMs}ms)")
        }

        return null
    }

    // Include current RGB only for relative adjustments (明るく/暗く etc.).
    // For pure color names, omitting it saves ~8 prefill tokens per call.
    private fun isRelativeAdjustment(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("明るく", "暗く", "brighter", "darker", "lighter", "もっと", "少し", "薄く", "濃く", "鮮やか").any { lower.contains(it) }
    }

    private fun buildPrompt(text: String, current: InterpretedColor?): String =
        if (isRelativeAdjustment(text) && current != null)
            "Current RGB: ${current.r},${current.g},${current.b}\nInput: $text"
        else
            "Input: $text"

    private fun buildStabilizedRetryPrompt(text: String, current: InterpretedColor?): String =
        if (isRelativeAdjustment(text) && current != null)
            "RGB: ${current.r},${current.g},${current.b}. Color: $text"
        else
            "Color: $text"

    // Label → RGB lookup (model outputs a single English word; 1-2 tokens vs ~12 for JSON)
    private val colorLabelMap = mapOf(
        "red"      to InterpretedColor(255,   0,   0, ""),
        "blue"     to InterpretedColor(  0,   0, 255, ""),
        "green"    to InterpretedColor(  0, 255,   0, ""),
        "yellow"   to InterpretedColor(255, 255,   0, ""),
        "white"    to InterpretedColor(255, 255, 255, ""),
        "black"    to InterpretedColor(  0,   0,   0, ""),
        "pink"     to InterpretedColor(255,  51, 153, ""),
        "orange"   to InterpretedColor(255, 115,   0, ""),
        "purple"   to InterpretedColor(153,   0, 255, ""),
        "cyan"     to InterpretedColor(  0, 217, 255, ""),
        "sunset"   to InterpretedColor(255, 100,  50, ""),
        "sunrise"  to InterpretedColor(255, 160, 100, ""),
        "ocean"    to InterpretedColor(  0, 105, 180, ""),
        "sky"      to InterpretedColor(135, 206, 235, ""),
        "forest"   to InterpretedColor( 34, 139,  34, ""),
        "fire"     to InterpretedColor(255,  60,   0, ""),
        "snow"     to InterpretedColor(220, 240, 255, ""),
        "gold"     to InterpretedColor(255, 200,   0, ""),
        "silver"   to InterpretedColor(192, 192, 192, ""),
        "lavender" to InterpretedColor(194, 158, 255, ""),
        "sakura"   to InterpretedColor(255, 184, 209, ""),
        "sad"      to InterpretedColor( 60,  80, 180, ""),
        "happy"    to InterpretedColor(255, 200,  50, ""),
        "angry"    to InterpretedColor(200,  30,  30, ""),
        "calm"     to InterpretedColor( 70, 130, 200, ""),
        "warm"     to InterpretedColor(255, 160,  80, ""),
        "cool"     to InterpretedColor(100, 150, 255, ""),
        "lonely"   to InterpretedColor(100, 100, 160, ""),
        "vibrant"  to InterpretedColor(255,  80,  80, ""),
        "muted"    to InterpretedColor(150, 150, 150, ""),
        // Extended: food / nature / material (model may output these creatively)
        "tomato"   to InterpretedColor(255,  99,  71, ""),
        "rose"     to InterpretedColor(255,  20,  80, ""),
        "coral"    to InterpretedColor(255, 127,  80, ""),
        "peach"    to InterpretedColor(255, 168, 130, ""),
        "mint"     to InterpretedColor(152, 255, 152, ""),
        "teal"     to InterpretedColor(  0, 128, 128, ""),
        "indigo"   to InterpretedColor( 75,   0, 130, ""),
        "violet"   to InterpretedColor(143,   0, 255, ""),
        "amber"    to InterpretedColor(255, 165,   0, ""),
        "crimson"  to InterpretedColor(220,  20,  60, ""),
        "maroon"   to InterpretedColor(128,   0,   0, ""),
        "olive"    to InterpretedColor(107, 142,  35, ""),
        "oak"      to InterpretedColor(139,  90,  43, ""),
        "brown"    to InterpretedColor(139,  69,  19, ""),
        "beige"    to InterpretedColor(245, 245, 220, ""),
        "ivory"    to InterpretedColor(255, 240, 200, ""),
        "turquoise" to InterpretedColor( 64, 224, 208, ""),
        "aqua"     to InterpretedColor(  0, 200, 200, ""),
        "lime"     to InterpretedColor(  0, 255,   0, ""),
        "navy"     to InterpretedColor(  0,   0, 128, ""),
        "gray"     to InterpretedColor(128, 128, 128, ""),
        "grey"     to InterpretedColor(128, 128, 128, ""),
        "charcoal" to InterpretedColor( 54,  69,  79, ""),
        "midnight" to InterpretedColor( 25,  25, 112, ""),
        "dusk"     to InterpretedColor(150, 100, 120, ""),
        "dawn"     to InterpretedColor(255, 180, 130, ""),
        "spring"   to InterpretedColor(200, 255, 150, ""),
        "autumn"   to InterpretedColor(200,  80,  30, ""),
        "earth"    to InterpretedColor(139, 115,  85, ""),
        "stone"    to InterpretedColor(150, 150, 140, ""),
        "sand"     to InterpretedColor(210, 180, 140, ""),
    )

    // Parse model output: try label → R,G,B → JSON (in that order).
    private fun parseColorOutput(raw: String, source: String): InterpretedColor? {
        // Label: extract first alphabetic word and look up
        val label = raw.trim().lowercase()
            .split(Regex("[^a-z]")).firstOrNull { it.isNotEmpty() }
        if (label != null) {
            colorLabelMap[label]?.let { return it.copy(source = source) }
        }
        // R,G,B fallback
        val rgbMatch = Regex("""(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""").find(raw)
        if (rgbMatch != null) {
            return runCatching {
                InterpretedColor(
                    r = rgbMatch.groupValues[1].toInt().coerceIn(0, 255),
                    g = rgbMatch.groupValues[2].toInt().coerceIn(0, 255),
                    b = rgbMatch.groupValues[3].toInt().coerceIn(0, 255),
                    source = source,
                )
            }.getOrNull()
        }
        // JSON fallback (legacy)
        return parseJsonDuty(raw, source)
    }

    private fun parseJsonDuty(json: String, source: String): InterpretedColor? = runCatching {
        val root = JSONObject(extractJsonObject(json))
        InterpretedColor(
            r = root.getInt("r").coerceIn(0, 255),
            g = root.getInt("g").coerceIn(0, 255),
            b = root.getInt("b").coerceIn(0, 255),
            source = source,
        )
    }.getOrNull()

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1)
        }
        return raw
    }
}

private object RuleColorInterpreter {
    private val colorMap = listOf(
        listOf("桜色", "さくらいろ", "sakura", "cherry blossom") to InterpretedColor(255, 184, 209, "rule"),
        listOf("桃色", "ももいろ", "peach", "peach pink") to InterpretedColor(255, 168, 194, "rule"),
        listOf("ラベンダー", "lavender") to InterpretedColor(194, 158, 255, "rule"),
        listOf("クリーム", "cream", "ivory", "アイボリー") to InterpretedColor(255, 240, 199, "rule"),
        listOf("パステルピンク", "pastel pink") to InterpretedColor(255, 194, 214, "rule"),
        listOf("パステル", "pastel") to InterpretedColor(217, 217, 217, "rule"),
        listOf("水色", "cyan", "light blue") to InterpretedColor(0, 217, 255, "rule"),
        listOf("ピンク", "pink") to InterpretedColor(255, 51, 153, "rule"),
        listOf("オレンジ", "orange") to InterpretedColor(255, 115, 0, "rule"),
        listOf("黄色", "黄", "yellow") to InterpretedColor(255, 255, 0, "rule"),
        listOf("紫", "むらさき", "purple") to InterpretedColor(153, 0, 255, "rule"),
        listOf("白", "white") to InterpretedColor(255, 255, 255, "rule"),
        listOf("赤", "red") to InterpretedColor(255, 0, 0, "rule"),
        listOf("緑", "green") to InterpretedColor(0, 255, 0, "rule"),
        listOf("青", "blue") to InterpretedColor(0, 0, 255, "rule"),
        listOf("黒", "消して", "off", "black") to InterpretedColor(0, 0, 0, "rule"),
        // Metallic (keep rule-based – LLMs rarely know exact values)
        listOf("金色", "きんいろ", "gold", "golden") to InterpretedColor(255, 200, 0, "rule"),
        listOf("銀色", "ぎんいろ", "silver") to InterpretedColor(192, 192, 192, "rule"),
        // Natural / atmospheric – rule-based for instant response
        listOf("夕焼け", "ゆうやけ", "sunset", "夕日") to InterpretedColor(255, 100, 50, "rule"),
        listOf("朝焼け", "あさやけ", "sunrise") to InterpretedColor(255, 160, 100, "rule"),
        listOf("空", "そら", "空色", "そらいろ", "sky", "sky blue") to InterpretedColor(135, 206, 235, "rule"),
        listOf("海", "うみ", "ocean", "sea", "marine") to InterpretedColor(0, 105, 180, "rule"),
        listOf("森", "もり", "forest", "jungle") to InterpretedColor(34, 139, 34, "rule"),
        listOf("炎", "ほのお", "fire", "flame") to InterpretedColor(255, 60, 0, "rule"),
        listOf("雪", "ゆき", "snow") to InterpretedColor(220, 240, 255, "rule"),
        listOf("夜", "よる", "night") to InterpretedColor(20, 20, 80, "rule"),
        listOf("月", "つき", "moon", "moonlight") to InterpretedColor(240, 230, 180, "rule"),
        listOf("太陽", "たいよう", "sun", "sunshine", "sunlight") to InterpretedColor(255, 180, 0, "rule"),
        listOf("星", "ほし", "star", "starlight") to InterpretedColor(255, 255, 150, "rule"),
        listOf("草", "くさ", "草原", "meadow") to InterpretedColor(100, 200, 50, "rule"),
        listOf("土", "つち", "earth", "soil", "dirt") to InterpretedColor(130, 90, 50, "rule"),
        listOf("砂", "すな", "sand", "desert") to InterpretedColor(210, 180, 140, "rule"),
        listOf("岩", "いわ", "rock", "stone") to InterpretedColor(130, 130, 120, "rule"),
        listOf("血", "ち", "blood") to InterpretedColor(150, 0, 0, "rule"),
        listOf("氷", "こおり", "ice", "glacier") to InterpretedColor(180, 230, 255, "rule"),
        listOf("霧", "きり", "fog", "mist") to InterpretedColor(200, 200, 210, "rule"),
        // Foods – common color associations
        listOf("たこ焼き", "タコ焼き", "たこやき") to InterpretedColor(150, 90, 50, "rule"),
        listOf("卵", "たまご", "egg", "玉子") to InterpretedColor(255, 220, 80, "rule"),
        listOf("バナナ", "banana") to InterpretedColor(255, 220, 0, "rule"),
        listOf("苺", "いちご", "strawberry") to InterpretedColor(220, 30, 50, "rule"),
        listOf("みかん", "蜜柑") to InterpretedColor(255, 140, 0, "rule"),
        listOf("ぶどう", "grape") to InterpretedColor(100, 0, 150, "rule"),
        listOf("レモン", "lemon") to InterpretedColor(255, 250, 0, "rule"),
        listOf("チョコ", "チョコレート", "chocolate") to InterpretedColor(80, 40, 20, "rule"),
        listOf("カレー", "curry") to InterpretedColor(200, 140, 40, "rule"),
        listOf("抹茶", "matcha") to InterpretedColor(120, 180, 50, "rule"),
        listOf("コーヒー", "珈琲", "coffee") to InterpretedColor(80, 50, 30, "rule"),
        listOf("ミルク", "牛乳", "milk") to InterpretedColor(250, 250, 240, "rule"),
        listOf("りんご", "リンゴ", "apple") to InterpretedColor(200, 30, 30, "rule"),
        listOf("すいか", "スイカ", "watermelon") to InterpretedColor(220, 50, 60, "rule"),
        listOf("抹茶アイス", "green tea") to InterpretedColor(120, 180, 80, "rule"),
        listOf("醤油", "しょうゆ", "soy sauce") to InterpretedColor(70, 40, 20, "rule"),
        listOf("味噌", "みそ") to InterpretedColor(180, 120, 60, "rule"),
        // Objects / materials
        listOf("机", "つくえ", "desk", "木", "木材", "wood", "timber") to InterpretedColor(120, 80, 40, "rule"),
        listOf("鉄", "てつ", "iron", "metal", "steel") to InterpretedColor(100, 100, 110, "rule"),
        listOf("草木", "植物", "plant") to InterpretedColor(80, 160, 60, "rule"),
        listOf("空気", "くうき", "air") to InterpretedColor(200, 230, 255, "rule"),
        // Animals
        listOf("ライオン", "lion") to InterpretedColor(200, 160, 50, "rule"),
        listOf("ぞう", "象", "elephant") to InterpretedColor(150, 150, 140, "rule"),
        listOf("カラス", "crow", "raven") to InterpretedColor(30, 30, 30, "rule"),
        listOf("白鳥", "はくちょう", "swan") to InterpretedColor(250, 250, 255, "rule"),
        // Brand colors
        listOf("line", "ライン") to InterpretedColor(6, 199, 85, "rule"),
        listOf("twitter", "ツイッター", "x（旧twitter）", "旧twitter") to InterpretedColor(29, 161, 242, "rule"),
        listOf("youtube", "ユーチューブ") to InterpretedColor(255, 0, 0, "rule"),
        listOf("instagram", "インスタ", "インスタグラム") to InterpretedColor(193, 53, 132, "rule"),
        listOf("facebook", "フェイスブック") to InterpretedColor(24, 119, 242, "rule"),
    )

    fun interpret(text: String, current: InterpretedColor? = null): InterpretedColor? {
        val lower = text.lowercase()
        val modifierOnly = parseRelativeAdjustment(lower, current)
        if (modifierOnly != null) {
            return modifierOnly
        }

        val base = colorMap.firstOrNull { (keys, _) -> keys.any { lower.contains(it.lowercase()) } }?.second
            ?: parseRgbDuty(lower)
            ?: parseHexColor(lower)
            ?: return null
        val softened = applyToneKeywords(base, lower)
        return applyBrightnessKeywords(softened, lower)
    }

    private fun parseRgbDuty(text: String): InterpretedColor? {
        val numbers = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
        if (numbers.size < 3 || !text.contains("rgb")) {
            return null
        }
        return InterpretedColor(
            r = numbers[0].coerceIn(0, 255),
            g = numbers[1].coerceIn(0, 255),
            b = numbers[2].coerceIn(0, 255),
            source = "rule",
        )
    }

    private fun parseHexColor(text: String): InterpretedColor? {
        val hex = Regex("#([0-9a-fA-F]{6})").find(text)?.groupValues?.getOrNull(1) ?: return null
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        return InterpretedColor(
            r = r,
            g = g,
            b = b,
            source = "rule",
        )
    }

    private fun parseRelativeAdjustment(text: String, current: InterpretedColor?): InterpretedColor? {
        val base = current ?: return null
        val hasRelativeKeyword = listOf(
            "もっと", "少し", "濃", "薄", "柔ら", "soft", "vivid", "dark", "bright", "明る", "暗"
        ).any { text.contains(it) }
        if (!hasRelativeKeyword) {
            return null
        }

        val toned = applyToneKeywords(base.copy(source = "rule-relative"), text)
        return applyBrightnessKeywords(toned, text).copy(source = "rule-relative")
    }

    private fun applyToneKeywords(base: InterpretedColor, text: String): InterpretedColor {
        val gentle = text.contains("柔ら") || text.contains("soft") || text.contains("パステル")
        val vivid = text.contains("濃") || text.contains("vivid") || text.contains("鮮")
        return when {
            gentle -> blendWithWhite(base, ratio = if (text.contains("もっと")) 0.45f else 0.28f)
            vivid -> increaseSaturation(base, gain = if (text.contains("もっと")) 1.45f else 1.2f)
            else -> base
        }
    }

    private fun applyBrightnessKeywords(base: InterpretedColor, text: String): InterpretedColor {
        val scale = when {
            text.contains("半分") || text.contains("half") -> 0.5f
            text.contains("もっと明") || text.contains("much brighter") -> 1.45f
            text.contains("明") || text.contains("bright") -> if (text.contains("少し")) 1.12f else 1.25f
            text.contains("もっと暗") || text.contains("much darker") -> 0.5f
            text.contains("暗") || text.contains("dark") -> if (text.contains("少し")) 0.82f else 0.65f
            else -> 1.0f
        }
        return base.copy(
            r = (base.r * scale).roundToInt().coerceIn(0, 255),
            g = (base.g * scale).roundToInt().coerceIn(0, 255),
            b = (base.b * scale).roundToInt().coerceIn(0, 255),
        )
    }

    private fun blendWithWhite(base: InterpretedColor, ratio: Float): InterpretedColor {
        val w = 255
        val k = ratio.coerceIn(0f, 0.9f)
        return base.copy(
            r = (base.r * (1f - k) + w * k).roundToInt().coerceIn(0, 255),
            g = (base.g * (1f - k) + w * k).roundToInt().coerceIn(0, 255),
            b = (base.b * (1f - k) + w * k).roundToInt().coerceIn(0, 255),
        )
    }

    private fun increaseSaturation(base: InterpretedColor, gain: Float): InterpretedColor {
        val avg = (base.r + base.g + base.b) / 3f
        fun saturate(v: Int): Int = (avg + (v - avg) * gain).roundToInt().coerceIn(0, 255)
        return base.copy(r = saturate(base.r), g = saturate(base.g), b = saturate(base.b))
    }
}