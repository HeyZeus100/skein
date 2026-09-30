# Privacy

This document describes the current Skein Android application and its known
limits. The implementation was reviewed at `b62977f3a` on 30 September 2026.
Skein remains pre-alpha. Source mechanisms, test results and physical-device
acceptance are different kinds of evidence; none alone is a security certification.

## 0. The one-line promise

Skein processes notes and chats on your device, without automatic cloud uploads,
analytics or telemetry. The application has no `INTERNET` permission. Explicit
exports, shares and document-provider access can hand plaintext to Android or
another app; the destination's storage and network behavior are outside Skein.

## 0.1 Implementation and evidence status

The vault, key management, attachment encryption, document provider, export,
retrieval, model verification and native inference paths have implementations.
The presence of residual `Placeholder.kt` files does not make those modules
placeholders. Conversely, a service declaration or unit test does not establish
that every model, device or workflow is ready.

The [e62 automated evidence](ux/runs/2026-09-29-confirmed-delete-e62f947/README.md)
records exact-source XML and artifact checks, including ordinary instrumentation
for database encryption, vault keys, provider operations and native services.
Some unit outputs were cached; those results are not all fresh executions. The
[later physical Delete check](ux/runs/2026-09-30-delete-physical-e62/README.md)
used one disposable note and chat. It is not broader Fold, backup, model-quality
or security acceptance. The missing-key source changes at this document's
checkpoint are newer than the e62 application evidence and are not installed
on the Fold, which still has e62.

Production embedding/reindex integration, broader inference/runtime and formal
M0 gates remain open. Signature attestation and the current PDF cleanup gap are
identified below rather than described as completed protections.

## 1. What data Skein handles

Skein keeps one local vault. Content and metadata needed to edit, retrieve and
answer from that vault are stored as follows.

| Data | Current storage and protection |
|---|---|
| Notes, document titles/frontmatter, chats and assistant messages | App-private `filesDir/vault.db`, opened through SQLCipher. Plaintext is present in process memory while used. |
| Unsent chat text and selection | The encrypted `chat_drafts` table and session draft state; migration 011 adds the table. Existing-chat drafts cascade with their chat. |
| Chunks, keyword index, links, tags, persona records and revisions | Tables inside the same encrypted database. `chunks_vec` exists, but production retrieval currently receives no embedder; its presence does not prove semantic indexing is operational. |
| Retrieved citation records | Message-associated records include document/revision identity, locator and excerpt. An excerpt is an independent retained copy of source text. |
| Attachments | App-private `attachments/<id>` files using the encrypted SKAT v2 container, not plaintext attachment files. |
| Imported model weights and companions | App-private `filesDir/models/<id>/`, sealed read-only by the model store where supported. These files are outside SQLCipher; model files are not promised separate vault-content encryption. |
| Settings and work scheduling | App-private preference DataStore and WorkManager storage outside the SQLCipher vault. Export-sweep work input contains a stage ID, not document text or a plaintext path. |
| Explicit exports and print staging | User-selected destination files, receiving apps, or temporary plaintext PDF staging; see §3.3. These are exceptions to vault-at-rest encryption. |

The storage implementation is in [VaultLifecycle](../core/vault/src/main/kotlin/app/skein/core/vault/lifecycle/VaultLifecycle.kt),
[VaultRepositoryImpl](../core/vault/src/main/kotlin/app/skein/core/vault/repository/VaultRepositoryImpl.kt),
[the migrations](../core/vault/src/main/resources/migrations/INDEX.txt) and
[FileAttachmentStore](../core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt).
SKAT v2 derives a per-write key using the vault master, attachment ID and a fresh
random file salt, and authenticates chunks including the final marker. This
supersedes the earlier deterministic-per-ID attachment design.

### Keys and locking

[AndroidKeystoreFacade](../core/vault/src/main/kotlin/app/skein/core/vault/key/AndroidKeystoreFacade.kt)
creates authentication-bound wrapping keys for biometric and device-credential
factors. [VaultKeyProviderImpl](../core/vault/src/main/kotlin/app/skein/core/vault/key/VaultKeyProviderImpl.kt)
wraps the vault master and tries StrongBox, with an explicit non-StrongBox
fallback when unavailable. It is therefore inaccurate to promise StrongBox on
every device. The wrapped envelope lives under `filesDir/keys/`; key buffers are
cleared by the lock/lifecycle paths. This is not proof that every transient
plaintext copy in Android, Kotlin or native memory is securely erased.

