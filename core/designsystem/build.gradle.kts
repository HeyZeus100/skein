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
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
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
}
