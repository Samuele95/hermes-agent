plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nousresearch.hermes.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nousresearch.hermes"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // runtime.zip is deflated already; the APK stores it as is.
    androidResources { noCompress += "zip" }
}

// The runtime payload `python -m scripts.android.payload --out <dir>` assembles: an Android
// source-set layout (jniLibs/, assets/). Defaults to apps/android/payload; -Phermes.payload=<dir>.
val payload: File = providers.gradleProperty("hermes.payload").map(::file).getOrElse(rootProject.file("payload"))

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(payload.resolve("jniLibs").path)
        variant.sources.assets?.addStaticSourceDirectory(payload.resolve("assets").path)
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
