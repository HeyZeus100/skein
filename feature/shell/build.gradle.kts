plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.shell"
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

    debugImplementation(libs.compose.ui.tooling)
    // `lintDebug` resolves `src/debug/AndroidManifest.xml`'s
    // `androidx.activity.ComponentActivity` reference against the *debug
    // variant's* own compile classpath, not the test classpath — a
    // `testImplementation`-only dependency (below) isn't visible to it and
    // trips lint's `MissingClass` check. Same artifact, both scopes.
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
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
}
