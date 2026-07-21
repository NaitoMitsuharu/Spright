/*
 * ble1507_qwen.cpp – JNI bridge: on-device Qwen inference via llama.cpp
 *
 * Optimisation: the static system-message KV prefix is decoded once and kept
 * in the persistent context.  Each subsequent call only decodes the short
 * user/assistant suffix (~30 tokens), cutting prefill from ~32 s to ~1-3 s.
 */

#include <jni.h>
#include <string>
#include <utility>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <chrono>
#include <android/log.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "llama.h"

#define TAG "ble1507_qwen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── globals ───────────────────────────────────────────────────────────────────
static std::mutex g_mutex;
static llama_model *g_model = nullptr;
static std::string g_model_path;
struct CachedContext
{
    llama_context *ctx = nullptr;
    int32_t n_sys_cached = 0;
    std::vector<llama_token> sys_tokens;
};
static CachedContext g_color_cache;
static CachedContext g_relative_cache;

// System messages (must be identical between warmup and inference).
static const char *const COLOR_SYSTEM_MSG =
    "日本語の物体、情景、感情、比喩からRGB LEDペンライトの発光色を一色決める。"
    "絵の具ではなく光の加法混色である。考え方や文章は出力せず、必ず大文字#RRGGBBだけを返す。"
    "二色の間・中間・混合を求められたら両方の光を同程度に加える。赤+緑=黄、緑+青=シアン、青+赤=マゼンタを基準にする。"
    "主題の意味に最も近い基準を選び、修飾語で彩度と明度を調整する。未知語を黒へ逃がしてはいけない。"
    "意味と基準色: 苔・湿った森・植物・スターバックス=#176B45、"
    "渓流・澄んだ水・氷・冷風・ガリガリ君=#55BFD0、"
    "無花果・紫の果実・後悔=#74305F、"
    "落ち葉・錆・革・古い木=#8A4A24、真鍮・琥珀=#C58A24、"
    "熱気・怒り・焦り・恥ずかしさ・赤レーザー・メルカリ=#F04030、"
    "湯気・湯船・ぬるま湯・温もり=#E0B080、"
    "静かな決意・秩序・穏やかな湖=#486C90、"
    "静寂・退屈・疲労・摩耗した金属=#707880、"
    "綿菓子・柔らかさ=#F0C8E0、刃物・鋭い金属=#A8C0C8、"
    "蛍光マーカー・マクドナルド=#E8FF30、"
    "ネオン・サイバーパンク・ゲームセンター=#20E0F0、"
    "洞窟・閉ざした暗所=#101018、消えかけの燠火=#401008、焦げ・鍋底=#281810、"
    "月明かり=#8090B0、ネオンブルー=#00E0FF、宇宙=#301050。"
    "追加知識: 濡れたという語は物体固有の色相を変えず明度だけを下げる。"
    "無花果の果皮は暗い赤紫、後悔は沈んだ青紫、凍える冷気は淡い水色である。"
    "静寂は暗闇ではなく低彩度の青灰、真鍮は金色、一般的な蛍光マーカーは黄緑である。"
    "ブランド基準: スターバックスは深緑、マクドナルドのアーチは黄、メルカリは赤。"
    "有名なブランドやキャラクターは一般に認識される代表色を使う。"
    "暗い・深い・閉ざしたは明度を下げ、淡い・柔らかいは彩度を下げ、蛍光・ネオン・鮮烈は彩度と明度を上げる。"
    "#000000は完全な消灯だけに使う。";

static const char *const RELATIVE_SYSTEM_MSG =
    "日本語の相対的な色変更命令を、現在色へ適用する操作へ分類する。"
    "必ずTARGET,L,S,V;形式だけを返す。"
    "TARGETは色方向: NONE=なし,RED=赤,ORANGE=橙,YELLOW=黄,GREEN=緑,CYAN=水色,"
    "BLUE=青,PURPLE=紫,PINK=桃,WARM=暖色,COOL=寒色,BLACK=黒。"
    "Lは色方向の強さ: 0=なし,1=少し,2=通常,3=もっと,4=少し抑える,5=通常抑える,6=強く抑える。"
    "Sは彩度: 0=維持,1=少し下げる,2=強く下げる,3=少し上げる,4=強く上げる。"
    "Vは明度: 0=維持,1=少し下げる,2=強く下げる,3=少し上げる,4=強く上げる。"
    "名前が明示された色はWARM等へ一般化せず、必ずその色のTARGETにする。"
    "操作例: もう少し赤っぽく=RED,1,0,0; もっと赤っぽく=RED,3,0,0; "
    "青みを少し足す=BLUE,1,0,0; 黄色寄り=YELLOW,2,0,0; "
    "少し暖色寄り=WARM,1,0,0; もっと濃く=NONE,0,4,0; "
    "少し明るく=NONE,0,0,3; もっと暗く=NONE,0,0,2; "
    "赤みを少し抑える=RED,4,0,0; 彩度を下げる=NONE,0,1,0; "
    "赤を20%足して少し明るく=RED,1,0,3; もう少し紫っぽく=PURPLE,1,0,0;。";

