plugins {
    alias(libs.plugins.android.library)
}

// E1.I5: the native SQLite build (SQLCipher + sqlite-vec + FTS5, statically
// linked into one shared object). AGP consumes `native/sqlite/CMakeLists.txt`
// via externalNativeBuild and packages `libskein_sqlite.so` under the
// module's AAR jniLibs, from where the app pulls it into `lib/<abi>/` in the
// final APK. The vendored androidx.sqlite JNI wrapper that surfaces this .so
// to Kotlin as `SkeinSQLiteDriver` (E2.I1) is filed separately as skein-e2ki.

android {
    namespace = "app.skein.core.vault"
    compileSdk = 37

    // Pin the NDK to match the E0.I7 spike (r27c). The reproducibility contract
    // in E1.I8 hashes the .so; a floating NDK version would break that.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 30
        // skein-e2ki: SkeinSQLiteDriverInstrumentedTest exercises the real
        // libskein_sqlite_jni.so + libskein_sqlite.so on-device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // -DANDROID_STL is a no-op for our C-only build but AGP wires
                // it anyway; explicit `none` keeps the .so free of libc++_shared.
                arguments += listOf("-DANDROID_STL=none")
                cFlags += "-fPIC"
                // skein-e2ki: build both the payload .so and the JNI shim.
                targets += listOf("skein_sqlite", "skein_sqlite_jni")
            }
        }
    }

    // Match :app's flavor set so `packageFossDebug` picks up the vault
    // module's flavored jniLibs directly. `foss` ships arm64-v8a only
    // (production install), `dev` also carries x86_64 for the emulator.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("dev") {
            dimension = "distribution"
            ndk {
                abiFilters += "arm64-v8a"
                abiFilters += "x86_64"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("$rootDir/native/sqlite/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // E2.I2 (skein-5my): MigratorInstrumentedTest exercises the
    // deliberately-broken `999_bad.sql` fixture that lives under
    // `src/test/resources/migrations-bad/` (shared with the JVM-only
    // MigratorStatementSplitterTest, which needs no `.so`). Android's
    // `test` and `androidTest` source sets don't share resources by
    // default, so the fixture directory is added to `androidTest` here
    // rather than duplicated under `src/androidTest/resources/`.
    sourceSets {
        getByName("androidTest") {
            resources.srcDirs("src/test/resources")
        }
    }

    // skein-3el: JVM unit tests instantiate `KeyPermanentlyInvalidatedException`
    // and `StrongBoxUnavailableException` from `android.security.keystore.*` to
    // drive the fake Keystore's invalidation path. `returnDefaultValues = true`
    // makes AGP's mockable android.jar stubs return sensible defaults instead
    // of throwing `RuntimeException("Stub!")` from super-constructor chains, so
    // those exception subclasses can be constructed and caught in tests
    // (`VaultKeyProviderImplTest`). The setting is scoped to unit tests only —
    // it has no effect on instrumented tests or on production `.aar` builds.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // AGP already strips .so symbols in release. Debug builds keep them for
    // easier crash triage; the OpenSSL static archive is stripped separately
    // by the CMake build's -fno-ident + --build-id=none flags.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // skein-e2ki: SkeinSQLiteDriver implements androidx.sqlite.SQLiteDriver
    // and returns androidx.sqlite.SQLiteConnection / SQLiteStatement, so
    // downstream modules (Room / Skein's own vault repositories) can consume
    // them without seeing any Skein-specific interface. `sqlite-framework`
    // and `sqlite-bundled` are NOT wanted — we bring our own JNI shim
    // (libskein_sqlite_jni.so) that binds through libskein_sqlite.so.
    api(libs.androidx.sqlite)

    // skein-3el: VaultKeyProvider surfaces AuthorizationToken from :core:model
    // and consumes androidx.biometric for the FragmentActivity + BiometricPrompt
    // authentication flow (`AndroidBiometricAuthenticator`). BiometricPrompt
    // uses only `androidx.core`/`androidx.fragment` transitives that are already
    // depended on across the project — no Play Services / ML Kit is pulled in
    // (verified against `DependencyGuardTask.BANNED_GROUPS`). The `USE_BIOMETRIC`
    // uses-permission that androidx.biometric merges in is NOT on
    // `ManifestGuardTask.BANNED_PERMISSIONS` (only INTERNET / ACCESS_NETWORK_STATE
    // are).
    api(project(":core:model"))
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // skein-yrp (E2.I13): VaultLifecycleTest uses `TempDirRule` for a real,
    // per-test filesystem directory (the file-exists checks in `create` /
    // `open` are real `java.io.File` checks, not simulated by the fake
    // native bridge).
    testImplementation(project(":testing"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    // skein-qi6 / E2.I15: the `IndexStoreContractTest` shared suite lives in
    // `:testing` (`us.aherrera.skein.testing.IndexStoreContractTest`) so both
    // `InMemoryIndexStore` (JVM) and `IndexStoreImpl` (this module,
    // instrumented) prove they satisfy the same semantic contract.
    androidTestImplementation(project(":testing"))
}

// E0.I7 followup (skein-2lq9): fast, standalone entry point for the same
// SHA256SUMS.txt check that native/sqlite/CMakeLists.txt §0 already runs on
// every externalNativeBuild configure. Useful for CI/dev to check amalgamation
// integrity without doing a full native configure+compile. The slow
// regenerate-from-source check (native/sqlite/verify_amalgamation.sh
// --regenerate) only runs in the release-tag CI job -- see
// docs/design/AMALGAMATION_POLICY.md.
tasks.register<Exec>("verifyAmalgamationHashes") {
    group = "verification"
    description = "Verify native/sqlite amalgamation files match amalgamation/SHA256SUMS.txt"
    workingDir = rootDir
    commandLine("native/sqlite/verify_amalgamation.sh", "--verify")
}
