# Skein Hub — two-app model acquisition, and the `ModelSource` seam in Core

**Status:** Design, not implemented. Bead `skein-1m7v` (epic `skein-rkrq`).
**Date:** 2026-09-22
**Amended:** 2026-09-22 by `skein-utrb` — §1.1 invariant I6 and the new §1.3 record the capability
split decision of `docs/design/NORTH_STAR_BRIEF.md` §10, with the matching clarification in §2.3 and
one REJECT row in §8. Nothing else in this document changed; §§1.2, 2–7 and 9–12 stand as written.
**Authority reconciled:** `docs/research/POCKETPAL_RECON_BRIEF.md` §§7–16, 19, 20, 23–26 (the owner's
briefing); spec `docs/superpowers/specs/2026-09-19-skein-design.md` §2 (non-negotiables) and §9
(threat model); `docs/design/MODEL_STORE.md`; `ModelManifest` v2
(`core/model/src/main/resources/schema/model-manifest.schema.json`, `skein-3v9`); `:core:verify`
(`skein-hiwb`); `:core:ipc` AIDL v2 and `docs/design/POST_REVIEW_RESOLUTIONS.md` §3; plan `E4.I5`
(`skein-cyq`) and `E4.I6` (`skein-5oi`); `E0.I4` (`skein-bxk`), `E0.I8` (`skein-5hr`);
`docs/ARCHITECTURE.md`; `app/src/main/AndroidManifest.xml` + `ManifestPolicyTest` +
`tools/ci/manifest-audit.sh`; `docs/design/NORTH_STAR_BRIEF.md` §10 (capability separation) as read by
`docs/design/NORTH_STAR_REVIEW.md` §5.
**Constraint honoured throughout:** `docs/Handoffs/skein-v1-autonomous-completion.md` §3 — Core keeps
zero `INTERNET`, zero GMS, zero telemetry, isolated inference, and every model file dual-hash-verified
before mmap. Nothing below relaxes any of those; several things below tighten them.

> **Where each bead requirement lives.** §1 topology/trust · §2 handoff mechanism (decided) · §3
> Core-side acceptance pipeline on existing code + what changes in `skein-cyq` · §4 the `ModelSource`
> seam · §5 storage ownership, no-duplicate rule, import without Hub · §6 curated catalog and
> device-aware recommendations (schema sketch) · §7 Automatic vs Advanced · §8 non-goals and REJECT
> list · §9 bead breakdown. §10 is the threat table, §11 the proposed `ARCHITECTURE.md` and spec §2
> wording, §12 the reconciliation hook for `docs/research/POCKETPAL_RECON.md`.

---

## 1. Topology and trust model

Two APKs, signed with the same key (`docs/SIGNING.md`), built from two **separate Gradle builds** in
this repository.

```
        Internet
           │
           ▼
┌──────────────────────────────┐        ┌───────────────────────────────────────────┐
│ app.skein.hub  (Skein Hub)   │        │ app.skein  (Skein Core)                   │
│                              │        │                                           │
│ INTERNET                     │        │ NO INTERNET, NO GMS, NO telemetry         │
│ Hugging Face API + downloads │        │ ┌─────────┐ ┌────────────┐ ┌────────────┐ │
│ curated catalog              │        │ │ :app    │ │ :inference │ │ :embedder  │ │
│ download state machine       │        │ │ vault   │ │ isolated   │ │ isolated   │ │
│ SHA-256 after download       │        │ │ RAG     │ │ llama.cpp  │ │ ONNX       │ │
│ HF token in Keystore         │        │ └─────────┘ └────────────┘ └────────────┘ │
│ FileProvider (read-only)     │        │ no component exported to Hub              │
└──────────────┬───────────────┘        └──────────────▲────────────────────────────┘
               │                                       │
               └────── one-shot read-only content URI ─┘
                       (Core asks; Hub answers; bytes only)
```

### 1.1 The six invariants, and what enforces each

| # | Invariant (brief §§9–15) | Enforced by |
|---|---|---|
| I1 | Hub is the only app with `INTERNET`. | Core's `checkManifestGuards` denylist + `ManifestPolicyTest` + `tools/ci/manifest-audit.sh`; the two builds are separate so no Hub source can merge a permission into Core's manifest. |
| I2 | Hub never reads the vault. | Core exports exactly two components (`MainActivity`, `VaultDocumentsProvider`) and adds **none** for Hub (§2). The `DocumentsProvider` is `MANAGE_DOCUMENTS`-guarded, so only the system DocumentsUI can reach it — Hub cannot, and gains nothing by being same-signature. There is no Hub-facing Core API of any kind. |
| I3 | Hub never commands Core. | The transfer is **Core-initiated** (§2). Hub has no way to start, wake or message Core: no exported receiver, no exported service, no intent-filter Hub can resolve. The channel is one call, from Core, returning bytes. |
| I4 | Core distrusts every artifact. | §3's acceptance pipeline: Core re-hashes what it wrote, structurally inspects inside `:inference`, and derives every load-bearing manifest field itself. Hub's declared digest is an integrity cross-check, never an authority. |
| I5 | HF credentials never enter Core. | The channel carries a `content://` URI and a small bundle of display hints (§3.4). There is no field for a token, and Hub's token lives in Hub's Keystore. Core has no code path that could accept one. |
| I6 | Installing Hub authorises no network behaviour by itself. | Each Hub capability that crosses the boundary is its own `signature` permission guarding its own Hub entry point, and Core declares only the ones it implements (§1.3). In v1 that is exactly one, `MODEL_TRANSFER`; frontier inference is not a refused call but an absent one. |

### 1.2 Authentication and trust are two different gates

This mirrors, deliberately, the separation `MODEL_STORE.md` §4 already draws between **content trust**
(the hard digest gate) and **origin trust** (the soft attestation badge). Hub adds a third, and it is
softer than both:

- **Sender authentication** (`app.skein.permission.MODEL_TRANSFER`, `signature`, plus an
  explicit `checkSignatures` call) answers *"is the app on the other end genuinely Skein Hub?"*
  It is a hard gate on **who**, and it buys exactly one thing: an unrelated app cannot impersonate
  Hub to harvest a user's gated-model downloads, and cannot pose as Hub to Core.
