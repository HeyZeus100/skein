# GGUF_IMPORT_ANALYSIS — arbitrary GGUF import, against Skein's Model Manager

Directive §10. Upstream `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b` (tag
`5.1.1`), Apache-2.0, reviewed 2026-09-23. Compared against
`core/inference/.../models/ImmutableModelStore.kt`, `docs/design/MODEL_STORE.md` and
`docs/design/SKEIN_HUB.md` §3/§5.

**Summary: OfflineLLM's import is roughly 60 lines and gets two things right that Skein also gets
right — stage-and-rename, and copy-in rather than persist-a-URI. It gets validation wrong in
exactly the way `skein-hewz` exists to fix, and it reproduces PocketPal's PP-73 (models vanishing)
as a deliberate feature. Nothing here is ADOPT; two items are REFERENCE and confirm existing
beads.**

---

## 1. The whole pipeline

`Settings → Import GGUF Model` → SAF picker → `ModelManager.importModel(uri)`
([`ModelManager.kt#L81-L131`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L81-L131)):

1. `getFileNameFromUri(uri)` via `OpenableColumns.DISPLAY_NAME`, falling back to
   `imported_<millis>.gguf`;
2. refuse unless the name ends in `.gguf`;
3. `getSizeFromUri(uri)` via `OpenableColumns.SIZE` (nullable);
4. `openInputStream(uri).copyTo(tempFile.outputStream(), 8192)` into `<name>.gguf.part`;
5. if the declared size was known and `copiedBytes != expectedSize` → delete, refuse with the
   byte counts;
6. `validateGGUF(tempFile)` — **read four bytes, compare to `GGUF`**
   ([`#L281-L294`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L281-L294));
7. delete any existing destination, `tempFile.renameTo(destFile)`;
8. `registerModelIfNeeded` → open a `GGUFReader`, pull `context_length` and `chat_template`, write
   a Room row.

Destination is `context.filesDir/models/` (`#L22`), i.e. app-private internal storage. No
persisted URI permission is taken anywhere in the tree (`grep` for `takePersistableUriPermission`:
no hits).

---

## 2. What it gets right

**Staging with a verified promote**, with the reason recorded in the code:

```kotlin
// Copy to a .part temp file and rename only after the copy is verified —
// otherwise an interrupted import leaves a truncated .gguf with a valid
// header that passes the magic check but fails at load with
// "tensor data is not within the file bounds".
```
([`ModelManager.kt#L82-L85`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L82-L85))

That comment is a bug report from the field. It is PocketPal's **PP-29** (download written
straight to its final path, no `.part`) and **PP-14** ("file exists ⇒ downloaded") arriving
independently, and it is the failure `skein-r8ah` exists to assert against. **Two independent
projects hit the same truncated-artifact bug; Skein's `<file>.tmp` + `ATOMIC_MOVE`
(`ImmutableModelStore.kt:239`, `TEMP_SUFFIX = ".tmp"` at `:454`) is the right shape, and
`skein-r8ah`'s first assertion — "simulate a killed import; nothing loadable remains" — is now
corroborated twice over.**

**Declared-size agreement.** `copiedBytes != expectedSize` → refuse, with both numbers in the
message and a "check free storage" hint (`#L101-L109`). This is a weak integrity check (it proves
only that the stream was as long as the provider claimed) but it catches the single most common
real failure: a truncated copy from a full disk.

Skein's equivalent is stronger and already designed: `SKEIN_HUB.md` §3.2 specifies three checks
before a byte is written (`free ≥ size × 1.05`; `size ∈ 1..16 GiB`; a running counter that
refuses at `declared + 1` and at a short EOF), with the note that "wrote more than declared" and
"hash mismatch" are *distinct, separately-tested refusals*. OfflineLLM's version has no upper
bound at all — a content provider declaring 900 GB fills `filesDir` until the copy throws. **The
16 GiB ceiling in `SKEIN_HUB.md` §3.2 is confirmed as necessary, not defensive over-engineering.**

**Copy in, don't persist a URI.** The model is copied into `filesDir` and the `Uri` is dropped.
That is PocketPal's **PP-72** and Skein's `ImmutableModelStore` model. It costs a full duplicate
of a multi-gigabyte file — which `SKEIN_HUB.md` §5.2's no-duplicate rule addresses for the Hub
path — but for *import without Hub* it is the only correct answer: a `content://` URI can be
revoked, its backing file can be swapped, and an isolated process cannot resolve it. **REFERENCE,
confirming PP-72.**

