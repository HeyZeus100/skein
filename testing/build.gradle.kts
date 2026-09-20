plugins {
    alias(libs.plugins.kotlin.jvm)
    // E10.I1: `:testing` stays pure Kotlin/JVM (no Android Gradle plugin) so
    // `:core:*` (and any other pure-JVM module) can depend on it without
    // pulling the Android SDK onto their classpath, and so its JUnit4 rules
    // (MainDispatcherRule, TempDirRule, FakeClock, SkeinLogCapture) and fakes
    // run in plain `test`/`check` — no Robolectric, no device. Modules that
    // need Android-flavored test doubles (Robolectric, Compose UI test,
    // androidx.test, WorkManager testing) declare those directly; see
    // docs/TESTING.md.
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `:core:model` carries the shared, pure-Kotlin domain types the plan's
    // §4 interface contracts (InferenceEngine, VaultRepository, IndexStore,
    // RetrievalService, PersonaService, EmbedderService) are built from as
    // those land (E0.I10-I13, E0.I17). `:testing`'s scaffold fakes in
    // `app.skein.testing.fakes` are self-contained until then — see the KDoc
    // on each fake.
    implementation(project(":core:model"))

    // Exposed as `api`: every consumer of `:testing` (testImplementation)
    // gets JUnit4 and coroutines-test transitively, so `MainDispatcherRule`
    // (a JUnit `TestRule` built on `TestDispatcher`) compiles for them
    // without a second explicit declaration.
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}
