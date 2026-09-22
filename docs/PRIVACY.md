# Privacy

This document is the authoritative, factual description of how Skein handles
data. It is referenced from `README.md`, design spec
`docs/superpowers/specs/2026-09-19-skein-design.md` §2, `SECURITY.md`, and
(once built) the in-app "About" screen and store listings. It describes
mechanisms, not intentions. Where a claim is not yet true of the code in
`main`, that is stated explicitly rather than implied.

## 0. The one-line promise

Skein's data stays on your device. There is no cloud, no analytics, no
telemetry, and no `INTERNET` permission in the manifest.

## 0.1 Status of this document and the build it describes

Skein is pre-alpha (see `README.md`). This document describes the **v1
architecture as specified** in the design spec and its design-resolution
addenda, which is what governs implementation and what the project commits
to shipping. Most of the substrate that architecture depends on — the vault
database layer, the isolated inference/embedder engines, model verification,
citation stability, the export provider — is under active construction; the
corresponding Gradle modules (`core/vault`, `core/security`, `core/ipc`,
`core/rag`, `core/export`) currently contain only placeholder Kotlin files.
A smaller set of mechanisms is already enforced in `main` today and
independently checkable right now. Every claim below is tagged:

- **[shipped]** — true of `main` today, checkable by reading the source or
  running a build task named below.
- **[v1 design, in progress]** — locked in the design spec / design
  resolutions, tracked as an open `bd` issue, not yet landed in `main`.
- **[v2 roadmap]** — not part of v1 at all; see `docs/ROADMAP.md`.

Nothing below is aspirational marketing; a `[v1 design, in progress]` tag
means the mechanism is specified precisely enough to implement and review,
not that it currently runs on a device.

Currently **[shipped]**: the manifest permission baseline (no `INTERNET`),
the `checkManifestGuards` Gradle task and its denylist, the backup exclusion
XML files, the `FLAG_SECURE` window flag on the main activity, dependency
checksum verification, and the license/attribution audit. These are cited by
file path throughout so they can be checked directly.

## 1. What data Skein handles

Skein is a single-vault, single-device personal knowledge system. Everything
below is a *kind of content the user puts into or gets out of the vault* —
none of it is collected by Skein about the user; it is the user's own data
that Skein stores so the user can retrieve it later.

| Data | Where it lives | At rest | In backup | In RAM |
|---|---|---|---|---|
| Notes (Markdown body + frontmatter) | `documents` table, `vault.db`, app-private storage | SQLCipher page-level encryption, StrongBox-wrapped key **[v1 design, in progress — `core/vault` is a placeholder today]** | Excluded (§7) | Plaintext while the vault is unlocked and the row is in a Kotlin object; no separate plaintext cache file |
| Chats (messages, roles, timestamps) | `messages` table, `vault.db` | Same as notes | Excluded | Plaintext while unlocked, same as above |
| Retrieved chunks / citation records | `messages.retrieved_chunks` JSON, `chunks` / `document_revisions` tables | Same as notes; citation records carry `(document_id, revision_hash, locator, excerpt)` so a citation stays traceable to the exact text it was drawn from, even after the source note is edited (design: `docs/design/POST_REVIEW_RESOLUTIONS.md` §1; tracked as `skein-uo5n`) **[v1 design, in progress]**. The `excerpt` inside a citation record is a second, independent copy of the cited text, retained alongside the message that cites it — deleting or editing away the source document does **not** scrub it (known gap, `skein-koda`; a "purge history for this note" action is a tracked follow-up, not yet built) | Excluded | Plaintext while unlocked |
| Model outputs (assistant messages) | `messages` table, `role='assistant'` | Same as chats | Excluded | Plaintext while unlocked; never logged (release builds strip verbose logging per spec §9) |
| Embeddings | `chunks_vec` virtual table (`sqlite-vec`, int8[256]) | Same as notes | Excluded | In-process only during retrieval |
| Vault attachments (files, images, PDFs) | `attachments/<uuidv7>` blob directory, referenced by `documents` rows with `kind='attachment'` | One immutable plaintext per attachment UUID, written once; deterministic per-attachment key and IV derived from the UUID so no key/IV pair is ever reused (design: `docs/design/POST_REVIEW_RESOLUTIONS.md`, decision `skein-wa1l`) **[v1 design, in progress]** | Excluded (`file/attachments/`) | Decrypted bytes exist only transiently during read/decode |
| Persona system prompts | `personas` table, `vault.db` | Same as notes | Excluded | Plaintext while unlocked |
| Model files (GGUF / ONNX weights) | `filesDir/models/<id>/`, app-private, read-only after import (`chmod 500`/`400`) **[v1 design, in progress]** | Not user content; not secret, but app-private and hash-pinned (§6) | Excluded (`file/models/`) | `mmap`'d read-only into the isolated `:inference`/`:embedder` process |
| Chat/document history (edit history, revisions) | `document_revisions` table (content-addressed by BLAKE3-256 revision hash) **[v1 design, in progress]** | Same as notes. **[shipped, `skein-a2yr`]** A superseded revision is retained only as long as it is still cited from a chat, or is the document's current revision; `VaultRepository.sweepUnreferencedRevisions()` removes the rest at most once per unlocked session (`docs/VAULT_FORMAT.md`'s Retention section) — it does not wait indefinitely, but it also does not purge on demand | Excluded | Plaintext while unlocked |
| Tags | `documents.frontmatter` JSON (`tags:` array) | Same as notes | Excluded | Plaintext while unlocked |
| Wikilinks | `edges` table (`kind='wikilink'`), resolved from `[[...]]` syntax in `body_md` | Same as notes | Excluded | Plaintext while unlocked |

