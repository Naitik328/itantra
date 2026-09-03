// Standalone on-device smoke test for OnnxMtAdapter and friends -- NOT the
// real app. See ../README.md for why this exists as its own tiny module
// (isolates the "does the in-graph tokenizer custom op even load on
// Android" question from the much bigger "merge into the real app" one).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.mttest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.mttest"
        minSdk = 24 // onnxruntime-android supports 21+; 24 is a safe modern floor
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Compiled directly from the shared source of truth in translation/kotlin --
    // no copy here to drift out of sync with. Only the packages this test
    // actually needs; the rest of com.itantra.mt (IndicProcessor etc.) comes
    // along transitively since Orchestrator/OnnxMtAdapter import it.
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("../../kotlin/com/itantra/mt", "../../kotlin/com/itantra/config", "../../kotlin/com/itantra/adapters", "../../kotlin/com/itantra/orchestrator")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        // onnxruntime + onnxruntime-extensions both bundle native libs;
        // avoid the classic "duplicate META-INF" merge failure.
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")
    // org.json is provided by the Android platform (android.jar) -- no
    // explicit dependency needed here, unlike the desktop kotlin_verify/
    // setup which had to fetch a real jar to stand in for the platform stub.

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
