package com.example.ble1507

import android.content.Context
import kotlin.math.sqrt
import kotlin.math.roundToInt

data class InterpretedColor(val r: Int, val g: Int, val b: Int, val source: String) {
    fun toColorInt(): Int = android.graphics.Color.rgb(
        r.coerceIn(0, 255),
        g.coerceIn(0, 255),
        b.coerceIn(0, 255),
    )
}

data class ColorResolution(
    val rgb: InterpretedColor,
    val source: String,
    val speechMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val bleMs: Long = 0L,
    val totalMs: Long = 0L,
)

class QwenColorInterpreter(private val context: Context) {
    @Volatile
    var isReady: Boolean = false
        private set

    /** Loads the selected GGUF and caches its static system prompt. */
    fun warmup(): Boolean {
        val modelFile = QwenModelStore.preferredModelFile(context) ?: return false
        return NativeQwenBridge.warmupModel(modelFile.absolutePath).also { isReady = it }
    }

    fun interpret(text: String, current: InterpretedColor? = null): ColorResolution? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }

        // Relative instructions already have a complete deterministic parse. The
        // on-device models produced effectively constant controls here and the
        // normalization layer had to replace them, so paying inference latency did
        // not change the result. Keep the LLM for genuinely semantic absolute
        // descriptions, where it makes a visible contribution to the exhibition.
        val relativeExpected = current?.let {
            RuleColorInterpreter.interpretRelative(normalized, it)
        }
        if (relativeExpected != null) {
            val resolved = relativeExpected.copy(source = "rule-relative")
            return ColorResolution(rgb = resolved, source = resolved.source)
        }
        val contextualRule = RuleColorInterpreter.interpret(normalized, current)
        if (contextualRule != null) {
            return ColorResolution(
                rgb = contextualRule,
                source = contextualRule.source,
            )
        }

        val modelFile = QwenModelStore.preferredModelFile(context)
            ?: return null
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val raw = NativeQwenBridge.inferColorHex(modelFile.absolutePath, buildColorPrompt(normalized))
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
        val output = raw?.substringBefore('|')
            ?: return null
        val timing = raw.substringAfter('|', "").takeIf(String::isNotEmpty)
        val baseSource = buildString {
            append(BuildConfig.COLOR_MODEL_ID)
            append(" (")
            append(elapsedMs)
            append("ms")
            timing?.let { append(" [$it]") }
            append(")")
        }
        val color = parseHexColor(output, baseSource) ?: return null
        // #000000 is a real "LED off" command, not a harmless fallback color.
        // Named black/off requests are handled by the deterministic rule path, so
        // exact black from the semantic LLM path is an inference failure and must
        // never be written to BLE.
        if (isUnsafeLlmBlack(color)) return null
        val guardedColor = applyAdditiveMixGuard(normalized, color)
        return ColorResolution(
            rgb = guardedColor,
            source = guardedColor.source,
            inferenceMs = elapsedMs,
        )
    }

    private fun buildColorPrompt(text: String): String {
        val thinkingSwitch = if (BuildConfig.COLOR_MODEL_ID.startsWith("qwen3")) "/no_think\n" else ""
        val mixedAnchors = mixedColorAnchors(text)
        val mixingHint = if (mixedAnchors.size >= 2) {
            "指定色のRGB光基準: ${mixedAnchors.joinToString { "${it.first}=${it.second}" }}。" +
                "両方の発光成分を同じ強さで加えた中間色を答える。\n"
        } else {
            ""
        }
        return "入力: $text\n$mixingHint${thinkingSwitch}出力は#RRGGBBのみ:"
    }

}

