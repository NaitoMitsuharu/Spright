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

    private fun candidateDirs(context: Context): List<File> = listOfNotNull(
        ensureExternalModelDirectory(context),
        Environment.getExternalStorageDirectory()?.let {
            File(it, "Android/data/${context.packageName}/files/models")
        },
        File(context.filesDir, "models"),
    )
}
