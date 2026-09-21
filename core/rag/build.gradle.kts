plugins {
    alias(libs.plugins.android.library)
    // E5.I2 (skein-bpt): `app.skein.core.rag.tokenizers` parses Hugging Face
    // `tokenizer.json` files with kotlinx.serialization (already pinned in the
    // version catalog and already on this module's runtime classpath via
    // `:core:model`'s `api` dependency — no new library is introduced).
    alias(libs.plugins.kotlin.serialization)
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
    // skein-bpt: `:core:model` already exposes this as `api`; naming it here
    // keeps the tokenizers package's direct use of kotlinx.serialization
    // explicit rather than relying on a transitive.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory `IndexStore`/`VaultRepository` fakes for JVM unit tests —
    // same fakes `:core:vault` uses for its own unit tests.
    testImplementation(project(":testing"))
}