Two structural facts apply to every row above:

- **All of it is app-private storage** (spec §2, principle 9). Other apps
  cannot read any of these paths without root; the only sanctioned outward
  path is the `DocumentsProvider` (§5) or an explicit user-initiated export
  (§4, flow 3).
- **Encryption keys are StrongBox-backed and biometric-gated** in the v1
  design: the SQLCipher passphrase is derived from a hardware-backed key
  that is only unwrapped after biometric authentication and held in memory
  only while the vault is unlocked (spec §5, §9). This key-wrapping layer is
  **[v1 design, in progress]** — not yet implemented in `core/security`.

## 2. What Skein does NOT collect

None of the following exist in Skein, in any form, opt-in or otherwise:

- Crash reports
- Usage analytics
- Model outputs sent anywhere off-device
- Chat contents sent anywhere off-device
- Error/diagnostic logs shipped to a server
- Feature-usage telemetry
- Install-source tracking (referrer IDs, attribution SDKs)
- Advertising or analytics identifiers of any kind

There is no telemetry pipeline to disable, because none exists in the
source tree — there is no networking client library, no analytics SDK
dependency, and no `INTERNET` permission for any such pipeline to use even
if one were added by mistake. This is enforced two ways, both **[shipped]**:

1. `app/src/main/AndroidManifest.xml` declares exactly two permissions,
   `POST_NOTIFICATIONS` and `USE_BIOMETRIC`; neither grants network access.
2. `build-logic/guards/src/main/kotlin/app/skein/gradle/ManifestGuardPlugin.kt`
   registers a `checkManifestGuards` Gradle task (and a per-variant
   `checkManifestGuards<Variant>` task) whose denylist includes `INTERNET`
   and `ACCESS_NETWORK_STATE`. If any dependency's merged manifest — direct
   or transitive — introduces either permission, the build fails. This is
   how a Play Services transitive dependency or a debug-only library
   (`androidx.compose.ui:ui-tooling` is a real example the project has hit)
   is caught before it reaches a release build. `app/src/test/kotlin/app/skein/ManifestPolicyTest.kt`
   asserts the final merged manifest's permission set directly.

## 3. Data flow diagrams

All three flows are internal to the app process tree; none crosses a
network boundary because there is no network boundary to cross.

### 3.1 User creates a note

```
Editor (Compose UI, :app process)
      │  user types / saves
      ▼
VaultRepository.upsertNote()      [v1 design, in progress — core/vault]
      │  canonicalize body + frontmatter, compute revision hash
      ▼
SQLCipher (vault.db, app-private storage)
      │
      └── nothing leaves app-private storage
```

No network call is possible at any step — the process has no `INTERNET`
permission (§2) — and no other app can observe this flow; `vault.db` is not
exported.

### 3.2 User asks the AI

