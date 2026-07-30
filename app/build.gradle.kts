plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gios.lightsports"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightsports"
        minSdk = 29
        targetSdk = 35
        // CI stamps versionCode from the workflow run number and appends it to the
        // major.minor below, so the release tag is <major>.<minor>.<run>. Bump this by
        // hand for anything Obtainium should treat as a new version.
        versionCode = 1
        versionName = "1.5.0"

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightsports.jks")
            storePassword = "lightsports"
            keyAlias = "lightsports"
            keyPassword = "lightsports"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig for VERSION_NAME, which the settings screen shows so a sideloaded
    // build can be identified without going through Obtainium.
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Networking. Every provider here is a plain public JSON endpoint, no auth.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
