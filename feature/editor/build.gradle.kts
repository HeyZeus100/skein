plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.editor"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    // E7.I4 (bd skein-twb): snapshotFlow-based autosave debouncer needs
    // Flow/debounce/withTimeoutOrNull directly, not just what compose
    // runtime pulls in transitively.
    implementation(libs.kotlinx.coroutines.core)

    // E7.I2 owns the Skein Markdown AST + the AnnotatedString renderer
    // this module reuses (`MarkdownStyle`). The parser stays inside
    // :core:markdown — no third-party markdown lib type crosses the
    // boundary of this module.
    implementation(project(":core:markdown"))
    implementation(project(":core:model"))
    // §9 IME hardening: SecureImeInterceptor + SecureBasicTextField.
    // RawTextFieldTest requires every text-input Composable to go
    // through one of :feature:shell's two allowlisted wrappers.
    implementation(project(":feature:shell"))

    debugImplementation(libs.compose.ui.tooling)
    // `lintDebug` resolves `src/debug/AndroidManifest.xml`'s
    // `androidx.activity.ComponentActivity` reference against the debug
    // variant's compile classpath, not the test classpath — same note as
    // :feature:shell's build script. Without this a `MissingClass` lint
    // error fires even though only the androidTest classpath needs the
    // class at runtime.
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    // EditorAutosaveTest (bd skein-twb) drives EditorState directly with
    // TestScope/runTest — pure JVM, no Robolectric/Compose UI test deps.
    testImplementation(libs.kotlinx.coroutines.test)
    // BacklinksStateTest (bd skein-9jj, E7.I8) drives BacklinksState against
    // the in-memory VaultRepository/IndexStore fakes + SyntheticVault —
    // same shape as :feature:timeline's TimelineStateTest. `:testing` is
    // pure JVM (docs/TESTING.md), so this pulls no Android test
    // infrastructure onto the JVM `test` classpath.
    testImplementation(project(":testing"))

    // On-device Compose UI test (skein-03f acceptance: compile the UI
    // test even where the local worktree cannot run it; bd `skein-k3b2`
    // tracks the CI emulator gate). Placed under `androidTest` so the
    // JVM `test` classpath does not pull in the Compose UI test deps —
    // those pull kotlinx-coroutines-test / androidx.collection variants
    // that are not yet pinned in `gradle/verification-metadata.xml`.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)
    // BacklinksDrawerInstrumentedTest (bd skein-9jj) seeds rows via the
    // in-memory fakes, same as SkeinEditorInstrumentedTest's WikilinkTarget
    // wiring test does with plain Kotlin types.
    androidTestImplementation(project(":testing"))
}
