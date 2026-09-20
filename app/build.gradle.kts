plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // E1.I2: manifest and dependency guards (spec §2.1/§2.2/§2.6). Applied
    // after the Android plugin so the Variant API extension is available.
    id("app.skein.guard.manifest")
    id("app.skein.guard.dependency")
    // E1.I7: license audit for foss flavor (spec §10).
    id("app.skein.guard.license")
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

    testOptions {
        unitTests {
            // E3.I1: ManifestPolicyTest reads the manifest's resource
            // references (dataExtractionRules) via Robolectric's shadowed
            // PackageManager, which needs merged resources on the classpath.
            isIncludeAndroidResources = true
        }
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
    // E1.I5: pulls libskein_sqlite.so (SQLCipher + sqlite-vec + FTS5) into the
    // APK's `lib/<abi>/`. The vault module's flavors mirror :app's foss/dev
    // dimension so the .so's ABIs line up with the app's abiFilters.
    implementation(project(":core:vault"))
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
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // E10.I1: Robolectric-backed Compose UI test sample (MainActivityComposeTest).
    // Launches `MainActivity` directly (already declared+exported in the
    // manifest) rather than depending on `ui-test-manifest`'s generic
    // `ComponentActivity` registration, which manifest-merges into an
    // *application* module's own manifest but not into a *library*
    // module's (verified: `:feature:shell` does not get it).
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
}
