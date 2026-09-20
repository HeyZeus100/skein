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
        externalNativeBuild {
            cmake {
                // -DANDROID_STL is a no-op for our C-only build but AGP wires
                // it anyway; explicit `none` keeps the .so free of libc++_shared.
                arguments += listOf("-DANDROID_STL=none")
                cFlags += "-fPIC"
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
    testImplementation(libs.junit)
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
