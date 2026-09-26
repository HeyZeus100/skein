// skein-xtov.23.1 (DS1, docs/ux/DESIGN_SYSTEM.md §13): the theme, tokens and
// bundled fonts, extracted from `:feature:shell`. Depends on Compose only —
// never on a feature module or `:core:vault` — so features can depend on it
// without pulling in the shell.
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

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
