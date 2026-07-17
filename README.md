# Spright

Android app that converts Japanese speech into LED RGB colors using **on-device LLM inference** (llama.cpp + Qwen3-0.6B).

Voice input → speech recognition → local LLM color interpretation → BLE command to LED controller (BLE1507).

---

## Features

- **On-device LLM** — Qwen3-0.6B-Q4_K_M runs entirely on the Android CPU (no internet required for inference)
- **BLE control** — Sends PWM color commands to BLE1507 LED controller via Bluetooth
- **Voice input** — Japanese speech recognition (online-first with offline fallback)
- **Fast response** — KV-cache prefix reuse + KleidiAI + DOTPROD; ~2–3 s for abstract colors, instant for common colors
- **Color types handled**
  - Basic colors: 赤、青、緑、ピンク … (instant, rule-based)
  - Natural: 夕焼け、海、森、雪 … (instant, rule-based)
  - Emotional: 悲しい、楽しい、怒り … (LLM ~2–3 s)
  - Foods & objects: たこ焼き、卵、LINE … (instant, rule-based)
  - Creative / abstract: 宇宙の深さ、懐かしい感じ … (LLM ~3–5 s)

---

## Requirements

- Android 10+ (API 29+)
- Bluetooth LE
- BLE1507 LED controller (paired)
- ~500 MB free storage (GGUF model)
- NDK 28+ (for building from source)

---

## Model Setup

The GGUF model file is **not included** in this repository (~380 MB).
Download it once and push it to the device:

```
Android/data/com.example.ble1507/files/models/Qwen3-0.6B-Q4_K_M.gguf
```

### Windows (PowerShell)

```powershell
# 1. Download
curl.exe -L --max-redirs 10 -o "$env:TEMP\Qwen3-0.6B-Q4_K_M.gguf" `
  "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

# 2. Push to device
adb shell mkdir -p /sdcard/Android/data/com.example.ble1507/files/models
adb push "$env:TEMP\Qwen3-0.6B-Q4_K_M.gguf" `
  /sdcard/Android/data/com.example.ble1507/files/models/
```

### macOS

```bash
# 1. Download
curl -L --max-redirs 10 -o ~/Downloads/Qwen3-0.6B-Q4_K_M.gguf \
  "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

# 2. Push to device
adb shell mkdir -p /sdcard/Android/data/com.example.ble1507/files/models
adb push ~/Downloads/Qwen3-0.6B-Q4_K_M.gguf \
  /sdcard/Android/data/com.example.ble1507/files/models/
```

### Linux

```bash
# 1. Download
wget -O /tmp/Qwen3-0.6B-Q4_K_M.gguf \
  "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

# 2. Push to device
adb shell mkdir -p /sdcard/Android/data/com.example.ble1507/files/models
adb push /tmp/Qwen3-0.6B-Q4_K_M.gguf \
  /sdcard/Android/data/com.example.ble1507/files/models/
```

> **Note:** `adb` must be installed and the device connected with USB debugging enabled.
> Model: [unsloth/Qwen3-0.6B-GGUF](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF) (Apache 2.0)

---

## Build

```bash
git clone --recurse-submodules https://github.com/NaitoMitsuharu/Spright.git
cd Spright
```

1. Open in Android Studio (Hedgehog or later)
2. Push the GGUF model (see above)
3. Run → **Debug** or `./gradlew :app:assembleDebug`

---

## Usage

1. **Connect** — Tap **CONNECT** to scan and pair with the BLE1507 device
2. **Select color** — Drag the color map or use Voice
3. **Voice input** — Tap 🎤 **Voice** and speak in Japanese:
   - `「赤」` → instant red (rule-based)
   - `「夕焼けの色」` → sunset orange (rule-based, instant)
   - `「悲しい感じの色」` → LLM → blue-gray (~2–3 s)
   - `「宇宙の深さ」` → LLM → deep purple/black (~3–5 s)
4. **Send** ✈️ — Sends current color as BLE PWM command

### Color resolver display

| Display | Meaning |
|---|---|
| `#FF6432 rule-based` | Built-in rule matched instantly |
| `#3C50B4 Qwen (pf:2100ms g:580ms)` | LLM succeeded |
| `Fail Qwen (...)` | LLM returned unrecognized label |

---

## Architecture

```
Speech → SpeechRecognizer (online-first)
       → RuleColorInterpreter  (instant: basic colors, nature, foods, brands)
       → QwenColorInterpreter  (LLM: abstract / emotional / unknown)
           └─ NativeQwenBridge (JNI)
               └─ llama.cpp (Qwen3-0.6B-Q4_K_M, CPU only)
                   ├─ KV-cache prefix reuse (system prompt cached)
                   ├─ KleidiAI optimized kernels
                   ├─ DOTPROD + FP16 (armv8-a+dotprod+fp16)
                   └─ Few-shot label output (1–3 tokens per call)
       → BLE1507Client (BLE PWM command)
```

---

## Performance (Snapdragon, 2nd call onwards)

| Phase | Time |
|---|---|
| Prefill (dynamic ~20 tokens) | ~2.0–2.3 s |
| Generation (label ~1–3 tokens) | ~0.3–0.6 s |
| **Total** | **~2.5–3.0 s** |
| Rule-based hit | **< 1 ms** |

---

## License

- App code: MIT
- llama.cpp: MIT
- Qwen3-0.6B model: Apache 2.0
