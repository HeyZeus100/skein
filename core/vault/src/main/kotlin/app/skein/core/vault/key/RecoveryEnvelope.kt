// skein-v9g (E3.I11) — the on-disk shape of a passphrase recovery export.
//
// bd `skein-v9g` pins the file to a small JSON document,
// `skein-recovery-<date>.json`, carrying `{version, salt, iterations, iv,
// ciphertext}`. This file owns the serialisation of exactly that document
// and nothing else; `PassphraseKeyExport` owns the crypto.
//
// Why a hand-rolled writer and parser. The document is six flat fields of
// ASCII — three fixed strings, one integer, three base64 blobs — and it is
// parsed on the recovery path, where the input is an arbitrary
// user-supplied file. A full JSON parser is a much larger attack surface
// than the grammar this needs, and `:core:vault` would otherwise gain a
// serialisation dependency for one 200-byte file. The parser below is
// strict by construction: it accepts one canonical spelling, in one field
// order, with no whitespace flexibility beyond the single space it never
// emits — anything else is `MALFORMED`. Round-tripping is therefore
// byte-exact, which is what lets the header double as GCM AAD (below).
//
// Canonical form (no whitespace, fields in exactly this order):
//
//   {"magic":"skein-recovery","version":1,"kdf":"PBKDF2WithHmacSHA256",
//    "iterations":600000,"salt":"<b64>","iv":"<b64>","ciphertext":"<b64>"}
//
// AAD. The GCM tag covers the ciphertext and the IV by construction, but
// NOT the header fields, so the header is bound in as AAD:
//
//   aad = "skein-recovery/v<version>/<kdf>/<iterations>/<saltB64>"
//
// A file whose `version`, `kdf`, `iterations` or `salt` has been edited
// therefore fails the tag check rather than silently deriving a different
// key — the same failure a wrong passphrase produces, deliberately
// indistinguishable from it (`PassphraseKeyExport`'s header).
//
// Confidentiality: the file holds only the AES-256-GCM ciphertext of the
// master key under a passphrase-derived KEK. The plaintext master never
// touches it (`PassphraseKeyExportTest` asserts no window of the master's
// bytes — raw or hex — appears anywhere in the output).

package app.skein.core.vault.key

import java.util.Base64

/**
 * The parsed fields of a recovery export. Values are raw bytes; the base64
 * encoding is an artefact of the JSON transport and does not survive
 * parsing.
 */
internal data class RecoveryEnvelope(
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    /**
     * The header bytes bound into the AES-GCM tag as AAD. Derived from the
     * parsed fields (not from the raw file text), so a rewritten header
     * produces a different AAD and the tag check fails.
     */
    fun aad(): ByteArray = "$MAGIC/v$version/$kdf/$iterations/${encode(salt)}".toByteArray(Charsets.US_ASCII)

    /** Serialises to the canonical JSON form documented at the top of this file. */
    fun toJsonBytes(): ByteArray =
        buildString {
            append("{\"magic\":\"").append(MAGIC)
            append("\",\"version\":").append(version)
            append(",\"kdf\":\"").append(kdf)
            append("\",\"iterations\":").append(iterations)
            append(",\"salt\":\"").append(encode(salt))
            append("\",\"iv\":\"").append(encode(iv))
            append("\",\"ciphertext\":\"").append(encode(ciphertext))
            append("\"}")
        }.toByteArray(Charsets.US_ASCII)

    // ByteArray fields need structural equals/hashCode for test comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecoveryEnvelope) return false
        return version == other.version &&
            kdf == other.kdf &&
            iterations == other.iterations &&
            salt contentEquals other.salt &&
            iv contentEquals other.iv &&
            ciphertext contentEquals other.ciphertext
    }

    override fun hashCode(): Int = version * 31 + iterations

    internal companion object {
        const val MAGIC: String = "skein-recovery"

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        /**
         * Parses the canonical form. Anything else — a different field
         * order, an unknown field, whitespace, a bad base64 blob, a
         * non-numeric or negative `iterations`, trailing bytes — is
         * [RecoveryFailedException.Reason.MALFORMED]. An otherwise
         * well-formed document whose `version` this build does not know is
         * [RecoveryFailedException.Reason.UNSUPPORTED_VERSION], so the user
         * is told to update rather than told their file is corrupt.
         */
        fun parse(bytes: ByteArray): RecoveryEnvelope {
            // Decoding as US-ASCII would silently substitute '?' for a high
            // byte, so reject non-ASCII input up front instead.
            if (bytes.any { it < 0 }) malformed()
            val cursor = Cursor(String(bytes, Charsets.US_ASCII))
            cursor.literal("{\"magic\":\"")
            val magic = cursor.until('"')
            if (magic != MAGIC) malformed()
            cursor.literal("\",\"version\":")
            val version = cursor.int(',')
            cursor.literal(",\"kdf\":\"")
            val kdf = cursor.until('"')
            cursor.literal("\",\"iterations\":")
            val iterations = cursor.int(',')
            cursor.literal(",\"salt\":\"")
            val salt = cursor.base64('"')
            cursor.literal("\",\"iv\":\"")
            val iv = cursor.base64('"')
            cursor.literal("\",\"ciphertext\":\"")
            val ciphertext = cursor.base64('"')
            cursor.literal("\"}")
            cursor.requireEnd()

            if (version != PassphraseKeyExport.FORMAT_VERSION) {
                throw RecoveryFailedException(RecoveryFailedException.Reason.UNSUPPORTED_VERSION)
            }
            if (iterations <= 0) malformed()
            return RecoveryEnvelope(version, kdf, iterations, salt, iv, ciphertext)
        }
    }
}

private fun malformed(): Nothing = throw RecoveryFailedException(RecoveryFailedException.Reason.MALFORMED)

/** Bounds every field so a hostile file cannot make the decoder allocate. */
private const val MAX_FIELD_LEN = 512
private const val MAX_INT_DIGITS = 10

/** A position in the document. Every accessor consumes exactly what it matched, or throws `MALFORMED`. */
private class Cursor(
    private val text: String,
) {
    private var at = 0

    fun literal(expected: String) {
        if (!text.startsWith(expected, at)) malformed()
        at += expected.length
    }

    fun until(terminator: Char): String {
        val end = text.indexOf(terminator, at)
        if (end < 0 || end - at > MAX_FIELD_LEN) malformed()
        return text.substring(at, end).also { at = end }
    }

    fun int(terminator: Char): Int {
        val end = text.indexOf(terminator, at)
        if (end < 0 || end == at || end - at > MAX_INT_DIGITS) malformed()
        val digits = text.substring(at, end)
        if (!digits.all { it in '0'..'9' }) malformed()
        return (digits.toIntOrNull() ?: malformed()).also { at = end }
    }

    fun base64(terminator: Char): ByteArray {
        val encoded = until(terminator)
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    fun requireEnd() {
        if (at != text.length) malformed()
    }
}
