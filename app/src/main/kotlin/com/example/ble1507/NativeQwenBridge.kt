package com.example.ble1507

object NativeQwenBridge {
    val isAvailable: Boolean = runCatching { System.loadLibrary("ble1507_qwen") }.isSuccess

    /** Pre-loads the model into the static C++ cache on the calling thread. */
    fun warmupModel(modelPath: String): Boolean {
        if (!isAvailable) return false
        return nativeWarmupModel(modelPath)
    }

    fun inferColorHex(modelPath: String, prompt: String): String? {
        if (!isAvailable) {
            return null
        }
        return nativeInferColorHex(modelPath, prompt)
    }

    fun inferRelativeCommand(modelPath: String, instruction: String): String? {
        if (!isAvailable) {
            return null
        }
        return nativeInferColorHex(
            modelPath,
            "Relative control classification: $instruction\n" +
                "Output TARGET,L,S,V; only. /no_think",
        )
    }

    private external fun nativeWarmupModel(modelPath: String): Boolean
    private external fun nativeInferColorHex(modelPath: String, prompt: String): String?
}
