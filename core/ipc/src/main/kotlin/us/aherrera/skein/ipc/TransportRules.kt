// skein-nxk (E4.I3): `TransportRules` — POST_REVIEW_RESOLUTIONS.md §3.2's
// numbers made executable. §5 item 12 assigns them to E4.I3/E4.I4.
//
// PLACEMENT (coordinator decision `skein-hiwb`, superseding §3.3's
// "illustrative" `core/inference` placement): this type lives in `:core:ipc`.
// §3.3 sketched it beside the engine, but `:core:inference` is an Android
// library that the isolation guard forbids `:inference-service` and
// `:embedder-service` from depending on, so a transport policy parked there
// would be unreachable from precisely the two processes that must enforce it.
// `:core:ipc` is on both services' allowlists and already owns the wire shapes
// these rules are about ([SharedMemRef], [GenerateRequest], [EmbedRequest]).
//
// WHY THE NUMBERS ARE WHAT THEY ARE (§3.1/§3.2)
// ============================================================================
//
// Binder gives each process ONE ~1 MiB transaction buffer, shared across every
// in-flight transaction. "Each request is individually under 1 MiB" is
// therefore not a safety property — eight concurrent requests that each fit can
// still exhaust the buffer and take down unrelated IPC in the same process.
// [INLINE_BUDGET_BYTES] is an order of magnitude under 1 MiB so that ~8
// concurrent transactions still fit; [INLINE_REFUSE_BYTES] is the hard wall
// past which a caller is simply wrong.
//
// The rules are enforced on BOTH sides on purpose. The client refuses with
// `IllegalArgumentException` before touching Binder; the service, which cannot
// assume its caller used this class, refuses with [ErrorCode.TX_TOO_LARGE].

package us.aherrera.skein.ipc

import android.os.Parcel
import android.os.Parcelable

/** The transport budget every cross-process call in this contract obeys. */
object TransportRules {
    /**
     * Above this, a payload travels as a [SharedMemRef] instead of inline
     * (§3.2 rule 1, "32 KiB hard"). Not an error — a routing decision.
     */
    const val INLINE_BUDGET_BYTES: Int = 32 * 1024

    /**
     * Above this, a payload is refused outright (§3.2 rule 1, "128 KiB
     * refuse"): nothing this large has any business inline, and accepting it
     * would let one caller monopolise the shared buffer.
     */
    const val INLINE_REFUSE_BYTES: Int = 128 * 1024

    /**
     * How many `onTokens` batches may be queued toward a client before the
     * service sheds the oldest (§3.2 rule 4). A slow or wedged client must
     * cost the service bounded memory, never unbounded — and the client learns
     * exactly how much it missed through `onTokens(…, dropped)`.
     */
    const val MAX_INFLIGHT_TOKEN_BATCHES: Int = 8

    /** A token batch is flushed at least this often, so first output feels immediate. */
    const val TOKEN_BATCH_INTERVAL_MS: Long = 20L

    /** …or this many tokens, whichever comes first. */
    const val TOKEN_BATCH_MAX_TOKENS: Int = 16

    /** A single `onTokens` call stays under this much of pieces + ids (`IInferenceCallback`'s KDoc). */
    const val TOKEN_BATCH_MAX_BYTES: Int = 16 * 1024

    /** At most this many texts per `embed` call, inline or spilled (`EmbedRequest`'s KDoc). */
    const val MAX_EMBED_TEXTS: Int = 32

    /**
     * `role` for a [SharedMemRef] carrying one spilled [ChatMessageParcel]'s
     * content (skein-0rkg). The role/ordering of the message itself stays on
     * the `ChatMessageParcel` — see its KDoc.
     */
    const val ROLE_MESSAGE: String = "message"

    /** `role` for a [SharedMemRef] carrying `EmbedRequest`/`RerankRequest` text that did not fit inline. */
    const val ROLE_INPUT_TEXTS: String = "input-texts"

    /** True when [sizeBytes] must travel out-of-band rather than inline. */
    fun mustSpill(sizeBytes: Int): Boolean = sizeBytes > INLINE_BUDGET_BYTES

    /** True when [sizeBytes] is not expressible inline at all and the call must be refused. */
    fun refuses(sizeBytes: Int): Boolean = sizeBytes > INLINE_REFUSE_BYTES

    /**
     * Client-side gate: throws before the transaction is built.
     *
     * @param what a fixed identifier for the payload (`"messages"`,
     *   `"texts"`, …). **Never** the payload itself: this message reaches logs
     *   and crash reports, and spec §9 forbids prompt or document text there.
     */
    fun requireWithinBudget(
        sizeBytes: Int,
        what: String,
    ) {
        require(!refuses(sizeBytes)) {
            "$what marshals to $sizeBytes bytes, over the $INLINE_REFUSE_BYTES byte inline limit; " +
                "spill it to a SharedMemRef"
        }
    }

    /** True when a service holding [inFlight] queued batches must drop the oldest before enqueuing another. */
    fun shouldDropOldestBatch(inFlight: Int): Boolean = inFlight >= MAX_INFLIGHT_TOKEN_BATCHES

    /**
     * The marshalled size of [value], measured against a real [Parcel].
     *
     * Measured rather than estimated because the interesting quantity is what
     * Binder will actually copy: `String` is UTF-16 on the wire, every
     * `Parcelable` carries its class name, and lists carry a length prefix —
     * an estimate built from `content.length` understates all three, which is
     * the mistake skein-0rkg records.
     */
    fun marshalledSize(value: Parcelable): Int {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
