// E2.I5 (skein-1nr) + skein-yn8d / skein-0nh8: the write side of the `SKAT`
// container.
//
// The whole header -- including the per-write random `fileSalt` that the
// reader needs in order to re-derive the file key -- is written up front, so
// the static AAD prefix is fixed from the first chunk onwards. Only
// `totalPlaintextBytes` is patched in on `close()`, and nothing authenticates
// it (see `SkatFormat`); it is a convenience for `size(id)`, not a framing
// input.
//
// Sealing a chunk means committing to whether it is the final one, because
// the final flag is in its AAD. This stream therefore keeps exactly one chunk
// of lookahead: a full buffer is *not* sealed when it fills, only once another
// byte arrives (proving it was interior) or `close()` is reached (proving it
// was final). Peak extra memory is one `CHUNK_SIZE` buffer -- the same buffer
// v1 already held -- and every container ends with exactly one final-flagged
// frame, including an empty one.

package app.skein.core.vault.blob

import java.io.OutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams plaintext into [raf] as an encrypted `SKAT` container under
 * [fileKey], salted with [fileSalt].
 *
 * This stream takes its own copy of [fileKey] (it needs the raw bytes for the
 * whole stream, one [SecretKeySpec] per chunk) and wipes that copy, the
 * plaintext chunk buffer and its salt copy on [close]. The caller still owns
 * wiping the arrays it passed in.
 */
internal class SkeinAttachmentOutputStream(
    private val raf: RandomAccessFile,
    fileKey: ByteArray,
    fileSalt: ByteArray,
) : OutputStream() {
    private val keyBytes = fileKey.copyOf()
    private val saltBytes = fileSalt.copyOf()
    private val staticHeader: ByteArray
    private val chunkBuffer = ByteArray(SkatFormat.CHUNK_SIZE)
    private var chunkBufferLen = 0
    private var chunkIndex = 0L
    private var closed = false

    /** Total plaintext bytes written so far; also the value patched into the header on [close]. */
    var totalPlaintextBytes: Long = 0L
        private set

    init {
        val header = SkatFormat.buildHeader(saltBytes, totalPlaintextBytes = 0L)
        staticHeader = SkatFormat.staticHeaderOf(header)
        raf.setLength(0)
        raf.seek(0)
        raf.write(header)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        check(!closed) { "stream is closed" }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            // One-chunk lookahead: a full buffer is only sealed once we know
            // another byte follows it, which is exactly what `remaining > 0`
            // proves. Sealing it when it filled would force a guess at the
            // final flag that close() might have to contradict.
            if (chunkBufferLen == chunkBuffer.size) {
                sealChunk(isFinal = false)
            }
            val n = minOf(chunkBuffer.size - chunkBufferLen, remaining)
            System.arraycopy(b, offset, chunkBuffer, chunkBufferLen, n)
            chunkBufferLen += n
            offset += n
            remaining -= n
            totalPlaintextBytes += n
        }
    }

    /** Encrypts whatever is buffered as one chunk frame and appends it. */
    private fun sealChunk(isFinal: Boolean) {
        val plainLen = chunkBufferLen
        val flag = if (isFinal) SkatFormat.FLAG_FINAL else SkatFormat.FLAG_INTERIOR
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(SkatFormat.GCM_TAG_BITS, SkatFormat.nonceFor(chunkIndex)),
        )
        cipher.updateAAD(SkatFormat.aadFor(staticHeader, chunkIndex, flag, plainLen))
        val ciphertext = cipher.doFinal(chunkBuffer, 0, plainLen)
        raf.write(SkatFormat.frameHeader(isFinal, plainLen))
        raf.write(ciphertext)
        chunkIndex++
        chunkBufferLen = 0
    }

    override fun flush() {
        // Deliberately NOT sealing a partial chunk here: a chunk boundary is only
        // meaningful once we know whether more bytes are still coming (sealing
        // commits to the final flag in that chunk's AAD, and a flush mid-stream
        // must not force that decision early).
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Always exactly one final-flagged frame, even for an empty
            // attachment: "the container ends here" must itself be signed.
            sealChunk(isFinal = true)
            raf.seek(SkatFormat.TOTAL_LENGTH_OFFSET.toLong())
            val total = ByteArray(8)
            SkatFormat.writeLongLe(totalPlaintextBytes, total, 0)
            raf.write(total)
        } finally {
            keyBytes.fill(0)
            saltBytes.fill(0)
            staticHeader.fill(0)
            chunkBuffer.fill(0)
        }
    }

    /**
     * Test hook (skein-yn8d): `true` once every buffer holding key material or
     * plaintext has been wiped. Only meaningful after [close].
     */
    internal fun sensitiveBuffersAreZeroed(): Boolean =
        keyBytes.all { it == 0.toByte() } &&
            saltBytes.all { it == 0.toByte() } &&
            staticHeader.all { it == 0.toByte() } &&
            chunkBuffer.all { it == 0.toByte() }
}
