package com.example.ble1507

import android.content.Context
import android.os.Environment
import java.io.File

object QwenModelStore {
    fun ensureExternalModelDirectory(context: Context): File? =
        context.getExternalFilesDir(null)
            ?.let { File(it, "models") }
            ?.also { it.mkdirs() }

    fun preferredModelFile(context: Context): File? {
        ensureBundledModel(context)
        val dirs = candidateDirs(context)
        return dirs.map { File(it, BuildConfig.COLOR_MODEL_FILE) }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    fun displayState(context: Context): String {
        val file = preferredModelFile(context)
        return if (file != null) {
            "Model: ${BuildConfig.COLOR_MODEL_ID} / ${file.name} (${file.length() / 1024 / 1024} MB)"
        } else {
            "Model: missing – run installDebug -PcolorModel=${BuildConfig.COLOR_MODEL_ID}"
        }
    }

    fun expectedExternalPath(context: Context): String =
        File(File(context.getExternalFilesDir(null), "models"), BuildConfig.COLOR_MODEL_FILE).absolutePath

    private fun ensureBundledModel(context: Context) {
        val modelDirectory = ensureExternalModelDirectory(context) ?: return
        val modelFile = File(modelDirectory, BuildConfig.COLOR_MODEL_FILE)
        if (modelFile.isFile && modelFile.length() > 0L) return

        val assetPath = "models/${BuildConfig.COLOR_MODEL_FILE}"
        runCatching {
            val temporaryFile = File(modelDirectory, "${BuildConfig.COLOR_MODEL_FILE}.part")
            context.assets.open(assetPath).use { input ->
                temporaryFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temporaryFile.length() > 0L) { "Bundled model asset is empty" }
            check(temporaryFile.renameTo(modelFile)) { "Could not install bundled model" }
        }.onFailure {
            modelFile.delete()
        }
    }

    private fun candidateDirs(context: Context): List<File> = listOfNotNull(
        ensureExternalModelDirectory(context),
        Environment.getExternalStorageDirectory()?.let {
            File(it, "Android/data/${context.packageName}/files/models")
        },
        File(context.filesDir, "models"),
    )
}
