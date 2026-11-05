plugins {
    id("com.android.application")
}

android {
    namespace = "com.ahmadman.podpil"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadman.podpil"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Opens Android's hidden Bluetooth L2CAP API for the AirPods accessory channel (exact battery %).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// Optional: -ProbolectricRepo=<maven mirror> when Maven Central is rate limited.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
