# Testing (E10.I1)

Skein's tests run in three tiers. This doc explains each one, when to use
it, and where its shared utilities live. For the module map and dependency
guards this doc's module list assumes, see `docs/ARCHITECTURE.md`.

## The three lanes

### 1. JVM unit tests — fast, every module, no Android runtime

Lives in every module's `src/test/kotlin/`. No device, no Robolectric, no
`android.jar` — pure Kotlin/JVM. Target: under 30 seconds per module.

Run with:

```bash
./gradlew test               # every pure-JVM module's `test` task
tools/test/run-jvm.sh         # test + ktlint + the Android modules' Robolectric lane
```

`:testing` (see below) provides shared JVM-only utilities so this tier stays
fast and dependency-free:

- `app.skein.testing.MainDispatcherRule` — swaps `Dispatchers.Main` for an
  `UnconfinedTestDispatcher` so `Dispatchers.Main`-bound code runs
  deterministically without an Android `Looper`.
- `app.skein.testing.TempDirRule` — a fresh temp directory per test,
  cleaned up automatically.
- `app.skein.testing.FakeClock` — a manually-advanced clock; production code
  should never read `System.currentTimeMillis()` directly in a way a test
  can't control.
- `app.skein.testing.SkeinLogCapture` / `SkeinLogCaptureRule` — the capture
  point the (not-yet-landed) `SkeinLog` facade's `isSensitive` test hook
  will call into. `SkeinLogCaptureRule` fails a test if anything tagged
  sensitive was logged during it.
- `app.skein.testing.fakes.*` — scripted/in-memory stand-ins for the plan's
  §4 service contracts (`InferenceEngine`, `VaultRepository`, `IndexStore`,
  `RetrievalService`, `PersonaService`, `EmbedderService`). See
  "Fakes" below.

### 2. Robolectric tests — Android APIs, no device

For tests that need real Android framework behavior (manifest/PackageManager
facts, Compose UI trees) but not a physical device or emulator. These live
directly in the Android module that needs them — **not** in `:testing`,
which stays pure Kotlin/JVM (see "Why `:testing` has no Android
dependencies" below) — and run as part of that module's
`testDevDebugUnitTest` / `testFossDebugUnitTest`.

**Always pin `@Config(sdk = [34])`.** Robolectric's SDK 35+/36+/37
`android-all` jars require Java 21 to load; this toolchain (and CI) has only
Java 17. SDK 34 (Android 14) still models every manifest attribute and API
surface the current tests need — see bd memory
`robolectric-sdk37-needs-java21`.

Examples already in the tree:

- `app/src/test/kotlin/app/skein/ManifestPolicyTest.kt` — asserts the
  manifest security baseline via Robolectric's shadowed `PackageManager`.
- `app/src/test/kotlin/app/skein/MainActivityComposeTest.kt` — a
  Robolectric-hosted `createAndroidComposeRule<MainActivity>()` test. It
  launches `MainActivity` directly because it is already declared and
  exported in `AndroidManifest.xml`; a plain `createComposeRule()` (which
  needs a generic launchable `ComponentActivity`, normally registered via
  `androidx.compose.ui:ui-test-manifest`) does **not** work inside a
  **library** module here — verified while building this issue,
  `ui-test-manifest`'s `debugImplementation` manifest merges into an
  *application* module's own merged manifest but not into a library
  module's (a library's own manifest-merge pass doesn't pull in dependency
  manifests the way consuming it from an app does). If a feature module
  needs its own Compose UI test in the future, either give it a manifest
  declaring a host activity, or exercise its composables from `:app`.

Run with:

```bash
./gradlew testDevDebugUnitTest
```

### 3. Instrumented tests — real device or emulator

Reserved for behavior that only a real Android runtime can prove: JNI
loading of `libskein_sqlite.so`, real `WorkManager` execution, a
`DocumentsProvider` permission handshake with a second app. Lives in each
module's `src/androidTest/kotlin/`.

Gated behind `.github/workflows/emulator.yml`'s `workflow_dispatch` +
06:00 UTC nightly schedule — **not** run on every push/PR, because booting
an emulator is slow. `app/src/androidTest/kotlin/app/skein/
SampleInstrumentedTest.kt` is the sample this issue adds; it asserts the
instrumentation actually targets `app.skein`, proving the lane runs
something real rather than an empty task trivially succeeding.

Run against an already-running device/emulator with:

```bash
tools/test/run-emulator.sh   # checks `adb devices` first, then runs connectedDevDebugAndroidTest
```

## The `:testing` module

`:testing` is a pure Kotlin/JVM library (`org.jetbrains.kotlin.jvm`, no
Android Gradle plugin) depending only on `:core:model`. Add it as
`testImplementation(project(":testing"))` from any module.

### Why `:testing` has no Android dependencies

The original plan sketch for `E10.I1` described `:testing` as an Android
library carrying Robolectric, Compose UI test, `androidx.test`, and
`WorkManager` testing directly. That was changed during implementation:

