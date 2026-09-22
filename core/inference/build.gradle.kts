plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.skein.core.inference"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        // skein-7p0 (E4.I9): ThermalGovernorInstrumentedTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // skein-7p0 (E4.I9): ThermalGovernorCore's `state: StateFlow<ThermalState>`
    // and ThermalGovernor's poll loop (kotlinx.coroutines.launch/delay on
    // Dispatchers.Default, which lives in -core, not -android).
    implementation(libs.kotlinx.coroutines.core)

    // skein-st1r (POST_REVIEW_RESOLUTIONS §2): `app.skein.core.inference.models`
    // logs refusals through `SkeinLog` (:core:model), which the NoRawLogging
    // guard requires instead of android.util.Log.
    api(project(":core:model"))
    // skein-nxk (E4.I3, coordinator decision skein-hiwb): `ModelVerifier`,
    // `ModelVerification`, `ModelFileRole` and the pinned-descriptor types moved
    // to the pure-JVM `:core:verify` so `:inference-service` (isolatedProcess,
    // allowlisted for pure-JVM project deps only) can run the same verifier this
    // module's store-side loader does. `api`, not `implementation`: those types
    // appear in this module's own public signatures (`ManifestBinding.bind`
    // returns a `ModelVerification.Refusal`, `BoundFile.role` is a
    // `ModelFileRole`), so consumers must see them.
    api(project(":core:verify"))
    // skein-28wm (POST_REVIEW_RESOLUTIONS §2.3 / §3.3): `WireBindings.toWire`
    // returns `us.aherrera.skein.ipc.ManifestBinding` (and takes/produces its
    // `ManifestFileRef` / `AttestationRefParcel`), so that type is part of this
    // module's public API surface and the edge must be `api`, not
    // `implementation` — the same reasoning `:core:ipc` gives for its own
    // `api(project(":core:model"))` edge. `:core:inference` is an app-side
    // module (unlike `:inference-service` / `:embedder-service`): neither
    // `IsolationGuardPlugin`'s `PURE_JVM_MODULES` nor its
    // `SERVICE_ALLOWLISTS` name `:core:inference`, so no guard restricts what
    // it may depend on, and this edge does not touch either isolated
    // service's own declared dependencies (verified by running both
    // services' `checkIsolationGuards` after adding this line).
    api(project(":core:ipc"))
    // skein-st1r: `ModelManifest.parse` reads the `*.skein.json` manifest with
    // kotlinx.serialization's tree API (`parseToJsonElement`) — no compiler
    // plugin, no new library (`:core:model` already exposes this as `api`;
    // naming it here keeps the direct use explicit, as :core:rag does).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // skein-28wm: `WireBindingsTest` opens real `ParcelFileDescriptor`s and
    // Parcel-round-trips the produced wire `ManifestBinding`, the same reason
    // `:core:ipc`'s `ParcelRoundTripTest` needs it (AGP's mockable
    // `android.jar` only throws `Stub!` for `android.os` types).
    testImplementation(libs.robolectric)
    // E10.I3 (skein-gzr): `LlamaCppEngineTest`'s `@Ignore`d placeholder
    // subclasses `InferenceEngineContractTest` from `:testing`, the same
    // way every other contract-suite consumer does.
    testImplementation(project(":testing"))

    // skein-7p0 (E4.I9): ThermalGovernorInstrumentedTest binds ThermalGovernor
    // to a real PowerManager (compiled unconditionally; on-device run is
    // gated on skein-k3b2 — same pattern as
    // core/vault's VaultKeyProviderInstrumentedTest).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
