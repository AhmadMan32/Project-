plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kalkan.dns"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kalkan.dns"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Kolay kurulum için debug anahtarıyla imzalanır; Play Store için kendi anahtarınızı kullanın.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // WARP modu: resmi WireGuard tünel kütüphanesi (wireguard-go, Apache-2.0)
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    testImplementation("junit:junit:4.13.2")
}
