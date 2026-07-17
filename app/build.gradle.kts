plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appId = "com.example.ble1507"
val qwenModelName = "qwen2.5-0.5b-instruct-q4.gguf"

android {
    namespace = appId
    compileSdk = 36
    ndkVersion = "28.1.13356709"

    defaultConfig {
        applicationId = appId
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    implementation(files("../../sprbox-SDKDemo/app/libs/sprboxlib-std-0.2.4-release.aar"))
    implementation(files("../../sprbox-SDKDemo/app/libs/sprboxlib-imuadv-0.2.1-debug.aar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val pushQwenModelDebug by tasks.registering {
    group = "install"
    description = "Pushes app/models/$qwenModelName to the installed app's external files directory."

    doLast {
        val installDebug = tasks.findByName("installDebug")
        if (installDebug?.state?.failure != null) {
            logger.lifecycle("Qwen model push skipped: installDebug did not complete successfully")
            return@doLast
        }

        val modelFile = layout.projectDirectory.file("models/$qwenModelName").asFile
        if (!modelFile.exists()) {
            logger.lifecycle("Qwen model push skipped: place $qwenModelName at ${modelFile.absolutePath}")
            return@doLast
        }

        val remoteDir = "/sdcard/Android/data/$appId/files/models"
        val remotePath = "$remoteDir/$qwenModelName"
        exec {
            commandLine("adb", "shell", "mkdir", "-p", remoteDir)
        }
        exec {
            commandLine("adb", "push", modelFile.absolutePath, remotePath)
        }
        logger.lifecycle("Qwen model pushed to $remotePath")
    }
}

tasks.matching { it.name == "installDebug" }.configureEach {
    finalizedBy(pushQwenModelDebug)
}