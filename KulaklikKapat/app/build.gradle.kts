plugins {
    id("com.android.application")
}

android {
    namespace = "com.ahmadman.disableheadphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadman.disableheadphone"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload APK: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Shizuku: runs a tiny helper with ADB (shell) rights, which may force media audio to the
    // speaker without the call-like "communication mode". See ShizukuForce.java.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
}
