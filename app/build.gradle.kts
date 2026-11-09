plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.tablet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // المفتاح بييجي من GitHub Secret وقت البناء
        buildConfigField("String", "GEMINI_KEY", "\"${System.getenv("GEMINI_API_KEY") ?: ""}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
