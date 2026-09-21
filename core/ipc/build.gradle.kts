plugins {
    alias(libs.plugins.android.library)
    // E0.I16 (skein-mfw): plan §4.7 declares every cross-process request /
    // response shape as an `@Parcelize data class`. AGP 9's built-in Kotlin
    // support compiles the sources; the Parcelize compiler plugin generates
    // the `writeToParcel`/`CREATOR` implementations. Applied by bare id, not
    // a catalog alias: AGP already carries this plugin on the build
    // classpath, and a versioned request is rejected with "already on the
    // classpath with an unknown version" (same reason the catalog has no
    // kotlin-android entry).
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    // E0.I16 (skein-mfw): contract modules live under `us.aherrera.skein.*`
    // (implementation modules use `app.skein.*`). The AIDL package
    // (`us.aherrera.skein.ipc`, plan §4.7) is what `:inference-service`,
    // `:embedder-service` and the `:app`-side clients import, so the module
    // namespace matches it rather than the `app.skein.core.ipc` placeholder
    // that E1.I1 scaffolded.
    namespace = "us.aherrera.skein.ipc"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        // E0.I16: compile `src/main/aidl/**` into the `IInferenceService`,
        // `IInferenceCallback` and `IEmbedderService` Java stubs.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // E0.I16: `Parcel.obtain()` / `ParcelFileDescriptor` are `android.os`
    // types with no JVM implementation in AGP's mockable android.jar, so the
    // round-trip and Binder size-guard tests run under Robolectric.
    testImplementation(libs.robolectric)
}
