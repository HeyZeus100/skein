plugins {
    alias(libs.plugins.android.library)
    // E1.I2: isolation guard — this module may depend only on :core:ipc,
    // :core:model, and Kotlin stdlib/coroutines (spec §2.6, plan §2.4).
    id("app.skein.guard.isolation")
}

android {
    namespace = "app.skein.inference.service"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
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
