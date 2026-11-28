plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE")
val releaseKeystorePassword = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.jarvis.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.tablet"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1) + 1
        versionName = "1.1.0"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
