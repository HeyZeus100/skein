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

    testOptions {
        unitTests {
            // bd `skein-fay`: `ShareMenuTest`'s Robolectric Compose host
            // needs merged resources to render `MaterialTheme`/`DropdownMenu`
            // — same reason `:feature:shell`'s `SecureTextFieldTest`/
            // `SkeinAppTest` set this (bd memory `robolectric-sdk37-needs-java21`).
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
    // E7.I3 (bd skein-6rr): the frontmatter hide/show editor round-trips
    // through the same `Frontmatter.parse`/`render` codec `:core:vault`
    // already ships (skein-3fn) — no reimplementation here. `:core:vault`
    // has no dependency back on this module (see its own build.gradle.kts
    // note on `:core:markdown`), so this does not create a cycle.
    implementation(project(":core:vault"))
    // bd `skein-fay` (E6.I16): "Export as PDF" calls `PdfExportService`
    // directly (it needs an Android `Context` to build the
    // `PrintDocumentAdapter`'s layout pipeline, per that class's own
    // header) rather than through `ExportService`'s pure-Kotlin contract,
    // which has no PDF method by design.
    implementation(project(":core:export"))
    // §9 IME hardening: SecureImeInterceptor + SecureBasicTextField.
    // RawTextFieldTest requires every text-input Composable to go
    // through one of :feature:shell's two allowlisted wrappers.
    implementation(project(":feature:shell"))
    // bd `skein-fay`: the "Save as..." menu launches `ACTION_CREATE_DOCUMENT`
    // via `rememberLauncherForActivityResult` from `NoteTab`'s main-source
    // composable (not just a debug preview), so this is a real
    // `implementation` dependency now, not the debug-only one below.
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

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
    // bd `skein-fay`: `ShareIntentsTest`/`SaveAsIntentsTest` build real
    // `android.content.Intent`s (needs Robolectric's shadow — the compile
    // `android.jar` stub throws on every method body), and `ShareMenuTest`
    // is a Robolectric Compose UI test over `NoteTab`'s header menu — same
    // shape as `:feature:shell`'s `SecureTextFieldTest`/`SkeinAppTest`.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)

    // On-device Compose UI test (skein-03f acceptance: compile the UI
    // test even where the local worktree cannot run it; bd `skein-k3b2`
    // tracks the CI emulator gate).
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
