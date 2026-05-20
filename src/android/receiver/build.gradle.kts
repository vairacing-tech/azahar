// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.azahar.secondscreen"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.azahar.secondscreen"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
