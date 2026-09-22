// skein-nxk (E4.I3, coordinator decision skein-hiwb): lowercase hex
// encode/decode, lifted here from the `:core:inference` BLAKE3 copy that this
// bead deleted.
//
// It lives beside [Blake3] rather than inside it because both digests of
// POST_REVIEW_RESOLUTIONS.md §2.2's dual-hash discipline print through it —
// SHA-256 from `java.security.MessageDigest` as well as BLAKE3 — and
// `ModelVerifier.constantTimeEquals` needs the *decode* half to compare digest
// bytes rather than digest strings. A digest comparison done on `String.equals`
// is the timing leak §2.4's `ConstantTimeCompareTest` exists to catch.

package us.aherrera.skein.core.model

/** Lowercase hex, shared by every digest surface in the codebase. */
public object Hex {
    private const val DIGITS = "0123456789abcdef"

    /** Lowercase hex of [bytes] — two characters per byte. */
    public fun encode(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = DIGITS[v ushr 4]
            chars[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    /**
     * Decodes lowercase-or-uppercase hex, or null when [value] is not
     * well-formed hex.
     *
     * Null rather than an exception on purpose: the caller is usually
     * comparing a digest that arrived from a manifest or over Binder, and a
     * malformed expectation must refuse the load, not crash it.
     */
    public fun decodeOrNull(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