static const char *const RELATIVE_CONTROL_GRAMMAR =
    "root ::= target \",\" level \",\" saturation \",\" value \";\"\n"
    "target ::= \"NONE\" | \"RED\" | \"ORANGE\" | \"YELLOW\" | \"GREEN\" | \"CYAN\" | \"BLUE\" | \"PURPLE\" | \"PINK\" | \"WARM\" | \"COOL\" | \"BLACK\"\n"
    "level ::= [0-6]\n"
    "saturation ::= [0-4]\n"
    "value ::= [0-4]\n";

// ── helpers ───────────────────────────────────────────────────────────────────
struct HsvColor
{
    float h;
    float s;
    float v;
};

static HsvColor rgb_to_hsv(int r8, int g8, int b8)
{
    const float r = r8 / 255.0f;
    const float g = g8 / 255.0f;
    const float b = b8 / 255.0f;
    const float hi = std::max({r, g, b});
    const float lo = std::min({r, g, b});
    const float delta = hi - lo;
    float hue = 0.0f;
    if (delta > 0.00001f)
    {
        if (hi == r)
            hue = 60.0f * std::fmod((g - b) / delta, 6.0f);
        else if (hi == g)
            hue = 60.0f * (((b - r) / delta) + 2.0f);
        else
            hue = 60.0f * (((r - g) / delta) + 4.0f);
    }
    if (hue < 0.0f)
        hue += 360.0f;
    return {hue, hi <= 0.0f ? 0.0f : delta / hi, hi};
}

static void hsv_to_rgb(const HsvColor &hsv, int &r8, int &g8, int &b8)
{
    const float hue = std::fmod(hsv.h + 360.0f, 360.0f);
    const float chroma = hsv.v * hsv.s;
    const float x = chroma * (1.0f - std::fabs(std::fmod(hue / 60.0f, 2.0f) - 1.0f));
    const float m = hsv.v - chroma;
    float r = 0.0f, g = 0.0f, b = 0.0f;
    if (hue < 60.0f)
        r = chroma, g = x;
    else if (hue < 120.0f)
        r = x, g = chroma;
    else if (hue < 180.0f)
        g = chroma, b = x;
    else if (hue < 240.0f)
        g = x, b = chroma;
    else if (hue < 300.0f)
        r = x, b = chroma;
    else
        r = chroma, b = x;
    r8 = (int)std::lround((r + m) * 255.0f);
    g8 = (int)std::lround((g + m) * 255.0f);
    b8 = (int)std::lround((b + m) * 255.0f);
}

static std::string extract_upper_hex(const std::string &text)
{
    for (size_t start = 0; start + 7 <= text.size(); ++start)
    {
        if (text[start] != '#')
            continue;
        bool valid = true;
        for (size_t i = start + 1; i < start + 7; ++i)
        {
            const char c = text[i];
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
            {
                valid = false;
                break;
            }
        }
        if (valid)
            return text.substr(start, 7);
    }
    return {};
}

// Keep the model's semantic hue while avoiding a tiny fixed output palette.
// FNV-1a makes the adjustment stable: the same utterance always has the same
// result, while different descriptions receive small, perceptually related
// hue/saturation/value offsets.
static std::string diversify_hex(const std::string &hex, const std::string &description)
{
    int r = std::stoi(hex.substr(1, 2), nullptr, 16);
    int g = std::stoi(hex.substr(3, 2), nullptr, 16);
    int b = std::stoi(hex.substr(5, 2), nullptr, 16);

    uint32_t hash = 2166136261u;
    for (unsigned char c : description)
    {
        hash ^= c;
        hash *= 16777619u;
    }

    static constexpr float HUE_OFFSETS[] = {-6.0f, -4.0f, -2.0f, 2.0f, 4.0f, 6.0f};
    static constexpr float SV_FACTORS[] = {0.94f, 0.96f, 0.98f, 1.02f, 1.04f, 1.06f};
    HsvColor hsv = rgb_to_hsv(r, g, b);
    hsv.h += HUE_OFFSETS[hash % 6u];
    // Very dark colors are dominated by rounding: scaling S/V there would push
    // them to pure black or add hue noise, so only the hue is nudged.
    if (hsv.v >= 0.12f)
    {
        hsv.s = std::clamp(hsv.s * SV_FACTORS[(hash >> 8) % 6u], 0.0f, 1.0f);
        hsv.v = std::clamp(hsv.v * SV_FACTORS[(hash >> 16) % 6u], 0.0f, 1.0f);
    }
    hsv_to_rgb(hsv, r, g, b);

    char adjusted[8] = {};
    std::snprintf(adjusted, sizeof(adjusted), "#%02X%02X%02X", r, g, b);
    return adjusted;
}

