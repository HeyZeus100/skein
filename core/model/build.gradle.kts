plugins {
    alias(libs.plugins.kotlin.jvm)
    // E1.I2: isolation guard — asserts this module never gains an Android
    // Gradle plugin (it must stay pure Kotlin/JVM so it can be unit-tested
    // on the JVM and consumed by every process; spec §2.6, plan §2.4).
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `Flow<Token>` in the `InferenceEngine` contract (§4.1, `E0.I10`) is
    // `kotlinx.coroutines.flow.Flow`; the module must stay Kotlin/JVM-only,
    // so we pull in the core coroutines library (no Android or Java-8 shim).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
