plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.skein.core.export"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // E2.I11 (skein-80m): MarkdownPrintAdapterTest is an androidTest
        // that compiles against the real android.print.* / android.graphics.pdf.*
        // APIs (see docs/design/AMENDMENTS, skein-k3b2) — compiled by the
        // ordinary `check` path, run for real once an emulator is available.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `:core:vault` (SafeFileName reuse) carries the skein-e2ki "distribution"
        // foss/dev flavor dimension for its native .so ABI filters. This module
        // has no flavors of its own and doesn't care which native build it
        // transitively links against — same resolution `:feature:editor` and
        // `:feature:shell` already use.
        missingDimensionStrategy("distribution", "foss")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // E7.I2: the Skein Markdown AST (SkeinDocument/BlockNode/InlineNode) is
    // the render tree PdfExportService/MarkdownFlattener drive the print
    // layout from. Pure Kotlin/JVM — pulls no Android dependency onto the
    // layout package's classpath.
    implementation(project(":core:markdown"))
    implementation(project(":core:model"))

    // SafeFileName (E2.I10, skein-90d) is reused for the staged PDF's file
    // name instead of re-implementing sanitization here.
    implementation(project(":core:vault"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(project(":testing"))
}