static void free_ctx_nolock()
{
    for (CachedContext *cache : {&g_color_cache, &g_relative_cache})
    {
        if (cache->ctx)
            llama_free(cache->ctx);
        cache->ctx = nullptr;
        cache->n_sys_cached = 0;
        cache->sys_tokens.clear();
    }
}

static llama_model *ensure_model_nolock(const std::string &path)
{
    if (g_model && g_model_path == path)
        return g_model;
    free_ctx_nolock();
    if (g_model)
    {
        LOGI("Unloading previous model");
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
    }
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    LOGI("Loading model: %s", path.c_str());
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model)
    {
        LOGE("Failed to load model");
        return nullptr;
    }
    g_model_path = path;
    LOGI("Model loaded OK");
    return g_model;
}

static llama_context_params make_ctx_params()
{
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1280;
    cp.n_threads = 4;
    cp.n_threads_batch = 6;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED; // speed up attention
    return cp;
}

// Batch with explicit absolute positions (required for KV-cache reuse).
static llama_batch make_pos_batch(const std::vector<llama_token> &toks, int32_t start_pos)
{
    llama_batch b = llama_batch_init((int32_t)toks.size(), 0, 1);
    for (int32_t i = 0; i < (int32_t)toks.size(); ++i)
    {
        b.token[i] = toks[i];
        b.pos[i] = start_pos + i;
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = 0;
        b.logits[i] = (i == (int32_t)toks.size() - 1) ? 1 : 0;
    }
    b.n_tokens = (int32_t)toks.size();
    return b;
}

static bool format_chat(
    const char *system_message,
    const std::string *user,
    std::string &out)
{
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl)
    {
        LOGE("model has no chat template");
        return false;
    }
    llama_chat_message messages[2] = {
        {"system", system_message},
        {"user", user ? user->c_str() : ""},
    };
    const size_t count = user ? 2 : 1;
    const bool add_assistant = user != nullptr;
    int32_t needed = llama_chat_apply_template(tmpl, messages, count, add_assistant, nullptr, 0);
    if (needed <= 0)
    {
        LOGE("chat template sizing failed: %d", needed);
        return false;
    }
    std::vector<char> buffer((size_t)needed + 1);
    int32_t written = llama_chat_apply_template(
        tmpl, messages, count, add_assistant, buffer.data(), (int32_t)buffer.size());
    if (written <= 0)
    {
        LOGE("chat template formatting failed: %d", written);
        return false;
    }
    out.assign(buffer.data(), (size_t)written);
    return true;
}

static bool tokenize_text(const std::string &text, bool add_special, std::vector<llama_token> &out)
{
    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    int32_t needed = -llama_tokenize(
        vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, add_special, true);
    if (needed <= 0)
        return false;
    out.resize((size_t)needed);
    int32_t count = llama_tokenize(
        vocab, text.c_str(), (int32_t)text.size(), out.data(), needed, add_special, true);
    if (count <= 0)
        return false;
    out.resize((size_t)count);
    return true;
}

// Decode the model-specific, static system prefix and retain its KV state.
static bool prefill_sys_nolock(CachedContext &cache, const char *system_message)
{
    std::string sys;
    if (!format_chat(system_message, nullptr, sys) ||
        !tokenize_text(sys, true, cache.sys_tokens))
    {
        LOGE("system prompt preparation failed");
        return false;
    }

    if (cache.sys_tokens.size() >= llama_n_ctx(cache.ctx))
    {
        LOGE("system prompt is too large: %zu tokens for n_ctx=%u",
             cache.sys_tokens.size(), llama_n_ctx(cache.ctx));
        return false;
    }
    auto t0 = std::chrono::steady_clock::now();
    llama_batch b = llama_batch_get_one(
        cache.sys_tokens.data(), (int32_t)cache.sys_tokens.size());
    bool ok = (llama_decode(cache.ctx, b) == 0);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0)
                  .count();
    if (!ok)
    {
        LOGE("sys prefill failed");
        return false;
    }
    cache.n_sys_cached = (int32_t)cache.sys_tokens.size();
    LOGI("sys_pf: %ldms (%d tokens) cached", ms, cache.n_sys_cached);
    return true;
}

