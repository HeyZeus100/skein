# MODEL_PROVENANCE_ANALYSIS — what OfflineLLM records, and what that tells us

Directive §11. Upstream `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b` (tag
`5.1.1`), Apache-2.0, reviewed 2026-09-23.

**Summary: OfflineLLM records no provenance of any kind. There is nothing to adopt. The value of
the review is threefold — it confirms that Skein's manifest schema has no upstream equivalent to
borrow from, it supplies one useful and transferable mechanism from an adjacent problem (APK
signature self-check), and it produces a licence-hygiene note about reusing OfflineLLM's own code
that matters if OL-01 is ported.**

---

## 1. The whole of OfflineLLM's model record

```kotlin
@Entity(tableName = "models")
data class ModelInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val contextSize: Int,
    val chatTemplate: String = "",
    val isBundled: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)
```
([`ModelInfo.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/local/entities/ModelInfo.kt),
16 lines; populated at
[`ModelManager.kt#L263-L270`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L263-L270)).

Against §11's thirteen-item checklist:

| §11 asks for | OfflineLLM |
|---|---|
| model name | filename with `_`/`-` → spaces |
| exact artifact | `path` (absolute, machine-local) |
| upstream source | — |
| revision | — |
| SHA-256 | — |
| size | `sizeBytes` |
| quantization | — (`general.file_type` never read) |
| architecture | — (`general.architecture` read then discarded, `GGUFReader.cpp#L18-L22`) |
| license identifier | — |
| license URL/reference | — |
| acquisition method | `isBundled: Boolean` — two states, no source detail |
| compatibility | — |
| verification state | — |

**Two of thirteen, both derived from the filesystem.** The README's "Recommended Models" table
([`README.md#L106-L116`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L106))
is prose pointing at Hugging Face searches — no repo pins, no revisions, no hashes, no licences.
The install instructions tell the user to *"download one from HuggingFace"*
([`README.md#L87`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L87))
with no verification step of any kind.

This is PocketPal's **PP-10** ("integrity fails open when no expectation exists") and **PP-15**
("SHA-256 computed after acceptance, never compared to anything") taken to the limit: there is no
expectation, so there is nothing to fail open *from*.

**Matrix OL-36: REJECT.**

---

## 2. Against Skein's manifest

`core/model/src/main/resources/schema/model-manifest.schema.json` requires, per model:

`manifest_version` (const), `id` (also the immutable-store directory name), `name`, `format`,
`file`, **`sha256`** (pre-mmap gate), **`blake3`** (post-mmap gate), `size_bytes`,
`capabilities`, `context_length`, **`license` as an object with a required `spdx`** (the schema's
own description says it is an object rather than a bare string *"so the license audit always has"*
a machine-readable identifier), `license.url`, `license.notes`, `source.url`,
`source.revision`, an optional sigstore `attestation` block (`bundle_file`, `url`,
`certificate_identity`, `certificate_oidc_issuer`, `covers`), and `companions` for every non-main
file the loader may open.

Twelve of §11's thirteen are present as schema fields; the thirteenth — *verification state* — is
runtime rather than manifest, carried by `ImmutableModelStore.StoredFile` (`sha256`, `blake3`,
persisted to `models.post_mmap_blake3`, migration 004) and enforced on **every load**, not only at
import (`MODEL_STORE.md` §3).

And the separation §11 asks for is already explicit in the design:

> **Content trust** (do the bytes equal what the manifest asserts?) … **origin trust** …
> (`MODEL_STORE.md` §4)

with the schema echoing it — `attestation` is described as *"Origin trust … the SOFT gate, kept
structurally separate from"* the hash gates.

**Conclusion for §11: there is no OfflineLLM mechanism worth recording.** The directive's
instruction — *"Do NOT blindly inherit OfflineLLM's model licensing assumptions. Verify licenses
independently"* — is trivially satisfiable because there are no assumptions to inherit. The only
licence statement about weights anywhere in the repository is the README's prose table, which
names no licence at all.

Skein's own smoke-model manifest entry already reflects the right posture:
`license: "verify upstream before formal citation"` and `formal_m0_candidate: false` for the Q3_K_M
artifact (`tools/m0-benchmark/models.yaml`). Nothing here changes that; OfflineLLM offers no
corroboration of any model's licence and should not be cited as a source for one.

---

## 3. The one transferable mechanism: signature self-check

```kotlin
class SignatureVerifier(private val context: Context) {
    fun isSignedByTrustedCert(): Boolean = try {
        val signatures = ... GET_SIGNING_CERTIFICATES ... apkContentsSigners
        val md = MessageDigest.getInstance("SHA-256")
        signatures.any { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } == TRUSTED_DIGEST }
    } catch (_: Exception) { false }

    companion object {
        // JEGLY keystore SHA-256 — offlineLLM release APK
        private const val TRUSTED_DIGEST = "98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c"
    }
}
```
([`SignatureVerifier.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/SignatureVerifier.kt),
39 lines; surfaced as *"Tamper Detection — release builds verify the APK signing certificate at
startup and refuse to run if repackaged"*,
[`README.md#L71`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L71)).

**As anti-tamper this is theatre.** Code that checks its own signature can be patched out by
anyone who can re-sign the APK; the platform already refuses to install an update signed by a
different key, which is the real protection. It also collides with a FOSS distribution model —
an F-Droid reproducible build signed by F-Droid's key, or a user's own build, would fail the check
and refuse to run. **Skein must not ship this as written.**

**The mechanism is still worth having, for a different job.** `SKEIN_HUB.md` §2.4 already requires
authenticating the sender across the Core↔Hub boundary — and that is the *correct* use of
`GET_SIGNING_CERTIFICATES`: comparing the **other** package's signing certificate against an
expected digest, where the checker and the checked are in different processes with different
trust. Two details from this 39-line file transfer directly:

- `apkContentsSigners` on API ≥ 28, with the deprecated `GET_SIGNATURES` path behind a version
  guard. Skein's `minSdk` is 30, so the legacy branch is dead code and should be dropped.
- Digest **the certificate**, compare hex. A `Signature` equality comparison is the common
  mistake; a digest constant is greppable and auditable.

One defect worth not reproducing: a single `MessageDigest` instance reused inside `signatures.any
{ }` without `reset()`. `digest()` resets implicitly, so it happens to work — but it is the kind
of thing that stops working when someone adds an `update()` call.

**Matrix OL-37: REFERENCE** — for `HubPicker.availability()`/§2.4, not for self-checking.
Also note the Hub direction has a property this one lacks: Skein's diagnostic surface (§12,
`INFERENCE_PLANNER_PROPOSAL.md` §5) can *report* the peer's certificate digest without gating
execution on it, which is strictly more useful and strictly less brittle.

---

## 4. Licence hygiene for reusing OfflineLLM's code

Relevant because `VULKAN_ANALYSIS.md` recommends porting OL-01 (the twelve-line device-list fix)
and `JNI_ANALYSIS.md` recommends OL-19 / OL-08 / OL-09 / OL-29.

| | |
|---|---|
| OfflineLLM licence | **Apache-2.0** (`LICENSE`; declared in `com.jegly.offlineLLM.yml` and `README.md`) |
| `NOTICE` file | **none in the tree** — so no `NOTICE` content to propagate |
| Compatible with Skein's permissive-only rule (directive §11, `skein-v1-autonomous-completion.md` §3) | **Yes.** Apache-2.0 is permissive; this is a licence Skein can carry inside the FOSS app. |
| Chain to be respected | `smollm/` is *"adapted from SmolChat-Android (Apache 2.0)"* ([`README.md#L171`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L171)). Any file-level reuse of `smollm/` inherits **both** attributions: `shubham0204/SmolChat-Android` and `jegly/OfflineLLM`. |
| llama.cpp | MIT, already Skein's dependency and already attributed |

**Obligations if OL-01 is ported** (Apache-2.0 §4):

1. Retain the Apache-2.0 licence text for the contributed portion, or point at it.
2. Retain attribution notices — here, a source comment naming the repository, the tag `5.1.1`, the
   commit `e81091e86013c0605381d15a1ad7276a4be0b92b` and the file/lines.
3. **State that the file was modified.** The port will be modified (Skein's naming, its
   `SKEIN_JNI_TRY`/`CATCH` macros, its two load entry points rather than one).
4. Carry any `NOTICE` content — not applicable, none exists.

Concretely, for `native/llama/jni/skein_jni.cpp`, a comment of the form:

```
/* CPU-only device restriction. Adapted from jegly/OfflineLLM @ 5.1.1
 * (e81091e86013c0605381d15a1ad7276a4be0b92b), smollm/src/main/cpp/LLMInference.cpp
 * lines 137-149, Apache-2.0. Modified: applied to both load entry points and
 * adapted to Skein's JNI error handling. See research/upstream/offlinellm/VULKAN_ANALYSIS.md.
 */
```

plus an entry in whatever third-party-notices surface the `foss` build already carries for
llama.cpp and SQLCipher.

**Alternative worth weighing.** OL-01 is twelve lines implementing an obvious use of a documented
public API (`llama_model_params::devices`). An independent implementation from `llama.h` plus this
document — which is a *description* of the technique, not a copy of the code — carries no
attribution obligation at all. The technique is not copyrightable; the expression is. Given the
size, an independent implementation citing this analysis is the cleaner route and avoids
propagating a two-hop attribution chain into the native tree for twelve lines. **Recommendation:
implement independently, cite this document and the upstream permalink in the comment as prior
art, and do not copy the text verbatim.** Same conclusion for OL-07, OL-08 and OL-29, each of
which is one to three lines.

OL-19 (log capture) and OL-09 (warm-up) are larger and more idiomatic; if they are written by
reading OfflineLLM's version closely, treat them as adaptations and attribute.

---

## 5. Findings

| Id | Item | Class | Note |
|---|---|---|---|
| OL-36 | `ModelInfo`: no hash, no licence, no source, no quantization, no architecture | **REJECT** | Two of §11's thirteen fields; confirms PP-10/PP-15 |
| OL-37 | `SignatureVerifier` APK self-check | **REFERENCE** | Wrong job (self-check is theatre, breaks FOSS rebuilds); right mechanism for `SKEIN_HUB.md` §2.4 peer authentication |

No bead is proposed against §11. `skein-cyq`'s provenance columns and migration
`009_model_origin.sql` (`SKEIN_HUB.md` §3.1 step 8) already cover everything §11 asks for, and
this review found nothing upstream that improves on them.
