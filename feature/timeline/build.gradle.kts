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
    // `debugCompileOnly`, not `debugImplementation` (bd `skein-64y9`):
    // `:testing` `api`-exposes JUnit 4 (EPL-1.0, off the foss allowlist) and
    // kotlinx-coroutines-test, and Gradle's runtime classpath is transitive
    // regardless of the `api`/`implementation` split at the *declaring*
    // edge — an `implementation`-scoped dependency still rides the runtime
    // classpath of anything that needs to load the class that depends on
    // it. `debugImplementation` therefore dragged `:testing` (+ its `api`
    // deps) into every consumer's debug runtime classpath — e.g. `:app`'s
    // `licenseAuditFossDebugRuntimeClasspath` — even though the only thing
    // that ever calls into `:testing` here is a `@Preview` function Android
    // Studio's tooling renders, never code that runs in a shipped debug
    // build. `debugCompileOnly` keeps `:testing` resolvable to *compile*
    // `TimelineScreenPreview.kt` in this module's own debug variant without
    // putting it on any consumer's runtime classpath, so `:app` no longer
    // needs `exclude(module = "testing")`.
    debugCompileOnly(project(":testing"))

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
