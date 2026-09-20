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
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
