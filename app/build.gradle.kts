plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("com.chaquo.python")
}

android {
    namespace = "com.gemma.api"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gemma.api"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "2.1.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // CI provides these via environment variables
            // Local builds fall back to debug signing
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && File(ksFile).exists()) {
                storeFile = File(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "oracle_os"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && File(ksFile).exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fall back to debug signing so APK always installs
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Chaquopy: Python runtime for RLM (Recursive Language Models)
    chaquopy {
        defaultConfig {
            version = "3.12"
            pip {
                // RLM minimal has no heavy deps — just needs these for the REPL
                install("rich")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // LiteRT-LM: The CORRECT library for Gemma 3n multimodal inference
    // (NOT mediapipe:tasks-genai - that's a different/older API)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.9.0-alpha01")
    
    // TFLite GPU delegate - Use Google Play Services versions (like Gallery app)
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-support:16.4.0")


    implementation("androidx.core:core-ktx:1.13.1")

    // UI & Layouts
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coroutines - 1.10.2 for Koog/Ktor 3.x compatibility
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Ktor 3.x Server (compatible with coroutines 1.10.2)
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-gson:3.0.3")

    // Koog - JetBrains Agent Framework
    implementation("ai.koog:koog-agents:0.6.0")

    // Kotlinx Serialization (required by Koog)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.jakewharton.timber:timber:5.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
