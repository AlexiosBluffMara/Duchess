plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.duchess.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duchess.glasses"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // No Compose — AOSP View-based UI only (640x360 Vuzix display)
}

dependencies {
    // AndroidX (AOSP-compatible, no GMS required)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // LiteRT — INT8 quantized YOLOv8-nano for PPE detection
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)

    // Camera2 is part of Android SDK — no Maven dependency needed

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
