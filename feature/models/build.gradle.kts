plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.models"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
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
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    // Pins the transitive `androidx.activity`/`androidx.savedstate` versions
    // `compose.ui.test.junit4` below otherwise resolves independently to an
    // old, unverified pair (1.2.1 / 1.1.0) — the same explicit-declaration
    // fix `:feature:shell`'s build file already applies for the same reason
    // (see that file's own comment).
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
    // Pins the transitive `kotlinx-coroutines-test` version Robolectric/
    // compose-ui-test otherwise resolve independently to 1.9.0 — whose jar
    // (only its module metadata) is unverified in this project — to the
    // same 1.11.0 every other module already uses and has fully verified.
    testImplementation(libs.kotlinx.coroutines.test)
}
