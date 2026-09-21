// E0.I16 (bd `skein-mfw`) — the merged v2 contract (plan §4.7 plus
// POST_REVIEW_RESOLUTIONS.md §3.3's `dropped` backpressure field). See
// `Parcels.kt`'s header for the full merge record.
//
// Implemented on the `:app` side and handed to `IInferenceService.generate`.
// The whole interface is `oneway`, so the service never blocks on the
// client's Binder thread pool.
package us.aherrera.skein.ipc;

import us.aherrera.skein.ipc.GenStats;

oneway interface IInferenceCallback {
    /**
     * Batched every <= 20 ms, <= 16 KiB of pieces + ids per call.
     * dropped > 0 iff the service shed earlier batches under backpressure
     * (the callback queue holds <= 8 batches, oldest dropped first —
     * POST_REVIEW_RESOLUTIONS.md §3.2 rule 4). The client should surface an
     * "output truncated for speed" state.
     */
    void onTokens(int requestId, in String[] pieces, in int[] ids, int dropped);
    void onDone(int requestId, in GenStats stats);
    /** code is an ErrorCode. */
    void onError(int requestId, int code, String message);
}
