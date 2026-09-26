plugins {
    alias(libs.plugins.kotlin.jvm)
    // Pure Kotlin/JVM, like `:testing` (checkIsolationGuards).
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

// skein-xtov.23.18 (UT-4, docs/ux/UX_TEST_PLAN.md §6.1): the JUnit-free half
// of the test doubles — the fakes, the fixture corpus (`app.skein.testing.corpus`)
// and the scenario engine (`app.skein.testing.scenario`) — in the same
// `app.skein.testing` packages they always had. `:testing` re-exports this
// module with `api`, so `testImplementation(project(":testing"))` consumers
// see no change.
//
// The point of the split is this module's runtime closure: Kotlin stdlib,
// kotlinx-coroutines-core and `:core:model` (+ its kotlinx-serialization-json),
// all already on `:app`'s classpath and licence-clean. So a preview or lab
// harness in a feature's `src/debug` can take `debugImplementation(project(":testing-fakes"))`
// without dragging JUnit (EPL-1.0) into a debug APK. Never add JUnit,
// kotlinx-coroutines-test or anything else test-only to `main` here;
// `checkNoTestDoublesInMain` (build-logic) keeps it out of every `src/main`.
dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
