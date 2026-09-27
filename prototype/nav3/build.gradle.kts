// skein-xtov.24.4 (AL-05): THROWAWAY Navigation 3 prototype for the D8 gate
// (docs/ux/ADAPTIVE_LAYOUT_SPEC.md §8.9). Nothing depends on this module and
// `:app` never sees it; delete `prototype/` and its `include` line in
// settings.gradle.kts in one step once AL-08 has ported what it needs.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.skein.prototype.nav3"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // Same reason as every leaf feature module: `:feature:shell` pulls in
        // `:core:vault`'s "distribution" flavor dimension.
        missingDimensionStrategy("distribution", "foss")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Merges src/debug/AndroidManifest.xml's host activities into the
            // Robolectric test classpath (bd memory `robolectric-sdk37-needs-java21`).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.material3.adaptive)
    implementation(libs.material3.adaptive.layout)
    implementation(libs.material3.adaptive.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.core)

    // The hosted screens, exactly as they ship today (no changes to them).
    implementation(project(":core:model"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:graph"))

    // Only for the evidence test that the stock T3 decorator defers onCleared (Nav3GateLockTest).
    testImplementation(libs.androidx.lifecycle.viewmodel.navigation3)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing"))
}
