plugins {
    alias(libs.plugins.kotlin.jvm)
    // Only used to give the AST a stable JSON form for golden-fixture tests
    // (see src/test/.../GoldenFixtureTest.kt) — not part of the public API.
    alias(libs.plugins.kotlin.serialization)
    // E7.I2: isolation guard — :core:markdown must stay pure Kotlin/JVM (no
    // Android SDK dependency) so it can be unit-tested on the JVM and shared
    // by :app, the editor, chat rendering, and the PDF/DOCX exporters
    // without pulling Android into those consumers' test classpaths.
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.jetbrains.markdown)
    implementation(libs.kotlinx.serialization.json)

    // AnnotatedString/TextStyle/SpanStyle (androidx.compose.ui:ui-text, a
    // Kotlin Multiplatform artifact) is resolved for the JVM/desktop target
    // here — no Android Gradle plugin or Android runtime is involved.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)

    testImplementation(libs.junit)
    // E2.I12 (bd skein-jq8): MarkdownFlattenerTest (moved here from
    // :core:export, which already had this dependency) asserts with Truth.
    testImplementation(libs.truth)
}
