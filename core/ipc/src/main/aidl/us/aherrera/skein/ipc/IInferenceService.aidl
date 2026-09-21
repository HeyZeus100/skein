// E0.I16 (bd `skein-mfw`) — the merged v2 contract. See the header of
// `core/ipc/src/main/kotlin/us/aherrera/skein/ipc/Parcels.kt` for which
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
package us.aherrera.skein.ipc;

import us.aherrera.skein.ipc.IInferenceCallback;
import us.aherrera.skein.ipc.LoadRequest;
import us.aherrera.skein.ipc.GenerateRequest;
import us.aherrera.skein.ipc.EmbedRequest;
import us.aherrera.skein.ipc.EngineStatus;

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
    // LOCK_POLICY_INDEXING.md §7.6/§5.2 (2026-09-20), additive to the v2 AIDL contract:
    /** oneway. Pushed the instant SessionState enters LOCKING; see LOCK_POLICY_INDEXING.md §5.2. */
    oneway void onSessionLocking(long epoch, long budgetMillis);
    /** oneway. Pushed once LOCKING's budget has elapsed or all in-flight work acknowledged. Idempotent. */
    oneway void onSessionLocked(long epoch);
}