private fun mixedColorAnchors(text: String): List<Pair<String, String>> {
    val normalized = text.lowercase()
    val hasMixIntent = listOf("の間", "あいだ", "中間", "混ぜ", "混ざ", "between", "mix", "blend")
        .any(normalized::contains)
    if (!hasMixIntent) return emptyList()
    val colorFamilies = listOf(
        Triple("赤", listOf("赤", "red"), "#FF0000"),
        Triple("黄", listOf("黄", "yellow"), "#FFFF00"),
        Triple("緑", listOf("緑", "green"), "#00FF00"),
        Triple("青", listOf("青", "blue"), "#0000FF"),
        Triple("紫", listOf("紫", "purple"), "#9900FF"),
        Triple("ピンク", listOf("ピンク", "pink"), "#FF3399"),
        Triple("白", listOf("白", "white"), "#FFFFFF"),
        Triple("黒", listOf("黒", "black"), "#000000"),
    )
    return colorFamilies.mapNotNull { (label, words, hex) ->
        if (words.any(normalized::contains)) label to hex else null
    }.take(2)
}

internal fun isUnsafeLlmBlack(color: InterpretedColor): Boolean =
    color.r == 0 && color.g == 0 && color.b == 0

internal fun applyAdditiveMixGuard(text: String, candidate: InterpretedColor): InterpretedColor {
    val anchors = mixedColorAnchors(text)
    if (anchors.size < 2) return candidate
    val rgbs = anchors.map { (_, hex) ->
        intArrayOf(
            hex.substring(1, 3).toInt(16),
            hex.substring(3, 5).toInt(16),
            hex.substring(5, 7).toInt(16),
        )
    }
    val sums = IntArray(3) { channel -> rgbs.sumOf { it[channel] } }
    val largest = sums.maxOrNull()?.coerceAtLeast(1) ?: return candidate
    val expected = IntArray(3) { channel ->
        (sums[channel] * 255f / largest).roundToInt().coerceIn(0, 255)
    }
    val candidateLargest = maxOf(candidate.r, candidate.g, candidate.b).coerceAtLeast(1)
    val distance = sqrt(
        (0..2).sumOf { channel ->
            val actual = intArrayOf(candidate.r, candidate.g, candidate.b)[channel].toDouble() / candidateLargest
            val target = expected[channel].toDouble() / 255.0
            (actual - target) * (actual - target)
        },
    )
    if (distance <= 0.55) return candidate
    return InterpretedColor(
        r = expected[0],
        g = expected[1],
        b = expected[2],
        source = "${candidate.source} additive-guard",
    )
}

internal fun isPlausibleRelativeResult(
    current: InterpretedColor,
    expected: InterpretedColor,
    candidate: InterpretedColor,
): Boolean {
    val expectedDelta = doubleArrayOf(
        (expected.r - current.r).toDouble(),
        (expected.g - current.g).toDouble(),
        (expected.b - current.b).toDouble(),
    )
    val candidateDelta = doubleArrayOf(
        (candidate.r - current.r).toDouble(),
        (candidate.g - current.g).toDouble(),
        (candidate.b - current.b).toDouble(),
    )
    val expectedLength = sqrt(expectedDelta.sumOf { it * it })
    val candidateLength = sqrt(candidateDelta.sumOf { it * it })
    if (expectedLength < 1.0) {
        return candidateLength <= 24.0
    }
    if (candidateLength < 1.0) {
        return false
    }
    val dot = expectedDelta.indices.sumOf { expectedDelta[it] * candidateDelta[it] }
    val cosine = dot / (expectedLength * candidateLength)
    val magnitudeRatio = candidateLength / expectedLength
    return cosine >= 0.35 && magnitudeRatio in 0.25..3.0
}

internal fun parseHexColor(raw: String, source: String): InterpretedColor? = runCatching {
    require(Regex("^#[0-9A-F]{6}$").matches(raw))
    InterpretedColor(
        r = raw.substring(1, 3).toInt(16),
        g = raw.substring(3, 5).toInt(16),
        b = raw.substring(5, 7).toInt(16),
        source = source,
    )
}.getOrNull()

internal object RuleColorInterpreter {
    private data class RelativeColorTarget(
        val code: String,
        val keys: List<String>,
        val color: InterpretedColor,
    )

