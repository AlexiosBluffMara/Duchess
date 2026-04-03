// TODO-PRINCIPAL: Build configuration review — issues:
//   1. No DAT SDK dependency. The Meta DAT SDK (mwdat-core, mwdat-camera, mwdat-mockdevice)
//      is referenced throughout the codebase but missing from this build file. This means
//      the app CANNOT compile as-is. Add the GitHub Packages Maven repository and DAT SDK
//      dependencies. See .claude/rules/dat-conventions.md for import patterns.
//   2. No ProGuard/R8 rules for MediaPipe and LiteRT. minifyEnabled=true in release will
//      strip native method bindings and crash at runtime. Need keep rules for:
//        - com.google.mediapipe.** (LLM inference JNI)
//        - org.tensorflow.lite.** (LiteRT interpreter JNI)
//      Test the release build BEFORE the hackathon demo.
//   3. No signing config for release. The hackathon needs a signed APK or AAB for the
//      "live demo URL" deliverable. Set up a keystore and reference it from local.properties.
//   4. compileSdk=35 but the Pixel 9 Fold ships with Android 14 (SDK 34). Verify that
//      all APIs we use are available on SDK 34. compileSdk=35 is fine for compilation but
//      runtime behavior should be tested on the actual target device.
//   5. Missing Cactus SDK dependency. HACKATHON_STRATEGY.md targets the Cactus $10K prize
//      but the SDK isn't in the dependency list. Add it when integration begins.
//   6. No test coverage plugin (JaCoCo). We have test dependencies but no way to measure
//      coverage. For a safety-critical system, coverage metrics should be mandatory in CI.
//
// TODO-ML-PROF: On-device ML dependency sizing:
//   - mediapipe-llm-inference bundles ~15MB of native .so libraries. Combined with
//     litert and litert-gpu, the APK size is ~40MB before the model. Profile the APK
//     with Android Studio's APK Analyzer to identify which native ABIs are included.
//     For Pixel 9 Fold (arm64-v8a only), strip x86/x86_64/armeabi-v7a to save ~25MB.
//   - LiteRT GPU delegate uses OpenCL on most devices but the Tensor G4 supports a
//     custom NPU delegate. Verify that libs.litert.gpu actually routes to NPU on
//     Pixel 9 Fold — may need the google-ai-edge litert-gpu-api artifact instead.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.duchess.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duchess.companion"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // DAT SDK application ID — "0" for local development
        manifestPlaceholders["APPLICATION_ID"] = "0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime)


    // Meta Wearables DAT SDK
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    debugImplementation(libs.mwdat.mockdevice)

    // LiteRT (for Gemma 4 inference)
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    // MediaPipe LLM Inference (Gemma 4 on-device)
    implementation(libs.mediapipe.llm.inference)
    // MediaPipe tasks-core: provides MPImage and BitmapImageBuilder for vision input
    implementation(libs.mediapipe.tasks.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.work.testing)
    testImplementation("org.json:json:20231013")
}
