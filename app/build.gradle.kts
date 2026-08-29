plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.itantra.relay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itantra.relay"
        minSdk = 26            // Android 8.0, per the spec
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // The on-device ML runtime (sherpa-onnx) ships arm64-v8a native libs only.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose — versions managed by the BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Added later (Week 1+), left here as a map of what's coming ----
    // implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:<version>")   // STT + TTS + VAD
    // implementation("com.github.mik3y:usb-serial-for-android:<ver>") // USB link to Jai's LoRa board
}