- `:core:model` and other pure-JVM modules (`:core:markdown`) must be able
  to `testImplementation(project(":testing"))` without pulling the Android
  SDK onto their compile/test classpath — the isolation guard
  (`checkIsolationGuards`, `build-logic/guards/.../IsolationGuardPlugin.kt`)
  now enforces `:testing` as pure-JVM the same way it already did for
  `:core:model`/`:core:markdown`.
- Robolectric SDK 34 pinning (Java 17 toolchain constraint) and
  Compose-UI-test's manifest requirements are both *Android-module*
  concerns; forcing them into a pure-JVM module would have meant `:testing`
  couldn't actually stay pure-JVM.

Practical effect: Robolectric, Compose UI test, `androidx.test` runner/
rules, and `androidx.work:work-testing` are declared directly by the
Android module that needs them (see `app/build.gradle.kts` for the current
example), not inherited from `:testing`. If several Android modules
accumulate near-identical Robolectric/Compose-test boilerplate, that's a
signal for a future `:testing-android` module — not yet needed, so not
built speculatively.

### Fakes

`us.aherrera.skein.testing` (note: **not** `app.skein.testing` — that
package holds the pure-JVM JUnit rules above; the fakes below live under
the plan's locked `us.aherrera.skein` contract-code prefix, alongside the
abstract contract suites, `core/model`'s interfaces, and the concrete
`*Impl` classes' `androidTest` contract subclasses) has one scripted or
in-memory fake implementing each real, locked plan §4 service contract,
plus a consistent builder DSL over them:

| Fake / builder | Stands in for | Approximation to know about |
|---|---|---|
| `FakeInferenceEngine` / `scriptedEngine("q" to listOf("a", "b"))` | `InferenceEngine` (`E0.I10`) | Deterministic script keyed by the prompt's last user message; no sampling, no real tokenizer. |
| `InMemoryVaultRepository` / `fakeVault { note(...); chat(...) }` | `VaultRepository` (`E0.I11`) | `searchBodies` is a substring match, not FTS5 BM25; one `Mutex` serializes writes rather than real DB transactions. |
| `InMemoryIndexStore` / `CountingIndexStore` (call-counting decorator) | `IndexStore` (`E0.I11`) | `bm25` is term-occurrence counting, not real BM25; `knn` is real cosine-over-int8, exact (no ANN). |
| `FakeRetrievalService` | `RetrievalService` (`E0.I12`) | Fixed fixture list, sorted once; the query text is ignored — no vector/lexical/graph recall or ranking. |
| `FakePromptAssembler` | `PromptAssembler` (`E0.I12`) | No `PromptGuard` neutralization — never feed its output to a real model outside tests. |
| `InMemoryPersonaService` | `PersonaService` (`E0.I13`) | Faithful to "always a Default, can't delete the last one"; otherwise a plain map, no `documents.persona_id` nulling on delete. |
| `FakeEmbedderService` / `fakeEmbedder()` | `EmbedderService` (`E0.I17`) | Vectors are a deterministic hash-seeded stream — reproducible per input, not semantically meaningful. |
| `FakeExportService` / `FakeImportService` | `ExportService` / `ImportService` | See each fake's own KDoc. |

Every fake's KDoc says exactly what it's faithful to and what it
approximates — read it before trusting a fake's behavior in a new test.

**Behavioural recorders**, also in `us.aherrera.skein.testing`:

- `CountingIndexStore(delegate)` — wraps any `IndexStore` (normally
  `InMemoryIndexStore`) and counts calls per method name (`counts`,
  `countOf("bm25")`), for asserting *how many times* a pipeline touched the
  index without the index itself growing recording behavior.
- `RecordingTabController(delegate = null)` — records `TabController`
  (`openPreview`/`openPinned`/`pin`/`close`/`closeOthers`/`activate`) calls
  in order. Not a locked contract — `feature/shell`'s real `TabsState` is a
  concrete Compose `@Stable` class `:testing` cannot depend on; this is a
  plain-JVM action vocabulary a shell test can assert against instead.

**History**: `E0.I10`–`E0.I13`/`E0.I17` (locking the real interfaces) landed
after `E10.I1` first scaffolded `:testing`, so an earlier, pre-contract
scaffold generation of these fakes briefly lived at `app.skein.testing.fakes`
implementing nothing (self-contained placeholders). `E10.I2` (bd
`skein-0j1`) retired that scaffold once it was confirmed unreferenced
anywhere outside its own tests — every real fake now implements its actual
`core/model` interface directly, and this is the only fakes package.

**Fakes must never grow real behavior.** If a test needs a fake to behave
more like production (real ranking, real tokenization, ...), that's a sign
the test belongs against the real implementation (once it exists) or the
approximation gap needs a tracked follow-up — not a reason to make the fake
smarter.

## Guard tasks

`check` also runs `build-logic/guards`' tasks: `checkManifestGuards`,
`checkDependencyGuards`, `checkIsolationGuards`, `licenseAudit`. None of the
test infrastructure above bypasses them — `:testing` is itself subject to
`checkIsolationGuards` (pure-JVM enforcement) the same as any other module.
