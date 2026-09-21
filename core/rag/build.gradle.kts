plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.skein.core.rag"
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
    implementation(libs.androidx.core.ktx)

    // E5.I11 (skein-7tw): `GraphRecall` is pure Kotlin over `IndexStore` /
    // `VaultRepository` (`:core:model`) — no SQL, no Android framework
    // dependency of its own.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory `IndexStore`/`VaultRepository` fakes for JVM unit tests —
    // same fakes `:core:vault` uses for its own unit tests.
    testImplementation(project(":testing"))
}
