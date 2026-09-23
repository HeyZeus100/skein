plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.chat"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `:core:vault` (skein-e2ki) declares a "distribution" flavor
        // dimension (foss/dev) for its native `.so` ABI filters, pulled in
        // transitively via `:feature:shell` (see below). This module has no
        // flavors of its own and doesn't care which native build it links
        // against for JVM/Robolectric compilation — same
        // `missingDimensionStrategy` every other feature module declares.
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
            // ChatScreenTest is a Robolectric Compose UI test over Material3
            // (`SecureTextField`, `WikilinkAutocompletePopup`) — same reason
            // `:feature:shell`/`:feature:editor` set this (bd memory
            // `robolectric-sdk37-needs-java21`).
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
    implementation(libs.kotlinx.coroutines.core)

    // skein-6as / NORTH_STAR_REVIEW.md §3.4 (SEAM NOW): this module sees
    // only the `core/model` contract — `InferenceEngine`, `RetrievalService`,
    // `PromptAssembler`, `VaultRepository` — never `:core:inference` (the
    // concrete `ContextBudget`/`LlamaCppEngine`) or `:core:ipc`. The
    // concrete `LlamaCppEngine` is supplied by `:app`'s composition root
    // (`skein-whg8`).
    implementation(project(":core:model"))

    // `CitationParser`/`CitationRecords`/`Segment` (skein-n5q, E5.I16) live
    // in `:core:rag`, which is NOT the banned `:core:inference`/`:core:ipc`
    // — it depends only on `:core:model` + `:core:security` (PromptGuard),
    // neither of which touches llama.cpp or the isolated-service AIDL.
    implementation(project(":core:rag"))

    // E7.I2: `MarkdownAst`/`MarkdownRenderer` for the message list.
    implementation(project(":core:markdown"))

    // `SecureTextField` (skein-qiu) lives in `:feature:shell`'s
    // `input` package; this module also reuses `SkeinTheme`/tokens for
    // previews.
    implementation(project(":feature:shell"))

    // The shared `[[` wikilink autocomplete popup (skein-zzu, E7.I5):
    // `AutocompleteHost`/`WikilinkAutocompleteState`/`WikilinkAutocompletePopup`
    // are public precisely so the chat bottom bar can implement its own
    // `AutocompleteHost` without depending on `EditorState` — see that
    // module's `AutocompleteHost` KDoc ("a future chat bottom-bar draft
    // holder").
    implementation(project(":feature:editor"))

    // 📎 attach: SAF `OpenDocument` via `rememberLauncherForActivityResult`.
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    // `FakeInferenceEngine`/`FakeRetrievalService`/`FakePromptAssembler`/
    // `InMemoryVaultRepository`/`InMemoryPersonaService`/`SkeinLogCapture`/
    // `RecordingTabController`/`Builders` — the bead's named fakes.
    testImplementation(project(":testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":testing"))
}