```
Chat surface (:app)
      │  query text
      ▼
RetrievalService.retrieveContext()        [v1 design — spec §7.2]
      │  vector (sqlite-vec) + BM25 (FTS5) + Personalized PageRank
      │  each hit resolves to (document_id, revision_hash, locator, excerpt)
      ▼
PromptAssembler                            [v1 design — spec §7.3]
      │  persona.system_prompt + recent turns + numbered [N] retrieved context
      │  retrieved text travels as DATA, never as instructions to the model
      ▼
IInferenceService.generate()  ──AIDL/Binder──▶  :inference (isolated process)
      │                                              │  android:isolatedProcess="true"
      │                                              │  no Android permissions at all
      │                                              │  model mmap'd read-only, hash-verified (§6)
      │  ◀── streamed tokens (onTokens callback) ────┘
      ▼
Chat surface renders tokens; citation markers [N] resolve against the
recorded (document_id, revision_hash) tuple. If the cited note has since
changed, the UI shows a "source changed" badge instead of silently
re-pointing the citation at different text (design:
`docs/design/POST_REVIEW_RESOLUTIONS.md` §1; tracked as `skein-uo5n`,
**[v1 design, in progress]**).
```

The isolated `:inference` process cannot open arbitrary files, open sockets,
or bind services beyond its single Binder connection back to `:app` (spec
§9). A crafted GGUF exploiting a parser bug is contained to that process.
This process isolation boundary itself is **[shipped]** at the manifest
level (`android:isolatedProcess="true"` on both `InferenceService` and
`EmbedderService` in `AndroidManifest.xml`); the inference engine that runs
inside it is **[v1 design, in progress]** (`inference-service` currently
contains a stub service and a placeholder; llama.cpp integration is queued,
per `NOTICE`).

### 3.3 User exports a note

```
Editor / chat surface — user taps "Export" or "Share"
      │
      ├─ Share as text/clipboard ─────────────▶ Android share sheet (§5)
      │                                          (user picks the destination app)
      │
      └─ Export as file (MD / PDF / DOCX)
             │
             ▼
      ACTION_CREATE_DOCUMENT (system file picker)   [v1 design]
             │  user chooses a save location outside Skein's storage
             ▼
      VaultRepository streams plaintext directly to the user-chosen URI
             (no on-disk staging copy for this path)

      Internal-only exception: PDF export via android.print.PrintManager
      spools a plaintext file the OS manages, and any other internal export
      staging lands in cache/staging_export/ (app-private cache), which is:
        - excluded from all backups (§7)
        - swept by a WorkManager job 10 minutes after creation
        - swept immediately on vault lock
        - re-swept on BOOT_COMPLETED if the device never woke in time
      (design: docs/design/POST_REVIEW_RESOLUTIONS.md §4, tracked as
      skein-7ki2) [v1 design, in progress — no code yet in core/export]
```

In every export path, the user makes an explicit, visible gesture (tap
Export/Share, then pick a destination) before any byte leaves Skein's
storage. Nothing exports automatically or in the background.

## 4. Third-party surfaces

Skein integrates with exactly four Android system surfaces. None of them
gives a third party visibility into vault content the user did not
explicitly hand over or share.

- **Storage Access Framework (SAF).** Used for two things: importing a
  model file or a document (the user picks a file; Skein reads it once) and
  `ACTION_CREATE_DOCUMENT` exports (the user picks a save location; Skein
  writes to it). SAF sees only the specific file the user picked in the
  system picker UI — it has no standing access to the vault.
- **Android biometric prompt (`BiometricPrompt`/`USE_BIOMETRIC`).** Used to
  gate unwrapping the StrongBox-backed vault key. The prompt is rendered by
  the OS in a separate, trusted UI surface; Skein receives only a
  success/failure/error callback, never biometric data itself.
- **Android share sheet (`Intent.ACTION_SEND`).** Used for the "share as
  text/clipboard" export path (§3.3) and as a share *target* for other apps
  sending text/image/file/PDF content into Skein. The share sheet sees only
  the specific payload the user chose to share, and only when the user
  triggers the share action.
- **`DocumentsProvider`.** Exposes vault content **as Markdown files** to
  other apps (Obsidian, external editors, backup tools) via the system file
  picker, per spec §2 principle 10. `android:grantUriPermissions="false"`
  plus scoped `<grant-uri-permission>` path prefixes mean no persistable,
  app-wide grant is ever issued; a receiving app sees only the document it
  was explicitly granted for that operation. **[v1 design, in progress]** —
  no `DocumentsProvider` implementation exists in `main` yet.

## 5. Model files

- **How they get in.** The user picks a `.gguf` (or ONNX) file via SAF, or
  chooses one of the two bundled default models (Qwen 2.5 3B Instruct
  Abliterated, Gemma 4 E4B) at first run. There is no automatic download and
  no background fetch of any kind — the app has no `INTERNET` permission to
  perform one with (spec §15 risk #2 explicitly rejects any cloud-fallback
  fetcher shipping inside the main app).
