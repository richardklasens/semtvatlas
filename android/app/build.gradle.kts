plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.atlas.semtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.atlas.semtv"
        minSdk = 24          // Android 7.0 (DevicePolicyManager.reboot vereist API 24)
        targetSdk = 33       // bewust 33: geen foreground-service-type-eisen van API 34 voor een kiosk
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