### Deletion and retained copies

Chats and independent notes have target-specific confirmed Delete actions.
The shared deletion coordinator pauses/reserves writers and prunes matching
routes. Files and extracted/source-backed notes remain outside that UI deletion
scope pending atomic metadata/blob deletion and orphan-recovery work.

[Migration 010](../core/vault/src/main/resources/migrations/010_fts_secure_delete.sql)
enables FTS5 `secure-delete` and runs an initial `optimize` to remove earlier
index residue. The migration's instrumented checks cover that index behavior;
this is not whole-device forensic erasure. Quotes retained in other chats'
citation records, cited revisions, encrypted WAL pages before checkpoint, old
flash blocks and copies already exported elsewhere have separate lifetimes.
Unreferenced superseded revisions are swept by the repository through the
per-session ingest scheduling path. See [object lifecycle §3.7](ux/OBJECT_LIFECYCLE_SPEC.md)
for the residue boundaries. Deleting a source does not automatically scrub its
quoted text from other chats or from a receiving app.

### Device lock changes and missing keys

Removing the device's secure screen lock can permanently invalidate the
authentication-bound keys used to open Skein. Re-enabling a PIN does not
restore those keys. Android documents this behavior for
[keys that require user authentication](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUserAuthenticationRequired(boolean)).

Skein now distinguishes an absent device key from a failed authentication or
a temporarily unavailable keystore. The message identifies the affected
factor; one missing key does not prove that every recovery option is lost.
This diagnosis does not delete files, replace keys, or start setup. Reset is
an explicit, permanent deletion of vault content behind two confirmation
steps; imported model files are retained.

Keep a passphrase-protected recovery export separately. It contains recovery
key material, not a backup of the notes or attachments. Recovery over an
existing envelope after device-key loss is **not yet supported by the UI**;
do not reset an existing vault in order to try that recovery. The remaining
recovery and credential-fallback work is tracked by `skein-gg11.29`.

## 2. What Skein does not send automatically

The Android application has no configured analytics, advertising attribution,
cloud chat, cloud sync or crash-report upload pipeline. Local diagnostics do
exist. [SkeinLog](../core/model/src/main/kotlin/app/skein/core/model/SkeinLog.kt),
[AndroidSkeinLogSink](../app/src/main/kotlin/app/skein/system/AndroidSkeinLogSink.kt)
and native-log redaction constrain diagnostic output; marker-based redaction is
a safety net, not a proof that arbitrary sensitive strings can never reach logs.
Release R8 rules strip the facade's debug/info calls. Diagnostic logs are not
uploaded by Skein.

The [source manifest](../app/src/main/AndroidManifest.xml) explicitly declares
`POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`. The latter two support
user-selected local model imports, not network synchronization. The merged
manifest also has narrowly allowed AndroidX compatibility permissions;
[ManifestPolicyTest](../app/src/test/kotlin/app/skein/ManifestPolicyTest.kt)
checks the actual allowlist rather than an obsolete two-permission total.

[ManifestGuardPlugin](../build-logic/guards/src/main/kotlin/app/skein/gradle/ManifestGuardPlugin.kt)
rejects `INTERNET` and `ACCESS_NETWORK_STATE` in merged manifests. Check the
actual APK and variant, not just source comments or a workflow's green summary.
This permission boundary does not prevent a user-selected receiving app,
keyboard or other system component from using its own network access.

## 3. Content flows

### 3.1 Editing and saving

The editor writes through the vault repository into SQLCipher. Indexing and
revision maintenance are local. Unsent chat drafts use the encrypted draft
store, not a plaintext draft file. Lock handling coordinates pending writes,
engine cancellation and vault teardown; a source-level implementation is not
an unlimited claim about every crash, device or memory-lifetime scenario.

### 3.2 Asking the model

[ModelServices](../app/src/main/kotlin/app/skein/models/ModelServices.kt) wires the
current retrieval service, prompt assembler and native inference client.
Retrieved context and chat text cross Binder to the isolated `:inference`
service; model bytes arrive through selected file descriptors. The service
contains real llama.cpp loading/generation code. Both inference and embedder
services are declared isolated and unexported; isolation limits their authority
but does not make model files, parsers or generated answers inherently safe.

