/*
 * ble1507_qwen.cpp – JNI bridge: on-device Qwen inference via llama.cpp
 *
 * Optimisation: the static system-message KV prefix is decoded once and kept
 * in the persistent context.  Each subsequent call only decodes the short
 * user/assistant suffix (~30 tokens), cutting prefill from ~32 s to ~1-3 s.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
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
static llama_context *g_ctx = nullptr;
static int32_t g_n_sys_cached = 0; // #tokens in cached system prefix

// System message (must be identical between warmup and inference).
static const char *const SYSTEM_MSG =
    "Convert color input to RGB JSON {\"r\":0-255,\"g\":0-255,\"b\":0-255}. "
    "Japanese basics: 赤=red,青=blue,緑=green,黄=yellow,白=white,黒=black,"
    "ピンク=pink,橙=orange,紫=purple,水色=cyan,桜色=sakura,ラベンダー=lavender. "
    "Adjust for 明るく/暗く. "
    "For natural/abstract descriptions pick an appropriate color: "
    "夕焼け/sunset->{\"r\":255,\"g\":100,\"b\":50}, "
    "朝焼け/sunrise->{\"r\":255,\"g\":160,\"b\":100}, "
    "空/sky->{\"r\":135,\"g\":206,\"b\":235}, "
    "海/ocean->{\"r\":0,\"g\":105,\"b\":180}, "
    "森/forest->{\"r\":34,\"g\":139,\"b\":34}, "
    "炎/fire->{\"r\":255,\"g\":60,\"b\":0}. "
    "Output ONLY JSON.";

// ── helpers ───────────────────────────────────────────────────────────────────
static void free_ctx_nolock()
{
    if (g_ctx)
    {
        llama_free(g_ctx);
        g_ctx = nullptr;
        g_n_sys_cached = 0;
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
    cp.n_ctx = 512;
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

// Decode the static system prefix and cache its KV state.
// Includes 4 few-shot examples to strongly condition label-only output.
static bool prefill_sys_nolock()
{
    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    // Build full static prefix: system msg + few-shot user/assistant pairs.
    // Few-shot examples are the strongest way to teach a small model output format.
    std::string sys =
        std::string("<|im_start|>system\n") + SYSTEM_MSG + "\n<|im_end|>\n"
                                                           // example 1
                                                           "<|im_start|>user\n\u5915\u713c\u3051\u306e\u8272\n<|im_end|>\n"
                                                           "<|im_start|>assistant\n<think>\n\n</think>\n\nsunsset\n<|im_end|>\n" // intentional dummy, replaced below
                                                           "<|im_start|>user\n\u60b2\u3057\u3044\u8272\n<|im_end|>\n"
                                                           "<|im_start|>assistant\n<think>\n\n</think>\n\nsad\n<|im_end|>\n"
                                                           "<|im_start|>user\n\u8d64\n<|im_end|>\n"
                                                           "<|im_start|>assistant\n<think>\n\n</think>\n\nred\n<|im_end|>\n"
                                                           "<|im_start|>user\n\u6d77\u306e\u8272\n<|im_end|>\n"
                                                           "<|im_start|>assistant\n<think>\n\n</think>\n\nocean\n<|im_end|>\n";
    // Fix the typo in example 1
    auto pos = sys.find("sunsset");
    if (pos != std::string::npos)
        sys.replace(pos, 7, "sunset");

    std::vector<llama_token> toks(1024);
    int n = llama_tokenize(vocab, sys.c_str(), (int32_t)sys.size(),
                           toks.data(), (int32_t)toks.size(), true, true);
    if (n <= 0)
    {
        LOGE("sys tokenize failed");
        return false;
    }
    toks.resize(n);

    auto t0 = std::chrono::steady_clock::now();
    llama_batch b = llama_batch_get_one(toks.data(), n);
    bool ok = (llama_decode(g_ctx, b) == 0);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0)
                  .count();
    if (!ok)
    {
        LOGE("sys prefill failed");
        return false;
    }
    g_n_sys_cached = n;
    LOGI("sys_pf: %ldms (%d tokens) cached", ms, n);
    return true;
}

// ── JNI: inference ────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ble1507_NativeQwenBridge_nativeInferColorJson(
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

    // ── Ensure context + cached system prefix ─────────────────────────────────
    if (!g_ctx)
    {
        auto t0 = std::chrono::steady_clock::now();
        g_ctx = llama_init_from_model(g_model, make_ctx_params());
        LOGI("ctx_create: %ldms",
             (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                 std::chrono::steady_clock::now() - t0)
                 .count());
        if (!g_ctx || !prefill_sys_nolock())
        {
            free_ctx_nolock();
            return nullptr;
        }
    }
    else
    {
        // Strip everything after the cached system prefix
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0,
                            (llama_pos)g_n_sys_cached, (llama_pos)-1);
    }

    // Dynamic suffix: no JSON pre-fill. Model outputs a color label (1-2 tokens).
    const std::string dyn =
        "<|im_start|>user\n" + user_prompt + "\n<|im_end|>\n"
                                             "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    std::vector<llama_token> dyn_toks(256);
    int n_dyn = llama_tokenize(vocab, dyn.c_str(), (int32_t)dyn.size(),
                               dyn_toks.data(), (int32_t)dyn_toks.size(), false, true);
    if (n_dyn <= 0)
    {
        LOGE("dyn tokenize failed");
        free_ctx_nolock();
        return nullptr;
    }
    dyn_toks.resize(n_dyn);

    auto t_pf0 = std::chrono::steady_clock::now();
    llama_batch db = make_pos_batch(dyn_toks, g_n_sys_cached);
    int pf_err = llama_decode(g_ctx, db);
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
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string result;
    result.reserve(48);
    int32_t cur_pos = g_n_sys_cached + n_dyn;
    int gen_count = 0;
    auto t_gen0 = std::chrono::steady_clock::now();

    // Pre-allocate a single-token batch – avoids repeated malloc/free each step.
    llama_batch gen_batch = llama_batch_init(1, 0, 1);
    gen_batch.n_tokens = 1;
    gen_batch.n_seq_id[0] = 1;
    gen_batch.seq_id[0][0] = 0;
    gen_batch.logits[0] = 1;

    for (int i = 0; i < 32; ++i)
    {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok))
            break;

        char piece[256] = {};
        int len = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1, 0, true);
        if (len > 0)
            result.append(piece, len);
        gen_count++;

        // Hybrid stop: if output starts with '{' treat as JSON (stop at '}'),
        // otherwise treat as label (stop on first non-alpha after alpha chars).
        bool json_mode = (!result.empty() && result[0] == '{');
        if (json_mode)
        {
            if (result.find('}') != std::string::npos)
                break;
        }
        else
        {
            bool has_alpha = std::any_of(result.begin(), result.end(),
                                         [](char c)
                                         { return std::isalpha((unsigned char)c); });
            if (has_alpha && !result.empty() && !std::isalpha((unsigned char)result.back()))
                break;
        }

        gen_batch.token[0] = tok;
        gen_batch.pos[0] = cur_pos++;
        if (llama_decode(g_ctx, gen_batch) != 0)
            break;
    }
    llama_batch_free(gen_batch);
    llama_sampler_free(smpl);
    // g_ctx is NOT freed – retained for the next call.

    long gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t_gen0)
                      .count();
    LOGI("gen: %ldms (%d tokens) → %s", gen_ms, gen_count, result.c_str());

    if (result.empty())
        return nullptr;
    // For label output: strip trailing non-alpha. For JSON: keep as-is.
    bool is_json = (!result.empty() && result[0] == '{');
    if (!is_json)
    {
        while (!result.empty() && !std::isalpha((unsigned char)result.back()))
            result.pop_back();
        if (result.empty())
            return nullptr;
    }
    std::string out = result + "|pf:" + std::to_string(pf_ms) + ",g:" + std::to_string(gen_ms);
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
    if (g_ctx)
        return JNI_TRUE; // already warmed up

    g_ctx = llama_init_from_model(g_model, make_ctx_params());
    if (!g_ctx)
        return JNI_FALSE;
    if (!prefill_sys_nolock())
    {
        free_ctx_nolock();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
