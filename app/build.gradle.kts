plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sih.itantra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sih.itantra"
        // Android 8.0. Fixed by the spec: the on-device ML runtime and the audio path are
        // only validated from here up.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // sherpa-onnx / ONNX Runtime ship large native libs per ABI. Every target phone
            // is 64-bit ARM, so shipping only this one keeps the APK inside the 100 MB budget.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    androidResources {
        // Model weights must stay uncompressed so ONNX Runtime can mmap them straight out of
        // the APK. Compressed assets would be inflated into heap on every load instead.
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // sherpa-onnx: on-device STT/TTS runtime (ONNX Runtime Mobile + JNI + Kotlin API), shipped
    // as a prebuilt AAR from the k2-fsa release. Bundles native libs for four ABIs; the
    // arm64-v8a filter in defaultConfig keeps only ours in the final APK.
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
