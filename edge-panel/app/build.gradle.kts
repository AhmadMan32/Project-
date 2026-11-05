plugins {
    id("com.android.application")
}

android {
    namespace = "com.ahmadman.kenarpanel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadman.kenarpanel"
        minSdk = 23
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// Optional: -ProbolectricRepo=<maven mirror> when Maven Central is rate limited.
tasks.withType<Test>().configureEach {
    // Conscrypt (Android's TLS library, used by Robolectric) reads a JDK internal on desktop Java
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
    providers.gradleProperty("robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
