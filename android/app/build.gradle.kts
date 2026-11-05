plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tasteradar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tasteradar.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Geofencing and the speed reading that suppresses alerts at 60 km/h.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // The six-hourly check that the fences are still registered. This is the
    // safety net for OEM battery managers, and on a Xiaomi it is not optional.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Deliberately absent: Room, Retrofit, Moshi, Compose. HttpURLConnection and
    // org.json are in the platform, the fence cache is 15 rows of JSON, and every
    // dependency with a code generator is a build that can fail in a way this
    // code was never able to test.
}
