// skein-xtov.23.16 (UT-2): `roborazzi.compare { outputDir }` below is
// `@ExperimentalRoborazziApi` (checked with `javap`); this is the standard
// build-script-scoped opt-in, not a suppression of a real warning elsewhere.
@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // skein-xtov.9: JVM screenshot tests (docs/ux/research/ROBORAZZI_SPIKE.md).
    alias(libs.plugins.roborazzi)
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
        // skein-xtov.9: the test-only `:feature:shell` dependency pulls in
        // `:core:vault`'s "distribution" flavor dimension — resolve it to
        // `foss`, same as every other feature module.
        missingDimensionStrategy("distribution", "foss")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // skein-xtov.9: Robolectric Compose screenshot tests need merged
            // resources (fonts) on the classpath — same as every other module.
            isIncludeAndroidResources = true
        }
    }
}

// skein-xtov.23.16 (UT-2), docs/ux/UX_TEST_PLAN.md §4.1: committed goldens
// live under the repo-root ux-baselines/feature-timeline/ (one directory per
// module); compare/verify write their _compare/_actual pairs into this
// module's own git-ignored build/ dir, so `git status ux-baselines/` only
// ever shows a deliberate record (never a stray compare run).
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/feature-timeline"))
    compare {
        outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
    }
}

dependencies {
    implementation(project(":core:designsystem"))
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
    // skein-xtov.23.18 (UT-4): from `:testing-fakes`, the JUnit-free half of
    // `:testing`. This used to be `debugCompileOnly(project(":testing"))`
    // (bd `skein-64y9`) because `:testing` `api`-exposes JUnit 4 (EPL-1.0,
    // off the foss allowlist) and a `debugImplementation` edge failed `:app`'s
    // `licenseAuditFossDebugRuntimeClasspath`; but compile-only left the
    // fakes on no runtime classpath, so nothing could actually run the
    // preview. `:testing-fakes`' runtime closure is `:core:model` +
    // coroutines-core, both already in the APK, so it can ride the debug
    // runtime classpath. Debug only: `checkNoTestDoublesInMain` fails the
    // build if a non-debug, non-test configuration (or any `src/main` file)
    // reaches `app.skein.testing`.
    debugImplementation(project(":testing-fakes"))

    // `TimelineStateTest` drives the state holder directly with
    // `runTest`/`backgroundScope` (JVM, no Robolectric/Compose UI test
    // deps) — same shape as `:feature:editor`'s `EditorAutosaveTest`.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing"))
    // skein-xtov.9: Roborazzi screenshot tests (screenshots/ test package) —
    // the same Robolectric Compose infra `:feature:graph` uses, plus
    // `:feature:shell` (test-only) for `SkeinTheme`, which `:app` wraps this
    // screen in. Main source still does not depend on `:feature:shell`.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // skein-xtov.23.15 (UT-1): the shared device matrix / captureUx /
    // skeinComposeRule helper module (docs/ux/UX_TEST_PLAN.md §5), replacing
    // this module's own copy of UxScreenshots.kt (and UxFixtures.kt, which
    // stays here — it is fixture vault content, not the generic helper).
    testImplementation(project(":testing-ui"))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(project(":feature:shell"))

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
