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
        // Versienummer loopt automatisch op met elke CI-build (-PverCode=<run-nummer>); zonder dat blijft het 1.
        // Android laat een update alleen toe met een HOGER versionCode en dezelfde handtekening.
        val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionCode = verCode
        versionName = "1.0.$verCode"
    }
    // Vaste handtekening: elke build is hiermee een geldige update op de vorige (de standaard debug-sleutel
    // verschilt per bouwmachine, waardoor updaten dan faalt met INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    signingConfigs {
        create("kiosk") {
            storeFile = file("kiosk.keystore"); storePassword = "semtvatlas"; keyAlias = "kiosk"; keyPassword = "semtvatlas"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("kiosk") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("kiosk") }
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
