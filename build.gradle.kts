// Root build file. Declares plugin versions (applied `false` here, applied per-module)
// so every subproject resolves the same versions from the catalog in gradle/libs.versions.toml.

plugins {
    // AGP 9 provides built-in Kotlin support for Android modules; the
    // `org.jetbrains.kotlin.android` plugin is no longer applied (see
    // https://kotl.in/gradle/agp-built-in-kotlin). Pure-JVM modules (e.g.
    // :core:model) still need the Kotlin/JVM plugin below.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    // E1.I11 (skein-4je): registered here (like ktlint above) so the
    // `apply(plugin = "app.skein.guard.logging")` call in `subprojects`
    // below can resolve it — an included-build plugin ID is only
    // discoverable through Gradle's plugin resolution when it appears in a
    // declarative `plugins {}` block at least once; the imperative
    // `apply(plugin = ...)` form alone cannot look it up in `build-logic`.
    id("app.skein.guard.logging") apply false
    // E10.I3 (skein-gzr): same reason as `app.skein.guard.logging` above —
    // declared here so the imperative `apply(plugin = ...)` below can
    // resolve this included-build plugin id.
    id("app.skein.contractreport") apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // E1.I11 (skein-4je): NoRawLogging — every module's `check` fails on a
    // raw `android.util.Log`/`println` call outside `SkeinLog.kt` (spec §9).
    apply(plugin = "app.skein.guard.logging")
}

// The root project has no source of its own; `lifecycle-base` gives it a
// `check` task so `./gradlew check` also runs build-logic's own guard unit
// tests, in addition to every module's checkManifestGuards /
// checkDependencyGuards / checkIsolationGuards tasks (wired into their own
// `check` individually).
apply(plugin = "lifecycle-base")

// E10.I3 (skein-gzr): registers the root-level `contractReport` task (see
// `ContractReportPlugin`'s KDoc). Deliberately NOT wired into `check` or
// any CI workflow — that is a follow-up bead once `skein-ddp` lands.
apply(plugin = "app.skein.contractreport")

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":guards:test"))
}

tasks.named("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