- **Content trust** (`ModelVerifier`'s two gates) answers *"are these the bytes we wrote?"* It is the
  hard gate, unchanged.
- **Artifact trust** (*"is this GGUF safe to parse?"*) is **never** granted. Not by the signature, not
  by a matching digest, not by a curated catalog entry. Every artifact is parsed only inside
  `:inference` (brief §15, spec §2.6).

Stated as the rule a reviewer should hold us to: **a same-signature sender may hand Core bytes; it may
not hand Core a conclusion.**

### 1.3 Hub capabilities are separate permissions — decided

`NORTH_STAR_BRIEF.md` §10: installing Hub must not be blanket authorisation for network behaviour, and
"a user who enables model downloads has not necessarily authorized sending context to a frontier
model". The decision, argued in `NORTH_STAR_REVIEW.md` §5:

**Each Hub capability that crosses the trust boundary gets its own `signature`-protected permission,
guarding its own Hub entry point. Core declares a `<uses-permission>` only for the capabilities it
implements. There is no capability-grant record.**

| Brief §10 capability | Crosses the boundary? | Permission | v1 status |
|---|---|---|---|
| `MODEL_DISCOVERY` | no — search and resolve run wholly inside Hub (§4.3) | none, and none is needed | Hub-internal |
| `MODEL_DOWNLOAD` | yes, as the artifact handoff (§2) | `app.skein.permission.MODEL_TRANSFER` | **declared by both apps** |
| `FRONTIER_INFERENCE` | yes, if it ever exists | `app.skein.permission.FRONTIER_INFERENCE` | name reserved; **declared by neither app** |
| `BRAIN_PACK_DOWNLOAD` | yes, as a pack handoff | `app.skein.permission.BRAIN_PACK_TRANSFER` | name reserved; declared by neither app |
| `SKILL_DOWNLOAD` | yes, as a skill handoff | `app.skein.permission.SKILL_TRANSFER` | name reserved; declared by neither app |

Three consequences, and they are the reason for the shape:

1. **An undeclared capability is absent, not refused.** Core's manifest declares no
   `FRONTIER_INFERENCE`, so no Core code path — including a buggy or compromised one — can invoke a
   frontier entry point. `ManifestPolicyTest` and `tools/ci/manifest-audit.sh` already assert Core's
   permission set, so the enabled set *is* that assertion, and a user can read it in the OS app-info
   screen.
2. **Core answers "downloads enabled, frontier not" locally, from three facts it owns.** For a given
   capability, `HubPicker.availability(capability)` returns `Available` only when Core declares the
   matching permission, `checkSignatures(self, app.skein.hub) == SIGNATURE_MATCH` (§2.4), and Hub
   exposes that capability's guarded entry point. No new data format, no Hub → Core state flow
   (§4.4, §6.3 stand unchanged).
3. **The user's runtime toggle stays inside Hub** and is reported per call as a typed
   `HubAvailability.Unavailable` reason on a cancelled result — never cached in Core. Caching it would
   make Core rely on a claim Hub makes about itself, which I4 forbids.

A capability-grant record was considered and rejected: being Hub-supplied, I4 would let Core treat it
only as a hint, so it would enforce nothing while costing a schema, a signature, a freshness and
revocation story, a hint-quarantine path (§3.4) and its own test suite — machinery with no v1 caller.

Nothing above changes v1's implementation surface: exactly one permission exists, it is the one §2.3
already specifies, and the other four names are reserved here so that adding a capability later is a
new permission beside `MODEL_TRANSFER` rather than a widening of it.

---

## 2. The handoff mechanism — decided

### 2.1 Options compared

| | Mechanism | Who initiates | Core components exported to Hub | Core's `filesDir` writable by Hub | Survives a 2.5 GB copy / process death | Verdict |
|---|---|---|---|---|---|---|
| **A1** | Hub `FileProvider` → `ACTION_SEND` at a new exported Core import Activity | Hub | +1 exported activity with a public intent-filter | no | yes | **Rejected** |
| **A2** | Hub `FileProvider` → Core `startActivityForResult` at Hub's picker; Hub returns the URI in the result Intent | **Core** | **none** | no | yes | **CHOSEN** |
| **B** | Exported Core `ContentProvider` that Hub writes into | Hub | +1 exported provider, writable | **yes** | yes | **Rejected** |
| **C** | Exported Core receiver; Hub broadcasts "artifact ready"; Core pulls | Hub | +1 exported receiver | no | grant lifetime is not tied to a task | **Rejected** |

### 2.2 Why A2, and why not the others

**B is rejected first and hardest.** It re-creates precisely the "permanently shared writable
filesystem area" brief §13 says to avoid, and it is incompatible with `MODEL_STORE.md` §1: the store's
guarantees (`0500` directory, `0400` files, `<file>.tmp` staged then atomically renamed, a refused
import removing the whole directory) all follow from `:app` being the only writer. A provider Hub can
write into means Core's model directory has a second author, and `ImmutableModelStore`'s
`UncoveredFile` refusal becomes the only thing standing between a compromised Hub and a file dropped
beside a verified model. It also adds an exported, writable provider to an app whose spec §9 line is
"no exported components; tight `DocumentsProvider` grants".

**C is rejected** because it inverts brief §12. A broadcast from Hub is Hub telling Core to do
something — Hub commanding Core, even if the verb is "look at this". It needs an exported
`BroadcastReceiver`, which `ManifestPolicyTest`'s `no receiver is exported` test forbids outright, and
it would be the only Core component a background app can wake with no user present. Separately, the
mechanics are wrong: a URI grant attached to a broadcast is short-lived and not scoped to a task, so a
20-second, multi-gigabyte copy would be racing the grant.

**A1 is rejected** because it costs an exported activity with a public intent-filter. Guarding it with
`android:permission="app.skein.permission.MODEL_TRANSFER"` would make it unreachable by other
apps, but `ManifestPolicyTest`'s `EXPECTED_EXPORTED_COMPONENTS` and `manifest-audit.sh`'s
`release_exported_components` would both need a new entry, and spec §9's "no exported components"
would acquire a second exception beside the `DocumentsProvider`. The design does not need it: A2 gets
the same bytes with no exception at all.

**A2 is chosen.** Core starts an explicit `Intent` at Hub's picker Activity with
`startActivityForResult`; the user chooses an already-downloaded artifact *inside Hub*; Hub returns
`RESULT_OK` with `Intent.setData(contentUri)` and `FLAG_GRANT_READ_URI_PERMISSION`. Three properties
make it the right answer against spec §9:

1. **Core exports nothing new.** The exported-component set stays exactly
   `{MainActivity, VaultDocumentsProvider, SystemJobService}`. `ManifestPolicyTest` and
   `manifest-audit.sh` need no allowlist entry. The attack surface Hub could reach is unchanged by
   Hub existing — which is what makes I2 and I3 structural rather than conventional.
2. **The user is in the loop for every transfer**, in the foreground, choosing a specific file. No
   background path exists. This is the same posture spec §9 already demands of every out-of-app
   action ("every out-of-app action requires explicit user tap", handoff §3 item 12).
3. **It is the document-picker flow with a different picker.** `Intent.getData()` returns a
   `content://` URI either way, so `ModelManager.import(...)` takes the same code path for a Hub
   artifact as for a SAF pick (§5.3). One import pipeline, one set of tests, one place to get the
   verification right. That is decisive under Ponytail: the alternative designs all require a second
   ingress into the model store.

**GrapheneOS behaviour that shaped this.** (a) GrapheneOS implements its per-app network toggle by
controlling the `INTERNET` permission; an app that never declares it reads as network-denied and
cannot be toggled on — so the two-app split is user-verifiable on this OS specifically, which is the
whole point of brief §9. (b) Signature-level permissions and `checkSignatures` behave exactly as on
AOSP; there is no GrapheneOS divergence to design around. (c) GrapheneOS users commonly isolate
network-facing apps in a **separate user profile**. Cross-profile `startActivityForResult` does not
resolve, so Hub-in-another-profile is indistinguishable from Hub-not-installed. §5.3's document-picker
path is therefore not a fallback for a rare case — on this OS it is the expected path for a meaningful
share of users, and must stay first-class.

### 2.3 The exact Android pieces

**Permission.** Hub declares it; Core requests it.

```xml
<!-- hub/app/src/main/AndroidManifest.xml -->
<permission android:name="app.skein.permission.MODEL_TRANSFER"
            android:protectionLevel="signature" />
<uses-permission android:name="app.skein.permission.MODEL_TRANSFER" />
<activity android:name="app.skein.hub.transfer.ArtifactPickerActivity"
          android:exported="true"
          android:permission="app.skein.permission.MODEL_TRANSFER"
          android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="app.skein.action.PICK_MODEL_ARTIFACT" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

```xml
<!-- app/src/main/AndroidManifest.xml (Core) — the ONLY two additions -->
<uses-permission android:name="app.skein.permission.MODEL_TRANSFER" />
<queries><package android:name="app.skein.hub" /></queries>
```

Naming note: the brief wrote `us.skein.permission.MODEL_TRANSFER` "conceptually"; this document uses
`app.skein.permission.MODEL_TRANSFER` to match the namespace already in the tree
(`app.skein.documents`, `app.skein.ipc`).

Scope note (§1.3): `MODEL_TRANSFER` is the Core-visible leg of the `MODEL_DOWNLOAD` capability and
nothing else. It does not cover frontier inference, brain packs or skills, and it must never be
widened to — each of those is its own permission guarding its own entry point, declared by neither app
until the capability ships.

`<queries>` is required: Core targets SDK 37, so without it `resolveActivity` and
`getPackageInfo("app.skein.hub", …)` return nothing and Hub is invisible. It is a **package-visibility
declaration, not a permission** — it grants Core nothing beyond "may see that this package exists".

**Intent and URI shape.** Request, built in Core:

```kotlin
Intent(HubHandoff.ACTION_PICK_ARTIFACT).apply {
    component = ComponentName(HubHandoff.HUB_PACKAGE, HubHandoff.PICKER_CLASS)  // explicit, never implicit
    putExtra(HubHandoff.EXTRA_ACCEPT_FORMATS, arrayOf("gguf"))                   // a filter hint, not a contract
}
```

Result, returned by Hub: `RESULT_OK`, `data = content://app.skein.hub.artifacts/ready/<opaque-id>`,
`flags = FLAG_GRANT_READ_URI_PERMISSION`, plus one `Bundle` of untrusted hints (§3.4). Explicitly
**not** `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` and **not** `FLAG_GRANT_WRITE_URI_PERMISSION` — Core
copies once, within the life of the calling task, and never wants the URI again. This matches the
`DocumentsProvider` posture Core already ships ("no persistable URI permissions", spec §9).

**Grant lifetime and cleanup.** A result-Intent grant is scoped to the receiving *task* and is revoked
by the platform when that task finishes. Core additionally calls
`revokeUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` in the `finally` of the import, so the
window closes at the end of the copy rather than the end of the session. Hub's side: the artifact stays
in Hub's `cacheDir/ready/` until Core reports success (§5.2), at which point Hub deletes it.

**Staging directory ownership.** There is no shared staging directory. Hub's copy lives in Hub's
private storage; Core's copy lives in `filesDir/models/<id>/` and is written by
`ImmutableModelStore.import` through its existing `<file>.tmp` → atomic-rename discipline
(`MODEL_STORE.md` §1). Nothing is staged in a location both apps can see. This is brief §13's "avoid a
permanently shared writable filesystem area", taken literally: the only shared thing is a file
descriptor, open for reading, for the duration of one copy.

**Process death mid-copy.** Two cases, and the existing store already answers both:

- *Core dies mid-copy.* `ImmutableModelStore.import` leaves only `<file>.tmp` inside
  `filesDir/models/<id>/`; nothing was renamed, so no code path can mistake it for a verified file, and
  the next import over the same id removes the whole directory first. The URI grant died with the
  task. The user re-picks. **No new mechanism, and in particular no `BootReceiver` work** — unlike the
  export staging case (`POST_REVIEW_RESOLUTIONS` §4.3), a stranded `.tmp` here is ciphertext-irrelevant
  public model bytes, not plaintext, so the correct treatment is "occupies space until the next import
  or model delete", and §5.2's storage screen surfaces it.
- *Hub dies mid-copy.* Core's `InputStream` over the URI ends short. The copy has therefore written
  fewer bytes than the declared size; §3.2 step 2's size check refuses, the directory is removed, and
  the user retries. A short read can never produce a passing digest.

**Resumption is deliberately absent on the Core side.** Hub owns resumable *downloading* (brief §22);
the Hub→Core copy is local, fast (~20 s for 2.5 GB, the figure `E4.I5` already plans against) and
restartable, so a resumable local copy would be machinery with no failure mode to justify it (YAGNI).

### 2.4 Authenticating the sender — the two calls, in order

Core, before it starts the intent:

```kotlin
// app/src/main/kotlin/app/skein/models/HubPicker.kt  (NEW)
if (pm.checkSignatures(context.packageName, HubHandoff.HUB_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
    return HubAvailability.Unavailable(Reason.SIGNATURE_MISMATCH)   // surfaced, never silently degraded
}
```

`checkSignatures(String, String)` is chosen over `hasSigningCertificate(pkg, cert, CERT_INPUT_SHA256)`
because the relation being asserted is exactly "same signing identity as me", which `checkSignatures`
states directly and which survives a future key rotation of *both* APKs without a hard-coded digest in
the source. The signature permission on Hub's Activity is the belt; this check is the braces — it also
means Core fails closed and *explains itself* when a look-alike `app.skein.hub` is installed, rather
than starting an activity and getting a surprising result.

What this does **not** do, restated because it is the exact confusion brief §14 warns about: a
`SIGNATURE_MATCH` says the bytes came from our own app. It says nothing whatsoever about the GGUF. §3
runs identically for a signature-matched Hub artifact and for a file picked out of Downloads.

---

## 3. The Core-side acceptance pipeline, on existing code

Every step names the file it lives in. "EXISTS" means the code is in the tree today and this design
does not change it.

### 3.1 The pipeline

| # | Step | Where it runs | File | Status |
|---|---|---|---|---|
| 0 | Availability + signature check | `:app` | `app/src/main/kotlin/app/skein/models/HubPicker.kt` | NEW (§2.4) |
| 1 | Receive URI + hint bundle; free-space check (`size × 1.05`) | `:app` | `core/inference/.../models/ModelManager.kt` | `skein-cyq` |
| 2 | Size ceiling + declared-size agreement | `:app` | `core/inference/.../models/ModelManager.kt` | `skein-cyq`, amended (§3.2) |
| 3 | Copy into `filesDir/models/<id>/` while hashing SHA-256 **and** BLAKE3 in one pass; `<file>.tmp` → atomic rename; `0400`/`0500` | `:app` | `core/inference/.../models/ImmutableModelStore.kt` | **EXISTS** |
| 4 | Cross-check Hub's declared digest against Core's computed one | `:app` | `core/inference/.../models/ModelManager.kt` | `skein-cyq`, amended |
| 5 | Structural + metadata inspection of the GGUF | **`:inference` (isolated)** | `inference-service/.../InferenceService.kt` + new `inspect` on `IInferenceService.aidl` | NEW (§3.3) |
| 6 | Synthesise `ModelManifest` v2 from derived facts + quarantined hints | `:app` | `core/inference/.../models/ModelManager.kt` | `skein-cyq`, amended (§3.4) |
| 7 | Seal: register the `StoredModel` with the store | `:app` | `core/inference/.../models/ImmutableModelStore.kt` (`register`) | **EXISTS** |
| 8 | Registry row + provenance columns | `:app` | `core/inference/.../models/ModelRegistry.kt`, migration `009_model_origin.sql` | `skein-cyq` + NEW migration |
| 9 | Tell Hub it may delete its copy | `:app` → Hub | `app/src/main/kotlin/app/skein/models/HubPicker.kt` | NEW (§5.2) |
| 10 | Load: two gates, then `llama_model_load_from_file_ptr` over the pinned fd | `:inference` | `core/verify/.../ModelVerifier.kt`, `inference-service/.../InferenceService.kt` | **EXISTS** |

Steps 3, 7 and 10 are the security-critical ones and **none of them changes**. That is the intended
result: Hub is a new *source* of bytes, not a new *path* into the store.

### 3.2 Size and storage discipline (steps 1, 2)

Three checks, in this order, all before a byte is written:

1. `freeBytes() >= declaredSize * 1.05` — already in `skein-cyq`'s acceptance criteria, unchanged.
2. `declaredSize in 1..MAX_ARTIFACT_BYTES` where `MAX_ARTIFACT_BYTES = 16 GiB` — a new constant in
   `ModelManager.kt`. A declared size outside it refuses before any allocation.
3. During the copy, a running byte counter refuses at `declaredSize + 1` (a stream that is longer than
   it claimed) and refuses at EOF if fewer bytes arrived (a short stream). Neither can be reached by a
   *correct* transfer, and both are cheap.

Check 3 is the storage-exhaustion mitigation: without it, a compromised Hub declares 2 GB and streams
until `filesDir` is full. The stream is also the thing being hashed, so "wrote more than declared" and
"hash mismatch" are distinct, separately-tested refusals.

### 3.3 Inspection inside the isolated process (step 5) — the one new IPC method

Brief §15 and §17 are explicit: model files are untrusted binary input, parsed by native code, and
"keep actual model parsing/loading inside the isolated inference boundary where possible". Plan
`E4.I5` currently specifies the opposite — a pure-Kotlin `GgufMetadataProbe` in `:app` that "reads the
GGUF header (magic, version, KV pairs)". That is an independent, incomplete parser of hostile input,
running in the process that holds the vault. **This design deletes it** (§3.6).

Its replacement is one AIDL method:

```aidl
// core/ipc/src/main/aidl/app/skein/ipc/IInferenceService.aidl   (one added method)
/**
 * Sync. Verifies every fd in `binding` exactly as `load` does, then loads the MODEL ONLY
 * (no llama_context, no KV cache), reads metadata, frees it, and returns the findings.
 * Leaves the service in whatever state it was in. Returns a ModelInspection whose
 * `errorCode` is OK or a refusal code.
 */
ModelInspection inspect(in ManifestBinding binding);
```

```kotlin
// core/ipc/src/main/kotlin/app/skein/ipc/Parcels.kt  (+ ModelInspection.aidl)
@Parcelize data class ModelInspection(
    val errorCode: Int,           // ErrorCode.OK, INVALID_MODEL, HASH_MISMATCH, OOM, SESSION_LOCKED…
    val architecture: String?,    // general.architecture
    val quantization: String?,    // general.file_type, rendered
    val parameterCount: Long,     // 0 when the GGUF does not state it
    val contextLength: Int,       // <arch>.context_length
    val embeddingWidth: Int,      // llama_model_n_embd
    val hasVision: Boolean,       // LlamaNative.modelHasVision
    val hasChatTemplate: Boolean, // tokenizer.chat_template present
    val chatTemplateOk: Boolean,  // llama_chat_apply_template on a 2-message probe succeeded
) : Parcelable
```

Every field is derived by llama.cpp itself, through JNI entry points that **already exist**:
`LlamaNative.modelMeta`, `modelHasVision`, `modelNEmbd`, `applyChatTemplate`. No new native code, no
new parser. Total inline payload is a few hundred bytes — two orders of magnitude inside
`TransportRules.INLINE_BUDGET_BYTES` (32 KiB).

*Why a new method rather than reusing what exists:*

| Option | Why not |
|---|---|
| Reuse `load()` and read metadata via an extended `EngineStatus` | `load()` allocates a `llama_context` and a KV cache sized to `contextLength` — hundreds of MB for a model we have not yet decided to accept, and an OOM during *inspection* would be indistinguishable from an OOM during a real load. It also leaves the engine holding a model the user never asked to load, which collides with warm-swap (`E4.I8`). |
| Keep a pure-Kotlin `GgufMetadataProbe` in `:app`, header only | This is the option being deleted. It is a hand-written parser of attacker-controlled length fields running next to the vault, and brief §17 names exactly this anti-pattern ("rather than inventing an independent incomplete parser"). |
| Add `inspect` to the `InferenceEngine` contract | That contract is locked (`E0.I10`); `skein-1uw`'s own coordinator note already rules that `tokenCount` must not be added to it. Same reasoning applies. |

App-side seam, following the `TokenCounter` precedent exactly (`core/inference/.../TokenCounter.kt`):

```kotlin
// core/inference/src/main/kotlin/app/skein/core/inference/models/ModelInspector.kt   (NEW)
public fun interface ModelInspector {
    public suspend fun inspect(binding: ManifestBinding): ModelInspection
}
```

implemented by `LlamaCppEngine` (`skein-1uw`) over `IInferenceService.inspect`, and consumed by
`ModelManager`. `:app` therefore never opens a GGUF except to copy and hash opaque bytes.

*Structural checks and where each one happens.* Brief §17's candidate list, assigned:

| Check | Where | How |
|---|---|---|
| file-size consistency | `:app` | §3.2 check 3 — cheap, no parsing |
| SHA-256 / BLAKE3 | `:app` (import) and `:inference` (load) | `ImmutableModelStore`, `ModelVerifier` — EXISTS |
| GGUF magic, version, tensor counts, metadata lengths | `:inference` | `llama_model_load_from_file_ptr` refuses → `INVALID_MODEL`. We do not re-implement any of it. |
| supported architecture | `:inference` reports, `:app` decides | `ModelInspection.architecture` compared against an allowlist in `ModelManager` |
| sane context values | `:app` | `ModelInspection.contextLength` clamped to `InferenceConfig.contextLengthCap` (16 384) |
| quantization support | `:inference` | a quant llama.cpp cannot handle fails the load inside `inspect` |
| tokenizer presence, chat-template validity | `:inference` | `hasChatTemplate` / `chatTemplateOk`; the ChatML fallback is `E4.I6` (`skein-5oi`) and is unchanged — a missing template is a warning, not a refusal |

### 3.4 Which manifest fields Core derives, and which are untrusted hints (step 6)

`ModelManifest` v2's schema does **not change**. What changes is who is allowed to fill each field.

| Manifest field | Source | Rule |
|---|---|---|
| `id` | **Core** | Assigned by Core as `<slug-of-name>-<first-12-hex-of-sha256>`. **Never** taken from Hub: `id` is the store directory name (`MODEL_STORE.md` §1), so a Hub-supplied `id` would be Internet metadata steering a privileged filesystem operation — exactly brief §15's prohibition. Re-validated against the schema's `^[a-z0-9][a-z0-9.-]{2,63}$` after slugging. |
| `sha256`, `size_bytes` | **Core** | Computed/observed during the copy (step 3). |
| `blake3` | **Core** | Computed in the same pass; recorded as `models.post_mmap_blake3` (migration 004, `skein-cyq`). |
| `format` | **Core** | `gguf` only when `inspect` returned `OK`. |
| `capabilities` | **Core** | Derived from `ModelInspection`: always `text`; `+vision` iff `hasVision`; `+embedding` iff the GGUF declares a pooling type. |
| `context_length` | **Core** | `min(inspection.contextLength, InferenceConfig.contextLengthCap)`. |
| `name` | *Hub hint* | Display only. Trimmed, NFC-normalised, stripped of control characters and bidi overrides, capped at the schema's 120 chars. Never a file name, never an id. |
| `license.spdx` / `.url` / `.notes` | *Hub hint* | `spdx` accepted only if it matches `^[A-Za-z0-9.+-]{1,64}$`, else `"UNKNOWN"` — the same default `E4.I5` already uses for an unaccompanied pick. Provenance for the audit surface; never consulted at load. |
| `source.url` / `.revision` | *Hub hint* | Recorded verbatim for provenance. The schema's own words: "nothing in the app ever fetches it (no INTERNET permission)." |
| `attestation` | *Hub hint* | Still the soft gate (`MODEL_STORE.md` §4). `E3.I6` verifies it offline against a pinned root or it stays `UNAVAILABLE`. A Hub-supplied attestation can never excuse a digest mismatch, and its absence can never block a load the digest gate allows. |

Hub's declared SHA-256 is **not a manifest field.** It travels as `ArtifactOffer.transferDigest` and
is used once, at step 4, for exactly what brief §16 says it proves: transfer integrity. A mismatch is
reported as `TransferDigestMismatch` — deliberately a *different* outcome from
`ModelVerification.HashMismatch`, because the bytes Core hashed are self-consistent and the only thing
in doubt is whether Hub sent what it meant to.

The hint bundle itself:

```kotlin
// core/model/src/main/kotlin/app/skein/core/model/ModelOrigin.kt   (NEW, pure JVM)
public data class ArtifactOffer(
    val transferDigest: String?,   // Hub's post-download SHA-256, hex, or null
    val declaredSizeBytes: Long,
    val displayName: String?,
    val licenseSpdx: String?,
    val licenseUrl: String?,
    val sourceUrl: String?,
    val sourceRevision: String?,
)
public enum class ModelOrigin { BUNDLED, DOCUMENT_PICKER, HUB }
```

Both live in `:core:model` (pure JVM, no Android plugin, on every classpath) so that Hub's separate
build can compile **the same source file** via a `srcDir` pointing at it — one definition of the wire
vocabulary, and no Gradle dependency edge from Core to Hub or back.

### 3.5 End to end

```
 user taps "Get a model" in Core
   → HubPicker.availability()            [checkSignatures, <queries> visibility]
   → startActivityForResult(explicit PICK_MODEL_ARTIFACT)
   → (user picks inside Hub; Hub has already downloaded, hashed and pre-inspected)
   → RESULT_OK: content:// + FLAG_GRANT_READ_URI_PERMISSION + ArtifactOffer
   → ModelManager.import(ImportSource.HubOffer(uri, offer))
       free-space, ceiling                                      [ModelManager]
       ImmutableModelStore.import(...)   copy + SHA-256 + BLAKE3 + 0400/0500   [EXISTS]
       transferDigest cross-check                               [ModelManager]
       WireBindings.toWire(storedModel)  → ManifestBinding of fds              [EXISTS]
       ModelInspector.inspect(binding)   → :inference, verify → load model → meta → free
       manifest synthesis (derived + quarantined hints)         [ModelManager]
       ImmutableModelStore.register(...)                        [EXISTS]
       ModelRegistry.upsert(...) + origin columns               [ModelRegistry, 009]
       revokeUriPermission(uri); notify Hub it may delete       [HubPicker]
 later, at load time:
   LlamaCppEngine.load → IInferenceService.load → ModelVerifier.verifyPinned
       gate 1 SHA-256 over the pinned channel   (pre-mmap)      [EXISTS]
       gate 2 BLAKE3 over the MappedByteBuffer  (post-mmap)     [EXISTS]
   → llama_model_load_from_file_ptr over the dup'd fd                         [EXISTS]
```

### 3.6 What changes in `skein-cyq` (`E4.I5`), and what does not

**Changes:**

1. **`GgufMetadataProbe` is removed** from the bead's Files list
   (`core/inference/.../models/{…,GgufMetadataProbe,…}.kt`) and from its Interfaces note. Replaced by
   `ModelInspector` + `IInferenceService.inspect` (§3.3). The plan's own rationale for the probe —
   "so `:app` never parses tensors" — was right about the goal and wrong about the means: it still
   parses the header, which is where the hostile length fields are.
2. **`import(uri, manifest?)` becomes `import(source: ImportSource)`**, a sealed interface in
   `ModelManager.kt`: `Bundled(manifest)` (an `app/src/main/assets/models/*.skein.json` shipped and
   CI-validated by `tools/ci/validate-manifests.py`), `Picked(uri)` (the document picker),
   `HubOffer(uri, offer)`. One pipeline, three entry shapes. A `ModelOrigin` is recorded per row.
3. **The destination path in the bead's description is corrected** from
   `filesDir/models/<id>.<ext>` to `filesDir/models/<id>/<file>` — `MODEL_STORE.md` §1's layout, which
   `ImmutableModelStore` already implements. This is pre-existing plan drift, surfaced here because
   this design depends on the directory-per-model shape.
4. **Two new refusals** in `ModelManager`: `ArtifactTooLarge` (§3.2 checks 2–3) and
   `TransferDigestMismatch` (§3.4).
5. **`id` is Core-assigned** for `Picked` and `HubOffer` (§3.4). `Bundled` keeps the manifest's id.
6. **Registry gains provenance columns** via a new migration `009_model_origin.sql`
   (`core/vault/src/main/resources/migrations/`, appended to `INDEX.txt`): `origin TEXT NOT NULL
   DEFAULT 'document_picker'`, `source_url TEXT`, `source_revision TEXT`, `license_spdx TEXT`,
   `display_name TEXT`, `context_length INTEGER`. Migration 004 stays `skein-cyq`'s
   `post_mmap_blake3`; numbers are never reused or renumbered (`ARCHITECTURE.md` §3.3).

**Does not change:** `ImmutableModelStore` (staging, promotion, permissions, locking); `ModelVerifier`
and both gates; `PinnedModelFile` / `PinnedModel`; `ManifestBinding` / `ManifestFileRef` /
`WireBindings`; the `ModelManifest` v2 schema and parser; the `models` table's existing columns; the
`ui_prefs`-stored default; `ModelRegistry`'s `list/get/upsert/delete/setDefault` surface; the rule that
the default model cannot be deleted while loaded; the free-space `× 1.05` check; and **all five of the
bead's acceptance criteria, which remain satisfiable verbatim** (criterion 3's "import without manifest
→ `license.spdx == "UNKNOWN"`" is now `ImportSource.Picked`).

`skein-5oi` (`E4.I6`) changes **not at all**. Chat-template application stays entirely inside
`:inference`; `inspect` merely reports whether a template exists so the model-manager UI can warn
before a load, and the ChatML fallback behaviour is untouched.

---

## 4. The `ModelSource` seam

### 4.1 Where the seam is cut

Brief §7 asks for a `ModelSource` abstraction so "the inference engine should not care where the model
originated". The strongest version of that is not a shared interface — it is that **Core has no
concept of a source at all**. Core has one ingress (`ModelManager.import`), and the origin survives
only as a provenance enum on a row.

So the seam is cut at the process boundary: `ModelSource` and everything upstream of `Artifact` live
**in Hub**. Core's entire share of the abstraction is `ModelOrigin` + `ArtifactOffer` (§3.4). Adding a
`ModelSource` interface to Core with a single implementation that only ever returns "a URI the user
picked" would be a type existing to mirror another process's design — YAGNI, and it would create the
"convenience API that later becomes a vault-exfiltration channel" brief §11 warns about.

### 4.2 The Hub-side interfaces

```kotlin
// hub/core-source/src/main/kotlin/app/skein/hub/source/ModelSource.kt        (NEW, Hub build)
public interface ModelSource {
    public val id: SourceId                                   // SKEIN_CATALOG | HUGGING_FACE | LOCAL_FILE
    public suspend fun search(query: SearchQuery, page: PageToken?): SearchPage
    public suspend fun resolve(ref: ModelRef): ResolvedModel   // repo → files, quants, sizes, gated?
    public suspend fun artifact(selection: ArtifactSelection): ArtifactPlan
}
public data class ArtifactPlan(
    val url: String, val fileName: String, val sizeBytes: Long,
    val upstreamSha256: String?,        // brief §16: compare when the source publishes one
    val requiresAuth: Boolean,
)
```

Three implementations, all in `hub/`:

- **`SkeinCatalogSource`** — reads the signed catalog bundled in Hub's APK (§6). `search` filters
  locally; `resolve` is a lookup; no network for discovery, network only for the download.
- **`HuggingFaceSource`** — HF API search/pagination/GGUF filtering/gated repos. Holds the token
  (Android Keystore, StrongBox where available). Never leaves Hub.
- **`LocalFileSource`** — a SAF pick *inside Hub*, for a user who downloaded a GGUF with a browser and
  wants Hub to hash, inspect and catalogue it. Note this is a convenience, not the networkless path:
  **the networkless path is §5.3 and it does not involve Hub at all.**

### 4.3 The nine stages, and where each one runs

| Stage | Runs in | Artifact of the stage | Implementation |
|---|---|---|---|
| Search | Hub | `SearchPage` | `ModelSource.search` |
| Resolve | Hub | `ResolvedModel` | `ModelSource.resolve` |
| Artifact | Hub | `ArtifactPlan` | `ModelSource.artifact` |
| Download | Hub | file in Hub's private storage | `DownloadManager`, brief §22's state machine |
| — hash | Hub | `transferDigest`; compared to `upstreamSha256` when one exists | Hub |
| — pre-inspect | Hub | a *hint*, never a verdict | Hub may parse cheaply for UX; Core re-does it in `:inference` regardless |
| **Handoff** | **Core → Hub → Core** | `content://` + `ArtifactOffer` | §2 |
| Import | **Core** | `StoredModel` | `ImmutableModelStore.import` |
| Verify | **Core** | SHA-256 + BLAKE3 over what Core wrote | `ImmutableModelStore` / `ModelVerifier` |
| Inspect | **Core, `:inference`** | `ModelInspection` | `IInferenceService.inspect` |
| Register | **Core** | manifest + registry row | `ModelManager` / `ModelRegistry` |
| Benchmark | **Core** | `ModelRuntimeProfile` | §6.3, post-v1 |
| Load | **Core, `:inference`** | two gates, then mmap | `ModelVerifier.verifyPinned` — EXISTS |

The dividing line: **every stage that consumes the network runs in Hub; every stage that produces a
fact Core relies on runs in Core.** Hub's hash and Hub's pre-inspection are the two places where the
same work is done twice on purpose — Hub does it for its own UX, Core does it because Core does not
take Hub's word for anything.

### 4.4 Direction of data flow

One direction only: **Hub → Core, bytes and display hints.** There is no Core → Hub channel in v1 — no
telemetry, no "which models do you have", no device profile export, no download request. Core initiates
a *call*; the only payload that ever crosses is the request's format filter (a constant) and the
response's URI plus hints. §6.3 explains why this holds even for device-aware recommendations.

---

## 5. Storage ownership, the no-duplicate rule, and import without Hub

### 5.1 Ownership (brief §23)

| | Hub storage | Core storage |
|---|---|---|
| Location | Hub's `cacheDir/downloads/`, `cacheDir/ready/` | `filesDir/models/<id>/` |
| Holds | partial downloads, completed-but-untransferred artifacts, catalog cache, thumbnails | accepted models + companions, at `0400` in a `0500` directory |
| Truth about | the network | what may be loaded |
| Readable by the other app | via a one-shot read grant, during one import | **never** |
| Backed up | Hub excludes `cacheDir` (and declares `allowBackup=false`; Hub holds an HF token) | already excluded by `dataExtractionRules` / `backup_rules_legacy` |

### 5.2 No duplicate multi-gigabyte copies

After step 8 succeeds, Core calls Hub's picker once more with
`HubHandoff.ACTION_RELEASE_ARTIFACT` and the same opaque artifact id, and Hub deletes its copy. Three
properties make this safe and honest:

- It is still **Core calling Hub**, never the reverse — I3 holds.
- It carries no information about Core beyond "that transfer completed", which Hub already knew from
  having served the read.
- **It is advisory.** If Core dies before making the call, Hub is left holding a file, and Hub's own
  storage screen lists "transferred, safe to delete" artifacts with an age. Nothing about Core's
  correctness depends on Hub deleting anything, which is the only way a distrustful design can treat a
  request to the untrusted side.

The transient duplicate is unavoidable and bounded: during the copy, the artifact exists in both apps.
Core's free-space check (`× 1.05`) is computed against Core's own volume; Hub additionally refuses to
start a download when free space is below `size × 2.1`, so the handoff has room to complete. Both
numbers belong in one place each and are stated here so they do not drift apart silently.

### 5.3 Import without Hub is the baseline, not the fallback (brief §24)

`external downloader → Android document picker → Core → validate → import` **works today's way,
unchanged, forever.** Concretely:

- Hub is not a dependency of Core in any sense: not a Gradle dependency (separate builds), not a
  manifest `<uses-library>`, not a runtime requirement. `HubPicker.availability()` returning
  `Unavailable` disables one button and nothing else.
- `ImportSource.Picked(uri)` runs §3.1 steps 1–10 with `offer = null`: every derived field is derived
  identically, and the only difference is that there are no hints to quarantine and no transfer digest
  to cross-check.
- The two default models ship as `ImportSource.Bundled` from `app/src/main/assets/models/` with
  CI-validated manifests (`skein-bxk`, `tools/ci/validate-manifests.py`). First-run onboarding
  (`E6.I12`) therefore never touches Hub.
- A test asserts the negative: **`HubUnavailableImportTest` runs the full accept pipeline with the Hub
  package absent** and asserts a successful import. Without it, "Hub is optional" is a claim rather
  than a property.

On GrapheneOS with Hub in a separate profile (§2.2), this *is* the path. It stays first-class.

---

## 6. Curated catalog and device-aware recommendations — schema sketch only

Brief §8's own instruction governs this section: *"Do not overengineer the schema before required.
Design the seam correctly first."* What follows is deliberately thin.

### 6.1 The catalog (brief §19)

A single JSON document shipped inside Hub's APK, sigstore-signed with the same pinned root `E3.I6`
uses, re-fetchable from a pinned URL. Hub, not Core, reads it — Core never sees a catalog.

```jsonc
{ "catalog_version": 1, "generated": "2026-09-22",
  "models": [{
    "catalog_id": "qwen-2.5-3b-instruct-abliterated",
    "tier": "fast",                          // fast | balanced  (advanced = "browse HF", not an entry)
    "display_name": "Qwen 2.5 3B Instruct (abliterated)",
    "license": { "spdx": "…", "url": "…" },
    "artifacts": [{
      "quant": "Q4_K_M", "file": "…​.gguf", "size_bytes": 1234567890,
      "sha256": "…", "source_url": "https://huggingface.co/…", "revision": "<commit>"
    }],
    "validated": { "skein_version": "0.1.0", "measurements_ref": "docs/MEASUREMENTS.md#qwen-q4km" },
    "runtime_profiles": [ /* see 6.2 */ ]
  }]
}
```

`validated` is the load-bearing field and the reason the catalog exists: an entry means *Skein ran this
artifact on real hardware and recorded the numbers*, with a citation into `docs/MEASUREMENTS.md`
(`skein-5hr`). An unvalidated model is not in the catalog; it is reachable only through
"Browse Hugging Face →".

### 6.2 `DeviceProfile` and `ModelRuntimeProfile` (brief §20, §26)

```kotlin
data class DeviceProfile(          // Hub computes this about the device it is running on
    val socModel: String, val abi: String, val totalRamBytes: Long,
    val availableRamBytes: Long, val gpuBackend: String?,   // Build.*, ActivityManager.MemoryInfo
)
data class ModelRuntimeProfile(    // one measured (device-class, model, quant) triple
    val deviceClass: String, val catalogId: String, val quant: String,
    val backend: String, val gpuLayers: Int, val threads: Int, val batch: Int,
    val contextTested: Int, val mmap: Boolean,
    val loadMillis: Long, val ttftMillis: Long,
    val promptTokensPerSecond: Double, val genTokensPerSecond: Double,
    val peakRssBytes: Long, val thermalStateAtEnd: String,
    val provenance: String,        // artifacts/bench/<run>.json — same discipline as MEASUREMENTS.md
)
enum class Fit { EXCELLENT, GOOD, HEAVY, NOT_RECOMMENDED }
```

`ModelRuntimeProfile`'s fields are chosen to be exactly what `tools/m0-benchmark` already emits
(`lib/collect.py aggregate`, `models.yaml`'s pinned-hash discipline, `output/baseline/`'s immutability
rule). The catalog's `runtime_profiles` are generated from that harness's output at Hub build time —
so a recommendation is, traceably, a measurement someone took, not a heuristic. Handoff §3 item 16
applies unchanged: a Q3_K_M smoke run may never populate a formal profile.

### 6.3 How a recommendation is computed, and why Core is not involved

Hub matches its own `DeviceProfile` against the catalog's `runtime_profiles` and renders a `Fit`. Hub
runs on the same physical device as Core, so everything in `DeviceProfile` is readable by Hub directly
from `Build.*` and `ActivityManager.MemoryInfo`. **No Core → Hub channel is needed and none is
created.**

The tempting alternative — Core exports its *own measured* `ModelRuntimeProfile`s so Hub's advice
improves — is rejected for v1 and recorded on the REJECT list (§8). It would be the first byte ever to
flow Core → Hub, it would need a new exported Core surface (undoing §2.2's central property), and the
payload is a fingerprint of what the user runs. If it is ever wanted, it must be a user-initiated,
user-previewed, explicit export of a named profile — never automatic, never on a schedule.

---

## 7. Automatic vs Advanced (brief §25)

**The principle.** Skein ships one set of settings the user never has to understand, and one drawer of
settings that assumes the user understands llama.cpp. A setting is **Automatic** only if Skein can pick
a validated value from `docs/MEASUREMENTS.md` or from the model's own GGUF metadata. Everything else is
**Advanced**, off the default path, and reversible with one "Reset to Automatic". No llama.cpp concept
appears anywhere in the default UI.

**Initial Automatic set** — every value already has a home in this tree:

| Setting | Automatic value | Comes from |
|---|---|---|
| `context_length` | `min(gguf context_length, InferenceConfig.contextLengthCap = 16384)` | `core/inference/.../InferenceConfig.kt`, `ContextBudget` (`E4.I7`) |
| `threads` | `MEASUREMENTS.md` `threads` row | `skein-5hr` |
| `gpu_layers` / backend | `MEASUREMENTS.md` `inference_backend` + `gpu_layers` rows, per model | `skein-5hr`, `skein-9cg` |
| `batch` | `PROMPT_BATCH_TOKENS` | `inference-service/.../InferenceService.kt` |
| `mmap` | always on | `InferenceService.load(useMmap = true)` — load-bearing for the TOCTOU design, see §10 |
| sampling defaults | per-model, persona-overridable | `SamplingDefaults` (`E4.I6`, `skein-5oi`) |
| thermal backoff | measured curve | `ThermalGovernor` (`E4.I9`), `MEASUREMENTS.md` |

**Advanced (v1):** thread count override, GPU-layer override, backend override (CPU ⇄ Vulkan), context
override up to the cap, sampling overrides. That is all.

**Not exposed at all in v1** — on the REJECT list, not the Advanced drawer: `mlock` (pins pages against
an OS that may need them, for a benefit the Fold measurements do not show), KV-cache quantization
types, weight repacking, speculative decoding and draft models (a second model's worth of memory and
an entire second verification pipeline), and model-offload tuning. Each can be reconsidered when a
measurement asks for it.

---

## 8. Non-goals and the REJECT list

**Non-goals for this design.**

- Hub is not a second Skein. It has no vault, no notes, no chat, no RAG, no editor, no inference.
- Hub is not required to run Skein, ever (§5.3).
- This document does not design Hub's UI, its download state machine's implementation, or its HF client
  beyond the seam. Brief §22's state machine is Hub's own bead (§9).
- This document does not add a manifest field, change the AIDL `load` path, or alter either digest gate.

**REJECT list.**

| Rejected | Why |
|---|---|
| Any `INTERNET`, `ACCESS_NETWORK_STATE` or `networkSecurityConfig` in Core, in any variant, for any reason | Spec §2.1; handoff §3 item 1. The entire two-app design exists to avoid needing it. |
| A Core-exported `ContentProvider`, `Service` or `BroadcastReceiver` reachable by Hub | §2.2. Also spec §9's "no exported components". |
| A shared writable directory, an `android:sharedUserId`, or Hub and Core in one APK with two processes | Brief §13; and a shared UID would give Hub the vault's file permissions, destroying I2 by construction. |
| Trusting Hub's SHA-256, size, `id`, `capabilities`, `context_length` or `format` | Brief §10, §15, §16. Hints are display-only (§3.4). |
| A pure-Kotlin GGUF parser in `:app` (`GgufMetadataProbe`) | Brief §17; §3.3. Hostile length fields next to the vault. |
| HF tokens, cookies or any credential crossing into Core | Brief §18. No field exists to carry one. |
| Core → Hub data flow of any kind in v1, including runtime profiles and installed-model lists | §6.3. |
| Hub reading the vault "just for X" — recently-used models, personas, usage stats | Brief §11's "convenience API that later becomes a vault-exfiltration channel". |
| Automatic/background transfers, or any transfer without a user tap | §2.2 property 2; handoff §3 item 12. |
| A model-router, speculative decoding, or draft models in v1 | Spec §3.2 (router is v2, conditional on measurement); §7. |
| React Native, `llama.rn`, or Qualcomm-specific backends in either app | Brief §3, §28. Hub is Kotlin/Compose like Core. |
| Distributing Hub through Play, or bundling GMS in Hub | Spec §10, §2.2. Hub ships the same way Core does. |
| A blanket Hub permission covering future network capabilities, or a Hub-supplied capability-grant record in place of per-capability permissions | §1.3, I6. Installing Hub authorises nothing by itself, and a grant record Core would have to distrust (I4) enforces nothing. |

---

## 9. Proposed bead breakdown (not filed)

Ordering rule from epic `skein-rkrq`: nothing here may precede the v1 ask path
**`skein-1uw` (E4.I4, `LlamaCppEngine`) → `skein-cyq` (E4.I5, `ModelManager`/`ModelRegistry`) →
`skein-6as` (E6.I8, chat surface)**. Tier 0 is the exception and the reason is stated.

**Tier 0 — folded into the ask path, because doing it later means doing `skein-cyq` twice.**

| # | Title | Tier | Depends on | Note |
|---|---|---|---|---|
| H0 | *Amend* `skein-cyq`: drop `GgufMetadataProbe`, adopt `ImportSource`, add the size ceiling and `ArtifactTooLarge`, correct the store path, Core-assign `id` | — | — | A description edit on the existing bead (§3.6), made by the coordinator before `skein-cyq` is claimed. Not a new bead. |
| H1 | `IInferenceService.inspect` + `ModelInspection` Parcelable + service implementation | opus | `skein-nxk` (done) | §3.3. Touches the locked AIDL contract, so it is an additive `E0.I16`-class change and wants the same care. Must land **before** `skein-cyq` is implemented. |
| H2 | `ModelInspector` seam + `LlamaCppEngine.inspect` | sonnet | H1, `skein-1uw` | The `TokenCounter` precedent; folds into `skein-1uw`'s work if it has not started. |

**Tier 1 — after `skein-6as`. The transfer channel.**

| # | Title | Tier | Depends on |
|---|---|---|---|
| H3 | `HubHandoff` vocabulary + `ModelOrigin`/`ArtifactOffer` in `:core:model`; migration `009_model_origin.sql`; `ModelRegistry` provenance columns | sonnet | `skein-cyq` |
| H4 | Core manifest additions (`MODEL_TRANSFER` uses-permission, `<queries>`), `ManifestPolicyTest` + `tools/ci/manifest-audit.sh` updates, `HubPicker` with `checkSignatures` | opus | H3 |
| H5 | `ModelManager` Hub path: `ImportSource.HubOffer`, transfer-digest cross-check, hint quarantine and sanitisation, grant revoke, release call | sonnet | H4, H2 |
| H6 | `HubUnavailableImportTest` + the negative suite: absent Hub, signature-mismatched look-alike, revoked grant mid-copy, short stream, over-long stream, hostile hints (path separators in `name`, 10 MB `notes`, bidi overrides), a non-GGUF artifact | sonnet | H5 |

**Tier 2 — the Hub application. Parallel to Tier 1 after H3 fixes the vocabulary.**

| # | Title | Tier | Depends on |
|---|---|---|---|
| H7 | Hub Gradle build skeleton: separate `settings.gradle.kts` under `hub/`, same signing config, reproducible-build recipe, `srcDir` onto `core/model`'s handoff sources, its own manifest/dependency guards (Hub bans GMS too) | opus | H3 |
| H8 | `ModelSource` + `SkeinCatalogSource` over the signed bundled catalog | sonnet | H7 |
| H9 | `HuggingFaceSource`: search, pagination, GGUF filtering, gated repos; token in Keystore/StrongBox; token never logged, exported or clipboard-copied | opus | H7 |
| H10 | Download manager: brief §22's state machine + interrupt states; process death, sleep, network change, partial file, duplicate, renamed upstream artifact | opus | H7 |
| H11 | `ArtifactPickerActivity` + Hub `FileProvider` (read-only, `ready/` only), release-artifact handling, Hub storage screen | sonnet | H10, H4 |
| H12 | Hub packaging: F-Droid metadata, reproducible build recipe, `docs/REPRODUCIBLE_BUILDS.md` second recipe, release SHA-256 publication | sonnet | H7 |

**Tier 3 — catalog quality and recommendations. After the channel works end to end.**

| # | Title | Tier | Depends on |
|---|---|---|---|
| H13 | Catalog schema v1 + sigstore signing + the generator that reads `tools/m0-benchmark` output | sonnet | H8, `skein-5hr` |
| H14 | `DeviceProfile` / `ModelRuntimeProfile` / `Fit` in Hub; "Recommended for this device" | sonnet | H13 |
| H15 | Automatic vs Advanced settings split in Core's model-manager screen (`E6.I13`, `skein-ym3`) | sonnet | `skein-ym3`, H5 |
| H16 | `docs/MODELS.md` (`E9.I6`, `skein-wvw`) gains the Hub acquisition path beside the manual one | haiku | H5 |

**Deferred, filed as such:** benchmark-driven `ModelRuntimeProfile` learned *on the user's device*
(brief §26's "Apply Recommended"); `LANModelSource` / `EnterpriseModelSource` (brief §7's "potential
future"); any Core → Hub channel (§6.3).

---

## 10. Threat table

| Attacker | What they try | What this design provides |
|---|---|---|
| **Malicious app impersonating Hub** | Install `app.skein.hub`-alike; answer Core's picker intent; hand Core a crafted GGUF or harvest the user's downloads | Core resolves an **explicit `ComponentName`** and calls `checkSignatures(self, app.skein.hub) == SIGNATURE_MATCH` **before** starting the intent (§2.4); a different signer fails closed with a visible reason. Hub's picker is itself guarded by `app.skein.permission.MODEL_TRANSFER` (`signature`), so an unrelated app cannot invoke Hub either. **And if both were bypassed, the outcome is the "malicious GGUF" row** — because nothing downstream of the handoff trusts the sender. No exported Core component exists for the impostor to reach in the first place (§2.2). |
| **Compromised Hub** (our own signed app, taken over) | Exfiltrate vault data; make Core load a hostile model; command Core | Cannot read the vault: no Core surface is exported to Hub, and `VaultDocumentsProvider` is `MANAGE_DOCUMENTS`-guarded so same-signature buys nothing (I2). Cannot command Core: Core is always the initiator and Hub has no way to start, wake or message Core (I3). Cannot certify a model: `id`, digests, size, format, capabilities and context are all Core-derived (§3.4); a lying `transferDigest` yields `TransferDigestMismatch`; a hostile `name`/`license`/`source` is display-only and sanitised. Can at most offer bad bytes → next row. |
| **Malicious GGUF** (crafted parser exploit) | Reach vault data or Android permissions through llama.cpp | Parsed **only** inside `:inference`: `isolatedProcess="true"`, no permissions, no filesystem, no sockets, holding nothing but the dup'd model fd and one Binder connection (spec §2.6, §9; `ARCHITECTURE.md` §1). `inspect` (§3.3) runs in that same process, so even the *acceptance decision* is made without `:app` parsing a byte. `:app` only ever copies and hashes opaque bytes. Blast radius of a full llama.cpp RCE is a process with no data and no capabilities. `IsolatedSessionGate` additionally refuses every call while the vault is locked. |
| **TOCTOU between Core's hash and mmap** | Swap or rewrite the bytes after verification, before or during the mapping | Unchanged by this design and load-bearing for it. Gate 1 streams SHA-256 over the **open channel the shared lock is held on**, never a re-opened path; gate 2 re-digests the `MappedByteBuffer` itself with BLAKE3-256, so a write landing *between* the gates reports `Tampered`, distinct from `HashMismatch` (`MODEL_STORE.md` §3). `PinnedModelFile` makes "never resolve by path again" structural, and the service hands llama.cpp the **descriptor**, not `/proc/self/fd/<n>` (`skein-lnp2`, `skein-nxk`). Hub-specific addition: Core hashes **the bytes it wrote into its own store**, in the same single pass that writes them — never a separate pass over Hub's `content://` stream, which Hub could serve differently twice. |
| **Storage exhaustion** | Declare 2 GB, stream until `filesDir` is full; or fill the device with abandoned staging files | Pre-copy: free-space `≥ size × 1.05` and a `16 GiB` hard ceiling (§3.2). During the copy: a running counter refuses at `declaredSize + 1`, and a short stream refuses at EOF — so the write is bounded by a number checked against free space **before** it started. On refusal, `ImmutableModelStore` removes the whole `<model-id>` directory, leaving nothing. Hub side: refuses a download below `size × 2.1` free (§5.2) and its artifacts live in `cacheDir`, which the OS may reclaim under pressure. |

Two residual risks, stated rather than hidden:

- A same-signature compromised Hub can still cause the *user* to accept a bad model, because a user who
  reaches the picker intends to install something. The mitigations are the curated catalog's
  `validated` field (§6.1), sigstore attestation for models that carry it (`E3.I6`), and the fact that
  the worst outcome is a compromised `:inference` process.
- Physical/root attackers and a compromised OS remain explicitly out of scope (spec §9), and
  `MODEL_STORE.md` §2 is already blunt about what `filesDir`, `0400` and advisory locks do not stop.

---

## 11. Proposed wording (coordinator applies; this document does not edit those files)

### 11.1 For `docs/ARCHITECTURE.md` — proposed, as a new §1.2 after the startup sequence

```markdown
### 1.2 The second APK: Skein Hub (designed, not implemented)

Network-side model acquisition lives in a **separate application**, `app.skein.hub`, built from its
own Gradle build under `hub/` and signed with the same key. Hub holds `INTERNET`; Core
(`app.skein`) holds none, in any variant, forever (spec §2.1). Design: `docs/design/SKEIN_HUB.md`.

Four properties are structural rather than conventional, and each has an enforcement point:

- **Core exports nothing to Hub.** The exported-component set stays `{MainActivity,
  VaultDocumentsProvider, SystemJobService}` — `ManifestPolicyTest`, `tools/ci/manifest-audit.sh`.
- **Core initiates every transfer.** Core starts an explicit `startActivityForResult` at Hub's picker
  and receives a one-shot, read-only, non-persistable `content://` grant. Hub cannot start, wake or
  message Core.
- **Core distrusts the artifact.** Core copies the bytes into `filesDir/models/<id>/` while hashing
  them, cross-checks Hub's digest for transfer integrity only, and derives every load-bearing manifest
  field itself. Hub's `name`/`license`/`source` are display hints.
- **The GGUF is parsed only in `:inference`.** `IInferenceService.inspect` runs the structural and
  metadata inspection inside the isolated process; `:app` never parses a model file.

Hub is optional. `external downloader → document picker → Core → validate → import` is the baseline
path and is asserted by a test that runs with Hub absent.
```

### 11.2 For the spec's §2 — proposed, as a new non-negotiable 11

```markdown
11. **Network-side model acquisition lives in a separate application** (Skein Hub, `app.skein.hub`),
    which is the only Skein APK that may ever hold `INTERNET`. Skein Core exports no component to it,
    never receives a command from it, and treats every artifact it supplies as untrusted binary input:
    Core re-hashes what it writes, inspects it only inside the isolated inference process, and derives
    every load-bearing manifest field itself. A signature-level permission authenticates the sender and
    never the file. Hub never reads the vault, and Hub is optional — import via the system document
    picker is the baseline path and keeps working with Hub absent.
```

A one-line addition is also proposed for §3.1's v1 scope list, since Hub itself is **not** v1:

```markdown
- Model acquisition in v1 is the document picker plus two bundled defaults; Skein Hub
  (`docs/design/SKEIN_HUB.md`) is designed but ships after v1.
```

---

## 12. Reconciliation hook — `docs/research/POCKETPAL_RECON.md`

As of this document's commit, `docs/research/POCKETPAL_RECON.md` (bead `skein-e8ly`) **does not exist
on `origin/main`**; it was still being written. Nothing here depends on it, and nothing here should be
taken as having reconciled with it.

Reconciliation is owed on exactly five points, and whoever lands the recon document (or the first Hub
bead after it lands, whichever is sooner) should close them out by editing the sections named:

1. **§4.2, `HuggingFaceSource`** — PocketPal's HF request construction, pagination, GGUF filtering and
   gated-repo error taxonomy (brief §6, §18). Expect PORT-classified findings to replace the sketch.
2. **§9 H10, the download manager** — PocketPal's real-world interrupt states and the issues its users
   filed (brief §22). Expect the state list to grow; if it does, H10's acceptance criteria grow with
   it, not this document's §4.
3. **§3.3's structural-check table** — PocketPal's GGUF load-failure handling (brief §17). If it
   checks something llama.cpp does not, the row belongs in `inspect`, in `:inference`, never in `:app`.
4. **§6.2's `ModelRuntimeProfile` fields** — PocketPal's benchmark methodology (brief §27). Skein's own
   `tools/m0-benchmark` output stays authoritative for field *shape*; PocketPal may add a metric.
5. **§7's Automatic/Advanced split** — PocketPal's settings UX inventory (brief §25). If a knob we
   rejected turns out to carry a measured benefit, it moves to Advanced with the measurement cited.

If the recon document contradicts a **decision** in §§1–3 (the trust model, the handoff mechanism, the
acceptance pipeline), the decision wins and the contradiction is escalated to the owner — those
sections answer to spec §2 and §9, not to a donor project.
