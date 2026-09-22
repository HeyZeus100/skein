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

    testOptions {
        unitTests {
            // E6.I18 (skein-fsn): SettingsScreenTest is this module's first
            // Robolectric-backed Compose UI test — same reason as :app's
            // ManifestPolicyTest / :feature:shell's SecureTextFieldTest (bd
            // memory `robolectric-sdk37-needs-java21`): merged resources
            // need to be on the classpath for the Compose host to resolve.
            isIncludeAndroidResources = true
        }
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
    // skein-v9g (E3.I11): Settings › Security › "Export vault key
    // (passphrase)" evaluates the passphrase floor with
    // `:core:vault`'s `PassphraseStrength` — the one definition of that
    // floor, so this screen cannot drift below what
    // `PassphraseKeyExport.export` enforces. Only that object is used here:
    // the crypto itself and the in-memory master stay behind the host's
    // callbacks, exactly as `SecurityPrefs` does. `:feature:shell` depends
    // on `:core:vault` too, but as `implementation`, so it is not visible
    // transitively — hence this direct declaration.
    implementation(project(":core:vault"))
    // The export's `ACTION_CREATE_DOCUMENT` destination picker
    // (`rememberLauncherForActivityResult`). Previously debug-only, below.
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // E6.I18 (skein-fsn): SettingsScreenTest asserts the Indexing hint row
    // (`settings_indexing_hint`) via a Robolectric-hosted Compose rule —
    // same infra as `:feature:shell`'s SecureTextFieldTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    // Host activity for the Compose test rule: `:feature:settings` declares
    // its own debug-only `androidx.activity.ComponentActivity` registration
    // (src/debug/AndroidManifest.xml) rather than relying on
    // `ui-test-manifest`'s generic registration, which does not merge into
    // a *library* module's manifest (see `:feature:shell`'s equivalent file).
    testImplementation(libs.androidx.activity.compose)

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
}
