plugins {
    alias(libs.plugins.android.library)
    // E1.I2: isolation guard — this module may depend only on :core:ipc,
    // :core:model, and Kotlin stdlib/coroutines (spec §2.6, plan §2.4).
    id("app.skein.guard.isolation")
}

// E1.I4 (bd skein-ca2): the backend libskein_llama.so *requests* by default.
// The M0 Fold smoke proved Vulkan on Mali-G715 (23.47 pp / 5.61 tg on Qwen 2.5
// 3B Q3_K_M — docs/Handoffs/skein-fold-m0-hardware-handoff.md §9), but the
// binding choice is a docs/MEASUREMENTS.md decision (`inference_backend`,
// bd skein-5hr). Flipping it is this one line: it feeds both the native build
// (`-DSKEIN_LLAMA_DEFAULT_BACKEND`) and Kotlin (`BuildConfig`), so the two
// cannot drift. x86_64 has no Vulkan backend compiled in and always reports
// "cpu" from `skein_llama_default_backend()` regardless of this value.
val skeinLlamaDefaultBackend = "vulkan"

// E1.I8 followup (bd skein-ylux): `native/llama/CMakeLists.txt` §5a patches a
// build-tree copy of llama.cpp's Vulkan shader sources to work around an NDK
// glslc miscompile that makes libskein_llama.so nondeterministic. It is ON by
// default and there is no reason to turn it off for a real build — the switch
// exists so `tools/rb/so-determinism.sh --negative-control` can reintroduce
// the defect and prove the determinism check still catches it. Plumbed here
// because CMake options reach the native build only through AGP's
// `externalNativeBuild.cmake.arguments`.
val skeinLlamaShaderPatches =
    (providers.gradleProperty("skein.llama.shaderPatches").orNull ?: "true").toBoolean()

android {
    namespace = "app.skein.inference.service"
    compileSdk = 37

    // Pin the NDK to the same r27c the E0.I7 spike and :core:vault use. The
    // reproducibility contract in E1.I8 hashes the .so, and the Vulkan shader
    // set is decided by *this* NDK's bundled glslc (shaderc v2022.3), so a
    // floating NDK version would change both the bytes and the compiled
    // shader families.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 30

        // E4.I1 (bd skein-3aw): LlamaNativeTest. The run itself is the device
        // lane's (skein-80p emulator / skein-k3b2 Fold); CI compiles it on
        // every push, which is what keeps the `external fun` surface and the
        // `Java_…` symbols in libskein_llama.so from drifting apart.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // llama.cpp and ggml are C++; the static libc++ keeps
                // libskein_llama.so self-contained so the isolated inference
                // process needs no libc++_shared.so alongside it.
                // ktlint (multiline-expression-wrapping): a multiline
                // right-hand side starts on its own line.
                arguments +=
                    listOf(
                        "-DANDROID_STL=c++_static",
                        "-DSKEIN_LLAMA_DEFAULT_BACKEND=$skeinLlamaDefaultBackend",
                        "-DSKEIN_LLAMA_SHADER_PATCHES=" +
                            if (skeinLlamaShaderPatches) "ON" else "OFF",
                    )
                targets += "skein_llama"
            }
        }

        buildConfigField(
            "String",
            "LLAMA_DEFAULT_BACKEND",
            "\"$skeinLlamaDefaultBackend\"",
        )
    }

    // Mirror :app's flavor set so the .so's ABIs line up with the app's
    // abiFilters. `foss` ships arm64-v8a only (production install, CPU +
    // Vulkan); `dev` adds x86_64 for the emulator, which is CPU-only —
    // Android emulators expose at best a software Vulkan ICD and no
    // measurement backs running inference on one.
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
            path = file("$rootDir/native/llama/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

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
    // No androidx-core-ktx: it isn't used by this module, and this module's
    // isolation allowlist (E1.I2) only permits :core:ipc, :core:model, and
    // the Kotlin stdlib/coroutines — androidx.* is out of scope for the
    // isolated inference process.

    // E4.I1 (bd skein-3aw): LlamaNative.onNativeLog — the llama_log_set sink's
    // Kotlin half — forwards through LlamaLogRedactor into SkeinLog, which the
    // NoRawLogging guard (E1.I11) requires instead of android.util.Log.
    // :core:model is on the isolation allowlist (build-logic/guards).
    implementation(project(":core:model"))

    testImplementation(libs.junit)

    // Instrumented-only; the isolation guard scans implementation/api/
    // compileOnly/runtimeOnly, not the test configurations, so the androidx.test
    // runner does not enter the isolated process's production classpath.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

// skein-hwtn: see core/vault/build.gradle.kts for the full rationale. AGP
// deliberately leaves `.cxx/` (this module's CMake/ninja state for
// native/llama/CMakeLists.txt) out of `clean` for build-speed reasons, but
// that means ninja never re-detects an environment-only change such as
// `SOURCE_DATE_EPOCH` and a "clean rebuild" ships a stale libskein_llama.so.
tasks.named("clean", Delete::class) {
    delete(layout.projectDirectory.dir(".cxx"))
}
