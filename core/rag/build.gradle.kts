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
        // E5.I10 (skein-7v3): `IngestPipelineTest` composes the real
        // `EdgeUpserter`/`DanglingResolver` from `:core:vault` (test-only
        // dependency below), which carries the foss/dev `distribution`
        // dimension this module does not — same strategy as `:feature:*`.
        missingDimensionStrategy("distribution", "foss")
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
    // E5.I15 (skein-82g): `PromptAssemblerImpl` composes `PromptGuard.wrapRetrieved`
    // (`:core:security`, E3.I10) around the §7.3 retrieved-context segment. `implementation`
    // because `PromptAssemblerImpl`'s own public surface (`PromptAssembler`) never exposes a
    // `:core:security` type — only `:core:model` ones.
    implementation(project(":core:security"))
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
    // E5.I10 (skein-7v3): `IngestPipeline` keeps its link/entity steps behind
    // small interfaces so this module's *main* classpath never sees
    // `:core:vault`; the pipeline test still wires the real
    // `EdgeUpserter`/`DanglingResolver` (E5.I8/E5.I8b) through them.
    testImplementation(project(":core:vault"))
}