// ── JNI: inference ────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ble1507_NativeQwenBridge_nativeInferColorHex(
    JNIEnv *env, jobject,
    jstring jModelPath, jstring jPrompt)
{
    const char *cPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string path(cPath), user_prompt(cPrompt);
    env->ReleaseStringUTFChars(jModelPath, cPath);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    std::lock_guard<std::mutex> lock(g_mutex);
    // Boost priority and try to stay on big cores.
    setpriority(PRIO_PROCESS, 0, -8);
    // Pin this thread to all logical CPUs (hint to scheduler to prefer big cores).
    // Real per-device big-core pinning would need cpuinfo_max_freq parsing.
    cpu_set_t full_set;
    CPU_ZERO(&full_set);
    for (int i = 0; i < 8; ++i)
        CPU_SET(i, &full_set);
    sched_setaffinity((pid_t)syscall(SYS_gettid), sizeof(full_set), &full_set);

    if (!ensure_model_nolock(path))
        return nullptr;
    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    const bool relative_control =
        user_prompt.find("Relative control classification:") != std::string::npos;
    CachedContext &cache = relative_control ? g_relative_cache : g_color_cache;
    const char *system_message =
        relative_control ? RELATIVE_SYSTEM_MSG : COLOR_SYSTEM_MSG;

    // ── Ensure context + cached system prefix ─────────────────────────────────
    if (!cache.ctx)
    {
        auto t0 = std::chrono::steady_clock::now();
        cache.ctx = llama_init_from_model(g_model, make_ctx_params());
        LOGI("ctx_create: %ldms",
             (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                 std::chrono::steady_clock::now() - t0)
                 .count());
        if (!cache.ctx || !prefill_sys_nolock(cache, system_message))
        {
            free_ctx_nolock();
            return nullptr;
        }
    }
    else
    {
        // Strip everything after the cached system prefix
        llama_memory_t memory = llama_get_memory(cache.ctx);
        if (!llama_memory_seq_rm(memory, 0,
                                 (llama_pos)cache.n_sys_cached, (llama_pos)-1))
        {
            // Hybrid/recurrent models (for example Qwen3.5) cannot always
            // remove a partial sequence. Rebuild only the static prefix.
            LOGI("partial memory trim unsupported; rebuilding system prefix");
            llama_memory_clear(memory, true);
            if (!prefill_sys_nolock(cache, system_message))
            {
                free_ctx_nolock();
                return nullptr;
            }
        }
    }

    std::string full_prompt;
    std::vector<llama_token> full_toks;
    if (!format_chat(system_message, &user_prompt, full_prompt) ||
        !tokenize_text(full_prompt, true, full_toks) ||
        full_toks.size() <= cache.sys_tokens.size() ||
        !std::equal(cache.sys_tokens.begin(), cache.sys_tokens.end(), full_toks.begin()))
    {
        LOGE("dynamic prompt does not share cached system prefix");
        free_ctx_nolock();
        return nullptr;
    }
    std::vector<llama_token> dyn_toks(
        full_toks.begin() + (std::ptrdiff_t)cache.sys_tokens.size(), full_toks.end());
    const int32_t n_dyn = (int32_t)dyn_toks.size();
    if (cache.n_sys_cached + n_dyn + 32 >= (int32_t)llama_n_ctx(cache.ctx))
    {
        LOGE("prompt exceeds context: sys=%d dyn=%d n_ctx=%u",
             cache.n_sys_cached, n_dyn, llama_n_ctx(cache.ctx));
        return nullptr;
    }

    auto t_pf0 = std::chrono::steady_clock::now();
    llama_batch db = make_pos_batch(dyn_toks, cache.n_sys_cached);
    int pf_err = llama_decode(cache.ctx, db);
    llama_batch_free(db);
    long pf_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                     std::chrono::steady_clock::now() - t_pf0)
                     .count();
    LOGI("pf: %ldms (%d dyn tokens)", pf_ms, n_dyn);

    if (pf_err != 0)
    {
        LOGE("dyn prefill failed");
        free_ctx_nolock();
        return nullptr;
    }

    // ── Greedy generation ─────────────────────────────────────────────────────
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (relative_control)
    {
        llama_sampler *grammar = llama_sampler_init_grammar(
            vocab, RELATIVE_CONTROL_GRAMMAR, "root");
        if (!grammar)
        {
            LOGE("relative grammar initialization failed");
            llama_sampler_free(smpl);
            return nullptr;
        }
        llama_sampler_chain_add(smpl, grammar);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string result;
    result.reserve(48);
    int32_t cur_pos = cache.n_sys_cached + n_dyn;
    int gen_count = 0;
    auto t_gen0 = std::chrono::steady_clock::now();

    // Pre-allocate a single-token batch – avoids repeated malloc/free each step.
    llama_batch gen_batch = llama_batch_init(1, 0, 1);
    gen_batch.n_tokens = 1;
    gen_batch.n_seq_id[0] = 1;
    gen_batch.seq_id[0][0] = 0;
    gen_batch.logits[0] = 1;

    for (int i = 0; i < 24; ++i)
    {
        llama_token tok = llama_sampler_sample(smpl, cache.ctx, -1);
        if (llama_vocab_is_eog(vocab, tok))
            break;

        char piece[256] = {};
        int len = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1, 0, true);
        if (len > 0)
            result.append(piece, len);
        gen_count++;

        if ((!relative_control && !extract_upper_hex(result).empty()) ||
            (relative_control && !result.empty() && result.back() == ';'))
            break;

        gen_batch.token[0] = tok;
        gen_batch.pos[0] = cur_pos++;
        if (llama_decode(cache.ctx, gen_batch) != 0)
            break;
    }
    llama_batch_free(gen_batch);
    llama_sampler_free(smpl);
    // The selected context is retained for the next call.

    long gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t_gen0)
                      .count();
    LOGI("gen: %ldms (%d tokens) → %s", gen_ms, gen_count, result.c_str());

    const std::string hex_output = relative_control ? "" : extract_upper_hex(result);
    const bool valid_hex = !hex_output.empty();
    const size_t first_comma = result.find(',');
    const std::string relative_target =
        first_comma == std::string::npos ? "" : result.substr(0, first_comma);
    static const std::vector<std::string> RELATIVE_TARGETS = {
        "NONE", "RED", "ORANGE", "YELLOW", "GREEN", "CYAN",
        "BLUE", "PURPLE", "PINK", "WARM", "COOL", "BLACK",
    };
    const bool known_relative_target =
        std::find(RELATIVE_TARGETS.begin(), RELATIVE_TARGETS.end(), relative_target) !=
        RELATIVE_TARGETS.end();
    const std::string relative_tail =
        first_comma == std::string::npos ? "" : result.substr(first_comma);
    const bool valid_relative_control = known_relative_target &&
        relative_tail.size() == 7 &&
        relative_tail[0] == ',' &&
        relative_tail[1] >= '0' && relative_tail[1] <= '6' &&
        relative_tail[2] == ',' &&
        relative_tail[3] >= '0' && relative_tail[3] <= '4' &&
        relative_tail[4] == ',' &&
        relative_tail[5] >= '0' && relative_tail[5] <= '4' &&
        relative_tail[6] == ';';
    if ((!relative_control && !valid_hex) || (relative_control && !valid_relative_control))
    {
        LOGE("grammar output was invalid: %s", result.c_str());
        return nullptr;
    }
    const std::string final_output =
        relative_control ? result : diversify_hex(hex_output, user_prompt);
    LOGI("output%s: %s -> %s", relative_control ? " relative-control" : "",
         result.c_str(), final_output.c_str());
    std::string out = final_output + "|pf:" + std::to_string(pf_ms) + ",g:" + std::to_string(gen_ms);
    return env->NewStringUTF(out.c_str());
}

// ── JNI: warmup (load model + prefill system prefix) ─────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ble1507_NativeQwenBridge_nativeWarmupModel(
    JNIEnv *env, jobject, jstring jModelPath)
{
    const char *cPath = env->GetStringUTFChars(jModelPath, nullptr);
    std::string path(cPath);
    env->ReleaseStringUTFChars(jModelPath, cPath);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensure_model_nolock(path))
        return JNI_FALSE;
    if (g_color_cache.ctx && g_relative_cache.ctx)
        return JNI_TRUE; // already warmed up

    const std::pair<CachedContext *, const char *> contexts[] = {
        {&g_color_cache, COLOR_SYSTEM_MSG},
        {&g_relative_cache, RELATIVE_SYSTEM_MSG},
    };
    for (const auto &[cache, system_message] : contexts)
    {
        if (cache->ctx)
            continue;
        cache->ctx = llama_init_from_model(g_model, make_ctx_params());
        if (!cache->ctx || !prefill_sys_nolock(*cache, system_message))
        {
            free_ctx_nolock();
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}
