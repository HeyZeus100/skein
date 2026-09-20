// E2.I5 (skein-1nr): hand-rolled HKDF-SHA256 (RFC 5869) from `javax.crypto.Mac`.
// Kotlin's stdlib has no HKDF, and the plan (`docs/superpowers/plans/
// 2026-09-19-skein-v1-plan.md` §E2.I5) explicitly calls for implementing
// extract+expand directly rather than pulling in a third-party crypto lib.
//
// `FileAttachmentStore` uses this to turn the vault master key into a
// distinct, deterministic per-attachment content key:
//   fileKey = HKDF-SHA256(salt = masterKey, ikm = id.utf8Bytes, info = "skein-attachment-v1", L = 32)
// Salt/IKM are swapped relative to the "usual" HKDF phrasing (secret as IKM,
// public value as salt) on purpose -- see `FileAttachmentStore.deriveFileKey`
// for the rationale (deterministic per-id re-derivation without persisting
// anything).

package app.skein.core.vault.blob

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 HKDF-SHA256, extract-then-expand, built on `HmacSHA256`. */
internal object Hkdf {
    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val MAX_OUTPUT_LEN = 255 * HASH_LEN

    /**
     * Derives [length] bytes of key material from [salt] and [ikm], bound to
     * the context string [info]. Zeroes every intermediate buffer (the PRK
     * and the expand ladder) before returning; the caller owns wiping the
     * returned array and [salt]/[ikm] themselves.
     */
    fun deriveKey(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..MAX_OUTPUT_LEN) { "HKDF output length out of range" }
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }

    /** RFC 5869 §2.2: `PRK = HMAC-Hash(salt, IKM)`. An empty salt hashes as `HASH_LEN` zero bytes. */
    private fun extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, MAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 §2.3: `T(0) = ""`, `T(n) = HMAC-Hash(PRK, T(n-1) || info || n)`, output = `T(1) || T(2) || ...`. */
    private fun expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, MAC_ALGORITHM))
        val blocks = (length + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(blocks * HASH_LEN)
        var previousBlock = ByteArray(0)
        for (blockIndex in 1..blocks) {
            mac.update(previousBlock)
            mac.update(info)
            mac.update(blockIndex.toByte())
            val block = mac.doFinal()
            block.copyInto(okm, (blockIndex - 1) * HASH_LEN)
            previousBlock = block
        }
        val result = okm.copyOf(length)
        okm.fill(0)
        return result
    }
}
