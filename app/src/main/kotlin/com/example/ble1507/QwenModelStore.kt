package com.example.ble1507

import android.content.Context
import android.os.Environment
import java.io.File

object QwenModelStore {
    // Candidate model filenames in priority order (highest quality first).
    // Place any one of these in the models/ directory.
    private val MODEL_NAMES = listOf(
        // Qwen3 Q4_K_M preferred (best speed/quality balance for mobile CPU)
        "Qwen3-1.7B-Q4_K_M.gguf",
        "qwen3-1.7b-q4_k_m.gguf",
        "Qwen3-0.6B-Q4_K_M.gguf",
        "qwen3-0.6b-q4_k_m.gguf",
        // Qwen3 Q8_0 fallback (higher quality but ~4x slower)
        "Qwen3-1.7B-Q8_0.gguf",
        "qwen3-1.7b-q8_0.gguf",
        "Qwen3-0.6B-Q8_0.gguf",
        "qwen3-0.6b-q8_0.gguf",
        // Legacy
        "qwen2.5-0.5b-instruct-q4.gguf",
    )

    fun preferredModelFile(context: Context): File? {
        val dirs = candidateDirs(context)
        return MODEL_NAMES.firstNotNullOfOrNull { name ->
            dirs.map { File(it, name) }.firstOrNull { it.isFile && it.length() > 0L }
        }
    }

    fun displayState(context: Context): String {
        val file = preferredModelFile(context)
        return if (file != null) {
            "Model: ${file.name} (${file.length() / 1024 / 1024} MB)"
        } else {
            "Model: missing – place ${MODEL_NAMES.first()} or ${MODEL_NAMES[2]} in models/"
        }
    }

    fun expectedExternalPath(context: Context): String =
        File(File(context.getExternalFilesDir(null), "models"), MODEL_NAMES.first()).absolutePath

    private fun candidateDirs(context: Context): List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, "models") },
        Environment.getExternalStorageDirectory()?.let {
            File(it, "Android/data/${context.packageName}/files/models")
        },
        File(context.filesDir, "models"),
    )
}