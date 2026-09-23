# SECURITY_ANALYSIS

Directive §13. Upstream `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b` (tag
`5.1.1`), Apache-2.0, reviewed 2026-09-23. *"OfflineLLM is a reference, not Skein's security
model."*

**Summary: one adoptable hardening (`memtagMode="sync"`), one vulnerability in OfflineLLM that
confirms Skein's `ChatTemplating` design by counterexample, and a set of anti-patterns that map
one-to-one onto things Skein's architecture already refuses. Nothing found weakens Skein.**

---

## 1. Permissions and network capability

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />

<!-- STRICTLY FORBIDDEN — NEVER ADD THESE -->
<!-- android.permission.INTERNET -->
<!-- android.permission.ACCESS_NETWORK_STATE -->
<!-- android.permission.ACCESS_WIFI_STATE -->
```
([`AndroidManifest.xml#L5-L16`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L5-L16))

Two permissions. `READ_EXTERNAL_STORAGE` is capped at API 32 and `minSdk = 33`
([`smollm/build.gradle.kts#L69`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L69),
`app/build.gradle.kts#L16`), so it is dead on every supported device — harmless, but it should be
deleted rather than carried.

The comment block is the enforcement mechanism: a convention, not a check. No CI exists
(`.github/` contains only `FUNDING.yml`), so nothing prevents a transitive dependency merging
`INTERNET` into the manifest. Skein's `ManifestGuardTask` denylist plus `ManifestPolicyTest` is a
build-time gate, and Skein additionally *removes* three permissions that `androidx.work` merges in
(`app/src/main/AndroidManifest.xml`, the `tools:node="remove"` block with a per-permission
justification). **Skein's approach is strictly better; the comparison is worth citing in
`skein-v1-autonomous-completion.md` §3 discussions as evidence that the convention-only form is
what most zero-network apps do and why the gate is worth its cost.**

`android:allowBackup="false"` + `hasFragileUserData="true"`
([`#L20-L21`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L20)):
no cloud backup at all, and the user is prompted to keep data on uninstall. Skein sets
`allowBackup="true"` with `dataExtractionRules` and `fullBackupContent` — a deliberate, finer
decision; the vault is encrypted and the rules files scope what leaves. **Observation only, not a
recommendation.** Skein also sets `hasFragileUserData="true"`, matching.

`android:filterTouchesWhenObscured="true"` on the activity
([`#L33`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L33))
— tapjacking mitigation, with a user-facing toggle
([`SettingsRepository.kt#L79`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L79),
default on). Skein's `MainActivity` declares neither this nor `FLAG_SECURE`. Cheap and relevant to
a vault-holding activity. **Matrix OL-39: REFERENCE** — worth raising with whoever owns the UI
security baseline (spec §9 / `E3.I1`), which already exists and is not this bead's to amend.

**Dependencies.** `app/build.gradle.kts` is Compose + Hilt + Room + Coroutines + Jetpack Security
+ Biometric + `markdown-renderer`. No Firebase, no Play Services, no analytics, no crash
reporter — checked by reading the full dependency block
([`app/build.gradle.kts#L64-L117`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L64))
and the `libs.versions.toml`. **No telemetry, no external services, no WebView anywhere in the
tree.** PocketPal's PP-54 (Firebase App Check benchmark submission) and PP-75 (unsigned device
rules from a CDN) have no counterpart here. Genuine credit where due: this is a real zero-network
app, and the claim survives inspection.

---

## 2. Native library loading

`System.loadLibrary("smollm")` and `System.loadLibrary("ggufreader")` — standard. The interesting
surface is the plugin loader:

```cpp
ggml_backend_load_all_from_path(dirCstr);   // dirCstr = context.applicationInfo.nativeLibraryDir
```
([`smollm.cpp#L23`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L23),
called from [`ModelManager.kt#L204-L206`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L204-L206))

`nativeLibraryDir` is set by the platform and is not writable by the app, so this is not a load
path an attacker controls on a non-rooted device. It is nonetheless a `dlopen` of everything
matching a pattern in a directory, and it requires `useLegacyPackaging = true` — i.e. the
libraries are extracted to disk rather than mapped from the (signature-protected) APK.

Skein closed this by construction, with the reason recorded:

