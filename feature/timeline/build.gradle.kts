plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.timeline"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // skein-2qv (E6.I7): TimelineScreenInstrumentedTest is this module's
        // first androidTest source set (compile-only in this milestone; the
        // on-device lane is bd `skein-k3b2`).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    // TimelineState re-subscribes `VaultRepository.observeTimeline(...)`
    // through `flatMapLatest` on every filter / page-window change.
    implementation(libs.kotlinx.coroutines.core)

    // `VaultRepository`, `Document`, `TimelineFilter`, `DocumentKind`,
    // `Persona` — the aggregate contract this surface renders. Pure
    // Kotlin/JVM types; `:core:model` exposes coroutines-core and
    // kotlinx-serialization-json as `api`, so `Document.frontmatter`
    // (`JsonObject`) is readable here without a second declaration. This
    // module deliberately does not depend on `:core:vault` or
    // `:feature:shell`: the screen takes an already-open repository and
    // never unlocks anything itself.
    implementation(project(":core:model"))

    debugImplementation(libs.compose.ui.tooling)
    // `lintDebug` resolves `src/debug/AndroidManifest.xml`'s
    // `androidx.activity.ComponentActivity` reference against the debug
    // variant's compile classpath, not the test classpath — mirrors the
    // note in `:feature:editor` and `:feature:shell`. Without this a
    // `MissingClass` lint error fires even though only the androidTest
    // classpath needs the class at runtime.
    debugImplementation(libs.androidx.activity.compose)
    // `TimelineScreenPreview` (src/debug) seeds the design-time `@Preview`s
    // with `InMemoryVaultRepository` + `SyntheticVault.Preset.MEDIUM`.
    // Debug-variant only so the fake and the synthetic vault never ship in
    // a release build. `:testing` is pure JVM (docs/TESTING.md), so this
    // pulls no Android test infrastructure onto the debug classpath.
    debugImplementation(project(":testing"))

    // `TimelineStateTest` drives the state holder directly with
    // `runTest`/`backgroundScope` (JVM, no Robolectric/Compose UI test
    // deps) — same shape as `:feature:editor`'s `EditorAutosaveTest`.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing"))

    // On-device Compose UI test (skein-2qv acceptance: compile the UI test
    // even where the local worktree cannot run it; bd `skein-k3b2` tracks
    // the CI emulator gate).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(project(":testing"))
}
