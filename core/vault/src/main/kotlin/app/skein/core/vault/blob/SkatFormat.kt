// E2.I5 (skein-1nr): binary layout for the `SKAT` streaming chunked
// container that backs `FileAttachmentStore`.
//
// Header (32 bytes total):
//   offset  size  field
//   0       4     magic "SKAT" (ASCII)
//   4       1     version (=1)
//   5       4     chunkSize, little-endian (=1_048_576 / 1 MiB)
//   9       8     totalPlaintextBytes, little-endian
//   17      15    reserved (=0)
//
// Only the first 9 bytes (magic + version + chunkSize) are "static" -- they
// are known before the first byte of plaintext is seen and are fed into
// every chunk's AAD (`STATIC_HEADER || chunkIndex`). `totalPlaintextBytes`
// is NOT part of any chunk's AAD: it is only known once the caller's write
// callback finishes, so `SkeinAttachmentOutputStream` reserves the header's
// 32 bytes up front, streams+encrypts chunks as they arrive, and patches
// this field in on `close()` -- keeping the container genuinely streaming
// instead of buffering the whole attachment to learn its length first.
//
// Storing the plaintext length here (rather than leaving the header at a
// fixed magic/version/chunk-size triple, as the design doc sketch and the
// bd description's "16-byte header" imply) is a deliberate adjustment: it
// makes `size(id)` an O(1) header read instead of a full decrypt, and — more
// importantly — it is what makes truncation-to-a-chunk-boundary detectable
// at all. Without a recorded total, a file that is missing one or more
// whole trailing chunks looks bitwise identical, from the reader's side, to
// a container that legitimately ends at a chunk boundary. The task's SKAT
// note explicitly allows this ("Total 32 including possible padding —
// adjust as needed").

package app.skein.core.vault.blob

import java.nio.charset.StandardCharsets

internal object SkatFormat {
    const val MAGIC: String = "SKAT"
    const val VERSION: Byte = 1
    const val CHUNK_SIZE: Int = 1_048_576 // 1 MiB
    const val GCM_TAG_BYTES: Int = 16
    const val GCM_TAG_BITS: Int = GCM_TAG_BYTES * 8
    const val NONCE_SIZE: Int = 12
    const val HEADER_SIZE: Int = 32
    const val TOTAL_LENGTH_OFFSET: Int = 9
    val INFO: ByteArray = "skein-attachment-v1".toByteArray(StandardCharsets.UTF_8)

    /** The first [TOTAL_LENGTH_OFFSET] bytes of every header: magic + version + chunk size. Fed into every chunk's AAD. */
    val STATIC_HEADER: ByteArray =
        ByteArray(TOTAL_LENGTH_OFFSET).also { bytes ->
            MAGIC.toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, 0)
            bytes[4] = VERSION
            writeIntLe(CHUNK_SIZE, bytes, 5)
        }

    /** Builds a full [HEADER_SIZE]-byte header with [totalPlaintextBytes] patched in; the rest of the header is zero. */
    fun buildHeader(totalPlaintextBytes: Long): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        STATIC_HEADER.copyInto(header, 0)
        writeLongLe(totalPlaintextBytes, header, TOTAL_LENGTH_OFFSET)
        return header
    }

    /** `true` when [header] starts with the expected magic and a version this reader understands. */
    fun hasValidMagicAndVersion(header: ByteArray): Boolean {
        if (header.size < HEADER_SIZE) return false
        for (i in 0 until 4) {
            if (header[i] != MAGIC[i].code.toByte()) return false
        }
        return header[4] == VERSION
    }

    fun readTotalPlaintextBytes(header: ByteArray): Long = readLongLe(header, TOTAL_LENGTH_OFFSET)

    /** Chunk index, little-endian, zero-padded to [NONCE_SIZE] bytes -- the per-chunk GCM nonce. */
    fun nonceFor(chunkIndex: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        writeLongLe(chunkIndex, nonce, 0)
        return nonce
    }

    /** AAD for chunk [chunkIndex]: the static header bytes followed by the chunk index, little-endian. */
    fun aadFor(chunkIndex: Long): ByteArray {
        val aad = ByteArray(TOTAL_LENGTH_OFFSET + 8)
        STATIC_HEADER.copyInto(aad, 0)
        writeLongLe(chunkIndex, aad, TOTAL_LENGTH_OFFSET)
        return aad
    }

    /** Plaintext length of chunk [chunkIndex] (0-based) out of [totalPlaintextBytes] total, given [CHUNK_SIZE]-byte chunks. */
    fun chunkPlainLength(
        chunkIndex: Long,
        totalPlaintextBytes: Long,
    ): Int {
        val remaining = totalPlaintextBytes - chunkIndex * CHUNK_SIZE
        return minOf(remaining, CHUNK_SIZE.toLong()).toInt()
    }

    /** Number of chunks a [totalPlaintextBytes]-byte attachment is split into (0 for an empty attachment). */
    fun chunkCount(totalPlaintextBytes: Long): Long =
        if (totalPlaintextBytes == 0L) {
            0L
        } else {
            (totalPlaintextBytes + CHUNK_SIZE - 1) / CHUNK_SIZE
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

    private fun writeLongLe(
        value: Long,
        out: ByteArray,
        offset: Int,
    ) {
        for (i in 0 until 8) {
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
