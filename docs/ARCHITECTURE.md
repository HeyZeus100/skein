# Architecture

> **Status:** written by `E0.I18`/skein-edc from the state of the tree at
> commit `c3d22b8` (2026-09-21) — every module row, dependency edge and guard
> reference below is verified against a build file or a `build-logic/guards`
> source file, cited inline. Where a step in the startup sequence is not yet
> implemented, its diagram participant is labelled `planned (skein-xxxx)` and
> cites the bead that owns it — nothing below is invented. Kept in sync going
> forward is `E9.I10`/skein-4aw's job (ARCHITECTURE.md refresh at M3); until
> then, re-verify a claim against the cited file before trusting it blindly.

This document is the one every dispatched agent reads first (see §6, "How to
pick up an issue"). It answers three questions: what are the modules and what
may each depend on (§2), what actually happens when the app starts (§1), and
what conventions hold across all of it (§3–§5).

## 1. Process topology and startup

Skein runs as three Android processes from one APK (spec §4.1):

| Process | Manifest declaration | What runs there | Permissions |
|---|---|---|---|
| `:app` (main) | implicit (the process `SkeinApplication`/`MainActivity` run in) | UI, vault access, orchestration, RAG, editor, ingest | Full app permissions (`POST_NOTIFICATIONS`, `USE_BIOMETRIC`; SAF for file access — no `INTERNET`, ever) |
| `:inference` | `<service android:name="app.skein.inference.service.InferenceService" android:process=":inference" android:isolatedProcess="true" android:exported="false" />` (`app/src/main/AndroidManifest.xml`) | model mmap + llama.cpp inference | none — `isolatedProcess="true"` strips every permission |
| `:embedder` | `<service android:name="app.skein.embedder.service.EmbedderService" android:process=":embedder" android:isolatedProcess="true" android:exported="false" />` (same manifest) | ONNX Runtime embedding / NER / rerank | none |

Both isolated services communicate with `:app` only over the AIDL contract in
`:core:ipc` (§2.3) — no shared memory, no Android permissions, no path
strings crossing the boundary (fds only). This is the isolation the plan and
spec both call out as containing "the blast radius of any llama.cpp parser
exploit" (spec §4.1).

### 1.1 Startup sequence

Spec §4.1: *"`:app` → biometric unlock → StrongBox key unwrap → SQLCipher
open → bind `:inference` → hash-verify current model → mmap → ready."* The
diagram below names the real class/function for every step that exists in
this tree today, and labels the rest `planned` with the bead that owns it —
as of commit `c3d22b8`, everything up to and including "vault open, provider
installed" is implemented and unit-tested; binding the isolated service and
loading a model into it is not.

```mermaid
sequenceDiagram
    actor User
    participant MA as MainActivity
    participant UM as UnlockManager
    participant VKP as VaultKeyProvider
    participant VB as VaultBootstrap
    participant DVO as DeviceVaultOpener
    participant VL as VaultLifecycle
    participant Mig as Migrator
    participant VDP as VaultDocumentsProvider
    participant MM as ModelManager, planned skein-1uw
    participant IS as InferenceService, planned skein-nxk
    participant MV as ModelVerifier, implemented not yet wired
    participant Eng as LlamaCppEngine, planned skein-1uw

    Note over MA: runs in :app
    Note over IS,Eng: IS runs in the isolated :inference process

    User->>MA: launch app
    MA->>UM: unlock(activity, prompt, factor)
    UM->>VKP: unlock(activity, prompt, factor)
    Note right of VKP: Layer-0 Keystore alias unwraps master_key_material, 32 bytes
    VKP-->>UM: UnlockResult.Success(token)
    UM-->>MA: UnlockOutcome.Success(token), state to Unlocked
    MA->>VB: bringUp()
    VB->>DVO: open()
    DVO->>VL: create(keyCopy) or open(keyCopy)
    VL->>Mig: migrate(path)
    Note right of Mig: a wrong key throws here, before any migration SQL runs
    Mig-->>VL: MigrateResult
    VL-->>DVO: CreateResult.Success or OpenResult.Success
    DVO-->>VB: VaultSession with repository, indexStore, personaService
    VB->>VDP: install(Services(repository, exportService, unlockState))
    VB->>VDP: notifyRootsChanged()
    VB-->>MA: BringUpResult.Ready(session)

    rect rgba(128,128,128,0.08)
    Note over MA,Eng: planned, not yet implemented: E4.I3 skein-nxk (InferenceService), E4.I4 skein-1uw (ModelManager, LlamaCppEngine)
    MA->>MM: load default model
    MM->>IS: bindService, isolatedProcess true
    MM->>IS: load(LoadRequest with ManifestBinding fds, sha256, size, sessionEpoch)
    IS->>MV: verifyBeforeMmap(fd) - pre-mmap SHA-256, streamed over the open channel
    MV-->>IS: FileDigests, or ModelVerification.HashMismatch
    IS->>IS: dup the fd, mmap it via proc self fd
    IS->>MV: verifyAfterMmap(mappedBuffer) - post-mmap BLAKE3-256
    MV-->>IS: Ready, or ModelVerification.Tampered
    IS->>Eng: create context: contextLength, threads, gpuLayers
    Eng-->>IS: EngineStatus state ready
    IS-->>MM: onDone / status()
    end
    MM-->>MA: ready
```

Every arrow above the shaded box is a real call in this tree:

- `UnlockManager.unlock`/`unlockWith` — `core/vault/src/main/kotlin/app/skein/core/vault/session/UnlockManager.kt`
- `VaultKeyProvider.unlock` — `core/vault/src/main/kotlin/app/skein/core/vault/key/VaultKeyProvider.kt` (contract); `VaultKeyProviderImpl` for the Keystore unwrap
- `VaultBootstrap.bringUp` / `openAndInstall` — `app/src/main/kotlin/app/skein/vault/VaultBootstrap.kt`
- `DeviceVaultOpener.open` / `createOrOpen` — `app/src/main/kotlin/app/skein/vault/DeviceVaultOpener.kt`
- `VaultLifecycle.create` / `.open` and `Migrator.migrate` — `core/vault/src/main/kotlin/app/skein/core/vault/lifecycle/VaultLifecycle.kt`
- `VaultDocumentsProvider.install` / `.notifyRootsChanged` — called through the `DocumentsProviderPort` seam in `VaultBootstrap.kt`

Everything inside the shaded box is designed but not wired:

- `InferenceService` exists today only as a stub — `inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt`'s `onBind` returns `null`; the AIDL-backed `IInferenceService.Stub` implementation, the bind flow, and `load`/`generate` are `E4.I3` (skein-nxk).
- `ModelManager`/`ModelRegistry` (the `:app`-side caller that imports a model, builds a `LoadRequest`, and binds the service) is `E4.I5`, not yet landed; the client-side `InferenceEngine` implementation (`LlamaCppEngine`) that talks to it is `E4.I4` (skein-1uw).
- `ModelVerifier.verifyBeforeMmap` / `.verifyAfterMmap` / `.verifyForLoad` (`core/inference/src/main/kotlin/app/skein/core/inference/models/ModelVerifier.kt:167,203,225`) and `ImmutableModelStore` (`.../models/ImmutableModelStore.kt`) **are** implemented and unit-tested as pure logic (`skein-st1r`) — see `docs/design/MODEL_STORE.md` §3 for the two-gate design and §6 ("Not yet wired") for the explicit list of what still has to call them: `E4.I5`'s import flow and `E0.I16`'s `ManifestBinding` Parcelable (the latter has since landed — see §2.3 below).

## 2. Module map

### 2.1 Every Gradle module

All 22 modules below are declared in `settings.gradle.kts`; the "process"
column is where the module's code actually executes at runtime (a library
module has no process of its own — it is compiled into whichever
application/service module depends on it).

