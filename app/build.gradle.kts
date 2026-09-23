import java.io.IOException
import java.net.URI
import java.security.MessageDigest

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

            // skein-8jtj: AGP otherwise packages
            // `META-INF/version-control-info.textproto` describing the git
            // checkout the build ran in. That entry was the *only* thing
            // that differed between a release APK built in this repo's git
            // worktree and one built from a clone of the same commit at a
            // different path: a worktree yields
            // `generate_error_reason: NO_VALID_GIT_FOUND` (42 bytes) while a
            // normal clone embeds `revision: "<sha>"` (~120 bytes). That
            // makes the APK's sha256 depend on *how the tree was obtained*
            // rather than on its contents, which defeats the whole point of
            // the reproducible-build check and of E1.I8 (skein-ddp)'s
            // cross-machine hash comparison. It also changes on every commit
            // and leaks repo metadata into a FOSS build.
            vcsInfo {
                include = false
            }
        }
    }

    // skein-8jtj / E1.I8 (skein-ddp): never embed Play's dependency-metadata
    // blob. AGP writes it into the APK Signing Block, compressed and
    // encrypted to a Google public key, so it is nondeterministic by
    // construction — two signings of identical inputs need not produce
    // identical bytes. It is absent today only because the `release` build
    // type has no signing config yet (an unsigned APK has no Signing Block
    // at all); the moment E1.I8's signed pipeline lands it would silently
    // break the reproducible-build check. Disabling it now is also simply
    // correct for a FOSS/F-Droid build, which ships no Play metadata.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

// skein-lds9: Gradle's console prints only the exception class and line for a
// failed test, which hid the diagnostics MainActivityComposeTest attaches to a
// timeout. Print the full message and cause chain so a CI failure explains
// itself without the JUnit XML (which ci.yml also uploads on failure).
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
    // skein-whg8: `MainActivity` wires the real `ChatScreen`/`SendPipeline`
    // (feature/chat) into `SkeinApp`'s new `chatTabContent` slot, and the
    // minimal `/models` list screen (feature/models) into its `overlay`
    // slot — the same dependency-direction reason as `:feature:editor` and
    // `:feature:settings` above (`:feature:shell` cannot depend on either).
    implementation(project(":feature:chat"))
    implementation(project(":feature:models"))
    // E5.I10 (skein-7v3): `IngestScheduler`/`IngestWorker` compose the
    // on-device ingest pass — `Chunker` + `IngestPipeline`/`IngestSteps`
    // (`:core:rag`), `EdgeUpserter`/`DanglingResolver` (`:core:vault`, above)
    // and `ThermalGovernor` (`:core:inference`) for batch pacing.
    implementation(project(":core:rag"))
    implementation(project(":core:inference"))
    // skein-0m1z (POST_REVIEW_RESOLUTIONS.md §4.3): `ExportStageCoordinator`
    // implements `:core:export`'s `ExportStageRecorder` port — the seam that
    // turns a spooled PDF into an `export_stages` row plus a
    // `StagedPlaintextSweeper` request — and `BootReceiver`/`VaultServices`
    // read `PdfStaging.STAGING_DIR_NAME` rather than re-declaring it. Adds no
    // external artifact: `:core:export`'s own dependencies (`:core:markdown`,
    // `:core:model`, `:core:vault`, core-ktx, coroutines) are all already on
    // this module's runtime classpath.
    implementation(project(":core:export"))
    // E5.I10: WorkManager runs the pass as unique one-time work (no
    // constraints gate — `docs/design/LOCK_POLICY_INDEXING.md` §3.2/§7.1).
    // Its manifest-merged permissions are stripped in AndroidManifest.xml
    // (see the comment there); no GMS transitive (`checkDependencyGuards`).
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // skein-2ige: `MainActivity` must be a `FragmentActivity` — it hosts the
    // `BiometricPrompt` that `BiometricUnlockScreen` / `UnlockManager.unlock`
    // present. `androidx.biometric` exposes `androidx.fragment` as an API
    // dependency at the version the rest of the app already resolves, so this
    // adds no new artifact to the runtime classpath (license audit unchanged).
    implementation(libs.androidx.biometric)
    // skein-gg11.13: the fragment version biometric resolves (1.2.5) crashed
    // every `rememberLauncherForActivityResult` launch from `MainActivity`
    // ("Can only use lower 16 bits for requestCode" — the ActivityResult
    // registry issues codes >= 0x10000 and FragmentActivity < 1.3 still
    // validated the legacy rule). Pinned to 1.9.1; same Apache-2.0 group.
    implementation(libs.androidx.fragment)
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
    // skein-0m1z: the export-stage tests assert with Truth, as every other
    // module's tests in this repo already do. Test-only — no runtime or
    // license-audit classpath impact.
    testImplementation(libs.truth)
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
    // E5.I10 (skein-7v3): `WorkManagerTestInitHelper` /
    // `TestListenableWorkerBuilder` for `IngestWorkerTest` (Robolectric) and
    // the compile-only `IngestWorkerInstrumentedTest` (run gated on skein-k3b2).
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    // skein-rf5r: Enable Compose testing in androidTest. Allows writing
    // real, on-device E2E tests (e.g., LockPolicySettingsRecreationInstrumentedTest
    // evolved to drive SettingsScreen/LockPolicyControls through createAndroidComposeRule).
    androidTestImplementation(libs.compose.ui.test.junit4)
    // skein-1uw (E4.I4): `LlamaCppEngineInstrumentedTest` subclasses
    // `InferenceEngineContractTest` from `:testing`, the way every other
    // contract-suite consumer does, and asserts with Truth. `androidTest`
    // only — `:testing`'s `api(libs.junit)` (EPL-1.0) never reaches the
    // fossDebug RUNTIME classpath, so `licenseAuditFossDebugRuntimeClasspath`
    // is unaffected (this is the distinction the `:feature:timeline` comment
    // above records: `debugImplementation` would have been the problem).
    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.truth)
    debugImplementation(libs.compose.ui.tooling)
}