    private val relativeColorTargets = listOf(
        RelativeColorTarget("RED", listOf("赤", "red"), InterpretedColor(255, 0, 0, "rule-relative")),
        RelativeColorTarget("ORANGE", listOf("オレンジ", "橙", "orange"), InterpretedColor(255, 128, 0, "rule-relative")),
        RelativeColorTarget("YELLOW", listOf("黄色", "黄", "yellow"), InterpretedColor(255, 255, 0, "rule-relative")),
        RelativeColorTarget("GREEN", listOf("緑", "green"), InterpretedColor(0, 255, 0, "rule-relative")),
        RelativeColorTarget("CYAN", listOf("水色", "シアン", "cyan"), InterpretedColor(0, 220, 255, "rule-relative")),
        RelativeColorTarget("BLUE", listOf("青", "blue"), InterpretedColor(0, 0, 255, "rule-relative")),
        RelativeColorTarget("PURPLE", listOf("紫", "purple"), InterpretedColor(160, 0, 255, "rule-relative")),
        RelativeColorTarget("PINK", listOf("ピンク", "桃", "pink"), InterpretedColor(255, 64, 160, "rule-relative")),
        RelativeColorTarget("WARM", listOf("暖色", "温かい色", "warm"), InterpretedColor(255, 96, 24, "rule-relative")),
        RelativeColorTarget("COOL", listOf("寒色", "冷たい色", "cool"), InterpretedColor(24, 96, 255, "rule-relative")),
        RelativeColorTarget("BLACK", listOf("黒", "black"), InterpretedColor(0, 0, 0, "rule-relative")),
    )

    private val relativeCodeTargets = mapOf(
        "RED" to InterpretedColor(255, 0, 0, "rule-relative"),
        "ORANGE" to InterpretedColor(255, 128, 0, "rule-relative"),
        "YELLOW" to InterpretedColor(255, 255, 0, "rule-relative"),
        "GREEN" to InterpretedColor(0, 255, 0, "rule-relative"),
        "CYAN" to InterpretedColor(0, 220, 255, "rule-relative"),
        "BLUE" to InterpretedColor(0, 0, 255, "rule-relative"),
        "PURPLE" to InterpretedColor(160, 0, 255, "rule-relative"),
        "PINK" to InterpretedColor(255, 64, 160, "rule-relative"),
        "WARM" to InterpretedColor(255, 96, 24, "rule-relative"),
        "COOL" to InterpretedColor(24, 96, 255, "rule-relative"),
        "BLACK" to InterpretedColor(0, 0, 0, "rule-relative"),
    )

