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
        versionCode = 4
        versionName = "1.3"

        // Conscrypt ships native code; phones only need the ARM builds.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    // Built-in wireless debugging client (ADB pairing + TLS connection to the phone itself).
    // Gives a helper process ADB shell rights, which may force media audio to the speaker
    // without the call-like "communication mode". See AdbForce.java.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 key export needed by ADB pairing; the platform copy is a hidden API.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