```cmake
# Backends are compiled in, not dlopen'd: GGML_BACKEND_DL needs shared libs and
# would put loose .so files next to ours for the isolated process to dlopen.
skein_llama_opt(GGML_BACKEND_DL     OFF)
```
(`native/llama/CMakeLists.txt:625-627`)

**Confirmed correct, and the reason is stronger than the comment states:** AOSP's `isolated_app`
SELinux domain is heavily restricted, and adding a `dlopen`-by-path dependency to the isolated
inference process would be a new capability it does not need. `CPU_DISPATCH_ANALYSIS.md` §6 is
where the cost of that decision (no variant ladder) is accounted for.

---

## 3. Untrusted GGUF as input — the attack surface

Assume, as §13 says, that an imported GGUF is hostile.

| Surface | OfflineLLM | Skein |
|---|---|---|
| Pre-parse validation | 4 magic bytes ([`ModelManager.kt#L281-L294`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L281)) | two digest gates (`MODEL_STORE.md` §3); `skein-hewz` adds a bounded structural pre-check |
| Where GGUF metadata is parsed | **app process** (`libggufreader.so`) | `:inference`, isolated (`SKEIN_HUB.md` §3.3 `inspect`) |
| Where weights are loaded | **app process** | `:inference`, over a pinned fd |
| Process that also holds user data | **the same one** | `:app`, which never parses a GGUF |
| Untrusted string reaching the UI | `gguf_get_val_str("tokenizer.chat_template")` → Room → `llama_chat_apply_template` | template render happens in `:inference`; `ChatTemplating` segments the result |
| Mitigation | `memtagMode="sync"` | process isolation + `isolatedProcess="true"` + `IsolatedSessionGate` |

Two specific hostile-input paths worth naming:

