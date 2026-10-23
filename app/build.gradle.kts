plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.agent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.agent"
        minSdk = 28          // Android 9. ПРОВЕРЬТЕ: если merge manifest ругнётся на minSdk litertlm — см. README
        targetSdk = 34       // Android 14
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }   // модель всё равно только на arm64
    }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release") // лучше зафиксировать версию после первого успешного билда
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
