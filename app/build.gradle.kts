import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appId = "com.example.ble1507"

data class ColorModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

val colorModels = listOf(
    ColorModelSpec(
        id = "qwen3-0.6b",
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf",
        sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        sizeBytes = 396_705_472L,
    ),
    ColorModelSpec(
        id = "qwen3.5-0.8b",
        fileName = "Qwen3.5-0.8B-Q4_0.gguf",
        url = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/8fea620810c4afa23dd6443f999a48574c1611a3/Qwen3.5-0.8B-Q4_0.gguf",
        sha256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
        sizeBytes = 563_036_064L,
    ),
)
val selectedColorModelId = providers.gradleProperty("colorModel").orElse("qwen3-0.6b")
val selectedColorModel = colorModels.firstOrNull { it.id == selectedColorModelId.get() }
    ?: error("Unknown -PcolorModel=${selectedColorModelId.get()}; use ${colorModels.joinToString { it.id }}")
val modelCacheDir = gradle.gradleUserHomeDir.resolve("caches/spright/models")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun prepareModel(spec: ColorModelSpec): File {
    modelCacheDir.mkdirs()
    val target = modelCacheDir.resolve(spec.fileName)
    val validCached = target.isFile &&
        target.length() == spec.sizeBytes &&
        sha256(target).equals(spec.sha256, ignoreCase = true)
    if (validCached) {
        logger.lifecycle("Using cached ${spec.id}: $target")
        return target
    }
    if (target.exists()) target.delete()
    val partial = File(target.absolutePath + ".part")
    partial.delete()
    logger.lifecycle("Downloading ${spec.id} (${spec.sizeBytes / 1024 / 1024} MiB)")
    URI(spec.url).toURL().openStream().buffered().use { input ->
        partial.outputStream().buffered().use(input::copyTo)
    }
    val actualHash = sha256(partial)
    check(partial.length() == spec.sizeBytes) {
        "Model size mismatch for ${spec.id}: expected ${spec.sizeBytes}, got ${partial.length()}"
    }
    check(actualHash.equals(spec.sha256, ignoreCase = true)) {
        "Model SHA-256 mismatch for ${spec.id}: expected ${spec.sha256}, got $actualHash"
    }
    check(partial.renameTo(target)) { "Could not move $partial to $target" }
    return target
}

fun adbPrefix(): List<String> {
    val requestedSerial = providers.gradleProperty("adbSerial").orNull
    val output = ByteArrayOutputStream()
    exec {
        commandLine("adb", "devices")
        standardOutput = output
    }
    val connected = output.toString()
        .lineSequence()
        .drop(1)
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[1] == "device" }
        .map { it[0] }
        .toList()
    val serial = when {
        requestedSerial != null && requestedSerial in connected -> requestedSerial
        requestedSerial != null -> error("adb device '$requestedSerial' is not connected")
        connected.size == 1 -> connected.single()
        connected.isEmpty() -> error("No adb device is connected")
        else -> error("Multiple adb devices are connected; pass -PadbSerial=<serial>")
    }
    return listOf("adb", "-s", serial)
}

fun initializeAppModelDirectory(adb: List<String>) {
    val result = exec {
        commandLine(
            adb + listOf(
                "shell", "am", "start", "-W",
                "-n", "$appId/.MainActivity",
                "--ez", "prepare_model_directory", "true",
            ),
        )
        isIgnoreExitValue = true
    }
    check(result.exitValue == 0) { "Could not ask Spright to create its external model directory" }
}

fun restartAppAfterModelPush(adb: List<String>) {
    exec {
        commandLine(adb + listOf("shell", "am", "force-stop", appId))
    }
    val result = exec {
        commandLine(
            adb + listOf(
                "shell", "am", "start", "-W",
                "-n", "$appId/.MainActivity",
            ),
        )
        isIgnoreExitValue = true
    }
    check(result.exitValue == 0) { "Model was pushed, but Spright could not be restarted for warmup" }
}

