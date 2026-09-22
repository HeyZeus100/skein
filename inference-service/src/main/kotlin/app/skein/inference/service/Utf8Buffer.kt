// skein-nxk (E4.I3): the UTF-8 reassembly buffer between
// `LlamaNative.tokenToPieceBytes` and `IInferenceCallback.onTokens`.
//
// The boundary hands over BYTES because a token's piece is routinely a
// fragment of a UTF-8 sequence — every CJK character and every emoji is split
// across two or three tokens by any byte-level BPE vocabulary. `String(bytes)`
// per piece would replace those fragments with U+FFFD, so a user's name would
// stream as a row of replacement characters and then be "corrected" on the next
// token. This holds an incomplete trailing sequence until it completes.
//
// Not thread-safe: one buffer per in-flight request, owned by the worker thread.

package app.skein.inference.service

/**
 * Incrementally decodes UTF-8 across arbitrary byte-boundary splits.
 *
 * Contract: the concatenation of every [append] result plus the final [flush]
 * equals `String(everyByteEverAppended, Charsets.UTF_8)`.
 */
class Utf8Buffer {
    private val pending = ByteArray(MAX_SEQUENCE_BYTES)
    private var pendingLength = 0

    /** How many bytes of an incomplete sequence are held back right now. */
    val pendingBytes: Int get() = pendingLength

    /**
     * Decodes as much of [piece] (prefixed by any held-back bytes) as forms
     * complete characters, retaining an incomplete trailing sequence.
     */
    fun append(piece: ByteArray): String {
        if (piece.isEmpty() && pendingLength == 0) return ""

        val combined =
            if (pendingLength == 0) {
                piece
            } else {
                ByteArray(pendingLength + piece.size).also {
                    pending.copyInto(it, 0, 0, pendingLength)
                    piece.copyInto(it, pendingLength)
                }
            }
        pendingLength = 0

        val completeThrough = completeLength(combined)
        val tail = combined.size - completeThrough
        if (tail > 0) {
            combined.copyInto(pending, 0, completeThrough, combined.size)
            pendingLength = tail
        }
        return if (completeThrough == 0) "" else String(combined, 0, completeThrough, Charsets.UTF_8)
    }

    /**
     * Emits whatever incomplete tail is left, as the replacement character.
     *
     * Called once when generation ends. Bytes still held at that point will
     * never be completed — the stream stopped mid-character — and dropping them
     * silently would make a truncated response look whole.
     */
    fun flush(): String {
        if (pendingLength == 0) return ""
        val tail = pending.copyOf(pendingLength)
        pendingLength = 0
        return String(tail, Charsets.UTF_8)
    }

    /**
     * The length of the longest prefix of [bytes] that consists only of
     * complete UTF-8 sequences.
     *
     * Only the LAST few bytes can be incomplete, so this walks backwards from
     * the end over continuation bytes (`10xxxxxx`) until it finds a lead byte,
     * then asks whether that sequence is fully present. An invalid lead byte is
     * treated as complete — it is one byte of garbage that `String(...)` will
     * render as U+FFFD, and holding it back would wedge the buffer forever.
     */
    private fun completeLength(bytes: ByteArray): Int {
        var index = bytes.size - 1
        var continuations = 0
        while (index >= 0 && continuations < MAX_SEQUENCE_BYTES) {
            val b = bytes[index].toInt() and 0xFF
            if (b and 0xC0 != 0x80) {
                val needed = sequenceLength(b)
                val available = continuations + 1
                return if (needed > available) index else bytes.size
            }
            continuations++
            index--
        }
        return bytes.size
    }

    /** Bytes in the sequence a lead byte opens; 1 for ASCII and for anything invalid. */
    private fun sequenceLength(lead: Int): Int =
        when {
            lead and 0x80 == 0x00 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }

    private companion object {
        /** UTF-8 is at most 4 bytes, so at most 3 can ever be held back. */
        const val MAX_SEQUENCE_BYTES = 3
    }
}
