plugins {
    alias(libs.plugins.kotlin.jvm)
    // E4.I3 (bd skein-nxk, coordinator decision skein-hiwb): isolation guard —
    // asserts this module never gains an Android Gradle plugin. That is the
    // whole reason the module exists: `:inference-service` / `:embedder-service`
    // run `isolatedProcess=true` and their allowlist (spec §2.6, plan §2.4)
    // admits only pure-Kotlin/JVM project dependencies, so the model verifier
    // they must run could not stay in `:core:inference` (an Android library).
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `SkeinLog` for the refusal log lines (the NoRawLogging guard requires it
    // instead of android.util.Log, which this module could not use anyway) and
    // `Blake3`/`Hex` for the post-mmap gate. `:core:model` is pure Kotlin/JVM
    // and is already on both services' isolation allowlists.
    api(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
