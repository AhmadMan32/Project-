plugins {
    id("com.android.application")
}

android {
    namespace = "com.ahmadman.linkdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadman.linkdownloader"
        minSdk = 24 // required by youtubedl-android
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // One APK per processor type keeps the download small (Python + ffmpeg are native).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // yt-dlp bridge uses Jackson reflection: keep the code unshrunk.
            isMinifyEnabled = false
            // Sideload APK: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // The library unpacks Python/ffmpeg from the extracted native library folder.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // yt-dlp (Python) + QuickJS and ffmpeg for Android.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