android {
    namespace = appId
    compileSdk = 36
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = appId
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "COLOR_MODEL_ID", "\"${selectedColorModel.id}\"")
        buildConfigField("String", "COLOR_MODEL_FILE", "\"${selectedColorModel.fileName}\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_METAL=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.github.sceneview:sceneview:4.22.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val downloadColorModels by tasks.registering {
    group = "model"
    description = "Downloads and verifies every color-model candidate."
    doLast { colorModels.forEach(::prepareModel) }
}

val prepareColorModel by tasks.registering {
    group = "model"
    description = "Downloads and verifies -PcolorModel=${selectedColorModel.id}."
    doLast { prepareModel(selectedColorModel) }
}

val pushColorModelDebug by tasks.registering {
    group = "install"
    description = "Pushes the selected model after installing the debug APK."
    dependsOn(prepareColorModel)

    doLast {
        if (gradle.startParameter.taskNames.any { it.endsWith("benchmarkColorModelsDebug") }) {
            logger.lifecycle("Selected-model push skipped because the A/B task transfers both candidates.")
            return@doLast
        }
        val installDebug = tasks.findByName("installDebug")
        if (installDebug?.state?.failure != null) {
            logger.lifecycle("Model push skipped: installDebug did not complete successfully")
            return@doLast
        }
        val modelFile = prepareModel(selectedColorModel)
        val remoteDir = "/sdcard/Android/data/$appId/files/models"
        val remotePath = "$remoteDir/${selectedColorModel.fileName}"
        val adb = adbPrefix()
        initializeAppModelDirectory(adb)
        exec {
            commandLine(adb + listOf("push", modelFile.absolutePath, remotePath))
        }
        logger.lifecycle("${selectedColorModel.id} pushed to $remotePath")
        // MainActivity may already have completed its missing-model check while
        // the file was being transferred. Restart only after the atomic push so
        // every install/push path immediately discovers and warms the model.
        restartAppAfterModelPush(adb)
    }
}

tasks.matching { it.name == "installDebug" }.configureEach {
    finalizedBy(pushColorModelDebug)
}

val benchmarkColorModelsDebug by tasks.registering {
    group = "verification"
    description = "Installs both candidates and runs the on-device color-model benchmark."
    dependsOn(downloadColorModels, "installDebug", "installDebugAndroidTest")
    doLast {
        val adb = adbPrefix()
        val remoteDir = "/sdcard/Android/data/$appId/files/models"
        initializeAppModelDirectory(adb)
        colorModels.forEach { spec ->
            exec {
                commandLine(adb + listOf("push", prepareModel(spec).absolutePath, "$remoteDir/${spec.fileName}"))
            }
        }
        val instrumentationOutput = ByteArrayOutputStream()
        val instrumentationResult = exec {
            commandLine(
                adb + listOf(
                    "shell", "am", "instrument", "-w", "-r",
                    "-e", "class", "$appId.ColorModelBenchmarkInstrumentedTest",
                    "$appId.test/androidx.test.runner.AndroidJUnitRunner",
                ),
            )
            standardOutput = instrumentationOutput
            errorOutput = instrumentationOutput
            isIgnoreExitValue = true
        }
        val instrumentationText = instrumentationOutput.toString()
        logger.lifecycle(instrumentationText)
        val reportDir = layout.buildDirectory.dir("reports/color-model-benchmark").get().asFile
        reportDir.mkdirs()
        exec {
            commandLine(
                adb + listOf(
                    "pull",
                    "/sdcard/Android/data/$appId/files/benchmarks/.",
                    reportDir.absolutePath,
                ),
            )
            isIgnoreExitValue = true
        }
        logger.lifecycle("Benchmark reports: $reportDir")
        if (instrumentationResult.exitValue != 0 || !instrumentationText.contains("OK (1 test)")) {
            throw GradleException(
                "Color-model benchmark failed (exit ${instrumentationResult.exitValue}); reports were still pulled. " +
                    "See instrumentation output above.",
            )
        }
    }
}
