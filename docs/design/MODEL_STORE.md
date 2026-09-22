# Immutable Model Store and the Dual-Hash Load Gate

**Status:** Implemented (`:core:inference`, package `app.skein.core.inference.models`)
**Date:** 2026-09-21
**Implements:** `docs/design/POST_REVIEW_RESOLUTIONS.md` §2 — bead `skein-st1r`
**Affects:** `E3.I5` (`ModelVerifier`), `E4.I5` (`ModelManager.import`),
`E0.I15`/`E0.I16` (manifest + AIDL contract), `E10.I16` (TOCTOU test),
`E5.I1` (embedder load flow)

This document records the on-disk layout, the enforcement each layer actually
provides, and the two digest gates a model must pass before any byte of it
reaches a native engine. It is the implementation counterpart of
POST_REVIEW_RESOLUTIONS §2; that section is the decision, this one is the
shape it took.

## 1. Layout

```
<filesDir>/models/                     store root, owned by :app
└── <model-id>/                        one directory per imported model, 0500
    ├── model.gguf                     the mmap'd main file,             0400
    ├── tokenizer.json                 companion,                        0400
    ├── config.json                    companion,                        0400
    └── LICENSE                        companion (provenance, not loaded) 0400
```

Rules:

- Names are plain file names. `ModelManifest.parse` refuses any entry whose
  `file` contains a separator, a `..`, or a NUL, so nothing in the manifest can
  address a path outside its own model directory.
- A directory holds exactly the files its manifest covers. `ManifestBinding`
  refuses a binding when the directory holds anything else
  (`ModelVerification.UncoveredFile`), so an extra `tokenizer.json` dropped
  beside a verified model cannot be picked up by a runtime path.
- No metadata file lives in the directory — a manifest or state file beside the
  model would itself be an uncovered file. Store metadata (the manifest, the
  recorded BLAKE3) belongs in the `models` table.

Imports are staged as `<file>.tmp` in the same directory, verified while
streaming, then promoted with an atomic rename. A refused import removes the
whole `<model-id>` directory, so a failed import never leaves a partially
written file that a later code path could mistake for a verified one.

## 2. What each protection layer does and does not do

Stated plainly, because the external review's finding was precisely that these
were being over-claimed.

| Layer | Keeps out | Does **not** keep out |
| --- | --- | --- |
| `filesDir` (app-private) | other apps, other Android users | root, custom recovery, an extracted image, `adb` on a debuggable build |
| `0400` file / `0500` directory | our own code paths (an `openat(O_WRONLY)` fails `EACCES`) | the owning UID, which can `chmod` back — it is a guardrail, not a capability boundary |
| shared `FileChannel` lock held for the model's lifetime | cooperating writers (every writer in this codebase), concurrent re-import and delete (`ModelVerification.InUse`) | a hostile writer: POSIX file locks are **advisory** |

Because none of the three is airtight, detection does not depend on any of them
holding — that is what §3's second gate is for.

Permission enforcement is reported rather than assumed:
`StoredModel.permissionEnforcement` is `POSIX` when
`Files.setPosixFilePermissions` applied the mode, and `FILE_API_FALLBACK` on a
filesystem with no POSIX attribute view (where the store falls back to
`File.setWritable(false)`). The JVM tests assert the mode bits they can;
`ImmutableModelStoreInstrumentedTest` re-asserts them with `Os.stat` on a real
`filesDir`, and asserts that `Os.open(O_WRONLY)` from our own UID returns
`EACCES`.

## 3. The two gates

Every load runs both, in this order, over the same model:

1. **Pre-mmap — SHA-256.** Streamed over the *open channel* the shared lock is
   held on, not over a fresh open of the path: re-opening would reintroduce the
   swap-the-file race that handing `/proc/self/fd/<n>` to llama.cpp exists to
   close. Every companion is hashed too, by path, on **every load** — not only
   at import. A mismatch is `ModelVerification.HashMismatch(role, SHA256)`.
2. **Post-mmap — BLAKE3-256.** Computed over the `MappedByteBuffer` itself,
   i.e. over the very bytes about to be handed to the engine. A mismatch is
   `ModelVerification.Tampered(role)` — deliberately a different outcome from
   gate 1, because the same path passed SHA-256 seconds earlier, so the bytes
   changed *between the gates*.

Two different algorithms rather than SHA-256 twice: a second pass with the same
`MessageDigest` shares every bug the first one has, and BLAKE3 is faster than
SHA-256 on aarch64, so the second pass is close to free at load time. BLAKE3 is
hand-rolled (`Blake3.kt`, unkeyed mode only, ~300 lines) for the same reason
`Hkdf.kt` is: no third-party crypto. It is pinned to all 35 official reference
vectors, extended output included.

Where the expected BLAKE3 comes from:

- a shipped default-model manifest declares `blake3` next to `sha256`, so the
  post-mmap expectation exists before anything is mapped;
- a user-imported model has no such declaration, so the store computes BLAKE3
  in the same streaming pass that checks SHA-256 and records it
  (`StoredFile.blake3`, persisted as `models.post_mmap_blake3`, migration 004).

When a manifest declares both, import checks both, so a manifest whose two
digests disagree about the same bytes is refused rather than silently resolved.

### The attack this closes

> An actor with write access modifies the file in place after the hash check
> and before/while the mapping is read.

`LoadPhaseHook` exposes the two phase boundaries as overridable no-ops so the
tests can put that actor exactly there and drive the real
`ModelVerifier.verifyForLoad` rather than a copy of it. Production always passes
`LoadPhaseHook.None`; the hook can influence timing, never the verdict. Both
variants — write before the map, and write while the region is mapped — are
detected as `Tampered`, on the JVM and on device.

