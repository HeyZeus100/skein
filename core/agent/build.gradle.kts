plugins {
    alias(libs.plugins.kotlin.jvm)
    // Isolation guard — asserts this module never gains an Android Gradle
    // plugin. `:core:agent` must stay pure Kotlin/JVM so it can be unit-tested
    // on the JVM and consumed from every surface (editor, chat, future skill
    // dispatcher) without dragging in the Android SDK. See
    // docs/design/VAULT_TOOL_PRIMITIVES.md and skein-fvne.
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Data types the primitives reference (DocId, Tag, RevisionHash-shaped
    // strings, etc.) are declared here as pure Kotlin. When `:core:model`
    // starts holding those types they will move; today the interfaces here
    // reference nothing outside kotlin.stdlib.
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