Production retrieval is presently wired with `embedder = null`, and ingestion
uses the approximate tokenizer while semantic preparation remains pending.
Do not describe the implemented vector/PPR components as proof of a complete
production hybrid pipeline. The [retrieval execution handoff](Handoffs/skein-retrieval-repair-execution.md)
records the failed quality gate and outstanding embedding/reindex dependencies.

Prompt/citation guards and rendering code separate retrieved text and validate
citation markers against supplied records. They do not guarantee that a model
will ignore prompt injection or answer correctly. Retained citation records
also do not establish that citations rendered during a particular physical run.

### 3.3 Exporting or sharing

Note actions support text sharing, Markdown/DOCX save-as and PDF printing.
[ExportServiceImpl](../core/vault/src/main/kotlin/app/skein/core/vault/export/ExportServiceImpl.kt)
streams Markdown, DOCX and vault ZIP exports to caller-supplied output streams.
The normal save-as path uses Android's user-selected destination. Exported
plaintext and a receiving app's retained copy are outside Skein's deletion,
locking and backup controls.

PDF printing uses Android `PrintManager` and app-private plaintext files under
`cache/staging_export/`. A stage recorder, delayed sweeper, lock/unlock purge
and boot receiver are implemented. However, the current
[NoteTab PDF call](../feature/editor/src/main/kotlin/app/skein/feature/editor/notetab/NoteTab.kt)
constructs `PdfExportService` without the recorder, selecting
`NoOpExportStageRecorder`. **The current PDF UI therefore does not establish
the intended ten-minute cleanup timer.** Wiring is tracked by `skein-efwt`,
with staging/adversarial verification under `skein-xg6d`.

[ExportStageCoordinator](../app/src/main/kotlin/app/skein/export/stage/ExportStageCoordinator.kt)
and [BootReceiver](../app/src/main/kotlin/app/skein/export/stage/BootReceiver.kt)
provide separate purge paths. The receiver is not direct-boot-aware; do not
promise cleanup before credential-encrypted storage is available. Even after
timer wiring, WorkManager scheduling is not an exact wall-clock erasure bound,
and Skein cannot erase a print service's or destination app's copies.

## 4. Android and other-app surfaces

- **Storage Access Framework:** the user selects import sources or export
  destinations. A selected provider can be another app, with its own storage
  and privacy policy; selecting it is not proof the file is only local.
- **Biometric/credential authentication:** Android presents authentication UI;
  Skein uses the result to authorize key operations, not to collect biometrics.
- **Share sheet and printing:** explicitly shared text or print output becomes
  visible to the chosen destination or Android print machinery.
- **DocumentsProvider:** the implemented
  [VaultDocumentsProvider](../core/vault/src/main/kotlin/app/skein/core/vault/provider/VaultDocumentsProvider.kt)
  exposes eligible Markdown documents and attachments through the system picker
  while unlocked, with supported Markdown writes. The manifest requires
  `MANAGE_DOCUMENTS` and scopes URI grants to `/document/note:` and
  `/document/att:`. Whole-vault tree access is not advertised. Provider reads
  and writes check session state. These controls and bounded provider tests
  are not a blanket proof of every cross-UID or persisted-grant scenario.
- **Keyboard, accessibility and screen capture:** these can see content as
  described in §7; no-network permission on Skein does not constrain them.

The launcher and permission-guarded document provider are exported surfaces;
merged system-job/debug allowances are checked by `ManifestPolicyTest`. A claim
that Skein has no exported components would be incorrect.

## 5. Model files and trust

Model imports use a user-selected file and a local foreground copy service.
Opaque copying can continue across Activity destruction or vault lock;
inspection/registry attachment remains session-authorized. Imports stage,
hash, atomically promote and seal model files. Existing model handles carry
shared advisory locks. File permissions and advisory locks are defense in
depth, not protection against a compromised process with the owning UID.
See [ModelImportStager](../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelImportStager.kt),
[ImmutableModelStore](../core/inference/src/main/kotlin/app/skein/core/inference/models/ImmutableModelStore.kt)
and the [inference lifecycle handoff](Handoffs/skein-inference-lifecycle-20260929.md).

[ModelVerifier](../core/verify/src/main/kotlin/app/skein/core/verify/ModelVerifier.kt)
checks SHA-256 before mapping and rechecks the read-only mapping before load.
The second check uses BLAKE3 when a binding declares it, otherwise SHA-256.
It is inaccurate to promise two different digest algorithms for every import.
Declared companion files are part of the verification binding. The native
loader uses retained descriptors rather than trusting a freshly reopened path.
These checks constrain the verified load path; they do not certify a model's
publisher, license, parser safety or behavior.

