plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.skein.core.security"
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

    // E3.I10 (skein-xhi): `PromptGuard.wrapRetrieved` fences `Retrieved`
    // items (`:core:model`'s `Retrieval.kt`, locked by E0.I12). `api` because
    // the guard's public signature speaks that type, so every consumer
    // (`E5.I15`'s assembler, `E5.I16`'s citation parser) needs it too.
    api(project(":core:model"))

    testImplementation(libs.junit)
    // E3.I10 AC5: the locked `PromptAssemblerContractTest` runs here against a
    // reference assembler with the guard applied, proving the guard's framing
    // does not break the E0.I12 contract before `E5.I15` lands.
    testImplementation(project(":testing"))
    // E3.I10 AC3: `neutralizeActionable` is asserted at the AST level ("a code
    // span, not a link node") with `:core:markdown`'s parser — the same parser
    // the chat surface renders model output with. Test-only.
    testImplementation(project(":core:markdown"))
}
