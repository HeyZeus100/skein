plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.shell"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // skein-ugo (E3.I4): BiometricUnlockScreenTest is this module's
        // first androidTest source set.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `:core:vault` (skein-e2ki) declares a "distribution" flavor
        // dimension (foss/dev) for its native `.so` ABI filters. This
        // module has no flavors of its own and doesn't care which native
        // build it links against for JVM/Robolectric compilation, so
        // resolve the ambiguity the same way `:app` effectively does by
        // also declaring `distribution` — default to `foss` (the
        // production/arm64-only build); `:app`'s own flavor selection is
        // what actually decides the shipped variant.
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
            // E3.I9: SecureTextFieldTest reads the merged EditorInfo through a
            // Robolectric-shadowed InputMethodManager/InputConnection, which
            // needs merged resources on the classpath (same reason as :app's
            // ManifestPolicyTest — bd memory `robolectric-sdk37-needs-java21`).
            isIncludeAndroidResources = true
        }
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
    // E6.I2: fold posture (WindowInfoTracker/FoldingFeature) + WindowSizeClass
    // breakpoints (currentWindowAdaptiveInfo) for AdaptivePaneHost.
    implementation(libs.androidx.window)
    implementation(libs.material3.adaptive)
    implementation(libs.kotlinx.coroutines.core)

    // skein-ugo (E3.I4): BiometricUnlockScreen drives `UnlockManager`
    // (`skein-pya`) above `VaultKeyProvider` (`skein-3el`); both live in
    // `:core:vault`, which `api`-exposes `:core:model`'s
    // `AuthorizationToken`. `androidx.biometric` is needed directly here
    // only to build `BiometricPrompt.PromptInfo` — the `CryptoObject`
    // itself is constructed inside `:core:vault`'s
    // `AndroidBiometricAuthenticator`, not in this module.
    implementation(project(":core:vault"))
    implementation(libs.androidx.biometric)

    debugImplementation(libs.compose.ui.tooling)
    // `lintDebug` resolves `src/debug/AndroidManifest.xml`'s
    // `androidx.activity.ComponentActivity` reference against the *debug
    // variant's* own compile classpath, not the test classpath — a
    // `testImplementation`-only dependency (below) isn't visible to it and
    // trips lint's `MissingClass` check. Same artifact, both scopes.
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    // skein-ank2: VaultSetupStateTest drives the setup state holder's
    // launched work under `runTest` / `backgroundScope`.
    testImplementation(libs.kotlinx.coroutines.test)
    // E3.I9: SecureTextFieldTest asserts the EditorInfo flags a Robolectric
    // Compose host produces (bd memory `compose-ui-test-infra-robolectric-compose-ui-test`
    // — this is the module's first real Compose UI test infra, added here
    // rather than waiting on a broader rollout since the acceptance criteria
    // requires reading a live EditorInfo).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    // Host activity for the Compose test rule: `:feature:shell` declares its
    // own debug-only `androidx.activity.ComponentActivity` registration
    // (src/debug/AndroidManifest.xml) rather than relying on
    // `ui-test-manifest`'s generic registration, which does not merge into
    // a *library* module's manifest (see that file's comment).
    testImplementation(libs.androidx.activity.compose)

    // skein-ugo (E3.I4): BiometricUnlockScreenTest — on-device Compose UI
    // test, matching `:feature:editor`'s `SkeinEditorInstrumentedTest`
    // shape. Compiled here; the on-device run is gated on the CI emulator
    // lane tracked by bd `skein-k3b2`.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
