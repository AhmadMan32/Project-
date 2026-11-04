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
        versionCode = 1
        versionName = "1.0"
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
