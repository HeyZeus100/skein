plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // E1.I2: manifest and dependency guards (spec §2.1/§2.2/§2.6). Applied
    // after the Android plugin so the Variant API extension is available.
    id("app.skein.guard.manifest")
    id("app.skein.guard.dependency")
}

android {
    namespace = "app.skein"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.skein"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "FOSS", "true")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("dev") {
            dimension = "distribution"
            buildConfigField("boolean", "FOSS", "false")
            ndk {
                abiFilters += "arm64-v8a"
                abiFilters += "x86_64"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":inference-service"))
    implementation(project(":embedder-service"))
    implementation(project(":feature:shell"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
}