- **Verification, as specified.** Before the file is mmap'd, its SHA-256 is
  streamed and compared against the manifest's pinned hash. After `mmap`,
  a second digest — BLAKE3-256, a different algorithm from the pre-mmap
  check — is computed over the actual mapped bytes and compared again; a
  mismatch aborts the load with `HASH_MISMATCH_POST_MMAP`. This closes the
  gap where `mmap`-ing a file only preserves inode identity, not immunity
  to another writer modifying the underlying bytes between the hash check
  and use (design: `docs/design/POST_REVIEW_RESOLUTIONS.md` §2, tracked as
  `skein-st1r`). Companion files (tokenizer, config, `mmproj`, license) each
  carry their own required SHA-256 in the model manifest — an unhashed
  companion is a hard load failure, not a silent trust. Sigstore attestation
  is checked separately as an *origin*-trust signal; the digest match is the
  hard gate regardless of attestation state. **All of this is
  [v1 design, in progress]** — the streaming SHA-256 check and the
  post-mmap BLAKE3 check are both specified but not yet implemented; there
  is currently no model-loading code in `inference-service` beyond a stub.
- **Import-time immutability, as specified.** Once imported, the model
  directory is `chmod 500` and each file `chmod 400`, and the loading
  service holds an advisory read lock for the file's lifetime; re-importing
  over an in-use model ID is refused. **[v1 design, in progress]**
- **Zero automatic anything.** No automatic download, no background
  refresh, no update check, no telemetry about which model is loaded.

## 6. What happens on backup

Skein ships `android:allowBackup="true"` (so the OS backup/restore and
device-to-device transfer machinery exists at all) but excludes every
privacy-relevant path via two files, both **[shipped]**:

