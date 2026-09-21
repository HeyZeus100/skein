// E2.I5 (skein-1nr) + hardening skein-yn8d / skein-0nh8: binary layout for
// the `SKAT` streaming chunked container that backs `FileAttachmentStore`.
//
// ---------------------------------------------------------------------------
// Header (48 bytes, fixed size)
//
//   offset  size  field
//   0       4     magic "SKAT" (ASCII)
//   4       1     version (=2)
//   5       4     chunkSize, little-endian (=1_048_576 / 1 MiB)
//   9       16    fileSalt -- SecureRandom, fresh per write (skein-yn8d)
//   25      8     totalPlaintextBytes, little-endian -- INFORMATIONAL ONLY
//   33      15    reserved (=0)
//
// Bytes 0..24 are the *static header*: every field in it is known before the
// first byte of plaintext is seen, and all of it is fed into every chunk's
// AAD. `totalPlaintextBytes` deliberately sits *after* the static prefix
// because it is only known once the caller's write callback finishes, so it
// cannot be authenticated by any chunk tag -- see below.
//
// ---------------------------------------------------------------------------
// Chunk frames (repeated until a frame carries the final flag)
//
//   offset  size        field
//   0       1           finalFlag: 0x00 interior, 0xFF final (skein-0nh8)
//   1       4           plainLen, little-endian (0..chunkSize)
//   5       plainLen    AES-256-GCM ciphertext
//   5+pl    16          GCM tag
//
// AAD for chunk `i` = staticHeader(25) || i LE(8) || finalFlag(1) || plainLen LE(4)
//
// The task's required AAD composition is `static prefix || chunk_index ||
// final_flag`; `plainLen` is appended as a strict superset. It is on disk
// because the reader must be able to frame the final chunk *without*
// consulting `totalPlaintextBytes`, and it is in the AAD because anything on
// disk that steers parsing must be authenticated -- otherwise re-framing the
// container would be an unauthenticated way to steer the reader.
//
// Every container ends with exactly one final-flagged frame, including an
// empty attachment (which is a single final frame with `plainLen = 0`). That
// makes "this is the end of the attachment" a statement signed by a GCM tag
// rather than an inference from a mutable length field.
//
// ---------------------------------------------------------------------------
// Why `totalPlaintextBytes` is no longer trusted (skein-0nh8)
//
// v1 computed the chunk count from this field. An attacker who *decreased*
// it made the reader stop early and hand back a silently truncated plaintext.
// v2's reader never reads it: termination comes from the authenticated final
// flag, and chunk boundaries come from the authenticated frame headers. The
// field survives purely so `size(id)` stays an O(1) header read; it is
// documented as unauthenticated everywhere it is exposed.
//
// ---------------------------------------------------------------------------
// Why the salt (skein-yn8d)
//
// v1's file key was `HKDF(salt = master, ikm = id, info = "…-v1")` -- fully
// deterministic in `(master, id)`, so two containers ever written to the same
// id would share a key *and* a per-chunk nonce sequence. The store's
// write-once check prevents that from inside, but not out-of-band (backup
// restore, file manager, an adversary with filesystem write). v2 mixes a
// fresh 16-byte `SecureRandom` salt into the HKDF `info`, so two writes to
// the same id cannot collide on a key no matter how they land on disk, and
// the salt is authenticated by every chunk tag via the static header.

package app.skein.core.vault.blob

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

internal object SkatFormat {
    const val MAGIC: String = "SKAT"

    /** Current container version. v1 containers are refused outright -- see [FileAttachmentStore]. */
    const val VERSION: Byte = 2

    const val CHUNK_SIZE: Int = 1_048_576 // 1 MiB
    const val GCM_TAG_BYTES: Int = 16
    const val GCM_TAG_BITS: Int = GCM_TAG_BYTES * 8
    const val NONCE_SIZE: Int = 12

    const val FILE_SALT_SIZE: Int = 16
    const val FILE_SALT_OFFSET: Int = 9

    /** Bytes 0 until here are the static header: the part fed into every chunk's AAD. */
    const val STATIC_HEADER_SIZE: Int = FILE_SALT_OFFSET + FILE_SALT_SIZE // 25

    const val TOTAL_LENGTH_OFFSET: Int = STATIC_HEADER_SIZE // 25
    const val HEADER_SIZE: Int = 48

    /** `finalFlag || plainLen` prefix in front of each chunk's ciphertext. */
    const val FRAME_HEADER_SIZE: Int = 5

    const val FLAG_INTERIOR: Byte = 0x00
    val FLAG_FINAL: Byte = 0xFF.toByte()

