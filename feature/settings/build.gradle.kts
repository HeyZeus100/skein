plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // E9.I8: LicensesRepository deserializes assets/licenses.json via
    // kotlinx.serialization (LicenseEntry).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.skein.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // skein-ugo (E3.I4): `:feature:shell` now depends on `:core:vault`
        // (skein-e2ki's "distribution" foss/dev flavor dimension, for its
        // native `.so` ABI filters). This module has no flavors of its own
        // and doesn't care which native build it transitively links
        // against, so resolve the ambiguity the same way `:app` effectively
        // does — default to `foss` (the production/arm64-only build);
        // `:app`'s own flavor selection is what actually decides the
        // shipped variant.
        missingDimensionStrategy("distribution", "foss")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // `SecureTextField`, `SkeinTheme`, `SkeinTokens` (E6.I1/`skein-qiu`). One-way
    // dependency only: `:feature:settings` must never be depended on by
    // `:feature:shell` (see `SettingsViewModel`'s doc on why `:app` — not
    // `:feature:shell` — owns wiring `SecurityPrefs` into this screen).
    implementation(project(":feature:shell"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // On-device Compose UI test for the E3.I14 (skein-up0) idle-timeout
    // selector surviving activity recreation — compile the UI test even
    // where the local worktree cannot run it; bd `skein-k3b2` tracks the CI
    // emulator gate (same pattern as `:feature:graph`'s `GraphViewInstrumentedTest`).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)

    debugImplementation(libs.androidx.activity.compose)
}
