// skein-nxk (E4.I3): `onTokens` batching and backpressure,
// POST_REVIEW_RESOLUTIONS.md §3.2 rule 4 and `IInferenceCallback`'s KDoc.
//
// Batching, because a Binder transaction per token at 40 tok/s is 40
// transactions a second against a buffer shared with every other IPC in the
// process. Backpressure, because a client that stops draining must cost the
// service BOUNDED memory: the queue holds at most
// `TransportRules.MAX_INFLIGHT_TOKEN_BATCHES` batches and the oldest is shed
// first, with the count surfaced as `onTokens(…, dropped)` so the client can
// show "output truncated for speed" rather than silently rendering a gap.
//
// The clock and the in-flight count are injected so the whole policy is
// JVM-testable without a Binder.

package app.skein.inference.service

import us.aherrera.skein.ipc.TransportRules

/** One `onTokens` payload. */
data class TokenBatch(
    val pieces: Array<String>,
    val ids: IntArray,
    /** Pieces shed under backpressure since the last delivered batch. */
    val dropped: Int,
) {
    // Array fields: data-class equals would compare identity. Only the tests
    // compare batches, and they compare the fields, but an incorrect equals on
    // a public type is a trap for the next caller.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is TokenBatch &&
                    pieces.contentEquals(other.pieces) &&
                    ids.contentEquals(other.ids) &&
                    dropped == other.dropped
            )

    override fun hashCode(): Int = (pieces.contentHashCode() * 31 + ids.contentHashCode()) * 31 + dropped
}

/**
 * @param nowNanos monotonic clock; production passes `System::nanoTime`.
 * @param inFlightBatches how many batches the transport believes are still
 *   outstanding toward the client.
 * @param emit delivers a batch. Called on the worker thread.
 */
class TokenBatcher(
    private val nowNanos: () -> Long,
    private val inFlightBatches: () -> Int,
    private val emit: (TokenBatch) -> Unit,
) {
    private val pieces = mutableListOf<String>()
    private val ids = mutableListOf<Int>()
    private var bytes = 0
    private var windowStartNanos: Long? = null

    /** Pieces shed under backpressure and not yet reported to the client. */
    var droppedPieces: Int = 0
        private set

    /** Adds one token's text, flushing if either threshold is now met. */
    fun offer(
        piece: String,
        id: Int,
    ) {
        if (windowStartNanos == null) windowStartNanos = nowNanos()
        pieces += piece
        ids += id
        bytes += piece.length * 2 + ID_BYTES

        val elapsed = nowNanos() - (windowStartNanos ?: nowNanos())
        val due =
            pieces.size >= TransportRules.TOKEN_BATCH_MAX_TOKENS ||
                bytes >= TransportRules.TOKEN_BATCH_MAX_BYTES ||
                elapsed >= TransportRules.TOKEN_BATCH_INTERVAL_MS * NANOS_PER_MILLI
        if (due) flush()
    }

    /**
     * Delivers whatever is buffered, or sheds it when the client is at the
     * in-flight ceiling.
     */
    fun flush() {
        if (pieces.isEmpty()) {
            windowStartNanos = null
            return
        }
        if (TransportRules.shouldDropOldestBatch(inFlightBatches())) {
            droppedPieces += pieces.size
            reset()
            return
        }
        emit(TokenBatch(pieces.toTypedArray(), ids.toIntArray(), droppedPieces))
        droppedPieces = 0
        reset()
    }

    private fun reset() {
        pieces.clear()
        ids.clear()
        bytes = 0
        windowStartNanos = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** A token id is a 4-byte int; counted so a batch of long pieces flushes on bytes. */
        const val ID_BYTES = 4
    }
}
