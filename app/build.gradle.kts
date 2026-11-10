import java.util.Properties

// 외부 API 키는 local.properties(깃에 올라가지 않음) 또는 환경변수에서 읽는다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.taxitalk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taxitalk"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Pleos 에뮬레이터(arm64) + 실차/x86 에뮬레이터만 포함 (ML Kit 네이티브 라이브러리 용량 절감)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Google Cloud (Speech-to-Text / Translation / Text-to-Speech) API 키 — 없으면 온디바이스 엔진만 사용
        buildConfigField("String", "GOOGLE_CLOUD_API_KEY", "\"${secret("GOOGLE_CLOUD_API_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources {
        // UI 언어는 운전자(ko) + 승객(en/ja/zh)만 포함
        localeFilters += listOf("ko", "en", "ja", "zh")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Pleos Vehicle SDK (typed API)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // Gleo AI 음성 SDK (Pleos STT/TTS — 표준 android.speech/TextToSpeech 는 Pleos에서 동작하지 않음)
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // Pleos NaviHelper SDK: 목적지·남은 거리·도착 이벤트 (지도 화면은 제공하지 않음)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    // On-device translation & language identification
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
