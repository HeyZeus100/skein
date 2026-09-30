plugins {
    alias(libs.plugins.android.library)
    // E1.I2: isolation guard — this module may depend only on :core:ipc,
    // :core:model, :core:verify, Kotlin stdlib/coroutines, and onnxruntime-android (spec
    // §2.6, plan §2.4).
    id("app.skein.guard.isolation")
}

android {
    namespace = "app.skein.embedder.service"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // onnxruntime-android ships armeabi-v7a/x86/x86_64 .so's too; :app's
        // `foss` flavor already restricts packaging to arm64-v8a via
        // `ndk.abiFilters`, but these excludes make the arm64-v8a-only intent
        // explicit at the module that introduces the native dependency.
        jniLibs {
            excludes += listOf("**/armeabi-v7a/**", "**/x86/**")
        }
    }
}

dependencies {
    implementation(project(":core:ipc"))
    implementation(project(":core:verify"))
    // Backend-neutral Matryoshka output uses the shared, fixed int8 rule.
    implementation(project(":core:model"))
    // Keep the isolated service free of app/vault and AndroidX dependencies.
    // This pre-existing runtime dependency is not an approved backend selection.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Desktop native lib so OrtSmokeTest can run real inference on the JVM
    // (see the libs.versions.toml comment on onnxruntime-jvm).
    testImplementation(libs.onnxruntime.jvm)
}
