// E0.I16 (bd `skein-mfw`) — the merged v2 contract. See `Parcels.kt`'s
// header for which document each element comes from and for the recorded
// judgment calls (in particular J2, three ManifestBindings on
// EmbedderLoadRequest, and J3, inputFd on extractEntities/rerank).
//
// Implemented by `:embedder-service` (E5.I1), an `isolatedProcess=true`
// service that depends only on `:core:ipc`, `:core:model` and
// onnxruntime-android.
//
// Same transport and session rules as IInferenceService: 32 KiB inline
// budget with `SharedMemRef` fds for anything larger (the service OWNS every
// fd it receives and MUST close() it), and `IsolatedSessionGate.guard()` on
// `req.sessionEpoch` before any plaintext is touched.
package us.aherrera.skein.ipc;

import us.aherrera.skein.ipc.EmbedderLoadRequest;
import us.aherrera.skein.ipc.EmbedRequest;
import us.aherrera.skein.ipc.EmbedResult;
import us.aherrera.skein.ipc.ExtractEntitiesRequest;
import us.aherrera.skein.ipc.RerankRequest;
import us.aherrera.skein.ipc.EntitySpanParcel;

interface IEmbedderService {
    /** Sync. Verifies every fd in each of req's bindings, pre- and post-mmap, exactly as IInferenceService.load does. */
    int load(in EmbedderLoadRequest req);
    /**
     * Texts either inline (within the 32 KiB budget) or in req.inputFd
     * (shared memory). Returns 256 int8 per text, concatenated row-major —
     * <= 8 KiB for the 32-text maximum. req.isQuery selects the
     * "search_query: " prefix. Max 32 texts per call.
     */
    EmbedResult embed(in EmbedRequest req);
    /** Same "inline or shared-memory" rule as embed: req.text or req.inputFd. */
    List<EntitySpanParcel> extractEntities(in ExtractEntitiesRequest req);
    /** Same "inline or shared-memory" rule as embed: req.candidates or req.inputFd. One score per candidate. */
    float[] rerank(in RerankRequest req);
    int tokenCount(String text);
    void unload();
    // LOCK_POLICY_INDEXING.md §7.6/§5.2 (2026-09-20), additive to the v2 AIDL contract:
    /** oneway. Previously absent (POST_REVIEW_RESOLUTIONS.md §3.3 defined embed as synchronous with no cancel path). Interrupts an in-flight embed/extractEntities batch between chunks (§4.2). */
    oneway void cancel(int requestId);
    /** oneway. Pushed the instant SessionState enters LOCKING; see LOCK_POLICY_INDEXING.md §5.2. */
    oneway void onSessionLocking(long epoch, long budgetMillis);
    /** oneway. Pushed once LOCKING's budget has elapsed or all in-flight work acknowledged. Idempotent. */
    oneway void onSessionLocked(long epoch);
}
