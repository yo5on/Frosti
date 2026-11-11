plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.frosti.shell"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.frosti.shell"
        minSdk = 28          // J7 Pro runs Android 9
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "armeabi-v7a" }
        testInstrumentationRunner = "com.frosti.shell.WakeWordCheck"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// FROSTI's animations are authored once in core/ (anim/author.py) and played by both the console and this app
val animAssets = layout.buildDirectory.dir("generated/frosti-anim")
val copyAnims by tasks.registering(Copy::class) {
    from("../../core/frosti_core/anim/frosti_anims.json")
    into(animAssets)
}
val copyWake by tasks.registering(Copy::class) {
    from("D:/frosti-models/speech/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01") {
        include("encoder*int8.onnx", "decoder*int8.onnx", "joiner*int8.onnx", "tokens.txt")
    }
    from("../../core/config/wake-words.txt") { rename { "keywords.txt" } }
    from("../../core/config/satellite-commands.txt") { rename { "commands.txt" } }  // scripts/prepare-satellite.py
    into(animAssets.map { it.dir("wake") })
}
android.sourceSets["main"].assets.srcDir(animAssets)
android.sourceSets["androidTest"].assets.srcDir("../../data/wake-tests")
tasks.named("preBuild") { dependsOn(copyAnims, copyWake) }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("D:/frosti-tools/sherpa-onnx-1.13.8.aar"))
}
