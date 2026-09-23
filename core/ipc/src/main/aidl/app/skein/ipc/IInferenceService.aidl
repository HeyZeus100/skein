// E0.I16 (bd `skein-mfw`) — the merged v2 contract. See the header of
// `core/ipc/src/main/kotlin/app/skein/ipc/Parcels.kt` for which
// document each element comes from and for the four recorded judgment calls.
//
// Implemented by `:inference-service` (E4.I3), an `isolatedProcess=true`
// service that depends only on `:core:ipc` and `:core:model`.
//
// Transport: the aggregate inline payload of any single call (arguments AND
// response) stays inside the 32 KiB budget of
// POST_REVIEW_RESOLUTIONS.md §3.2; images, audio and oversized text travel as
// `SharedMemRef` fds, which the service OWNS and MUST close().
//
// Sessions: every request Parcelable carries `sessionEpoch`, which the
// service checks through `IsolatedSessionGate.guard()`
// (LOCK_POLICY_INDEXING.md §5.3) before touching plaintext, refusing a stale
// epoch with `ErrorCode.SESSION_LOCKED`. The gate is service-side and is NOT
// part of this module.
package app.skein.ipc;

import app.skein.ipc.IInferenceCallback;
import app.skein.ipc.LoadRequest;
import app.skein.ipc.GenerateRequest;
import app.skein.ipc.EmbedRequest;
import app.skein.ipc.EngineStatus;
import app.skein.ipc.InspectRequest;
import app.skein.ipc.ModelInspection;
import app.skein.ipc.BackendReportRequest;
import app.skein.ipc.BackendReport;