## 4. Content trust and origin trust are separate gates

- **Content trust** (do the bytes equal what the manifest asserts?) is the
  **hard** gate: `ModelVerifier`. A failure refuses the load, always.
- **Origin trust** (did someone we trust sign this manifest?) is the **soft**
  gate: `ManifestAttestation` → `AttestationStatus`. It is a badge in the model
  manager and an input to policy ("the default-model slot requires `VERIFIED`"),
  never a precondition inside `ModelVerifier`. Implementation lands with
  `E3.I6` (sigstore, offline, pinned trust root); until then every model is
  `UNAVAILABLE`.

The separation is structural, not conventional: `ModelVerifier` and
`ImmutableModelStore` do not reference `ManifestAttestation`, and
`AttestationStatus` never appears in `ModelVerification` or `BoundFile`. So a
degraded attestation cannot weaken the digest gate, and a verified one cannot
excuse a digest mismatch. `ManifestAttestationTest` asserts this by reflection
so a future refactor cannot quietly reconnect them.

## 5. Logging

Refusals carry a log-safe `summary` that names roles, digest algorithms and
model ids only. Filesystem paths, the expected digest and the observed digest
stay in the typed fields for the callers that legitimately need them (tests,
the model-manager UI) and are never formatted into a `SkeinLog` line
(spec §9, `docs/PRIVACY.md`).

## 6. Not yet wired

This bead landed the store, the manifest, the binding and the verifier as
pure-JVM types with typed results. Still open, and filed separately:

- `E0.I16` — the `ManifestBinding` Parcelable in `:core:ipc` plus the
  `LoadRequest` / `EmbedderLoadRequest` fields that carry it across Binder.
- `E4.I5` — `ModelManager.import` calling this store from the SAF import flow,
  and migration 004's `models.post_mmap_blake3` column.
- `E3.I6` — the sigstore `ManifestAttestation` implementation.
- `skein-k3b2` — running `ImmutableModelStoreInstrumentedTest` on a device; it
  compiles unconditionally today.

**Update 2026-09-21 (`skein-3v9` / `E0.I15`, decision `skein-cqiu`).** The
manifest this document describes is now the *only* manifest shape: plan §4.8
had separately described a v1 document, and the two were mutually
unsatisfiable. `ModelManifest` keeps its place in `:core:inference` and gained
§4.8's catalog fields (`name`, `format`, `capabilities`, `context_length`,
`source`), a `license` **object** with a required `spdx` (it was a bare string
here), and an `attestation` object with `covers` (it was a bare
`attestation_url` string). The normative schema is
`core/model/src/main/resources/schema/model-manifest.schema.json`, and
`tools/ci/validate-manifests.py` enforces §3's rule that a *shipped* default
manifest must declare `blake3` while an imported model need not.

**Update 2026-09-21 (`skein-v2s` / `E3.I5`).** The two gates gained the last
two properties plan `E3.I5` asked for, and the fd half of the discipline
became a type.

*Cancellable.* Both gates poll a `VerifyCancellation` at the top of their read
loop, once per 4 MiB chunk, so an `unload` arriving mid-verify costs at most
the read already in flight rather than the ~10 s a 2.5 GB model takes to hash.
A cancelled pass is `ModelVerification.Cancelled(role)` — deliberately not a
`HashMismatch`, because a cancelled digest proves nothing about the bytes in
either direction and "this model is tampered" is not the right thing to tell a
user who backgrounded the app. `VerifyProgress` reports cumulative bytes for
`EngineStatus.state = "verifying"`; each gate counts its own pass, since the
two read the same bytes twice.

*Pinned by descriptor.* `PinnedModelFile` is the `dup`'d descriptor the loader
was handed, made into a type so "never resolve the model by path again" is
structural rather than a comment: it exposes a `FileChannel` and a
`/proc/self/fd/<n>` `enginePath`, and no path to re-open by accident. The
`dup` itself is supplied by the caller (`DescriptorDup`), because
`ParcelFileDescriptor` lives in `android.os` and a `java.io.FileDescriptor`
will not report its own number — that keeps the type pure-JVM and
JVM-testable. `PinnedModel` groups the main descriptor with any companion that
arrived as an fd; a companion that did not falls back to its store path, which
is right for the app-side loader (shared lock, `0500` directory) and wrong for
a service that was handed descriptors it could pin instead.
`ModelVerifier.verifyPinned` runs both gates over those descriptors and closes
them all on refusal, so a model that failed either gate has no fd left to hand
to `llama_model_load_from_file` even if the caller ignores the result.

`ModelVerifierCancellationTest` measures promptness through the progress
callback rather than the clock, `PinnedModelFileTest` renames a different file
over a verified path and asserts the pin still reads the verified bytes (the
JVM half of `E10.I16`; `skein-d4o3` owns the device-lane version with a real
`ParcelFileDescriptor` and a real `/proc` open), and
`ModelVerifierLargeFixtureTest` checks the chunked readers against an
independent `MessageDigest` pass over a generated 100 MB fixture.

Still not wired: the service side. `skein-nxk` (`E4.I3`) and `skein-lbw`
(`E5.I1`) own the `dup` of the received `ParcelFileDescriptor`, the
`ModelVerification.Refusal → ErrorCode` mapping (`HASH_MISMATCH`,
`HASH_MISMATCH_POST_MMAP`, `COMPANION_HASH_MISMATCH`, `MODEL_IN_USE`, and
`CANCELLED` for the new `Cancelled`) and the `EngineStatus` progress
reporting. `:core:inference` deliberately does not depend on `:core:ipc`, so
none of that mapping lives here.
