// E0.I16 (bd `skein-mfw`) — the locked cross-process contract for Skein's two
// `isolatedProcess=true` services (`:inference-service`, `:embedder-service`).
//
// ============================================================================
// WHICH DOCUMENT SAYS WHAT (merge record, coordinator decision 2026-09-21)
// ============================================================================
//
// Three authorities describe this contract and none of them, alone, is it:
//
//   A. `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` §4.7 — the v1
//      shapes, plus the LOCK_POLICY_INDEXING.md §7.6/§5.2 additions that were
//      inlined into it on 2026-09-20 (`skein-ltcr`).
//   B. `docs/design/POST_REVIEW_RESOLUTIONS.md` §3.3 — "AIDL contract (v2 —
//      supersedes plan §4.7 v1)", the answer to `skein-pn1l` (Binder's 1 MiB
//      per-process transaction buffer is shared across ALL in-flight
//      transactions, so nothing large may travel inline).
//   C. `docs/design/POST_REVIEW_RESOLUTIONS.md` §2.3 — `ManifestBinding` /
//      `ManifestFileRef`, the answer to `skein-st1r` (every file a service
//      opens is hash-verified, pre- AND post-mmap).
//
// POST_REVIEW_RESOLUTIONS.md §5 item 1 instructs the reconciliation pass to
// "replace v1 AIDL with the v2 shapes in §3.3 ... including ManifestBinding,
// SharedMemRef, onTokens dropped field, new ErrorCode values". That pass is
// this bead. The contract below is the UNION, resolved in this precedence:
//
//   1. §3.3/§2.3 (v2) win wherever they and plan §4.7 describe the same
//      thing — `LoadRequest.binding`, `GenerateRequest.attachmentFds`,
//      `onTokens(..., dropped)`, oneway `cancel`, `EmbedRequest.inputFd`,
//      `EmbedResult`, and ErrorCode 7/8/9/10.
//   2. The LOCK_POLICY_INDEXING.md §7.6/§5.2 additions are ADDITIVE on top:
//      `sessionEpoch` on every request Parcelable, `onSessionLocking` /
//      `onSessionLocked` on both services, oneway `cancel` on
//      `IEmbedderService`, `ErrorCode.SESSION_LOCKED = 11`.
//   3. Where §3.3 elides a signature ("// ..."), plan §4.7 fills the gap and
//      nothing is dropped: `tokenCount`, `IInferenceService.embed`'s
//      `float[]` return, `status()`, `SamplingParcel`, `ChatMessageParcel`,
//      `GenStats`, `EngineStatus`, `EntitySpanParcel`, `ExtractEntitiesRequest`
//      and `RerankRequest`.
//
// Four judgment calls were required where no authority is explicit. They are
// also recorded on `bd note skein-mfw`:
//
//   J1. `AttestationRefParcel` is named by §2.3 (`ManifestBinding.attestation`,
//       "sigstore bundle fd + covers") but never defined anywhere. Defined
//       below as an fd plus the `covers` vocabulary that the manifest schema
//       uses (`main` | `companions` | `all`, per `skein-cqiu`).
//   J2. `EmbedderLoadRequest` — POST_REVIEW line 584 says it "gains a
//       ManifestBinding field", but `:embedder` loads up to THREE independent
//       models (embed, NER, rerank) and a `ManifestBinding.manifestId` "ties
//       this load to a specific models row" (singular). One binding cannot
//       address three rows, so it takes three: `embedBinding` plus nullable
//       `nerBinding` / `rerankBinding`. Every file plan §4.7 listed (model +
//       tokenizer for each of the three) is still reachable, now as a
//       `ManifestFileRef` with the appropriate `role`.
//   J3. §3.3 says "extractEntities and rerank follow the same 'inline or
//       shared-memory' rule" but elides their signatures, so
//       `ExtractEntitiesRequest` and `RerankRequest` each carry an
//       `inputFd: SharedMemRef?` alongside their inline field. Without it the
//       wire contract could not express the rule the document states, which
//       is the `skein-pn1l` defect all over again.
//   J4. `TransportRules` (§3.3) is explicitly "illustrative" and placed in
//       `core/inference`; §5 item 12 assigns it to E4.I3/E4.I4. It is NOT
//       declared here — `:core:ipc` is the wire shape, not the client policy
//       that decides inline-vs-fd. The numbers it will carry are documented
//       below so the contract is readable on its own.
//
//       J4 ADDENDUM (skein-nxk, coordinator decision `skein-hiwb`): reversed
//       on placement, kept on substance. `TransportRules` now DOES live in this
//       module, as `TransportRules.kt`. §3.3's `core/inference` placement was
//       written before the isolation allowlist was enforced and cannot work:
//       `:core:inference` is an Android library, `IsolationGuardPlugin` forbids
//       `:inference-service`/`:embedder-service` from depending on it, and a
//       transport budget the two isolated services cannot see is a budget
//       nothing enforces. The J4 reasoning that `:core:ipc` is "the wire shape,
//       not the policy" still holds for anything model- or engine-shaped; these
//       particular numbers are properties of the Binder transport itself, which
//       is what this module is.
//
//   J5. `ChatMessageParcel.contentFd` (skein-nxk, answering the M0.5 review
//       finding `skein-0rkg`). §3.3 said oversized text spills to
//       `GenerateRequest.attachmentFds` with `role = "text"`, but measured
//       against `TokenBudget` a NORMAL prompt is ~60 KB of text and therefore
//       ~120 KB of UTF-16 Parcel — over the 32 KiB budget and at the 128 KiB
//       refusal wall — so nearly every real `generate` must spill, and the
//       spill shape as specified carries neither a message index nor a chat
//       role. `ChatMessageParcel` therefore takes an optional per-message
//       descriptor instead: role stays attached to its own content, ordering
//       stays the list index, and nothing has to invent a framing convention
//       that E4.I3 and E4.I4 could disagree about. ADDITIVE — the field
//       defaults to null and an inline message parcels exactly as before.
//
//   J6. `IInferenceService.onSessionUnlocked(long epoch)` (skein-nxk). The
//       LOCK_POLICY_INDEXING.md §5.2 delta added only the two LOCKING pushes,
//       but §5.3's gate starts at `SessionEpoch.NONE` and §6.1 invariant I6
//       requires it to refuse "every request until an explicit onUnlocked is
//       received" — and §5.3's own prose says ":app re-sends onUnlocked on
//       every fresh bind". There was no method to send that on, so as locked
//       the contract could never authorize a service: every call would refuse
//       with `SESSION_LOCKED`, forever, including the very first load after a
//       normal unlock. Adding the push is additive (a new `oneway` method) and
//       is the only reading under which §5.3 and §6.1 are both satisfiable.
//       `IEmbedderService` needs the symmetric addition; that is `skein-6j93`,
//       filed rather than done here because `:embedder-service` is `E5.I1`'s.
//
//   J8. `InspectRequest` (skein-91yy / H1). `docs/design/SKEIN_HUB.md` §3.3
//       writes the new method as `ModelInspection inspect(in ManifestBinding
//       binding)`. It is declared below as `inspect(in InspectRequest req)`
//       instead, because §3.3's literal signature has nowhere to put
//       `sessionEpoch` and this file's own rule — "every request Parcelable
//       carries `sessionEpoch`" — is what makes
//       `IsolatedSessionGate.guard()` the first statement of an entry point.
//       The alternatives were both worse: putting the epoch on
//       `ManifestBinding` would duplicate it inside `LoadRequest`, which
//       already carries one, and taking the epoch as a bare second AIDL
//       argument would repeat the defect `skein-x9xn` files against
//       `tokenCount(String)` — a signature with no request Parcelable can
//       never be widened additively, so that method is gated on the weaker
//       "some session is authorized". `InspectRequest` is `LoadRequest`
//       minus the engine knobs an inspection has no use for (no
//       `contextLength`, `threads` or `embeddingMode`: `inspect` creates no
//       `llama_context`), following the coordinator decision on `skein-ltcr`
//       that request-carrying methods take exactly one Parcelable.
//
//       NULLABILITY ADDENDUM. §3.3's sketch types `parameterCount`,
//       `contextLength` and `embeddingWidth` as non-null with `0` meaning
//       "the GGUF does not state it". They are nullable below instead, so a
//       consumer cannot mistake an unanswered field for a real zero — and
//       `parameterCount` in particular is unanswerable through the JNI
//       surface this bead is allowed to use: llama.cpp computes it with
//       `llama_model_n_params`, which has no `external fun`, and neither
//       llama.cpp's GGUF writer nor `gguf-py` emits a `general.parameter_count`
//       key. It is read opportunistically and is null for every GGUF the
//       project's own tooling produces. Recorded on `bd skein-91yy`; adding
//       the native accessor is a separate bead, since H1 adds no native code.
//
// ============================================================================
// IMAGE / LARGE-PAYLOAD TRANSPORT DECISION (E0.I16 acceptance criterion 4)
// ============================================================================
//
// The bead framed this as "inline `ByteArray` <= 512 KiB, else `MemoryFile`-
// backed fd". Measured (`BinderSizeGuardTest`), plan §4.7 v1's
// `GenerateRequest.images: List<ByteArray>` with one 4 MiB image parcels to
// well over Binder's 1 MiB buffer on its own, so the "if the guard fails"
// branch is the one that applies — and §3.2's aggregate-budget argument makes
// the rule unconditional rather than a 512 KiB threshold:
//
//   DECISION: images, audio and oversized text NEVER travel inline. There is
//   no inline attachment field in this contract at all. Every attachment is a
//   `SharedMemRef` — a `ParcelFileDescriptor` over an ashmem `MemoryFile` (or
//   an app-private tmpfile) plus its size and a MIME hint. The Parcel carries
//   the fd and a few bytes of metadata; the service `mmap`s the region
//   read-only, consumes it, and closes the fd (§3.2 rule 3: the service OWNS
//   every fd it receives and MUST `close()` it, success or failure).
//
//   The inline budget for everything else is 32 KiB hard / 128 KiB refuse per
//   transaction (§3.2 rule 1), enforced client-side by `TransportRules`
//   (E4.I3/E4.I4) — an order of magnitude under 1 MiB so ~8 concurrent
//   transactions still fit the shared buffer. 512 KiB inline, the bead's
//   fallback threshold, is superseded: it is legal for a single transaction
//   but not for the aggregate budget `skein-pn1l` is about.
//
// ============================================================================
// SESSION EPOCHS (LOCK_POLICY_INDEXING.md §5.2/§5.3)
// ============================================================================
//
// Every request Parcelable carries `sessionEpoch`. On the service side —
// NOT here, `:core:ipc` is contract-only — `IsolatedSessionGate.guard()`
// (§5.3) is the first statement of every entry point that touches plaintext:
// it admits the call only when `requestEpoch == authorizedEpoch`, and
// otherwise refuses with `ErrorCode.SESSION_LOCKED`. A service that restarts
// comes back with `authorizedEpoch = NONE`, i.e. unauthorized by default.
// The epoch closes the race the `onSessionLocking` push cannot: calls already
// queued on Binder's thread pool when the lock notice arrives.
//
// Relationship to `app.skein.core.inference.models.ManifestBinding`
// (`:core:inference`, landed by `skein-st1r`): that type is the store-side,
// parsed manifest. `app.skein.ipc.ManifestBinding` below is the WIRE
// type — the same information with opened read-only fds in place of paths.
// `skein-cqiu` records that it is "a straight mapping from it, not a second
// parser"; the converter belongs in `:core:inference` (see follow-up bead).

