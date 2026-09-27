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
    namespace = "app.skein.feature.models"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // skein-xtov.9/24.9: the `:feature:shell` dependency pulls in
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
            isIncludeAndroidResources = true
        }
    }
}

// skein-xtov.23.16 (UT-2), docs/ux/UX_TEST_PLAN.md §4.1: committed goldens
// live under the repo-root ux-baselines/feature-models/ (one directory per
// module); compare/verify write their _compare/_actual pairs into this
// module's own git-ignored build/ dir, so `git status ux-baselines/` only
// ever shows a deliberate record (never a stray compare run).
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/feature-models"))
    compare {
        outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
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
    // Pins the transitive `androidx.activity`/`androidx.savedstate` versions
    // `compose.ui.test.junit4` below otherwise resolves independently to an
    // old, unverified pair (1.2.1 / 1.1.0) — the same explicit-declaration
    // fix `:feature:shell`'s build file already applies for the same reason
    // (see that file's own comment).
    implementation(libs.androidx.activity.compose)
    // skein-xtov.23.6 (DS6): `SkeinIcons` for the model row's leading glyph.
    // `:core:designsystem` is the whole point of DS1's extraction
    // (docs/ux/DESIGN_SYSTEM.md §13.1): a UI/tokens-only module with no
    // vault/inference dependency of its own, so depending on it doesn't
    // compromise this screen's "plain data and function types" boundary
    // (this file's header comment).
    implementation(project(":core:designsystem"))
    // skein-xtov.24.9 (AL-09b): `entries/ModelsEntries.kt` re-hosts
    // `ModelsScreen`'s panes as NavDisplay entries, over `:feature:shell`'s
    // `SkeinShellState`/`EntryTopBar` (ADAPTIVE_LAYOUT_SPEC.md §8.1: "features
    // depend on keys, never on one another" — `:feature:shell` is the shared
    // host every re-hosted destination depends on). `ModelsScreen`'s own
    // "plain data and function types" screens are unchanged by this.
    implementation(project(":feature:shell"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    // skein-xtov.9: Roborazzi screenshot tests (screenshots/ test package).
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // skein-xtov.23.15 (UT-1): the shared device matrix / captureUx /
    // skeinComposeRule helper module (docs/ux/UX_TEST_PLAN.md §5), replacing
    // this module's own copy of UxScreenshots.kt.
    testImplementation(project(":testing-ui"))
    // skein-xtov.24.9 (AL-09b): the entry tests host the real `SkeinShellHost`,
    // whose session stores register with an `UnlockManager`.
    testImplementation(project(":core:vault"))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
    // Pins the transitive `kotlinx-coroutines-test` version Robolectric/
    // compose-ui-test otherwise resolve independently to 1.9.0 — whose jar
    // (only its module metadata) is unverified in this project — to the
    // same 1.11.0 every other module already uses and has fully verified.
    testImplementation(libs.kotlinx.coroutines.test)
}