A locally generated import hash establishes consistency with the selected
bytes, not independent publisher authenticity.
[ManifestAttestation](../core/inference/src/main/kotlin/app/skein/core/inference/models/ManifestAttestation.kt)
still supplies the `Unavailable` implementation; automatic Sigstore origin
verification must not be claimed. No qualified bundled default model ships in
`assets/models/`. The demo's imported model is a particular observed artifact,
not a universal default-model or production embedding decision. There is no
automatic model download, refresh or network fallback in the application.

## 6. Backup configuration and its limits

The manifest sets `allowBackup="true"` and references
[data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml)
for cloud/device-transfer rules and
[backup_rules_legacy.xml](../app/src/main/res/xml/backup_rules_legacy.xml)
for the older backup API. Both configure exclusions for the database domain,
`vault.db` and its journal siblings, `keys/`, `models/`, `attachments/`,
`cache/staging_export/`, external storage and a root-domain exclusion.
Cloud rules additionally name `sharedpref/unlock_state.xml`.

These are real exclusion rules, covered by `DataExtractionRulesTest` and
manifest tests. They are not a tested promise that every device, backup
transport or app-private preference is excluded. Content already exported to
a user-selected destination is outside these rules. A recovery-key export is
also distinct from a content backup.

[BACKUP_EXCLUSIONS.md](BACKUP_EXCLUSIONS.md) contains the Seedvault/device-transfer
verification procedure. This document does not claim that procedure was
completed by the e62 unit, ordinary instrumentation or disposable Delete run.

## 7. Device compromise and residual risk

The design spec's [threat discussion](superpowers/specs/2026-09-19-skein-design.md)
and [D7 security review](ux/SECURITY_REVIEW_D7.md) describe the intended boundaries.
Implemented protections include authentication-bound keys, app-private storage,
SQLCipher, isolated services, scoped provider access and guarded export paths.
They depend on Android and the device's security, and retain the gaps above.

[MainActivity](../app/src/main/kotlin/app/skein/MainActivity.kt) applies
`FLAG_SECURE` synchronously and responds to its user setting; the default is
on. On API 33+, it also disables Recents screenshots independently of that
setting. These source controls and focused tests are not physical acceptance
of every screenshot/Recents/fold combination. Disabling screenshot protection
changes the screen-capture boundary.

[SecureTextField](../feature/shell/src/main/kotlin/app/skein/feature/shell/input/SecureTextField.kt)
requests no suggestions/personalized learning. Those flags are advisory;
a malicious keyboard or accessibility service can still observe content.
Root access, a compromised OS/firmware, an unlocked cooperating device, copies
already shared elsewhere and timing/thermal side channels are outside the
protection this document establishes. Neither encryption nor prompt guards
make an unlocked session safe to hand to an attacker.

## 8. Independently checking the claims

1. Inspect the **actual APK's** permissions with `apkanalyzer manifest print`
   or `aapt dump permissions`, and run the appropriate
   `checkManifestGuards<Variant>` task. Source comments alone are insufficient.
2. Inspect the source mechanisms linked above and actual test XML. Existing
   e62 evidence includes `ManifestPolicyTest`, `DataExtractionRulesTest`,
   `MainActivityFlagSecureTest`, `RecentsScreenshotDisabledTest`,
   `FileAttachmentStoreTest`, `ModelVerifierTest`, staging-worker/boot tests,
   `AtRestEncryptionInstrumentedTest` and bounded provider/key/migration tests.
   A passing helper test does not prove missing production wiring is present.
3. Use [VERIFICATION.md](VERIFICATION.md) and retained reproducibility artifacts
   to compare the exact source, dependencies and produced bytes. Do not treat
   signed debug installation bytes as the unsigned release artifact pair.
4. Evaluate backup transports and security journeys using disposable fixtures
   under the separate procedures. Do not infer whole-device security or
   complete runtime readiness from the scoped automated and physical checks.

## 9. Version and effective date

Updated 2026-09-30 from the implementation at `b62977f3a`. This documentation
audit did not install an app or run new build/device tests. Earlier e62
results remain attributed to e62. Changes are tracked in
`git log -- docs/PRIVACY.md`.

## 10. Scope of this document

This is a technical description of implemented behavior and limitations,
not a statement of compliance with a particular privacy law.