    /** HKDF `info` prefix; the file salt is appended to it, so the info string is per-file. */
    val INFO_PREFIX: ByteArray = "skein-attachment-v2".toByteArray(StandardCharsets.UTF_8)

    private val secureRandom = SecureRandom()

    /** A fresh [FILE_SALT_SIZE]-byte salt for one write. */
    fun newFileSalt(): ByteArray = ByteArray(FILE_SALT_SIZE).also(secureRandom::nextBytes)

    /** HKDF `info` for a container: `"skein-attachment-v2" || fileSalt`. */
    fun infoFor(fileSalt: ByteArray): ByteArray {
        require(fileSalt.size == FILE_SALT_SIZE) { "bad file salt size" }
        val info = ByteArray(INFO_PREFIX.size + FILE_SALT_SIZE)
        INFO_PREFIX.copyInto(info, 0)
        fileSalt.copyInto(info, INFO_PREFIX.size)
        return info
    }

    /**
     * Builds a full [HEADER_SIZE]-byte header. [totalPlaintextBytes] is patched
     * in again on close once it is known; the rest of the header is final from
     * the moment the container is created.
     */
    fun buildHeader(
        fileSalt: ByteArray,
        totalPlaintextBytes: Long,
    ): ByteArray {
        require(fileSalt.size == FILE_SALT_SIZE) { "bad file salt size" }
        val header = ByteArray(HEADER_SIZE)
        MAGIC.toByteArray(StandardCharsets.US_ASCII).copyInto(header, 0)
        header[4] = VERSION
        writeIntLe(CHUNK_SIZE, header, 5)
        fileSalt.copyInto(header, FILE_SALT_OFFSET)
        writeLongLe(totalPlaintextBytes, header, TOTAL_LENGTH_OFFSET)
        return header
    }

    /** `true` when [header] is long enough and starts with the `SKAT` magic. */
    fun hasValidMagic(header: ByteArray): Boolean {
        if (header.size < HEADER_SIZE) return false
        for (i in 0 until 4) {
            if (header[i] != MAGIC[i].code.toByte()) return false
        }
        return true
    }

    fun readVersion(header: ByteArray): Byte = header[4]

    /** The AAD prefix: magic || version || chunkSize || fileSalt. */
    fun staticHeaderOf(header: ByteArray): ByteArray = header.copyOf(STATIC_HEADER_SIZE)

    fun readFileSalt(header: ByteArray): ByteArray =
        header.copyOfRange(
            FILE_SALT_OFFSET,
            FILE_SALT_OFFSET + FILE_SALT_SIZE,
        )

    /**
     * The header's recorded plaintext length. **Unauthenticated**: no chunk tag
     * covers it, so it is a hint for `size(id)` only and must never steer
     * framing or termination.
     */
    fun readTotalPlaintextBytes(header: ByteArray): Long = readLongLe(header, TOTAL_LENGTH_OFFSET)

    /** Chunk index, little-endian, zero-padded to [NONCE_SIZE] bytes -- the per-chunk GCM nonce. */
    fun nonceFor(chunkIndex: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        writeLongLe(chunkIndex, nonce, 0)
        return nonce
    }

    /** `finalFlag || plainLen` as it is written in front of a chunk's ciphertext. */
    fun frameHeader(
        isFinal: Boolean,
        plainLen: Int,
    ): ByteArray {
        val frame = ByteArray(FRAME_HEADER_SIZE)
        frame[0] = if (isFinal) FLAG_FINAL else FLAG_INTERIOR
        writeIntLe(plainLen, frame, 1)
        return frame
    }

    /** AAD for chunk [chunkIndex]: static header || chunk index LE || final flag || plain length LE. */
    fun aadFor(
        staticHeader: ByteArray,
        chunkIndex: Long,
        flag: Byte,
        plainLen: Int,
    ): ByteArray {
        val aad = ByteArray(STATIC_HEADER_SIZE + 8 + FRAME_HEADER_SIZE)
        staticHeader.copyInto(aad, 0)
        writeLongLe(chunkIndex, aad, STATIC_HEADER_SIZE)
        aad[STATIC_HEADER_SIZE + 8] = flag
        writeIntLe(plainLen, aad, STATIC_HEADER_SIZE + 9)
        return aad
    }

    fun readIntLe(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    fun writeLongLe(
        value: Long,
        out: ByteArray,
        offset: Int,
    ) {
        for (i in 0 until 8) {
            out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun writeIntLe(
        value: Int,
        out: ByteArray,
        offset: Int,
    ) {
        for (i in 0 until 4) {
            out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun readLongLe(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
}
