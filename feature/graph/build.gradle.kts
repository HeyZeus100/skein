plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.graph"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // skein-z2u (E6.I11): this module depends on `:feature:shell` for
        // `LocalSkeinTokens`/`SkeinTheme` (terminal/editor visual style,
        // IBM Plex Mono) the same way `:feature:editor` does, which pulls in
        // `:feature:shell`'s `:core:vault` dependency (skein-e2ki's
        // "distribution" foss/dev flavor dimension for its native `.so` ABI
        // filters). This module has no flavors of its own and doesn't care
        // which native build it transitively links against — default to
        // `foss`, same as every other leaf feature module.
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
    // `GraphState` loads `IndexStore.neighborhood(...)` on `LaunchedEffect`
    // launch via `rememberCoroutineScope()`; same shape as
    // `:feature:timeline`'s `TimelineState`.
    implementation(libs.kotlinx.coroutines.core)

    // `DocId`, `Document`, `DocumentKind`, `Edge`, `EdgeKind`, `IndexStore`,
    // `VaultRepository` — the aggregate contract `GraphState` reads. Pure
    // Kotlin/JVM types; this module deliberately does not depend on
    // `:core:vault` directly (same discipline as `:feature:timeline` /
    // `:feature:editor`'s backlinks package) — it takes an already-open
    // `IndexStore`/`VaultRepository` and never unlocks anything itself.
    implementation(project(":core:model"))
    // `LocalSkeinTokens` (glyphs, corner radius) and `SkeinTheme`
    // (all-monospace IBM Plex Mono `SkeinTypography`) for the graph legend
    // and screen chrome — same terminal/editor visual style `NoteTab` and
    // `BacklinksDrawer` already render with.
    implementation(project(":feature:shell"))

    debugImplementation(libs.compose.ui.tooling)
    // `lintDebug` resolves `src/debug/AndroidManifest.xml`'s
    // `androidx.activity.ComponentActivity` reference against the debug
    // variant's compile classpath, not the test classpath — same note as
    // `:feature:editor`/`:feature:timeline`. Without this a `MissingClass`
    // lint error fires even though only the androidTest classpath needs the
    // class at runtime.
    debugImplementation(libs.androidx.activity.compose)

    // `GraphStateTest`/`ForceLayoutTest`/`GraphHitTestTest`/
    // `GraphTransformTest` — pure JVM, no Robolectric/Compose UI test deps.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // `GraphStateTest` drives `GraphState` against the in-memory
    // `IndexStore`/`VaultRepository` fakes, hand-building `Edge` rows
    // directly — same shape as `core/rag`'s `GraphRecallTest` (plan's own
    // words: "Entity rows are inserted directly by the tests here").
    // `:testing` is pure JVM (docs/TESTING.md), so this pulls no Android
    // test infrastructure onto the JVM `test` classpath.
    testImplementation(project(":testing"))

    // On-device Compose UI test (skein-z2u acceptance: compile the UI test
    // even where the local worktree cannot run it; bd `skein-k3b2` tracks
    // the CI emulator gate).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(project(":testing"))
}
