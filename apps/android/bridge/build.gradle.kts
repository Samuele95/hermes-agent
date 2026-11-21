plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The payload `scripts/android/payload.py` assembled: the C glue compiles against its headers and libpython.
val payload: File = providers.gradleProperty("hermes.payload").map(::file).getOrElse(rootProject.file("payload"))

android {
    namespace = "com.nousresearch.hermes.bridge"
    compileSdk = 37
    ndkVersion = "27.3.13750724" // python.org's Android/android-env.sh pin for 3.14
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DHERMES_PAYLOAD=$payload", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
