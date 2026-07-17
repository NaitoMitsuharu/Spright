package com.example.ble1507

object NativeQwenBridge {
    val isAvailable: Boolean = runCatching { System.loadLibrary("ble1507_qwen") }.isSuccess

    /** Pre-loads the model into the static C++ cache on the calling thread. */
    fun warmupModel(modelPath: String): Boolean {
        if (!isAvailable) return false
        return nativeWarmupModel(modelPath)
    }

    fun inferColorJson(modelPath: String, prompt: String): String? {
        if (!isAvailable) {
            return null
        }
        return nativeInferColorJson(modelPath, prompt)
    }

    private external fun nativeWarmupModel(modelPath: String): Boolean
    private external fun nativeInferColorJson(modelPath: String, prompt: String): String?
}