| Module | Kind | Runs in | Declared / allowed dependencies | Verified against | Owner epic |
|---|---|---|---|---|---|
| `:app` | Android application | `:app` | `:core:model`, `:core:vault`, `:inference-service`, `:embedder-service`, `:feature:shell`, `:feature:settings`, `:feature:editor`, `:feature:timeline`, `:feature:graph`, `:core:rag`, `:core:inference`; androidx.work/biometric/lifecycle/datastore, Compose. Guarded: `checkDependencyGuards*` (bans GMS/Firebase/Play Core/ML Kit transitively), `checkManifestGuards*` (bans `INTERNET`/exported components), `licenseAudit*RuntimeClasspath` (foss variant license allowlist) | `app/build.gradle.kts` | E1 (scaffold, `E1.I1`); vault bring-up `E3` |
| `:core:model` | Pure Kotlin/JVM | wherever it's linked (every process) | `kotlinx-coroutines-core` (api), `kotlinx-serialization-json` (api); no project deps | `core/model/build.gradle.kts`; isolation-guarded (`IsolationGuardPlugin.PURE_JVM_MODULES` includes `:core:model`) | E0 (contracts: `E0.I10`–`E0.I13`) |
| `:core:ipc` | Android library (AIDL + Parcelize) | contract-only today — no module's `build.gradle.kts` currently declares a dependency on it (`grep -rl "core:ipc" **/build.gradle.kts` finds none); it is what `:inference-service`/`:embedder-service`/`:app` will depend on once the bind flow lands (`E4.I3` skein-nxk, `E4.I5`) | `androidx.core.ktx`; **landing:** an `api(project(":core:model"))` edge for `ErrorCodes.toException` (`skein-k7e9`) is not yet present in this worktree's build file | `core/ipc/build.gradle.kts`; contract itself in `core/ipc/src/main/aidl/**` + `Parcels.kt` | E0 (`E0.I16`, v2) |
| `:core:vault` | Android library (+ native `libskein_sqlite.so`) | `:app` | `api(:core:model)`, `implementation(:core:markdown)`, androidx.biometric/sqlite, pdfbox-android | `core/vault/build.gradle.kts` | E2 (vault); keys/unlock `E3.I2`/`E3.I3a` |
| `:core:security` | Android library | `:app` | `api(:core:model)`; test-only `:testing`, `:core:markdown` | `core/security/build.gradle.kts` | E3 (`E3.I10`, PromptGuard) |
| `:core:inference` | Android library | `:app` | `api(:core:model)`, `api(:core:verify)`, kotlinx-coroutines-core, kotlinx-serialization-json | `core/inference/build.gradle.kts` | E4 (models/thermal: `skein-st1r`, `E4.I9`) |
| `:core:verify` | Pure Kotlin/JVM | wherever it's linked — `:app` (via `:core:inference`) AND both isolated processes | `api(:core:model)`; no other project deps | `core/verify/build.gradle.kts`; isolation-guarded BOTH ways (`IsolationGuardPlugin.PURE_JVM_MODULES` forbids it an Android plugin, and it is on `COMMON_SERVICE_PROJECT_ALLOWLIST` so the two services may depend on it) | E3/E4 (`E3.I5` verifier `skein-v2s`, extracted here by `E4.I3` per coordinator decision `skein-hiwb`) |
| `:core:rag` | Android library | `:app` | `api(:core:model)`, kotlinx-coroutines-android, kotlinx-serialization-json; test-only `:core:vault`, `:testing` (main source set deliberately has no `:core:vault` edge — see the module's own build-file comment) | `core/rag/build.gradle.kts` | E5 (RAG/ingest) |
| `:core:markdown` | Pure Kotlin/JVM | wherever it's linked | `jetbrains.markdown`, `kotlinx-serialization-json`, `compose-ui`/`compose-ui-graphics` (JVM target only — no Android plugin) | `core/markdown/build.gradle.kts`; isolation-guarded (`PURE_JVM_MODULES`) | E7 (`E7.I2`) |
| `:core:export` | Android library | `:app` | `:core:markdown`, `:core:model`, `:core:vault` (`SafeFileName` reuse), androidx-core-ktx, coroutines-core | `core/export/build.gradle.kts` | E2 (`E2.I10`–`E2.I12`) |
| `:core:agent` | Pure Kotlin/JVM | wherever it's linked | `implementation(:core:model)`; no project deps beyond that | `core/agent/build.gradle.kts`; isolation-guarded (`PURE_JVM_MODULES`) | **not in the v1 plan's epic numbering** — added by `docs/design/VAULT_TOOL_PRIMITIVES.md` (`skein-fvne`); see §7 drift note |
| `:inference-service` | Android library (`android:isolatedProcess`), native `libskein_llama.so` | `:inference` | Isolation-guard allowlist: project deps limited to `{:core:ipc, :core:model, :core:verify}`, external groups limited to `{org.jetbrains.kotlin, org.jetbrains.kotlinx}` — nothing else compiles | `inference-service/build.gradle.kts`; enforced by `IsolationGuardTask.SERVICE_ALLOWLISTS[":inference-service"]` (`build-logic/guards/.../IsolationGuardPlugin.kt`) | E4 (native build `E4.I1`; service impl `E4.I3`, not yet landed) |
| `:embedder-service` | Android library (`android:isolatedProcess`) | `:embedder` | Same allowlist as `:inference-service` plus `com.microsoft.onnxruntime` | `embedder-service/build.gradle.kts`; `IsolationGuardTask.SERVICE_ALLOWLISTS[":embedder-service"]` | E5 (embedder) |
| `:feature:shell` | Android library + Compose | `:app` | `:core:vault`, androidx.biometric/window, material3-adaptive, coroutines. **Must never depend on `:feature:settings` or `:feature:editor`** — those depend on it, not the reverse (see `feature/settings/build.gradle.kts`'s and `app/build.gradle.kts`'s E6.I14 comments) | `feature/shell/build.gradle.kts` | E6 (`E6.I1`) |
| `:feature:timeline` | Android library + Compose | `:app` | `:core:model` only — deliberately no `:core:vault` or `:feature:shell` edge ("the screen takes an already-open repository and never unlocks anything itself") | `feature/timeline/build.gradle.kts` | E6 (`E6.I7`) |
| `:feature:chat` | Android library (stub) | `:app` | `androidx.core.ktx` only | `feature/chat/build.gradle.kts` | E6 (`E6.I8`, not yet implemented) |
| `:feature:editor` | Android library + Compose | `:app` | `:core:markdown`, `:core:model`, `:core:vault`, `:core:export`, `:feature:shell` (for the `SecureTextField`/`SecureBasicTextField` wrappers `RawTextFieldTest` requires) | `feature/editor/build.gradle.kts` | E7 (editor) |
| `:feature:graph` | Android library + Compose | `:app` | `:core:model`, `:feature:shell` (theme tokens only); deliberately no `:core:vault` | `feature/graph/build.gradle.kts` | E6 (`E6.I11`) |
| `:feature:personas` | Android library (stub) | `:app` | `androidx.core.ktx` only | `feature/personas/build.gradle.kts` | E6 (`E6.I10`, not yet implemented) |
| `:feature:settings` | Android library + Compose | `:app` | `:feature:shell` (one-way — shell must never depend back), `:core:vault` (`PassphraseStrength`) | `feature/settings/build.gradle.kts` | E6 (`E6.I14`) |
| `:feature:onboarding` | Android library (stub) | `:app` | `androidx.core.ktx` only | `feature/onboarding/build.gradle.kts` | E6 (`E6.I12`, not yet implemented) |
| `:feature:models` | Android library (stub) | `:app` | `androidx.core.ktx` only | `feature/models/build.gradle.kts` | E6 (`E6.I13`, not yet implemented) |
| `:testing` | Pure Kotlin/JVM | test classpaths only (`testImplementation`/`androidTestImplementation`) | `implementation(:core:model)`; `api` JUnit4 + `kotlinx-coroutines-test` | `testing/build.gradle.kts`; isolation-guarded (`PURE_JVM_MODULES`) so `:core:*` can depend on it without pulling in the Android SDK | E10 (`E10.I1`) |

(23 modules total: `:app`, `:core:agent`, `:core:model`, `:core:ipc`,
`:core:vault`, `:core:security`, `:core:inference`, `:core:verify`, `:core:rag`,
`:core:markdown`, `:core:export`, `:inference-service`,
`:embedder-service`, `:feature:shell`, `:feature:timeline`,
`:feature:chat`, `:feature:editor`, `:feature:graph`, `:feature:personas`,
`:feature:settings`, `:feature:onboarding`, `:feature:models`, `:testing` —
`grep include settings.gradle.kts` lists all 23 in one `include(...)` block.)

### 2.2 The dependency guards, and what each one enforces

Four Gradle plugins under `build-logic/guards` (`build-logic/guards/src/main/kotlin/app/skein/gradle/`) are the actual enforcement mechanism behind the table above — not just convention:

- **`DependencyGuardPlugin`/`DependencyGuardTask`** (`checkDependencyGuards*`, applied to `:app` only via `id("app.skein.guard.dependency")`) — walks every `*RuntimeClasspath`'s *resolved* dependency graph (direct + transitive) and fails if any resolved group is `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`, or `com.google.mlkit` (or a subpackage). Spec §2.2.
- **`IsolationGuardPlugin`/`IsolationGuardTask`** (`checkIsolationGuards`, applied per-module via `id("app.skein.guard.isolation")`) — two independent checks depending on which allowlist a module's path matches: (a) `:core:model`, `:core:markdown`, `:core:agent`, `:core:verify`, `:testing` must never apply an Android Gradle plugin; (b) `:inference-service`/`:embedder-service` may declare only the project/external dependencies in `IsolationGuardTask.SERVICE_ALLOWLISTS`. Spec §2.6, plan §2.4.
- **`ManifestGuardPlugin`/`ManifestGuardTask`** (`checkManifestGuards*`, applied to `:app` only via `id("app.skein.guard.manifest")`) — inspects every variant's *merged* manifest (via the Variant API, `SingleArtifact.MERGED_MANIFEST`) for banned permissions/components and the isolation attributes on the two services. Spec §2.1/§2.2/§2.6.
- **`LicenseAuditPlugin`/`LicenseAuditTask`** (`licenseAudit*RuntimeClasspath`, applied to `:app` via `id("app.skein.guard.license")`) — resolves each foss-variant runtime dependency's POM, extracts its license, and fails if it's outside `tools/licenses/allowlist.txt`. Spec §10, `E1.I7`.
- **`NoRawLoggingGuardPlugin`/`NoRawLoggingGuardTask`** (`checkNoRawLogging`, applied to *every* subproject from the root `build.gradle.kts`, the same way `ktlint` is) — fails if any `src/main/kotlin` file other than `SkeinLog.kt` (and, in `:app`, `AndroidSkeinLogSink.kt`) calls `android.util.Log.*` or `println`. Spec §9, `E1.I11`. See §3.5 below.

All five are wired into `check` (`build.gradle.kts`'s `subprojects {}` block, or the plugin's own `project.tasks.matching { it.name == "check" }.configureEach { dependsOn(...) }`), so `./gradlew check` runs all of them — no separate command is needed to exercise them (this bead did not run Gradle; the wiring is verified by reading the plugin source, not by executing it).

### 2.3 The IPC contract (`:core:ipc`)

The wire contract between `:app` and the two isolated services is defined once, in `core/ipc/src/main/aidl/**` (the `.aidl` files enumerated in §2.1's table) plus the Parcelable/constant definitions in `core/ipc/src/main/kotlin/us/aherrera/skein/ipc/Parcels.kt`. Landed by `skein-mfw` (`E0.I16`, v2 — supersedes plan §4.7 v1 per `docs/design/POST_REVIEW_RESOLUTIONS.md` §3.3/§2.3). Load-bearing rules, all cited in `Parcels.kt`'s own header comment:

- **No large payload ever travels inline.** Images, audio and oversized text are always a `SharedMemRef` (a `ParcelFileDescriptor` over ashmem/tmpfile + size + MIME hint), never a `ByteArray` field — Binder's 1 MiB per-process transaction buffer is shared across *all* in-flight transactions, so a single 4 MiB image alone would blow it. The inline budget for everything else is 32 KiB hard / 128 KiB refuse per transaction, enforced client-side by `TransportRules` (assigned to `E4.I3`/`E4.I4`, not yet in this contract module — `:core:ipc` is wire-shape only).
- **Every model load is fd-based and hash-gated.** `ManifestFileRef` carries an already-open read-only fd plus `expectedSha256`/`expectedSizeBytes` for one file (main model or a named companion role); `ManifestBinding` bundles the full set for one model plus an optional `AttestationRefParcel`. The service hashes and mmaps *that exact fd* — it never re-opens a path (POST_REVIEW_RESOLUTIONS.md §2.3; see §1.1's diagram for how this connects to `ModelVerifier`).
- **Every request carries a `sessionEpoch`.** `IsolatedSessionGate.guard()` (service-side, `LOCK_POLICY_INDEXING.md` §5.3) admits a call only when `req.sessionEpoch == authorizedEpoch`, otherwise refuses with `ErrorCode.SESSION_LOCKED = 11` — this is what closes the race between a lock landing and a call already queued on Binder's thread pool.
- **`ErrorCode`** (`object ErrorCode` in `Parcels.kt`) enumerates every failure a synchronous AIDL call or `onError` can report (`OK`=0 through `INTERNAL`=99); each constant's KDoc names the `InferenceException` subclass (or lack of one) it maps to. **Landing:** `ErrorCodes.toException` plus the `api(project(":core:model"))` edge that would let `:core:ipc` reference `InferenceException` directly (`skein-k7e9`) had not merged as of this worktree's base (`c3d22b8`) — today that mapping lives client-side.

## 3. Conventions

### 3.1 Contracts vs. implementations

Interface/contract types live under `us.aherrera.skein.*` (`:core:model`'s domain types, `:core:ipc`'s AIDL package and Parcelables). Concrete implementations live under `app.skein.*` (`:app`, `:core:vault`, `:core:inference`, the feature modules, the two service modules). `core/ipc/build.gradle.kts`'s own comment states the reasoning: the AIDL package is what every client imports, so the module namespace matches it rather than an `app.skein.core.ipc` placeholder. Some older modules (e.g. `:core:vault`'s `app.skein.core.vault.key`) still predate this split and are tracked for a single reconciliation pass (`skein-0j1`) rather than being moved piecemeal — see that package's own file header.

### 3.2 Coding conventions

- **Kotlin official code style**, enforced by `org.jlleitschuh.gradle.ktlint` applied to every subproject (`build.gradle.kts`).
- **`Result` for expected failures, exceptions for bugs.** The codebase's sealed-class outcome types (`SetupResult`, `UnlockResult`, `OpenResult`, `BringUpResult`, `ModelVerification`, ...) are the concrete expression of this: an *expected* failure (wrong key, user-cancelled biometric, hash mismatch) is a typed variant the caller must handle, never a thrown exception with a stack trace; an unhandled `Throwable` reaching one of these call sites is a bug, and is caught and converted at the boundary (see `DeviceVaultOpener.open`'s `catch (t: Throwable)` converting anything unexpected into `VaultOpenException`).
- **No `println`.** Enforced by `NoRawLoggingGuardTask` (§2.2) — the same guard that bans raw `android.util.Log`.
- **`SkeinLog` only**, and **never log document/prompt/chunk/embedding content** — see §3.5 below (kept from this file's original content, restructured under Conventions rather than discarded).
- **Coroutine dispatcher injection via constructor.** `DeviceVaultOpener(..., private val io: CoroutineDispatcher = Dispatchers.IO, ...)` is the pattern: a dispatcher is always a constructor parameter with a production default, never a hardcoded `Dispatchers.IO`/`.Main` reference inside a function body, so tests can substitute a `TestDispatcher`.
- **No static singletons except `SkeinLog`.** `SkeinLog.sink`/`SkeinLog.testHook` are the one sanctioned exception (a facade with no state of its own beyond the pluggable sink) — everything else is constructed and passed explicitly (`SkeinApplication.vault`, `VaultServices`, `UnlockManager`, etc. are all instance state owned by a composition root, never a Kotlin `object`).

### 3.3 Migration numbering

Migrations live in `core/vault/src/main/resources/migrations/`, one `NNN_<description>.sql` file per line in `INDEX.txt` (numeric order, not file-listing order — `ClassLoader.getResources` can't list a JAR directory). As of this worktree, three are shipped: `001_initial.sql`, `003_document_revisions.sql`, `007_drop_attachment_master_key.sql` (`PRAGMA user_version` reaches 7). Numbers **002 and 004–006 are reserved, not free** — `docs/VAULT_FORMAT.md`'s "Current migrations" section explains why: `002_attestation_status`, `004_post_mmap_blake3`, `005_export_stages`, and `006_recovery_drafts` are claimed by landed design docs and the open coordination bead `skein-voys` for migrations that haven't landed yet; taking one of those numbers for something else would collide. A migration numbered **008** (revision-stamped chunks, `skein-zx15`) is reported as merging into `main` concurrently with this bead and is not yet present in this worktree's `INDEX.txt` — do not assume its shape from this document; read `INDEX.txt` and the migration file directly before adding a ninth.

### 3.4 Native builds and submodules

`third_party/*` (`llama.cpp`, `Vulkan-Headers`, `SPIRV-Headers`, ...) are git submodules pinned by exact commit in `native/llama/PINNED_COMMIT` — a git commit is itself a content hash, which is what the reproducible-build contract (`E1.I8`) needs; `.gitmodules`'s header explains when a dependency is a submodule vs. a vendored tarball (`native/sqlite/*.sha256`) vs. a checked-in source copy. `tools/ci/check-submodules.sh` fails the build if either the working tree's checked-out commit or the superproject's recorded gitlink disagrees with `PINNED_COMMIT`. Bumping a pin means editing `PINNED_COMMIT`, moving the submodule, and updating `native/llama/README.md` in one reviewed commit (that file's own instruction). `native/sqlite/` follows the same discipline for the SQLCipher + sqlite-vec + FTS5 amalgamation, with its own `verify_amalgamation.sh` check wired as a Gradle task in `core/vault/build.gradle.kts`.

### 3.5 Logging

Spec §9: **never log document, prompt, chunk, or embedding content, at any
level, in any build.** This is a hard rule, not a debug-build convenience —
Skein's entire value proposition is that a user's notes and model
conversations never leave their control, and a stray `Log.d` is exactly the
kind of leak that undermines that promise silently.

#### `SkeinLog` is the only logging facade

`app.skein.core.model.SkeinLog` (`:core:model`, pure Kotlin/JVM) is the one
place in this codebase allowed to reach a real log sink. It exposes
`d`/`i`/`w`/`e(tag, message)`, each routed through a pluggable
`SkeinLog.Sink`:

- On the JVM (unit tests, tooling, `:core:model` itself) the sink defaults
  to a no-op — nothing here ever calls `android.util.Log`, so plain JUnit
  tests don't need Robolectric just to exercise logging call sites.
- `app.skein.system.AndroidSkeinLogSink` (`:app`) wraps
  `android.util.Log` and is installed by `SkeinApplication.onCreate` in
  every process that `Application` subclass runs in (`:app`, the isolated
  `:inference` and `:embedder` processes alike).

**`NoRawLogging`** (`build-logic/guards`, applied to every Gradle
subproject via the root `build.gradle.kts`, the same way `ktlint` is)
fails `check` if any `src/main/kotlin` file other than `SkeinLog.kt` calls
`android.util.Log.{v,d,i,w,e,wtf}(...)` or `println(...)`. `:app` also
allowlists `AndroidSkeinLogSink.kt`, the one sanctioned bridge from
`SkeinLog` to the real platform logger — `SkeinLog` itself cannot import
`android.util.Log` (it is isolation-guarded pure Kotlin/JVM), so that bridge
has to live somewhere. Test and `androidTest` sources are not scanned: this
repo's tests already use `println` for developer-visible benchmark output
and it never reaches a shipped build.

#### Release stripping (AC(a))

`SkeinLog.d`/`SkeinLog.i` are `@JvmStatic` and are compiled out of the
release dex by R8: `app/proguard-rules.pro` declares
`-assumenosideeffects class app.skein.core.model.SkeinLog { public static
void d(...); public static void i(...); }`, and `app/build.gradle.kts`'s
`release` build type now sets `isMinifyEnabled = true` so R8 actually runs
(AGP only invokes R8 for a minified variant). This is scoped narrowly on
purpose — see `proguard-rules.pro`'s header comment — to just enable that
one optimization (`-dontobfuscate` plus a blanket `-keep` disable shrinking
and renaming) without taking on the full R8-full-mode / keep-rule audit
that `E1.I8` (reproducible-build configuration) owns; that issue is
expected to replace this file's blanket keep with an audited rule set.
`w`/`e` are **not** stripped in any build — they carry real diagnostics —
but see the next section for why that's still safe.

Verified once via `tools/logging/check-release-dex.sh`, which disassembles
the release APK's dex with `dexdump` and greps for an `invoke-*` call site
referencing `SkeinLog.d`/`SkeinLog.i` (not just the method's own
definition, which `-keep` intentionally leaves in the dex).

#### Defense in depth: content markers, at every level, in every build

Convention (never logging content) is necessary but not sufficient — spec
§9 asks for it to hold even when someone makes a mistake. `SkeinLog`
therefore checks every call, at every level, against a small set of content
markers (`prompt:`, `text:`, `chunk:`, `document:`, `embedding:`); if one is
followed by non-blank, non-already-redacted text, the message that reaches
the sink is replaced with a fixed placeholder instead. This is a safety net
for accidental raw content reaching `SkeinLog`, not the primary control —
callers must still never build such a message in the first place — but it
means a mistake at `w`/`e` (which R8 does not strip) still can't leak
content into logcat.

`SkeinLog.testHook`, set by `:testing`'s `SkeinLogCaptureRule` for the
duration of a test, observes every call's sensitivity flag so
`SkeinLogCaptureRule` can fail a test outright if anything sensitive was
logged — see `app.skein.testing.SkeinLogCapture`'s KDoc.

#### llama.cpp: `LlamaLogRedactor`

`app.skein.inference.service.LlamaLogRedactor` (`:inference-service`, pure
Kotlin, no JNI dependency) is the redactor the future `llama_log_set`
native callback will call before anything from llama.cpp itself reaches
`SkeinLog` — llama.cpp logs prompt text directly at points `SkeinLog`'s own
marker check never sees, since that text never goes through `SkeinLog` at
all until this redactor has already run. `LlamaLogRedactor.forward(level,
message)`:

- drops (returns `null` for) anything at `DEBUG`/`INFO` — llama.cpp's own
  verbose/info logging is never forwarded, sensitive or not;
- redacts anything after a `prompt:`/`text:` marker before returning it for
  `WARN`/`ERROR`, e.g. `LlamaLogRedactor.redact("prompt: hello world") ==
  "prompt: <redacted 11 chars>"` — the marker and a single separating space
  are preserved; only the content is replaced, and a message may contain
  more than one marker.

Wiring the real native `llama_log_set` callback to call this is out of
scope for `E1.I11`/skein-4je — it lands with the llama.cpp build itself
(`E1.I4`/`E4.I1`, skein-ca2/skein-3aw) — this issue only ships the pure
Kotlin redactor those issues will call.

## 4. Test conventions

Full detail lives in `docs/TESTING.md`; the summary an agent needs before touching a test:

- **Three lanes, in order of preference:** (1) JVM unit tests — every module's `src/test/kotlin/`, no Android runtime, target under 30s/module (`./gradlew test`); (2) Robolectric tests — Android APIs without a device, used only where the JVM lane genuinely can't reach the API under test; (3) instrumented tests — real device/emulator, gated on the CI emulator lane (`skein-k3b2`) and compiled unconditionally in the meantime. Prefer the earliest lane that can actually exercise the behavior.
- **Test names:** `unit_condition_expectation` (per this bead's own acceptance criteria in `bd show skein-edc`).
- **`:testing`** (pure Kotlin/JVM, isolation-guarded — §2.1/§2.2) is where shared JVM-only rules and fakes live: `MainDispatcherRule`, `TempDirRule`, `FakeClock`, `SkeinLogCapture`/`SkeinLogCaptureRule`, and one fake per locked `core/model` contract (`InferenceEngine`, `VaultRepository`, `IndexStore`, `RetrievalService`, `PersonaService`, `EmbedderService`). Fakes must never grow real behavior — `docs/TESTING.md`'s own rule — a test that needs a smarter fake belongs against the real implementation instead.
- **`SecureTextField`/`SecureBasicTextField`-only text input.** `:testing`'s `RawTextFieldTest` asserts every text-entry Composable in the app goes through one of `:feature:shell`'s two allowlisted wrappers (`feature/shell/src/main/kotlin/app/skein/feature/shell/input/{SecureTextField,SecureBasicTextField}.kt`) — never a bare `BasicTextField`/`TextField`. This is why `:feature:editor` and `:feature:settings` both depend on `:feature:shell` (§2.1).
- Guard tasks (§2.2) run as part of `check` for every module, `:testing` included — there is no test-infrastructure exemption from `checkIsolationGuards`.

## 5. Commit convention

`type(scope): message`, signed off with `-s` (DCO). Example from this
tree's own history: `skein-3v9: lock the ModelManifest v2 schema, parser and
CI validator`. Every commit that closes or advances a bd issue should name
that issue's key in the subject or body so `git log --grep` finds it.

## 6. How to pick up an issue

This section is intentionally a direct quote of `bd prime`'s own workflow
(run it yourself for the full, current text — this is not paraphrased) and
`docs/BD_TAXONOMY.md`'s "Five-Step Dispatch":

```bash
bd ready              # Find available work
bd show <id>          # View issue details (description, acceptance criteria, metadata)
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

Five-step version (`docs/BD_TAXONOMY.md`):

1. **Identify:** `bd ready -l tier:sonnet -l milestone:M1` (or your tier/milestone).
2. **Review:** `bd show skein-xxx` — read the description, acceptance criteria, **Interfaces** (what the issue consumes from and produces for neighbours — an agent sees only its own issue, so this is how names and types stay consistent), and **Files**.
3. **Claim:** `bd update skein-xxx --claim`. Status → `in_progress`.
4. **Work, TDD:** write the failing test first, run it and confirm it fails for the expected reason, implement, run it again, commit with `-s` (§5).
5. **Close:** `bd close skein-xxx --reason "Acceptance criteria met: [list]. Files: [list]."` — never close without a reason that maps back to the acceptance criteria, and never close without having pushed (`docs/BD_TAXONOMY.md`'s Anti-Patterns table: "Close without committing" is called out explicitly).

Also read, in order, before writing code: §2 (constraints) and §4 (contracts)
of the plan, this document, then the issue's own **Interfaces** and
**Acceptance criteria** — `docs/BD_TAXONOMY.md`'s Anti-Patterns table calls
out "dispatch an issue without reading its interfaces" as a named failure
mode.

## 7. Known drift filed as follow-ups

- `:core:agent` (`core/agent/build.gradle.kts`) is a real, isolation-guarded module in `settings.gradle.kts` but does not appear in the v1 plan's own module-existence checklist (`docs/superpowers/plans/2026-09-19-skein-v1-plan.md:1737`, the `E1.I2` acceptance criterion enumerating all 21 modules it expected) and has no `E<n>.I<n>` epic/issue number of its own — it was added later by `docs/design/VAULT_TOOL_PRIMITIVES.md` (`skein-fvne`). Filed as `skein-3siw` to either backfill the plan-doc checklist or record the module under an explicit epic.