interface IInferenceService {
    /**
     * Sync. Verifies EVERY fd in req.binding.files against its expected sha256
     * pre-mmap, takes a shared read lock on the main file, then re-digests the
     * mapped bytes with a distinct algorithm post-mmap
     * (POST_REVIEW_RESOLUTIONS.md §2.3). Returns ErrorCode.OK (0) or an
     * ErrorCode.
     */
    int load(in LoadRequest req);
    /**
     * Sync. Additive, H1 (bd skein-91yy), docs/design/SKEIN_HUB.md §3.3.
     *
     * Verifies EVERY fd in req.binding.files exactly as load does — same
     * pre-mmap gate, same refusals, same ErrorCodes — then loads the MODEL
     * ONLY (no llama_context, no KV cache, no GPU offload), reads metadata
     * through the existing JNI entry points, frees the model, and returns the
     * findings. Never allocates the hundreds of megabytes a context for a
     * model the user has not yet accepted would cost.
     *
     * Leaves the service in whatever state it was in: a model loaded before
     * the call is still loaded, with the same handles, after it. Refused with
     * ErrorCode.BUSY while a generate is in flight, rather than queueing
     * behind it on the single worker thread.
     *
     * Never throws for a refusal: the outcome is ModelInspection.errorCode.
     * The service OWNS and closes every fd in req.binding on every path.
     */
    ModelInspection inspect(in InspectRequest req);
    /**
     * Sync. Additive, bd skein-gg11.2 (OL-05, docs/design/SKEIN_HUB.md §12).
     *
     * A privacy-safe diagnostic: which backend devices this build's CURRENT
     * load actually selected (CPU-only at gpuLayers<=0, R-1), which ARM CPU
     * features the linked ggml-cpu was compiled with, how many layers were
     * actually offloaded, and the live context's n_outputs_max/n_batch/
     * n_ubatch. Every field is allowlist-built inside the isolated process
     * (a fixed device-name allowlist, ggml's own compiled-in feature flags,
     * plain counts) -- never a GGUF string, a path, or free text.
     *
     * Works with a loaded model AND without one: with nothing loaded it
     * returns the compile-time CPU feature list only (devices empty,
     * gpuLayersOffloaded 0, the context fields null).
     *
     * Refused with ErrorCode.SESSION_LOCKED like every other entry point.
     * Never BUSY -- unlike inspect, this reads existing state rather than
     * allocating, so it is safe to let it queue behind an in-flight
     * generate on the single worker thread.
     */
    BackendReport backendReport(in BackendReportRequest req);
    /**
     * Async. cb must be a fresh IInferenceCallback (client-owned). Tokens
     * arrive on cb; exactly one onDone or onError per requestId.
     * req.messages + req.sampling stay inside the inline budget; attachments
     * travel in req.attachmentFds.
     */
    void generate(in GenerateRequest req, in IInferenceCallback cb);
    /** oneway. The service acknowledges by emitting onDone(stopReason = "CANCELLED"). */
    oneway void cancel(int requestId);
    /** Sync. Releases the mmap, the fd read lock and the KV cache. Idempotent. */
    void unload();
    /** Only when the loaded model has the EMBEDDING capability. Flattened row-major. req.isQuery is ignored (no "search_query:" prefix behavior on this engine). */
    float[] embed(in EmbedRequest req);
    int tokenCount(String text);
    /** Sync. Small. Never call while a generate is in flight (returns a BUSY state). */
    EngineStatus status();
    // LOCK_POLICY_INDEXING.md §7.6/§5.2 (2026-09-20), additive to the v2 AIDL contract.
    //
    // WHY THE THREE PUSHES ARE NOT ALL THE SAME SHAPE (bd skein-gg11.8).
    // Binder orders `oneway` transactions only relative to EACH OTHER on the
    // same binder object. It gives NO ordering guarantee between a `oneway`
    // call and a LATER two-way call from the same calling thread: the two-way
    // call can be dispatched to a different, already-idle thread in this
    // process's binder-thread pool and be serviced BEFORE the still-queued
    // `oneway` push. So the direction of each push decides its shape:
    //
    //   * UNLOCK is two-way. The caller's very next `load`/`generate` carries
    //     the epoch this push authorizes; if the push can be overtaken, that
    //     first call is refused SESSION_LOCKED on a perfectly unlocked vault —
    //     a spurious refusal on a busy device. A two-way transaction returns to
    //     the caller only after `IsolatedSessionGate.onUnlocked` has run, so
    //     "the push landed" is something the caller can simply know instead of
    //     poll for. The handler is an `AtomicLong` write: no work is added to
    //     the caller's critical path, only the round trip.
    //
    //   * The two LOCK pushes stay `oneway`, deliberately. `:app` sends them
    //     while it is tearing the session down and is about to zero the master
    //     key; it must never be able to be BLOCKED by this process. A two-way
    //     lock push would hand a wedged — or compromised — isolated process a
    //     lever over when (or whether) the vault locks, which is precisely the
    //     fail-safe direction. Refusal after a lock does not depend on the
    //     round trip: `onLocking`/`onLocked` revoke authorization as their
    //     FIRST act, before any cancellation or free, and every request
    //     independently carries `sessionEpoch` for the calls already sitting on
    //     the thread pool when the push arrives (LOCK_POLICY_INDEXING.md §4.1,
    //     §5.2). Losing a race here can only refuse work, never admit it.
    //
    // A two-way unlock CAN overtake a queued `oneway` lock push for an older
    // epoch. That is why both lock handlers check the epoch they name against
    // the authorized one and ignore a stale push (`IsolatedSessionGate`).
    /** oneway. Pushed the instant SessionState enters LOCKING; see LOCK_POLICY_INDEXING.md §5.2. */
    oneway void onSessionLocking(long epoch, long budgetMillis);
    /** oneway. Pushed once LOCKING's budget has elapsed or all in-flight work acknowledged. Idempotent. */
    oneway void onSessionLocked(long epoch);
    /**
     * Sync (bd skein-gg11.8 — see the note above for why this one is not
     * `oneway`). Returns only once the gate has been authorized for `epoch`,
     * so the caller's next request cannot race the push that authorizes it.
     * Idempotent: re-sending the same epoch authorizes the same session again.
     *
     * Pushed on unlock AND on every fresh bind — judgment call J6, see
     * `Parcels.kt`'s header (skein-nxk).
     *
     * `IsolatedSessionGate` (LOCK_POLICY_INDEXING.md §5.3) starts at
     * `SessionEpoch.NONE` and refuses everything; §5.3 says ":app re-sends
     * onUnlocked on every fresh bind" and §6.1 invariant I6 says the service
     * "refuses every request until an explicit onUnlocked is received". §5.2's
     * AIDL delta listed only the two LOCKING pushes, so there was no method to
     * send it on and the gate could never be authorized — every call would
     * refuse with SESSION_LOCKED forever. This is that method. Additive.
     */
    void onSessionUnlocked(long epoch);
}
