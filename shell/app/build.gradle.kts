plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sparky.shell"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sparky.shell"
        minSdk = 28          // J7 Pro runs Android 9
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