- `app/src/main/res/xml/data_extraction_rules.xml` — API 31+, referenced by
  `android:dataExtractionRules`. Declares **both** `<cloud-backup>` and
  `<device-transfer>` sections with the same exclusion set, because
  Seedvault (GrapheneOS's backup app) exercises the `<device-transfer>`
  path, not `<cloud-backup>` — an app that only populates `<cloud-backup>`
  would leak its vault to a Seedvault D2D transfer while passing every
  `bmgr backupnow` check.
- `app/src/main/res/xml/backup_rules_legacy.xml` — API 30 fallback,
  referenced by `android:fullBackupContent`, since `dataExtractionRules`
  only exists on API 31+ and `minSdk = 30`.

Both files exclude: the whole `database` domain (SQLCipher/Room), `vault.db`
and its `-wal`/`-shm`/`-journal` siblings by explicit path (in case a future
build path ever writes the DB file outside the `database` domain), `keys/`,
`models/`, `attachments/`, `cache/staging_export/`, all of `external/`
(defensive — Skein never writes there), and a blanket `root/.` fail-safe so
anything not named above is still excluded by default. The cloud-backup
section additionally excludes `sharedpref/unlock_state.xml` so unlock state
never leaves the device via cloud sync, even though it is allowed to
accompany a same-user device transfer.

`app/src/test/kotlin/app/skein/DataExtractionRulesTest.kt` parses both XML
files and asserts the exclusion set is present in every section and that
the two files agree (modulo the cloud-only `unlock_state.xml` rule);
`ManifestPolicyTest.kt` asserts the manifest points at these two files by
name. Both tests are **[shipped]** and run on every `./gradlew :app:check`.

**Verifying this yourself.** `docs/BACKUP_EXCLUSIONS.md` documents the full
manual procedure against real Seedvault hardware: force a backup
(`adb shell bmgr backupnow app.skein`), grep the backup agent's logcat
output for the excluded paths (they should not appear), inspect Seedvault's
own per-app restore listing (should show a near-empty data set, not
something sized like the vault database), and — separately, because it is
the actual code path Seedvault uses — exercise GrapheneOS's "Copy your
data" device-transfer flow and confirm the destination device gets a fresh,
empty vault rather than a restored one. That document also records why
`bmgr backupnow` alone is an insufficient check.

## 7. What happens on device compromise

Skein's threat model is defined in the design spec §9
(`docs/superpowers/specs/2026-09-19-skein-design.md`); a standalone
`THREAT_MODEL.md` is planned for delivery by milestone M3 and does not yet
exist in the tree. Until then, spec §9 is authoritative.

**In scope — what Skein defends against:**

- A malicious model publisher or a crafted GGUF exploiting a parser bug:
  contained by the isolated `:inference` process, which has no Android
  permissions and cannot open arbitrary files or sockets.
- Malicious note content attempting prompt injection: retrieved text is
  treated as data, never as instructions; model output never derives
  Intent URIs or triggers tool calls without an explicit user tap
  (CaMeL-style separation, spec §9).
- A physical attacker with a *locked* device: vault keys are StrongBox-
  backed and biometric-gated; `FLAG_SECURE` (**[shipped]**,
  `app/src/main/kotlin/app/skein/MainActivity.kt`) blocks screenshots and
  recents-thumbnail capture of vault/chat surfaces, with a user-facing
  toggle in Settings (default on).
- A co-installed malicious app: no exported components beyond the
  `DocumentsProvider`'s deliberately scoped grants; the app has no
  `INTERNET` permission for such an app to exfiltrate through even if it
  compromised Skein's process.
- Backup exfiltration via Seedvault D2D: covered in §7 above.
- A compromised model mirror or MITM during model import: covered by the
  hash-and-attestation pipeline in §6.
- In-place file mutation between a model's hash check and its use
  (TOCTOU): covered by the post-mmap re-digest in §6.

**Explicitly out of scope — what Skein does not defend against:**

- A rooted device or an unlocked bootloader. Root access defeats
  app-private storage guarantees entirely; this is an OS/hardware trust
  boundary, not something an app can mitigate.
- Forensic extraction of an **unlocked, cooperating** device — if the vault
  is unlocked and the device is handed to an attacker, the attacker has the
  same access the user does.
- A malicious third-party keyboard (IME) or accessibility service. Vault
  text-entry fields set `textNoSuggestions` and disable personalized
  learning (spec §9) to reduce IME leakage, but an actively malicious IME
  with accessibility-service-level access to the screen can still observe
  what the user types or reads, the same as with any Android app.
- Timing or thermal side-channels, and traffic analysis (moot — there is no
  network traffic to analyze).
- Vulnerabilities in the Android OS or device firmware itself.

## 8. How to independently verify our claims

These are concrete, repeatable checks — not appeals to trust:

1. **No `INTERNET` permission.** Unpack a release APK
   (`unzip app-release.apk AndroidManifest.xml`, then decode with
   `aapt dump permissions app-release.apk` or `apkanalyzer manifest print`)
   and confirm `android.permission.INTERNET` does not appear. In the source
   tree, the same check is `grep -i internet app/src/main/AndroidManifest.xml`
   — it should only appear in the comment explaining that it must never be
   added. The build-time guard is `./gradlew :app:checkManifestGuardsFossRelease`
   (or the equivalent variant task), which fails the build if `INTERNET` or
   `ACCESS_NETWORK_STATE` enters the merged manifest from any dependency.
2. **Reproducible builds.** Once a tagged release exists, rebuild from the
   pinned commit and diff the result against the published APK using the
   recipe in `docs/VERIFICATION.md` (Gradle dependency verification against
   `gradle/verification-metadata.xml`, which pins a SHA-256 for every
   resolved artifact) together with the `.github/workflows/reproducible-build.yml`
   double-build-and-diff workflow. A mismatch on either check is a build
   failure, not a warning.
3. **GrapheneOS per-app network permission.** GrapheneOS lets you toggle a
   per-app "Internet" permission independent of the manifest. Because
   Skein's manifest never requests `INTERNET` in the first place, this
   toggle for Skein has nothing to grant — install Skein, open GrapheneOS's
   per-app permission screen for it, and confirm no network toggle is even
   offered (or that it stays permanently denied), then use a network
   monitor (e.g., GrapheneOS's own connectivity indicators, or `adb shell
   dumpsys netstats` filtered to Skein's UID) while using the app normally
   to confirm zero bytes are ever sent or received.
4. **Backup exclusions.** Run the manual procedure in
   `docs/BACKUP_EXCLUSIONS.md` against Seedvault directly (§7 above).
5. **Third-party attributions.** Cross-check `NOTICE` and (once generated)
   `app/src/main/assets/licenses.json` against the actual compiled
   dependency list; the `licenseAudit` Gradle task fails the build on an
   unlisted or non-permissive dependency in the `foss` flavor.

## 9. Version and effective date

Effective 2026-09-20. Substantive changes to this document are tracked in
git history (`git log -- docs/PRIVACY.md`); user-facing changes are
announced in release notes once Skein ships tagged releases.

## 10. Not legal advice

This document describes Skein's technical behavior — what data exists,
where it is stored, how it is protected, and what leaves the device and
when. It is not a legal privacy policy, does not constitute legal advice,
and makes no representations about compliance with any specific
jurisdiction's data-protection law. Consult a qualified professional if you
need a legal privacy policy for a specific regulatory context.