**A hostile `tokenizer.chat_template`.** OfflineLLM reads it from the GGUF
([`GGUFReader.cpp#L30-L41`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/GGUFReader.cpp#L30-L41)),
stores it in Room, and passes it to `llama_chat_apply_template` — a Jinja-ish interpreter in
llama.cpp, running in the app process, over a string the model author chose. It also
substring-sniffs it to pick the assistant role name
([`LLMInference.cpp#L224-L233`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L224-L233)).
Skein has the same dependency on `llama_chat_apply_template` — but inside `:inference`, and with
the render then split scaffold/content before tokenizing. **The isolation is what makes this
acceptable; it is not a theoretical benefit.**

**Unbounded metadata.** No KV-count or string-length bound anywhere before `gguf_init_from_file`.
`skein-hewz`'s `kvCount <= 4096` and per-length-vs-remaining-bytes checks are the answer, and
`skein-wt92`'s twelve fixtures (PP-12) remain the only ready-made adversarial corpus — OfflineLLM
supplies none.

---

## 4. The vulnerability: control-token injection via user text

This is the sharpest finding in the review and it validates `skein-0ztk` by counterexample.

```cpp
std::string queryString(query);
if (queryString.find("<turn|") != std::string::npos || queryString.find("<start_of_turn>") != std::string::npos) {
     _promptTokens = tokenizeText(llama_model_get_vocab(_model), queryString, true, true);
} else {
    addChatMessage(query, "user");
    ...
}
```
([`LLMInference.cpp#L290-L294`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L290-L294))

`tokenizeText(vocab, text, addSpecial=true, parseSpecial=true)`
([`#L19-L29`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L19-L29)).

**If the user's message contains `<start_of_turn>`, the chat template is bypassed entirely and the
raw message is tokenized with `parse_special = true`** — the literal text becomes real control
tokens. The user can open and close turns, impersonate `model`, and inject a system turn.

The templated path is no better:

```cpp
std::string prompt(_formattedMessages.begin() + _prevLen, _formattedMessages.begin() + new_len);
_promptTokens = tokenizeText(llama_model_get_vocab(_model), prompt,
                                /*add_special=*/_prevLen == 0, /*parse_special=*/true);
```
([`#L333-L336`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L333-L336))

The whole rendered string — template chrome **and** message content — is tokenized with
`parse_special = true`. That is the standard llama.cpp example idiom and it is the bug: the
chrome's `<|im_start|>` must become a control token, and the same flag makes a *user's* literal
`<|im_start|>system` become one too.

The Kotlin layer's defences are cosmetic. `SecurityUtils.sanitizePrompt` takes the first 2048
characters and strips NUL
([`SecurityUtils.kt#L15-L20`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SecurityUtils.kt#L15-L20));
`cleanModelOutput` regex-strips control-token *text* from the **output**
([`InferenceEngine.kt#L211-L236`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L211-L236)).
Neither touches the input tokenization.

**For a single-user chat app with no retrieval, the impact is limited to self-injection** — the
user attacking their own session. The reason it matters for Skein is that **Skein will inject text
the user did not write**: retrieved notes, ingested documents, repository contents, tool results.
In that architecture this is a remote prompt-injection primitive with an attacker-controlled
document as the vector.

Skein already closed it, and the reasoning in the file is exactly right:

> *"llama.cpp's own examples do that with `parse_special = true`, because the template's
> `<|im_start|>` chrome must become real control tokens. The same flag turns a NOTE containing the
> literal text `<|im_start|>system` into a real system turn: the fence is bypassed one layer below
> where any string-level defence can see it."*
> (`inference-service/.../ChatTemplating.kt`, header)

with scaffold → `parseSpecial=true`, content → `parseSpecial=false`, **no exemption for "trusted"
messages**, and a fail-closed segmentation that degrades the answer rather than opening the
primitive.

**Matrix OL-42: REJECT.** No Skein change. Three things follow:

1. `skein-0ztk` / `ChatTemplating` is **confirmed by counterexample** against a shipping app that
   has the bug. Worth one line in that file's header.
2. This is PocketPal's **PP-33/PP-34** (fail-open templating, second template engine) but worse —
   OfflineLLM has the injection without even the mitigation PocketPal's Nunjucks layer
   accidentally provides. Two upstreams, two failures, same root cause: the llama.cpp example
   idiom.
3. Skein's `ChatTemplatingTest` should assert this specific case — *content containing the
   model's own turn-opener text tokenizes to ordinary text tokens, never to the control token* —
   as a named regression with a pointer here (R-10 companion).

**Responsible disclosure note:** this is a real issue in a published, downloadable app. The
scope is self-injection in a single-user local chat, so it is low severity and there is no user
data crossing a trust boundary. A courtesy report to `jegly/OfflineLLM`'s issue tracker would be
appropriate; filing one is outside this bead's authority and is noted for the coordinator.

---

## 5. Single-process design

Already covered in `ARCHITECTURE_ANALYSIS.md` §3; the security consequence, stated for §13:
untrusted GGUF parsing, C++ tensor execution over hostile input, and the user's conversation
database and `EncryptedSharedPreferences` all share one address space and one uid. A memory-safety
bug in `gguf_init_from_file` reaches the chat database.

This is PocketPal's **PP-16** (GGUF parsed inside the app process) arriving independently, and it
is the argument `SKEIN_HUB.md` §3.3 already makes when it deletes `E4.I5`'s planned in-`:app`
`GgufMetadataProbe`:

> *"That is an independent, incomplete parser of hostile input, running in the process that holds
> the vault."*

**Two of two reviewed upstreams parse GGUF in the app process. Skein's `inspect`-inside-`:inference`
decision has no upstream precedent and is the single largest security differentiator found in
this epic.** No change; strong confirmation.

---

## 6. `memtagMode="sync"` — the one thing to adopt

```xml
android:memtagMode="sync"
```
([`AndroidManifest.xml#L24`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L24))

ARM Memory Tagging Extension in synchronous mode: heap allocations are tagged and a
use-after-free or buffer overflow faults **at the access**, not later. It requires MTE-capable
hardware (Armv8.5+; the Pixel 8/9 Tensor generations qualify) and is a no-op elsewhere.

For a process whose job is running a large C++ tensor library over attacker-supplied binary
input, this is the right mitigation and it is one manifest attribute. Skein declares it nowhere
(`grep memtagMode` across `app/src/main/AndroidManifest.xml` and
`inference-service/src/main/AndroidManifest.xml`: no hits).

**Matrix OL-38: ADOPT**, with three caveats that belong in the bead rather than in this document:

- Apply it to the **`:inference` and `:embedder`** processes, which is where the C++ is. Whether
  `:app` should also carry it is a separate question with a different cost profile.
- `sync` costs measurable performance versus `async`. On an inference process this needs a
  measurement, not an assumption — which makes it a `docs/MEASUREMENTS.md` item adjacent to
  `skein-5hr`, and a reason **not** to land it silently before an M0 measurement run.
- GrapheneOS has its own per-app memory-tagging controls and hardened allocator. The manifest
  attribute is the AOSP mechanism and should compose, but this is a claim to verify on the Fold
  rather than assert. Flagged as **could not determine** — no device access.

Skein's isolation makes MTE less *necessary* than it is for OfflineLLM, and no less *valuable*:
defence in depth on the one process that runs untrusted-input C++.

---

## 7. Anti-patterns to leave behind

**`SecurityUtils.secureDelete`** — overwrite the file with `SecureRandom` bytes, then delete
([`SecurityUtils.kt#L34-L60`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SecurityUtils.kt#L34-L60);
advertised in the README as *"Secure deletion — files overwritten before removal"*,
[`README.md#L166`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L166)).
On flash storage with a translation layer, overwriting a logical block does not overwrite the
physical one; on F2FS the write may land in a new segment entirely. It also **defeats
`hasFragileUserData`'s intent and burns write cycles**. And it is called from nowhere — model
deletion uses plain `File.delete()` (`ModelManager.kt#L242`). A README claim with no
implementation behind it and no effect if there were. **Matrix OL-44: REJECT.** Skein's answer is
encryption at rest plus key destruction, which is the only mechanism that works on flash.

**`sanitizePrompt(maxLength = 2048)`** — silently truncating user input at 2048 characters, in the
name of security ([`SecurityUtils.kt#L15-L20`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SecurityUtils.kt#L15)).
It is data loss dressed as a control, and it does not address the actual injection path (§4).
Skein's `ContextBudget` bounds by *tokens* against the real context window and reports rather than
silently truncating. **Matrix OL-43: REJECT.**

**`isPathSandboxed`** ([`#L65-L69`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SecurityUtils.kt#L65))
uses `canonicalPath.startsWith(canonicalSandbox)` — the classic prefix bug: `/data/models-evil`
starts with `/data/models`. Also uncalled. A separator check is required. Noted because Skein's
`ImmutableModelStore` does containment checks and should use path-component comparison, not
`startsWith`, wherever it does.

**`EncryptedSharedPreferences` + StrongBox** ([`SettingsRepository.kt#L84-L116`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L84)):
requests a StrongBox-backed master key, then determines the backend by *reflection* on
`isStrongBoxBacked()` with a feature-detection fallback, and reports `"StrongBox"` when the
**system feature** is present regardless of whether the key actually got it (`#L104-L108`). A
diagnostic label that can lie. Skein's vault is SQLCipher with its own KDF and does not depend on
Jetpack Security. **Matrix OL-45: REFERENCE** — the useful part is the reminder that *"Jetpack
Security doesn't consistently expose StrongBox state across versions"*, which is worth knowing if
any Skein surface ever claims a hardware backing.

Also worth noting: OfflineLLM's chat database is **plaintext Room**
([`ChatDatabase.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/local/ChatDatabase.kt)) —
only `SharedPreferences` is encrypted, while the conversations themselves are not. The README's
"Security — encrypted settings" is precise about this; a reader skimming might not be.

---

## 8. Findings

| Id | Item | Class | Milestone |
|---|---|---|---|
| OL-38 | `android:memtagMode="sync"` on the native-executing process | **ADOPT** (measure `sync` vs `async`; verify GrapheneOS interaction) | M1 |
| OL-39 | `filterTouchesWhenObscured`, `allowBackup=false`, `hasFragileUserData` | **REFERENCE** | — |
| OL-40 | Zero-network manifest enforced by comment | **REFERENCE** | Skein's build-time gate is better |
| OL-41 | Single process: GGUF parsed beside user data | **REJECT** | Confirms PP-16 and `SKEIN_HUB.md` §3.3 |
| OL-42 | `parse_special = true` over user content; `<start_of_turn>` template bypass | **REJECT** | Confirms `skein-0ztk`; add the named `ChatTemplatingTest` case |
| OL-43 | `sanitizePrompt` 2048-char truncation | **REJECT** | — |
| OL-44 | `secureDelete` overwrite-then-delete on flash | **REJECT** | — |
| OL-45 | EncryptedSharedPreferences + reflective StrongBox probe | **REFERENCE** | Backend label can lie |

**Nothing in this document requires a Skein change other than OL-38, and OL-38 needs a
measurement first.**
