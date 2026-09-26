plugins {
    alias(libs.plugins.android.library)
}

// skein-xtov.23.15 (UT-1) / skein-xtov.23.14 (UT-0), docs/ux/UX_TEST_PLAN.md §5:
// the shared screenshot/UI-test helper module, replacing the seven identical
// copies of `UxScreenshots.kt` (docs/ux/research/ROBORAZZI_SPIKE.md). An
// Android library so it can see Robolectric / Compose UI test / Roborazzi
// (all Android-only test APIs — `:testing` stays pure JVM by
// `app.skein.guard.isolation`'s guard, see that module's own comment), but it
// depends on no feature module and ships no resources: `testImplementation`
// only, never a production dependency. Flat directory name (`testing-ui/`,
// not nested under `testing/`) — `:testing` is itself a Gradle project and
// nesting one inside its directory confuses source-set globbing.
android {
    namespace = "app.skein.testing.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Exposed as `api`: every consumer's screenshot test compiles
    // `UxDeviceRule` (extends `ExternalResource`), `skeinComposeRule()`
    // (returns `AndroidComposeTestRule<ActivityScenarioRule<RoborazziActivity>, RoborazziActivity>`)
    // and `captureUx()` (extends `SemanticsNodeInteraction`) against these
    // types without redeclaring the dependency itself.
    api(libs.junit)
    api(libs.compose.ui.test.junit4)
    api(libs.androidx.test.ext.junit)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)

    // `RuntimeEnvironment.setQualifiers`/`setFontScale` are called only
    // inside `UxDeviceRule.before()`'s body, never in a public signature, so
    // `implementation` is enough — Gradle still puts it on every consumer's
    // test *runtime* classpath (the variant-aware `runtimeElements` include
    // `implementation` dependencies), which is all a JUnit rule needs.
    implementation(libs.robolectric)

    // Compose UI test's artifacts have no `version.ref` in the catalog: they
    // take their version from the BOM platform constraint. Every consumer
    // module already applies its own `platform(libs.compose.bom)`, but this
    // module must pin its own compilation too.
    implementation(platform(libs.compose.bom))

    // Pins the transitive `androidx.activity`/`androidx.savedstate` versions
    // `compose.ui.test.junit4` otherwise resolves independently to an old,
    // unverified pair (1.2.1 / 1.1.0) — the same explicit-declaration fix
    // `:feature:shell`/`:feature:models` already apply for the same reason.
    implementation(libs.androidx.activity.compose)
    // Same reason: pins `androidx.core`/`androidx.annotation:annotation-experimental`
    // to the version every other module already verifies, instead of the
    // older transitive pair `androidx.test`/`espresso` otherwise resolve to.
    implementation(libs.androidx.core.ktx)
    // Same reason: pins the transitive `kotlinx-coroutines-test` version
    // Robolectric/compose-ui-test otherwise resolve independently to 1.9.0 —
    // whose jar is unverified in this project — to the 1.11.0 every other
    // module already uses and has fully verified (`:feature:models` applies
    // the identical fix, as a `testImplementation`; this module's own main
    // variant needed it too, for `compileDebugKotlin`'s tool classpath).
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
