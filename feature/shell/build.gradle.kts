plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.skein.feature.shell"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    // E6.I2: fold posture (WindowInfoTracker/FoldingFeature) + WindowSizeClass
    // breakpoints (currentWindowAdaptiveInfo) for AdaptivePaneHost.
    implementation(libs.androidx.window)
    implementation(libs.material3.adaptive)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
