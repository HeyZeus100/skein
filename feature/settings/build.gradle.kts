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
}
