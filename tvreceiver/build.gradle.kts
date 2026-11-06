plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rajat.fireremote.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rajat.fireremote.tv"
        // dispatchGesture() (taps and swipes) needs API 24+.
        // That excludes the 2nd-gen Fire TV Stick (Fire OS 5 / API 22).
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