---

## 3. What it gets wrong

### 3.1 Validation is four bytes

```kotlin
private fun validateGGUF(file: File): Boolean {
    return try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) return false
            magic[0] == 'G'.code.toByte() && ... magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }
}
```
([`ModelManager.kt#L281-L294`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L281-L294))

No version check, no KV-count bound, no tensor-count bound, no length-vs-remaining-bytes checks.
Anything beginning `47 47 55 46` is accepted and handed to `gguf_init_from_file`
([`GGUFReader.cpp#L9-L12`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/GGUFReader.cpp#L9-L12))
and then to `llama_model_load_from_file`, **in the app process** (`SECURITY_ANALYSIS.md` §5).

This is precisely PocketPal's **PP-11** (bounded header reader: magic, version 1–3, per-field
sanity limits, throw on any anomaly) seen from the other side, and it is the whole argument for
`skein-hewz`. **Matrix OL-32: REJECT.** The corroboration is useful: two apps, and *neither* has
a bounded pre-check. `skein-hewz` is not re-implementing something upstream already solved.

Fairness: OfflineLLM's null-handle path does behave. `gguf_init_from_file` returning `nullptr`
gives `nativeHandle = 0`, and `GGUFReader.getContextSize()`'s `check(nativeHandle != 0L)` throws
before dereferencing
([`GGUFReader.kt#L18-L22`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/GGUFReader.kt#L18-L22)),
and `registerModelIfNeeded` swallows it with a `catch (_: Exception) {}` and defaults to
`contextSize = 2048` (`ModelManager.kt#L256-L261`). So a malformed GGUF *registers successfully*
with default metadata and fails later at load. Refusing at import would be better.

### 3.2 Identity is the file name

```kotlin
val modelInfo = ModelInfo(
    name = file.nameWithoutExtension.replace("_", " ").replace("-", " "),
    path = file.absolutePath, ...)
```
([`ModelManager.kt#L263-L270`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L263-L270))

The display name is the filename with separators replaced; the primary key is the path
(`getModelByPath`, `#L251`). Duplicate handling is *"delete the existing destination and
overwrite"* (`#L117-L119`) — no collision dialog, no "keep both", no content comparison. Two
different models with the same filename silently become one. PocketPal's **PP-72** specifies the
replace / keep-both / cancel dialog; OfflineLLM has the worst of the three hard-coded.

Skein derives identity from the manifest `id` (which is also the store directory name) and content
from the digests. **Matrix OL-33: REJECT.**

### 3.3 Architecture and quantization detection: none

`GGUFReader` reads exactly two keys — `<arch>.context_length` and `tokenizer.chat_template`
([`GGUFReader.cpp#L16-L40`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/GGUFReader.cpp#L16-L40)).
`general.architecture` is read only to build the context-length key name and is discarded.
`general.file_type` (quantization) is never read at all. So there is no compatibility check, no
"this architecture is unsupported" message before the load, and no quantization-conditional
policy — which is why `skein-brwf`'s PP-41 rule (mmap off for `MOSTLY_Q4_0` / `MOSTLY_IQ4_NL`)
would be *unimplementable* in OfflineLLM's structure, and why it defaults mmap off for everything
instead (`MEMORY_ANALYSIS.md` §4). **The absence explains the blunt default.**

Skein's `skein-hewz` explicitly extracts `general.architecture` and `general.file_type`, and
`SKEIN_HUB.md` §3.3 moves full inspection into the isolated process. Both decisions are confirmed
by what their absence costs here.

---

## 4. Deletion, storage accounting, and PP-73

```kotlin
suspend fun getAvailableModels(): List<ModelInfo> {
    val models = chatRepository.getAllModelsSync()
    for (model in models) {
        if (!File(model.path).exists()) { chatRepository.deleteModel(model.id) }
    }
    return chatRepository.getAllModelsSync()
}
```
([`ModelManager.kt#L229-L237`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L229-L237))

**Every model listing silently deletes the registry row for any model whose file is not at the
recorded absolute path.** That is PocketPal's **PP-73** (`a-ghorbani/pocketpal-ai#684`, "model
disappears after app upgrade") implemented as intended behaviour: if the app-private container
path changes across an upgrade or a restore, every model vanishes from the UI on next launch, with
the row gone so no re-anchoring is possible. PP-73's remedy is to re-anchor the path against the
current `filesDir`, not to delete.

**Matrix OL-35: REJECT** — and `skein-r8ah` gains a third assertion worth adding: *a model whose
recorded path no longer resolves is re-anchored or reported, never silently deregistered.* Skein's
`ImmutableModelStore` keys on a per-model directory under `filesDir`, so re-anchoring is a prefix
substitution; the failure mode is reachable and the fix is cheap.

Deletion (`deleteModel`, `#L239-L248`) is `File.delete()` plus the row, plus resetting
`activeModelId` — correct, and notably it does **not** use `SecurityUtils.secureDelete`
([`SecurityUtils.kt#L34-L60`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SecurityUtils.kt#L34-L60)),
which exists in the tree and is called from nowhere. See `SECURITY_ANALYSIS.md` §7.

**Storage accounting: none.** No free-space check before a multi-gigabyte copy, no total-usage
figure, no per-model size in the UI beyond `sizeBytes`. `SKEIN_HUB.md` §3.2's `size × 1.05`
headroom (PocketPal **PP-30**) has no counterpart here.

---

## 5. Against Skein's Model Manager

| Stage | OfflineLLM | Skein (`SKEIN_HUB.md` §3.1) |
|---|---|---|
| Free-space check | none | step 1, `size × 1.05` |
| Size ceiling | none | step 2, 1 B … 16 GiB, plus over/under-run refusal |
| Copy | `copyTo` into `.part` | step 3, copy into `filesDir/models/<id>/` **hashing SHA-256 and BLAKE3 in one pass**, `<file>.tmp` → `ATOMIC_MOVE`, then `0400`/`0500` |
| Digest cross-check | none | step 4 |
| Structural inspection | 4 magic bytes, in the app process | step 5, **inside `:inference`**, via the new `inspect` AIDL method |
| Manifest synthesis | filename → display name | step 6, derived facts + quarantined hints |
| Seal | — | step 7, `ImmutableModelStore.register` |
| Registry + provenance | Room row: name, path, size, ctx, template, isBundled | step 8, registry row + provenance columns (migration `009_model_origin.sql`) |
| Load gates | none | step 10, **two gates**: pre-mmap SHA-256 over the open channel, post-mmap BLAKE3 over the `MappedByteBuffer` (`MODEL_STORE.md` §3) |
| Permissions | default `filesDir` mode | `0400` files, `0500` directory |
| Sealing order | — | files first, directory last — *"sealing the directory to 0500 removes the write bit needed to rename into it"* (`ImmutableModelStore.kt:248-254`) |

The gap is not incremental. Skein has a **content-trust gate** (do the bytes equal the
expectation?) and OfflineLLM has none, which is exactly PocketPal's **PP-10** ("fails open when no
expectation exists") and **PP-15** ("hashing that proves nothing") taken to their limit: no hash at
all. See `MODEL_PROVENANCE_ANALYSIS.md`.

**The convergence question** — default manifests, user import and the future Hub converging on one
internal registry — gets no help from OfflineLLM, which has two sources (bundled asset,
`copyBundledModelIfNeeded` at `ModelManager.kt#L37-L79`; SAF import) sharing one private
`registerModelIfNeeded`. That *is* the right shape, and it is the shape `SKEIN_HUB.md` §5.3
already specifies with `ImportSource.Bundled` / `Picked` / Hub-offered running the same
steps 1–10. Weak corroboration of a decision already made; no new information.

---

## 6. Findings

| Id | Item | Class | Note |
|---|---|---|---|
| OL-31 | `.part` staging + declared-size agreement + atomic rename | **REFERENCE** | Confirms PP-14/PP-29/PP-30 and `skein-r8ah` from a second codebase |
| OL-32 | Four-byte magic as the whole of validation | **REJECT** | Confirms `skein-hewz` (PP-11) is unaddressed upstream |
| OL-33 | Filename-derived identity; overwrite-on-collision | **REJECT** | PP-72's dialog is the right answer |
| OL-34 | Copy into app-private storage, no persisted URI | **REFERENCE** | Confirms PP-72 and `ImmutableModelStore` |
| OL-35 | Listing deletes rows for missing files | **REJECT** | Reproduces PP-73; adds an assertion to `skein-r8ah` |

Proposed bead amendments (not filed — see `ADOPTION_MATRIX.md` §3): one assertion added to
`skein-r8ah` (path re-anchoring, never silent deregistration), and a note on `skein-hewz` that two
reviewed upstreams both lack a bounded pre-check, so there is no reference implementation to
borrow and the twelve fixtures in `skein-wt92` (PP-12) remain the only ready-made adversarial set.
