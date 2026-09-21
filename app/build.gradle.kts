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
        // skein-2ige: VaultBootstrapInstrumentedTest (compile-only until the
        // emulator lane in bd skein-k3b2 runs it) needs the AndroidX runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // E1.I11 (skein-4je): minification is turned on *only* so R8's
            // `-assumenosideeffects` (app/proguard-rules.pro) can strip
            // `SkeinLog.d`/`SkeinLog.i` call sites from the release dex —
            // see that file's header comment for the narrow scope (no
            // obfuscation, no real shrinking yet) and why full R8 mode is
            // left to E1.I8.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // E6.I14: `:app` is the only module allowed to depend on both
    // `:feature:shell` (for `SkeinApp`) and `:feature:settings` (for
    // `SettingsScreen`) — see `SkeinApp.destinationContent`'s doc for why
    // `:feature:shell` itself cannot.
    implementation(project(":feature:settings"))
    // skein-u01 (E6.I9): `:app` wires the real `NoteTab` into `SkeinApp`'s
    // `noteTabContent` slot for the same dependency-direction reason as
    // `:feature:settings` above — `:feature:editor` depends on
    // `:feature:shell`, so `:feature:shell` cannot depend back on it.
    implementation(project(":feature:editor"))
    // skein-2ige: `MainActivity` feeds `TimelineScreen`/`TimelineRail` the
    // live `VaultRepositoryImpl` once the vault is open, both directly
    // (`Destination.TIMELINE`) and via `SkeinApp`'s `timelinePane` slot
    // (skein-64y9). `:feature:timeline` used to declare
    // `debugImplementation(project(":testing"))` for its design-time
    // previews, which dragged `:testing` and its `api` deps (JUnit 4 —
    // EPL-1.0, off the foss allowlist — and kotlinx-coroutines-test) into
    // the fossDebug APK and failed `licenseAuditFossDebugRuntimeClasspath`;
    // it now declares `debugCompileOnly` instead (skein-64y9), so this
    // exclude is no longer needed. This app's unit tests get `:testing`
    // through their own `testImplementation` edge below.
    implementation(project(":feature:timeline"))
    // skein-z2u (E6.I11): `MainActivity` mounts `GraphScreen` as the ✦
    // button's overlay target. `:feature:graph` only reaches `:testing` via
    // `testImplementation`/`androidTestImplementation` (no `debugImplementation`
    // edge for design-time previews), so it never reaches this module's
    // runtime/debug classpath and needs no `exclude` either.
    implementation(project(":feature:graph"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // skein-2ige: `MainActivity` must be a `FragmentActivity` — it hosts the
    // `BiometricPrompt` that `BiometricUnlockScreen` / `UnlockManager.unlock`
    // present. `androidx.biometric` exposes `androidx.fragment` as an API
    // dependency at the version the rest of the app already resolves, so this
    // adds no new artifact to the runtime classpath (license audit unchanged).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // E3.I14 (skein-up0): ProcessLifecycleOwner, for LockPolicyObserver's
    // "lock when app leaves foreground" trigger.
    implementation(libs.androidx.lifecycle.process)
    // E3.I8: SecurityPrefs (FLAG_SECURE toggle) persistence.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
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
    // skein-2ige: VaultBootstrapTest / TestSkeinApplication run the bring-up
    // over the JVM fakes (`InMemoryVaultRepository`, `FakeExportService`, …).
    testImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.tooling)
}
