plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tasteradar.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tasteradar.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The only dependency. Everything else is platform API, on purpose:
    // a spike that cannot build is worth nothing.
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
