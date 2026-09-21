plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.skein.core.inference"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // skein-7p0 (E4.I9): ThermalGovernorInstrumentedTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // skein-7p0 (E4.I9): ThermalGovernorCore's `state: StateFlow<ThermalState>`
    // and ThermalGovernor's poll loop (kotlinx.coroutines.launch/delay on
    // Dispatchers.Default, which lives in -core, not -android).
    implementation(libs.kotlinx.coroutines.core)

    // skein-st1r (POST_REVIEW_RESOLUTIONS §2): `app.skein.core.inference.models`
    // logs refusals through `SkeinLog` (:core:model), which the NoRawLogging
    // guard requires instead of android.util.Log.
    api(project(":core:model"))
    // skein-st1r: `ModelManifest.parse` reads the `*.skein.json` manifest with
    // kotlinx.serialization's tree API (`parseToJsonElement`) — no compiler
    // plugin, no new library (`:core:model` already exposes this as `api`;
    // naming it here keeps the direct use explicit, as :core:rag does).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    // skein-7p0 (E4.I9): ThermalGovernorInstrumentedTest binds ThermalGovernor
    // to a real PowerManager (compiled unconditionally; on-device run is
    // gated on skein-k3b2 — same pattern as
    // core/vault's VaultKeyProviderInstrumentedTest).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
