// skein-xtov.23.1 (DS1, docs/ux/DESIGN_SYSTEM.md §13): the theme, tokens and
// bundled fonts, extracted from `:feature:shell`. Depends on Compose only —
// never on a feature module or `:core:vault` — so features can depend on it
// without pulling in the shell.
//
// skein-xtov.23.10 (DS10): also depends on `:core:markdown` for
// `rememberSkeinMarkdownStyle()` (theme/SkeinMarkdownStyle.kt). That module
// must stay pure Kotlin/JVM (`app.skein.guard.isolation`, E7.I2) and declares
// zero project dependencies of its own, so this is the only direction that
// doesn't cycle: `:core:markdown` can never depend back on this Android
// module. `api`, not `implementation`, since `rememberSkeinMarkdownStyle()`'s
// return type is `:core:markdown`'s `MarkdownStyle` — a caller needs that
// type on its own classpath, not just this function.
//
// skein-xtov.23.8 (DS8): the `components` package's behaviour/semantics
// tests and screenshots need Roborazzi + `:testing-ui`, wired exactly like
// the feature modules (skein-xtov.23.16's `roborazzi { outputDir }`, so the
// goldens land in ux-baselines/core-designsystem/). `compare { outputDir }`
// is `@ExperimentalRoborazziApi` — the same build-script opt-in they use.
@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.skein.core.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
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
            // skein-xtov.23.6 (DS6): SkeinIconsTest resolves every
            // `R.drawable.ic_skein_*` id and enumerates the merged resource
            // set — Robolectric only sees those without this on (same
            // reason `:core:vault`'s Robolectric tests need it).
            isIncludeAndroidResources = true
        }
    }
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/core-designsystem"))
    compare {
        outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
    }
}

dependencies {
    // Not used directly: pins the transitive `androidx.core` to the verified
    // version every other module declares (Compose alone resolves older,
    // unverified ones).
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    api(project(":core:markdown"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // skein-xtov.23.6 (DS6): SkeinIconsTest resolves `R.drawable` ids and
    // reads back their resource names, which needs a real `Resources` —
    // same Robolectric-on-the-JVM setup as `:core:vault`'s tests.
    // skein-xtov.23.5 (DS5): also exercised directly by SkeinReducedMotionTest,
    // which fakes Settings.Global.ANIMATOR_DURATION_SCALE against a real
    // Context (androidx.test.ext.junit for ApplicationProvider).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // skein-xtov.23.8 (DS8): Compose UI tests + Roborazzi captures of the
    // `components` package (same set as `:feature:models`; the explicit
    // activity/coroutines-test entries pin versions the way that module's
    // comments explain). `:testing-ui` test-depends back on this module for
    // its preview-annotation test — a configuration-level cycle only, no
    // task cycle (each side needs only the other's main classes).
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(project(":testing-ui"))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.kotlinx.coroutines.test)
}
