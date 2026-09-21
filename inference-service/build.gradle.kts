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

        externalNativeBuild {
            cmake {
                // llama.cpp and ggml are C++; the static libc++ keeps
                // libskein_llama.so self-contained so the isolated inference
                // process needs no libc++_shared.so alongside it.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DSKEIN_LLAMA_DEFAULT_BACKEND=$skeinLlamaDefaultBackend",
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
    // No androidx-core-ktx: it isn't used by this stub, and this module's
    // isolation allowlist (E1.I2) only permits :core:ipc, :core:model, and
    // the Kotlin stdlib/coroutines — androidx.* is out of scope for the
    // isolated inference process.
    testImplementation(libs.junit)
}