    private val colorMap = listOf(
        // Explicit emotion names are stable reference intents. Nuanced or
        // unknown descriptions still fall through to Qwen.
        listOf("怒り", "激怒", "腹が立", "angry", "anger") to
            InterpretedColor(255, 64, 32, "rule"),
        listOf("悲し", "喪失感", "涙が止まら", "sadness", "sad") to
            InterpretedColor(80, 96, 128, "rule"),
        listOf("喜び", "うれし", "嬉し", "joy") to
            InterpretedColor(255, 208, 64, "rule"),
        listOf("安心", "安堵", "穏やか", "心がほどけ", "relief") to
            InterpretedColor(160, 192, 128, "rule"),
        listOf("嫉妬", "jealous") to InterpretedColor(64, 128, 64, "rule"),
        listOf("恐怖", "怖い", "fear") to InterpretedColor(48, 16, 80, "rule"),
        listOf("愛情", "愛おし", "love") to InterpretedColor(192, 80, 128, "rule"),
        listOf("孤独", "ひとりぼっち", "lonely") to
            InterpretedColor(80, 96, 128, "rule"),
        listOf("郷愁", "ノスタルジ", "nostalgia") to InterpretedColor(192, 128, 64, "rule"),
        listOf("興奮", "胸が高鳴", "excitement") to InterpretedColor(255, 64, 32, "rule"),
        // Dark and deep blues/blacks must precede generic 青 / 黒 substring rules.
        listOf("濃紺", "のうこん") to InterpretedColor(0, 0, 96, "rule"),
        listOf("紺色", "紺", "ネイビー", "navy") to InterpretedColor(0, 0, 128, "rule"),
        listOf("墨色", "すみいろ", "墨") to InterpretedColor(26, 26, 26, "rule"),
        listOf("漆黒", "しっこく", "真っ黒") to InterpretedColor(0, 0, 0, "rule"),
        listOf("深夜") to InterpretedColor(10, 10, 51, "rule"),
        // Japanese traditional colors must precede generic 黄 / 青 / 赤 substring rules.
        listOf("山吹色", "やまぶきいろ") to InterpretedColor(248, 181, 0, "rule"),
        listOf("瑠璃色", "るりいろ") to InterpretedColor(30, 80, 162, "rule"),
        listOf("浅葱色", "あさぎいろ") to InterpretedColor(0, 163, 175, "rule"),
        listOf("萌黄色", "もえぎいろ") to InterpretedColor(168, 201, 127, "rule"),
        listOf("臙脂色", "えんじ色", "えんじいろ") to InterpretedColor(185, 64, 71, "rule"),
        listOf("群青色", "ぐんじょういろ") to InterpretedColor(76, 108, 179, "rule"),
        listOf("鴇色", "ときいろ") to InterpretedColor(244, 179, 194, "rule"),
        listOf("鶯色", "うぐいすいろ") to InterpretedColor(146, 140, 54, "rule"),
        listOf("柿渋色", "かきしぶいろ") to InterpretedColor(159, 86, 58, "rule"),
        listOf("藍色", "あいいろ") to InterpretedColor(22, 94, 131, "rule"),
        // Canonical entity colors: factual names use stable references; free-form
        // descriptions and emotions continue through the local LLM.
        listOf(
            "xのブランド",
            "x のブランド",
            "エックスのブランド",
            "xのロゴ",
            "x のロゴ",
            "エックスのロゴ",
        ) to
            InterpretedColor(0, 0, 0, "rule"),
        listOf("discord", "ディスコード") to InterpretedColor(88, 101, 242, "rule"),
        listOf("spotify", "スポティファイ") to InterpretedColor(29, 185, 84, "rule"),
        listOf("nintendo", "任天堂", "ニンテンドー") to InterpretedColor(230, 0, 18, "rule"),
        listOf("tiktok", "tik tok", "ティックトック") to InterpretedColor(37, 244, 238, "rule"),
        listOf("ピカチュウ", "pikachu") to InterpretedColor(255, 213, 0, "rule"),
        listOf("ドラえもん", "doraemon") to InterpretedColor(0, 158, 219, "rule"),
        listOf("アンパンマン", "anpanman") to InterpretedColor(217, 43, 43, "rule"),
        listOf("初音ミク", "はつねミク", "ミク色", "hatsune miku") to
            InterpretedColor(0, 183, 194, "rule"),
        listOf("マリオ", "mario") to InterpretedColor(229, 37, 33, "rule"),
        listOf("ルイージ", "luigi") to InterpretedColor(67, 176, 71, "rule"),
        listOf("カービィ", "kirby") to InterpretedColor(255, 182, 193, "rule"),
        listOf("ソニック", "sonic") to InterpretedColor(0, 98, 190, "rule"),
        listOf("トトロ", "totoro") to InterpretedColor(100, 105, 100, "rule"),
        listOf("ミニオン", "minion") to InterpretedColor(255, 224, 54, "rule"),
        listOf("ブルーベリー", "blueberry") to InterpretedColor(70, 60, 130, "rule"),
        listOf("サーモン", "salmon") to InterpretedColor(250, 128, 114, "rule"),
        listOf("かぼちゃ", "カボチャ", "南瓜", "pumpkin") to InterpretedColor(230, 126, 20, "rule"),
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
        listOf("血", "血液", "blood") to InterpretedColor(150, 0, 0, "rule"),
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

    // Short basic color names are especially prone to stealing nuanced phrases
    // such as "緑と青の間の色". They are rule-resolved only when the normalized
    // utterance is itself a direct color request. Longer factual names (foods,
    // brands, traditional colors, and so on) retain their existing fast lookup.
    private val standaloneColorKeys = setOf(
        "水色", "cyan", "light blue",
        "ピンク", "pink",
        "オレンジ", "orange",
        "黄色", "黄", "yellow",
        "紫", "むらさき", "purple",
        "白", "white",
        "赤", "red",
        "緑", "green",
        "青", "blue",
        "黒", "消して", "off", "black",
    )

    private val directColorRequestSuffixes = listOf(
        "", "色", "にして", "色にして", "にしてください", "色にしてください",
        "に変えて", "色に変えて", "に変えてください", "色に変えてください",
        "でお願い", "色でお願い", "でお願いします", "色でお願いします",
        "をお願い", "色をお願い", "をお願いします", "色をお願いします",
        "がいい", "色がいい", "くして", "くしてください", "please",
    )

    fun interpret(text: String, current: InterpretedColor? = null): InterpretedColor? {
        val lower = text.lowercase()
        val modifierOnly = current?.let { interpretRelative(lower, it) }
        if (modifierOnly != null) {
            return modifierOnly
        }

        // Check mixed-color intent before any modifier synthesis. Otherwise a
        // phrase such as "暗い緑と青の間" could be consumed as merely "暗い緑".
        if (isMixedBasicColorDescription(lower)) {
            return null
        }

        // Absolute "modifier + color word" phrases (for example "黒に近い青") are
        // synthesized in HSV before the substring color map so dark and pale
        // variants stay current-independent instead of collapsing to a base hue.
        val composed = composeModifiedAbsoluteColor(lower)
        if (composed != null) {
            return composed
        }

        // A simile qualifying a basic color word ("月明かりのような青") is a nuanced
        // description for the LLM; the bare color-word substring must not shortcut it.
        if ((lower.contains("のよう") || lower.contains("みたい")) && containsComposableColorWord(lower)) {
            return null
        }

        val base = colorMap.firstOrNull { (keys, _) -> keys.any { matchesColorMapKey(lower, it) } }?.second
            ?: parseRgbDuty(lower)
            ?: parseHexColor(lower)
            ?: return null
        val softened = applyToneKeywords(base, lower)
        return applyBrightnessKeywords(softened, lower)
    }

    internal fun interpretRelative(text: String, current: InterpretedColor): InterpretedColor? =
        parseRelativeAdjustment(text.lowercase(), current)

    private fun matchesColorMapKey(text: String, key: String): Boolean {
        val normalizedKey = key.lowercase()
        if (normalizedKey !in standaloneColorKeys) return text.contains(normalizedKey)
        val compact = text
            .replace(Regex("[\\s　、。,.！!？?]+"), "")
            .trim()
        val compactKey = normalizedKey.replace(" ", "")
        return directColorRequestSuffixes.any { suffix -> compact == compactKey + suffix }
    }

    private fun isMixedBasicColorDescription(text: String): Boolean {
        val hasMixIntent = listOf(
            "と", "の間", "あいだ", "中間", "混ぜ", "混ざ", "境目", "グラデーション",
            "between", "mix", "blend",
        ).any(text::contains)
        if (!hasMixIntent) return false
        val matchedFamilies = composableBaseColors.count { (keys, _) ->
            keys.any { text.contains(it.lowercase()) }
        }
        return matchedFamilies >= 2
    }

    private data class HsvBase(val hue: Float, val saturation: Float, val value: Float)

    // Base hues (S=1, V=1 unless noted) for the limited set of color words that
    // participate in "modifier + color" synthesis. 紺 (navy) starts dark; 白
    // (white) starts desaturated.
    private val composableBaseColors: List<Pair<List<String>, HsvBase>> = listOf(
        listOf("紺", "ネイビー", "navy") to HsvBase(240f, 1f, 0.50f),
        listOf("水色", "シアン", "cyan") to HsvBase(190f, 1f, 1f),
        listOf("赤", "red") to HsvBase(0f, 1f, 1f),
        listOf("オレンジ", "orange") to HsvBase(30f, 1f, 1f),
        listOf("黄", "yellow") to HsvBase(60f, 1f, 1f),
        listOf("緑", "green") to HsvBase(120f, 1f, 1f),
        listOf("青", "blue") to HsvBase(240f, 1f, 1f),
        listOf("紫", "purple") to HsvBase(280f, 1f, 1f),
        listOf("ピンク", "pink") to HsvBase(330f, 1f, 1f),
        listOf("白", "white") to HsvBase(0f, 0f, 1f),
    )

    /**
     * Synthesizes an absolute color from a "modifier + color word" phrase such as
     * "黒に近い青" or "淡い青". Metaphors (のよう/みたい) and relative phrases return
     * null so they continue to the LLM or the relative path. Returns null when no
     * modifier is present so plain color words fall through to [colorMap].
     */
    private fun composeModifiedAbsoluteColor(lower: String): InterpretedColor? {
        val metaphor = lower.contains("のよう") || lower.contains("みたい")
        if (metaphor) return null
        // Shares the relative marker vocabulary so the two gates cannot drift.
        // Adnominal modifiers ("淡い", "濃い", "薄い") are not markers, so absolute
        // "modifier + color word" phrases still reach the synthesis below.
        if (hasRelativeMarker(lower)) return null

        val base = composableBaseColors.firstOrNull { (keys, _) ->
            keys.any { lower.contains(it.lowercase()) }
        }?.second ?: return null
        val isNavy = base.value <= 0.50f && base.saturation >= 1f && base.hue == 240f

        var saturation = base.saturation
        var value = base.value
        when {
            lower.contains("黒に近い") || lower.contains("ほぼ黒") || lower.contains("限りなく黒") ->
                value = 0.12f
            lower.contains("ほとんど消え") || lower.contains("消えそう") || lower.contains("消えかけ") ->
                value = 0.06f
            lower.contains("深夜") -> {
                saturation = 1f
                value = 0.15f
            }
            lower.contains("深い") -> value = 0.45f
            lower.contains("暗い") || lower.contains("ダーク") || lower.contains("dark") ->
                value = 0.35f
            lower.contains("濃い") || lower.contains("濃") -> {
                saturation = 1f
                value = if (isNavy) 0.38f else 0.65f
            }
            lower.contains("淡い") || lower.contains("薄い") || lower.contains("パステル") -> {
                saturation = 0.35f
                value = 1f
            }
            lower.contains("鮮やか") || lower.contains("ビビッド") || lower.contains("vivid") -> {
                saturation = 1f
                value = 1f
            }
            else -> return null
        }
        return hsvToInterpretedColor(base.hue, saturation, value, "rule")
    }

    // Pure-Kotlin HSV→RGB so unit tests run on the JVM without android.graphics.
    private fun hsvToInterpretedColor(
        hue: Float,
        saturation: Float,
        value: Float,
        source: String,
    ): InterpretedColor {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = value - c
        val (rp, gp, bp) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return InterpretedColor(
            r = ((rp + m) * 255f).roundToInt().coerceIn(0, 255),
            g = ((gp + m) * 255f).roundToInt().coerceIn(0, 255),
            b = ((bp + m) * 255f).roundToInt().coerceIn(0, 255),
            source = source,
        )
    }

    internal fun interpretRelativeControl(
        code: String,
        originalText: String,
        current: InterpretedColor,
    ): InterpretedColor? {
        val match = Regex(
            "^(NONE|RED|ORANGE|YELLOW|GREEN|CYAN|BLUE|PURPLE|PINK|WARM|COOL|BLACK),([0-6]),([0-4]),([0-4]);$",
        ).matchEntire(code) ?: return null
        val targetCode = match.groupValues[1]
        val level = match.groupValues[2].toInt()
        val saturationCode = match.groupValues[3].toInt()
        val valueCode = match.groupValues[4].toInt()
        if (targetCode == "NONE" && level != 0) {
            return null
        }
        var result = current.copy(source = "rule-relative")
        if (targetCode != "NONE" && level != 0) {
            val target = relativeCodeTargets[targetCode] ?: return null
            val away = level >= 4
            val qualitativeLevel = if (away) level - 3 else level
            val ratio = Regex("(\\d{1,3})\\s*%")
                .find(originalText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.coerceIn(1, 80)
                ?.div(100f)
                ?: when (qualitativeLevel) {
                    1 -> 0.18f
                    2 -> 0.28f
                    else -> 0.42f
                }
            result = blendColors(result, if (away) oppositeOf(target) else target, ratio)
        }
        result = when (saturationCode) {
            1 -> increaseSaturation(result, 0.88f)
            2 -> increaseSaturation(result, 0.72f)
            3 -> increaseSaturation(result, 1.12f)
            4 -> increaseSaturation(result, 1.45f)
            else -> result
        }
        val brightnessScale = when (valueCode) {
            1 -> 0.82f
            2 -> 0.50f
            3 -> 1.12f
            4 -> 1.45f
            else -> 1.0f
        }
        return result.copy(
            r = (result.r * brightnessScale).roundToInt().coerceIn(0, 255),
            g = (result.g * brightnessScale).roundToInt().coerceIn(0, 255),
            b = (result.b * brightnessScale).roundToInt().coerceIn(0, 255),
            source = "rule-relative",
        )
    }

    internal fun normalizeRelativeControl(code: String, originalText: String): String? {
        val match = Regex(
            "^(NONE|RED|ORANGE|YELLOW|GREEN|CYAN|BLUE|PURPLE|PINK|WARM|COOL|BLACK),([0-6]),([0-4]),([0-4]);$",
        ).matchEntire(code) ?: return null
        val lower = originalText.lowercase()
        val explicitTarget = relativeColorTargets.firstOrNull { target ->
            target.keys.any { lower.contains(it) }
        }?.code
        val normalizedTarget = explicitTarget ?: "NONE"
        // NONE means "no hue direction", so the level must be cleared as well;
        // otherwise interpretRelativeControl rejects the pair and always falls back.
        val normalizedLevel = if (normalizedTarget == "NONE") "0" else match.groupValues[2]
        return "$normalizedTarget,$normalizedLevel,${match.groupValues[3]},${match.groupValues[4]};"
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

    // A relative command must contain an explicit marker: either an anchor that
    // references the current color ("もっと", "今の") or an adverbial change form
    // ("明るく", "彩度を下げ"). Tone adjectives alone ("新鮮な", "濃霧") describe an
    // absolute color and must not enter the relative path.
    private val relativeMarkerKeywords = listOf(
        "もっと", "もう少し", "少し", "ちょっと", "やや", "ほんのり", "さらに",
        "かなり", "強く", "大きく", "今の", "現在", "そのまま", "保った", "キープ",
        "近づけ", "寄せ", "っぽく", "寄り", "足し", "加え", "増や", "抑え", "減ら",
        "弱め", "弱く", "抜い", "半分", "明るく", "暗く", "濃く", "薄く", "淡く",
        "鮮やかに", "鮮明に", "彩度を", "彩度は", "明度", "下げ", "上げ", "戻し",
        "more", "less", "keep", "slightly",
    )
    private fun hasRelativeMarker(text: String): Boolean =
        relativeMarkerKeywords.any { text.contains(it) }
    private fun containsComposableColorWord(text: String): Boolean =
        composableBaseColors.any { (keys, _) -> keys.any { text.contains(it.lowercase()) } }

    private fun parseRelativeAdjustment(text: String, current: InterpretedColor?): InterpretedColor? {
        val base = current ?: return null
        // Without an explicit relative marker the utterance is an absolute
        // description and must fall through to absolute synthesis / colorMap / LLM.
        if (!hasRelativeMarker(text)) {
            return null
        }
        val target = relativeColorTargets.firstOrNull { candidate ->
            candidate.keys.any { text.contains(it) }
        }?.color
        val hasToneOrBrightnessCommand = listOf(
            "濃", "薄", "淡", "柔ら", "soft", "vivid", "鮮やか", "鮮烈", "彩度", "深み", "深く",
            "dark", "bright", "light", "明る", "暗",
        ).any { text.contains(it) }
        val hasColorDirectionCommand = target != null && listOf(
            "もっと", "少し", "ちょっと", "やや", "ほんのり", "強く",
            "っぽ", "寄り", "み", "足", "加", "近づ", "more",
        ).any { text.contains(it) }
        if (!hasToneOrBrightnessCommand && !hasColorDirectionCommand) {
            return null
        }

        val shifted = target?.let {
            val direction = if (listOf("減ら", "抑え", "弱く", "抜い", "less").any(text::contains)) {
                oppositeOf(it)
            } else {
                it
            }
            blendColors(base, direction, relativeStrength(text))
        } ?: base
        val toned = applyToneKeywords(shifted.copy(source = "rule-relative"), text)
        return applyBrightnessKeywords(toned, text).copy(source = "rule-relative")
    }

    private fun applyToneKeywords(base: InterpretedColor, text: String): InterpretedColor {
        val lowerSaturation = listOf(
            "彩度を下", "彩度下げ", "彩度を低", "くすませ", "グレー寄り", "desaturate",
        ).any { text.contains(it) }
        // "深み" deepens the color: more saturation but visibly lower value on the
        // LED. Evaluated before gentle/vivid; "深夜" is a color noun, not a tone.
        val deepen = text.contains("深") && !text.contains("深夜")
        val gentle = text.contains("柔ら") ||
            text.contains("soft") ||
            text.contains("パステル") ||
            text.contains("薄") ||
            text.contains("淡")
        val vivid = text.contains("濃") ||
            text.contains("vivid") ||
            text.contains("鮮やか") ||
            text.contains("鮮烈") ||
            text.contains("彩度を上") ||
            text.contains("彩度上げ") ||
            text.contains("彩度を高")
        return when {
            lowerSaturation -> increaseSaturation(
                base,
                gain = if (isSmallAdjustment(text)) 0.88f else 0.72f,
            )
            deepen -> scaleValue(increaseSaturation(base, gain = 1.15f), factor = 0.75f)
            gentle -> blendWithWhite(
                base,
                ratio = when {
                    isStrongAdjustment(text) -> 0.45f
                    isSmallAdjustment(text) -> 0.15f
                    else -> 0.28f
                },
            )
            // "濃い" on an LED reads as high saturation plus a slightly lower value.
            vivid -> scaleValue(
                increaseSaturation(
                    base,
                    gain = when {
                        isStrongAdjustment(text) -> 1.45f
                        isSmallAdjustment(text) -> 1.12f
                        else -> 1.25f
                    },
                ),
                factor = 0.85f,
            )
            else -> base
        }
    }

    private fun scaleValue(base: InterpretedColor, factor: Float): InterpretedColor = base.copy(
        r = (base.r * factor).roundToInt().coerceIn(0, 255),
        g = (base.g * factor).roundToInt().coerceIn(0, 255),
        b = (base.b * factor).roundToInt().coerceIn(0, 255),
    )

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

    private fun relativeStrength(text: String): Float {
        val explicitPercent = Regex("(\\d{1,3})\\s*%")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(1, 80)
        return when {
            explicitPercent != null -> explicitPercent / 100f
            isStrongAdjustment(text) -> 0.42f
            isSmallAdjustment(text) -> 0.18f
            else -> 0.28f
        }
    }

    private fun isSmallAdjustment(text: String): Boolean =
        listOf("少し", "ちょっと", "やや", "ほんのり", "slightly", "a little").any { text.contains(it) }

    private fun isStrongAdjustment(text: String): Boolean =
        listOf("もっと", "かなり", "強く", "大きく", "much", "more").any { text.contains(it) }

    private fun blendColors(
        base: InterpretedColor,
        target: InterpretedColor,
        ratio: Float,
    ): InterpretedColor {
        val k = ratio.coerceIn(0f, 0.8f)
        fun blend(from: Int, to: Int): Int =
            (from * (1f - k) + to * k).roundToInt().coerceIn(0, 255)
        return InterpretedColor(
            r = blend(base.r, target.r),
            g = blend(base.g, target.g),
            b = blend(base.b, target.b),
            source = "rule-relative",
        )
    }

    private fun oppositeOf(target: InterpretedColor): InterpretedColor = InterpretedColor(
        r = 255 - target.r,
        g = 255 - target.g,
        b = 255 - target.b,
        source = "rule-relative",
    )

    private fun increaseSaturation(base: InterpretedColor, gain: Float): InterpretedColor {
        val avg = (base.r + base.g + base.b) / 3f
        fun saturate(v: Int): Int = (avg + (v - avg) * gain).roundToInt().coerceIn(0, 255)
        return base.copy(r = saturate(base.r), g = saturate(base.g), b = saturate(base.b))
    }
}