package app.skein.ipc

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result codes returned by the synchronous AIDL entry points and carried by
 * [IInferenceCallback.onError].
 *
 * Each code translates into the `app.skein.core.model.InferenceException`
 * subclass named below. [ErrorCodes.toException] in this package IS that
 * translation — the table below is its prose form, and `ErrorMappingTest`
 * asserts the two agree by reflecting over the constants declared here.
 *
 * `skein-k7e9` closed the gap this KDoc used to record. The `E0.I10` hierarchy
 * had six subclasses (`ModelNotLoaded`, `HashMismatch`, `InvalidModel`,
 * `ServiceDied`, `OutOfMemory`, `Busy`) and twelve codes needed mapping, so
 * five codes shared a subclass with another code or fell back to a plain
 * `java.lang` exception. Six subclasses were added to `E0.I10` ADDITIVELY —
 * `PostMmapHashMismatch`, `CompanionHashMismatch`, `TransactionTooLarge`,
 * `ModelInUse`, `SessionLocked`, `Internal` — so every code now has a
 * dedicated one, as `POST_REVIEW_RESOLUTIONS.md` §3.4 requires ("every new
 * `ErrorCode` maps to a distinct `InferenceException` subclass"). The original
 * six are untouched: same names, same messages.
 *
 * Two codes map to NO exception, deliberately: [OK] and [CANCELLED].
 *
 * `InferenceException.ServiceDied` deliberately has no code: process death is
 * observed through `linkToDeath`, never returned over a transaction that by
 * definition can no longer complete.
 */
object ErrorCode {
    /** Success. Maps to no exception. */
    const val OK = 0

    /** Pre-mmap digest of a [ManifestFileRef] differed. → `InferenceException.HashMismatch`. */
    const val HASH_MISMATCH = 1

    /** File is not a loadable model (bad GGUF/ONNX header, unsupported arch). → `InferenceException.InvalidModel`. */
    const val INVALID_MODEL = 2

    /** Allocation failed while loading or generating. → `InferenceException.OutOfMemory`. */
    const val OOM = 3

    /** Call arrived before a successful `load`. → `InferenceException.ModelNotLoaded`. */
    const val NOT_LOADED = 4

    /** A request is already in flight; one at a time per service. → `InferenceException.Busy`. */
    const val BUSY = 5

    /**
     * The request was cancelled. Maps to no exception: `generate` surfaces it
     * as `GenStats.stopReason == "CANCELLED"` (`StopReason.CANCELLED`) and a
     * suspending client sees `kotlin.coroutines.cancellation.CancellationException`.
     */
    const val CANCELLED = 6

    /**
     * POST_REVIEW_RESOLUTIONS.md §2.3: the digest recomputed over the MAPPED
     * bytes, with a distinct algorithm, differed from the pre-mmap digest —
     * i.e. the file was mutated between verification and use.
     *
     * → `InferenceException.PostMmapHashMismatch` (`skein-k7e9`). Distinct
     * from [HASH_MISMATCH]'s subclass: this one means an active TOCTOU
     * attempt, not a stale or corrupt download.
     */
    const val HASH_MISMATCH_POST_MMAP = 7

    /**
     * POST_REVIEW_RESOLUTIONS.md §3.2: the marshalled payload exceeded the
     * inline budget. Normally unreachable — `TransportRules` refuses
     * client-side with `IllegalArgumentException` before touching Binder —
     * so it exists for a service that is handed an oversized transaction by a
     * client that skipped the guard.
     *
     * → `InferenceException.TransactionTooLarge` (`skein-k7e9`). It used to be
     * a plain `IllegalArgumentException`, which no caller could tell apart
     * from an ordinary argument bug.
     */
    const val TX_TOO_LARGE = 8

    /**
     * POST_REVIEW_RESOLUTIONS.md §2.3: the shared read lock on the main model
     * file could not be taken (`FileChannel.tryLock` returned null), so
     * another holder is using it. Also what `ModelManager.delete` reports.
     *
     * → `InferenceException.ModelInUse` (`skein-k7e9`). Distinct from
     * [BUSY]'s subclass: "another holder has the file" and "this service
     * already has a request in flight" have different remedies.
     */
    const val MODEL_IN_USE = 9

    /**
     * POST_REVIEW_RESOLUTIONS.md §2.3: a COMPANION file's digest differed
     * (tokenizer, mmproj, config, ...) while the main file verified. The
     * failing [ManifestFileRef.role] is carried in the error message.
     *
     * → `InferenceException.CompanionHashMismatch` (`skein-k7e9`), which
     * appends that role to its message.
     */
    const val COMPANION_HASH_MISMATCH = 10

    /**
     * LOCK_POLICY_INDEXING.md §7.6/§5.2 (2026-09-20): `IsolatedSessionGate.guard()`
     * refused the call because `req.sessionEpoch` is not the epoch this
     * service is currently authorized for — the vault locked underneath it.
     *
     * → `InferenceException.SessionLocked` (`skein-k7e9`), which plan `E4.I4`
     * requires to be "distinct from `ServiceDied` — the service is alive and
     * refusing, not dead". Having its own type does not change what a client
     * DOES with it: this is still a lock event, so the client cancels the
     * caller's flow and prompts for unlock rather than rendering an engine
     * error. The type is what lets it tell a refusal apart from a death or a
     * completed call in the first place.
     */
    const val SESSION_LOCKED = 11

    /**
     * Unclassified service-side failure.
     *
     * → `InferenceException.Internal` (`skein-k7e9`), which is also where
     * [ErrorCodes.toException] sends any code this build does not recognise.
     * It used to be a plain `IllegalStateException`, which the `stream`
     * contract ("errors close the flow with an `InferenceException`", plan
     * §4.1) did not permit.
     */
    const val INTERNAL = 99
}

/**
 * A large payload that travels out-of-band, as a read-only fd over ashmem
 * (`MemoryFile`) or an app-private tmpfile, instead of inline in the Parcel
 * (POST_REVIEW_RESOLUTIONS.md §3.2 rule 2).
 *
 * Ownership: the receiving service OWNS [fd] and MUST `close()` it after
 * processing, on success and on failure alike (§3.2 rule 3). A client that
 * needs the region after the call must `dup()` it first.
 *
 * @param fd read-only descriptor over the region.
 * @param sizeBytes exact payload length; the service maps only this much.
 * @param mimeHint `image/png` | `image/jpeg` | `text/plain; charset=utf-8` | `application/octet-stream`.
 * @param role `image` | `audio` | `input-texts` | ... — what the payload is for.
 */
@Parcelize
data class SharedMemRef(
    val fd: ParcelFileDescriptor,
    val sizeBytes: Long,
    val mimeHint: String,
    val role: String,
) : Parcelable

/**
 * One file of a model, opened read-only by `:app` from the immutable store
 * (POST_REVIEW_RESOLUTIONS.md §2.3). The service hashes THIS fd and mmaps
 * THIS fd — never re-opens the path — which is what makes the load TOCTOU-safe.
 *
 * @param role `main`, or one of the manifest companion roles fixed by
 *   `skein-cqiu`: `mmproj`, `tokenizer`, `tokenizer_config`, `config`,
 *   `generation_config`, `license`, `special_tokens_map`.
 * @param expectedSha256 64 lowercase hex characters, compared in constant time.
 * @param expectedSizeBytes sanity check and resource planning.
 */
@Parcelize
data class ManifestFileRef(
    val role: String,
    val fd: ParcelFileDescriptor,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
) : Parcelable

/**
 * Sigstore attestation accompanying a [ManifestBinding].
 *
 * Origin trust (this) and content trust ([ManifestFileRef.expectedSha256])
 * are separate gates; only the digest is a hard gate, so a degraded or
 * expired attestation never blocks a load (POST_REVIEW_RESOLUTIONS.md §2.5).
 *
 * Shape is judgment call J1 in this file's header: §2.3 names the type and
 * says "sigstore bundle fd + covers" without defining it.
 *
 * @param bundleFd read-only fd over the sigstore bundle; owned by the service.
 * @param covers the manifest's `attestation.covers` vocabulary —
 *   `main` | `companions` | `all`. Empty means `main` only.
 */
@Parcelize
data class AttestationRefParcel(
    val bundleFd: ParcelFileDescriptor,
    val covers: List<String>,
) : Parcelable

/**
 * Everything a service needs to verify and load one model, as fds rather than
 * paths (POST_REVIEW_RESOLUTIONS.md §2.3).
 *
 * This is the wire form of the parsed manifest that
 * `app.skein.core.inference.models.ManifestBinding` (`:core:inference`,
 * `skein-st1r`) holds store-side; `skein-cqiu` records that it is "a straight
 * mapping from it, not a second parser".
 *
 * @param manifestId ties this load to a specific `models` row.
 * @param manifestVersion the manifest schema version — 2 (`skein-cqiu`).
 * @param files `main` plus every companion the load flow will open.
 */
@Parcelize
data class ManifestBinding(
    val manifestId: String,
    val manifestVersion: Int,
    val files: List<ManifestFileRef>,
    val attestation: AttestationRefParcel?,
) : Parcelable

/**
 * @param gpuLayers 0 = CPU only; chosen from `docs/MEASUREMENTS.md`.
 * @param embeddingMode true when loading an embedding GGUF (the `:embedder`
 *   GGUF path); enables `IInferenceService.embed`.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class LoadRequest(
    val binding: ManifestBinding,
    val contextLength: Int,
    val threads: Int,
    val gpuLayers: Int,
    val embeddingMode: Boolean,
    val sessionEpoch: Long,
) : Parcelable

/**
 * Everything `IInferenceService.inspect` needs: which model to look at, and
 * for which session (judgment call J8 in this file's header).
 *
 * Deliberately *not* a [LoadRequest]: an inspection creates no
 * `llama_context`, so `contextLength`, `threads` and `embeddingMode` have
 * nothing to configure, and `gpuLayers` would allocate VRAM for a metadata
 * read.
 *
 * @param binding the same wire binding [LoadRequest] carries, verified by the
 *   same pre-mmap gate with the same refusals. The service OWNS every fd in it
 *   and closes them all, on success and on refusal alike (§3.2 rule 3).
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class InspectRequest(
    val binding: ManifestBinding,
    val sessionEpoch: Long,
) : Parcelable

/**
 * What `IInferenceService.inspect` found — `docs/design/SKEIN_HUB.md` §3.3.
 *
 * Every field is derived by llama.cpp itself, inside the isolated process,
 * through JNI entry points that already exist (`modelMeta`, `modelHasVision`,
 * `modelNEmbd`, `applyChatTemplate`). That is the point of the method: §3.3
 * deletes the plan's pure-Kotlin `GgufMetadataProbe`, which would have parsed
 * attacker-controlled length fields in the process that holds the vault, and
 * replaces it with a read performed where a parser exploit has nothing to
 * steal. `:app` never opens a GGUF except to copy and hash opaque bytes.
 *
 * **Only [errorCode] is meaningful unconditionally.** When it is not
 * [ErrorCode.OK] nothing was read — the verification refused, the model did
 * not load, or the session was locked — and every other field is null or
 * false. A caller reads the code first.
 *
 * Total inline payload is a few hundred bytes, two orders of magnitude inside
 * [TransportRules.INLINE_BUDGET_BYTES]; `BinderSizeGuardTest` pins that.
 *
 * Nothing here is trusted as identity: `skein-cyq`'s `ModelManager` compares
 * [architecture] against its own allowlist and clamps [contextLength] against
 * `InferenceConfig.contextLengthCap`, and size/digests/id stay Core-derived
 * (§3.4). A hostile GGUF can lie in every field below; none of them is a gate.
 *
 * @param errorCode [ErrorCode.OK], or the refusal — the same codes `load`
 *   returns for the same binding ([ErrorCode.HASH_MISMATCH],
 *   [ErrorCode.COMPANION_HASH_MISMATCH], [ErrorCode.HASH_MISMATCH_POST_MMAP],
 *   [ErrorCode.INVALID_MODEL], [ErrorCode.MODEL_IN_USE], [ErrorCode.OOM],
 *   [ErrorCode.SESSION_LOCKED]), plus [ErrorCode.BUSY] when a generation is in
 *   flight.
 * @param architecture GGUF `general.architecture` (`llama`, `gemma3`, …), or
 *   null when the file declares none.
 * @param quantization GGUF `general.file_type` rendered as the `llama_ftype`
 *   name it denotes (`Q4_K_M`, `F16`, …), or null when the file declares none.
 *   A value this build does not recognise renders as `ftype <n>` rather than
 *   being dropped, so a newer quantisation is still reportable.
 * @param parameterCount GGUF `general.parameter_count` when a producer
 *   happened to write one. **Null for every GGUF llama.cpp's own tooling
 *   writes** — see the nullability addendum in this file's header: the real
 *   count needs `llama_model_n_params`, which has no JNI entry point, and H1
 *   adds no native code.
 * @param contextLength GGUF `<architecture>.context_length` — the training
 *   context, NOT a promise the device can allocate a KV cache that big. Null
 *   when the file declares none.
 * @param embeddingWidth `llama_model_n_embd`, the width
 *   `IInferenceService.embed` would return. Null when the inspection refused.
 * @param hasVision `LlamaNative.modelHasVision` — the GGUF carries `clip.*` or
 *   `*.vision.*` metadata, so a `mmproj` companion is required (`E4.I11`).
 * @param hasChatTemplate the GGUF declares `tokenizer.chat_template`. A false
 *   here is a warning, not a refusal: `E4.I6`'s ChatML fallback covers it.
 * @param chatTemplateOk `llama_chat_apply_template` succeeded on a two-message
 *   probe of fixed, non-user strings. False when the model embeds no template
 *   or one llama.cpp's non-Jinja renderer cannot apply.
 * @param tokenizerModel GGUF `tokenizer.ggml.model` (`llama`, `gpt2`, `bert`,
 *   …) — §3.3's "tokenizer presence" row, and the only tokenizer identity the
 *   existing JNI surface can answer. Null when the file declares none.
 */
@Parcelize
data class ModelInspection(
    val errorCode: Int,
    val architecture: String?,
    val quantization: String?,
    val parameterCount: Long?,
    val contextLength: Int?,
    val embeddingWidth: Int?,
    val hasVision: Boolean,
    val hasChatTemplate: Boolean,
    val chatTemplateOk: Boolean,
    val tokenizerModel: String?,
) : Parcelable {
    companion object {
        /**
         * The inspection that did not happen: [code] and nothing else.
         *
         * One constructor for every refusal path, so a new field cannot be
         * accidentally populated on one refusal and left null on another.
         */
        fun refused(code: Int): ModelInspection =
            ModelInspection(
                errorCode = code,
                architecture = null,
                quantization = null,
                parameterCount = null,
                contextLength = null,
                embeddingWidth = null,
                hasVision = false,
                hasChatTemplate = false,
                chatTemplateOk = false,
                tokenizerModel = null,
            )
    }
}

/**
 * One turn of the prompt.
 *
 * Exactly one of [content] and [contentFd] carries the text: [content] while
 * the message marshals inside `TransportRules.INLINE_BUDGET_BYTES`, otherwise
 * [contentFd] with [content] empty. Judgment call J5 in this file's header —
 * the answer to `skein-0rkg`, which measured that a NORMAL prompt (persona +
 * history + ~3K tokens of retrieved context) marshals to roughly 120 KB, so
 * spilling is the common case rather than an edge one.
 *
 * Spilling per MESSAGE rather than per REQUEST is the whole point: [role] is
 * the only place the data/instruction boundary survives onto the wire, and a
 * spill that concatenated every turn into one fd would erase it — retrieved
 * document text could then be reassembled into the `system` turn. Ordering is
 * the list index in [GenerateRequest.messages] and needs no separate field.
 *
 * @param role `system` | `user` | `assistant` (`app.skein.core.model.Role.wire`).
 * @param content the inline text, or `""` when it travelled in [contentFd].
 * @param contentFd UTF-8 bytes of this one message's content, owned and closed
 *   by the receiving service like every other fd (§3.2 rule 3), with
 *   `role = TransportRules.ROLE_MESSAGE`. Null for an inline message.
 */
@Parcelize
data class ChatMessageParcel(
    val role: String,
    val content: String,
    val contentFd: SharedMemRef? = null,
) : Parcelable

@Parcelize
data class SamplingParcel(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repeatPenalty: Float,
    val maxTokens: Int,
    val seed: Long,
    val stop: List<String>,
) : Parcelable

/**
 * @param messages inline prompt text; the client keeps messages + sampling
 *   inside the 32 KiB inline budget and spills the remainder to
 *   [attachmentFds] with `role = "text"`.
 * @param attachmentFds images, audio and oversized text — see the transport
 *   decision in this file's header. Only images with an `mmproj` loaded.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class GenerateRequest(
    val requestId: Int,
    val messages: List<ChatMessageParcel>,
    val attachmentFds: List<SharedMemRef>,
    val sampling: SamplingParcel,
    val sessionEpoch: Long,
) : Parcelable

/** @param stopReason `app.skein.core.model.StopReason` name: EOS | LENGTH | STOP_STRING | CANCELLED. */
@Parcelize
data class GenStats(
    val stopReason: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Long,
    val tokensPerSec: Float,
) : Parcelable

@Parcelize
data class EngineStatus(
    val state: String,
    val modelSha256: String?,
    val contextLength: Int,
    val tokensPerSec: Float,
) : Parcelable

/**
 * `:embedder` loads up to three independent models, each its own `models` row
 * and therefore its own [ManifestBinding] — judgment call J2 in this file's
 * header.
 *
 * @param embedBinding the embedding model plus, on the ONNX path, its
 *   `tokenizer` companion.
 * @param embedFormat `onnx` | `gguf`.
 * @param nerBinding the entity-extraction model, or null when NER is disabled.
 * @param rerankBinding the cross-encoder reranker, or null when reranking is disabled.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class EmbedderLoadRequest(
    val embedBinding: ManifestBinding,
    val embedFormat: String,
    val nerBinding: ManifestBinding?,
    val rerankBinding: ManifestBinding?,
    val threads: Int,
    val sessionEpoch: Long,
) : Parcelable

@Parcelize
data class EntitySpanParcel(
    val start: Int,
    val end: Int,
    val text: String,
    val label: String,
    val score: Float,
) : Parcelable

/**
 * Shared by `IEmbedderService.embed` and `IInferenceService.embed` (coordinator
 * decision 2026-09-20, `skein-ltcr`: request-carrying methods take one
 * Parcelable so `sessionEpoch` and future request-scoped fields have one place
 * to land).
 *
 * Exactly one of [texts] and [inputFd] is populated: inline while the texts
 * marshal under the 32 KiB budget, otherwise a length-prefixed UTF-8 blob in
 * shared memory. At most 32 texts per call, either way.
 *
 * @param isQuery selects the `"search_query: "` prefix. `IEmbedderService.embed`
 *   honours it; `IInferenceService.embed` ignores it (that engine has no
 *   prefix behaviour), which is why it defaults to false.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class EmbedRequest(
    val texts: List<String>,
    val inputFd: SharedMemRef? = null,
    val isQuery: Boolean = false,
    val sessionEpoch: Long,
) : Parcelable

/**
 * `IEmbedderService.embed`'s reply.
 *
 * @param flat row-major int8, 256 per text — 8 KiB for the 32-text maximum,
 *   comfortably inline.
 * @param droppedInputs 0 in normal operation; non-zero only if the service had
 *   to shed inputs.
 */
@Parcelize
data class EmbedResult(
    val flat: ByteArray,
    val droppedInputs: Int,
) : Parcelable

/**
 * @param inputFd set instead of [text] when the document exceeds the inline
 *   budget — judgment call J3 in this file's header.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class ExtractEntitiesRequest(
    val text: String,
    val labels: List<String>,
    val inputFd: SharedMemRef? = null,
    val sessionEpoch: Long,
) : Parcelable

/**
 * @param inputFd set instead of [candidates] when they exceed the inline
 *   budget — judgment call J3 in this file's header.
 * @param sessionEpoch `IsolatedSessionGate.guard()` input, see file header.
 */
@Parcelize
data class RerankRequest(
    val query: String,
    val candidates: List<String>,
    val inputFd: SharedMemRef? = null,
    val sessionEpoch: Long,
) : Parcelable