// skein-1uw (E4.I4): the tiny GGUF `LlamaCppEngineInstrumentedTest` loads
// through the real `:inference` service.
//
// DUPLICATION, ON PURPOSE AND ONLY FOR NOW. This is a copy of
// `inference-service/build.gradle.kts`'s `fetchTestModel` (skein-80p), reading
// the SAME `tools/models/test-model.lock` and enforcing the same sha256 gate.
// It is copied rather than shared because the two modules have no build-logic
// home in common today: sharing it means a new convention plugin in
// `build-logic/`, which is a different module's scope. **A follow-up bead
// should lift both copies into `build-logic/` as one task type** — if the lock
// file's parser or the verification rule ever differs between the two copies,
// one lane will install a model the other refused. Until then, any change here
// must be made in both files.
//
// Network-gated per spec §9 ("the app never does"): wired ONLY into the
// androidTest asset-merge tasks, so no production variant of `:app` can ever
// trigger a download. The destination is git-ignored (`.gitignore`'s `*.gguf`).
abstract class FetchTestModelTask : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val dest = outputFile.get().asFile
        val expected = expectedSha256.get().lowercase()

        if (dest.isFile && sha256Of(dest) == expected) {
            logger.lifecycle("fetchTestModel: ${dest.name} present and verified, skipping download")
            return
        }

        dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.download")
        logger.lifecycle("fetchTestModel: downloading ${modelUrl.get()}")
        try {
            URI(modelUrl.get()).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw GradleException(
                "fetchTestModel: failed to download ${modelUrl.get()} -- ${e.message}. " +
                    "This task needs network (CI and a developer's Mac have it; the app never does).",
                e,
            )
        }

        val actual = sha256Of(tmp)
        if (actual != expected) {
            tmp.delete()
            throw GradleException(
                "fetchTestModel: sha256 mismatch for ${modelUrl.get()}\n" +
                    "  expected (tools/models/test-model.lock): $expected\n" +
                    "  actual:                                  $actual\n" +
                    "Refusing to install an unverified test model.",
            )
        }

        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        logger.lifecycle("fetchTestModel: verified sha256 $actual, wrote ${dest.path}")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// Same `key=value` parser as inference-service's copy; see the note above.
val testModelLockFile = rootProject.file("tools/models/test-model.lock")
val testModelLock: Map<String, String> =
    testModelLockFile
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx < 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

val fetchTestModel =
    tasks.register<FetchTestModelTask>("fetchTestModel") {
        group = "verification"
        description = "Downloads and sha256-verifies the tiny GGUF for LlamaCppEngineInstrumentedTest (skein-1uw)"
        modelUrl.set(testModelLock.getValue("url"))
        expectedSha256.set(testModelLock.getValue("sha256"))
        outputFile.set(layout.projectDirectory.file("src/androidTest/assets/tiny.gguf"))
    }

// Only merge*AndroidTestAssets — never merge*Assets, so the app's own
// production assets cannot trigger a download.
tasks.matching { it.name.contains("AndroidTestAssets") }.configureEach {
    dependsOn(fetchTestModel)
}
