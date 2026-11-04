plugins {
    id("com.android.application")
}

android {
    namespace = "com.ahmadman.pdfconverter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadman.pdfconverter"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // PdfBox uses reflection; keep the code unshrunk so nothing is stripped by mistake.
            isMinifyEnabled = false
            // Sideload APK: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric tests use strings and PDFBox assets
    }
}

dependencies {
    // Apache PDFBox port for Android: text extraction, merge/split, encryption, editing.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// Optional: -ProbolectricRepo=<maven mirror> when Maven Central is rate limited.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
