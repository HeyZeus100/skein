// E2.I5 (skein-1nr): the write side of the `SKAT` container. Buffers up to
// one chunk (`SkatFormat.CHUNK_SIZE`) of plaintext at a time, encrypts it
// with AES-256-GCM under the per-file key, and appends the ciphertext to
// the backing `RandomAccessFile`. The header's `totalPlaintextBytes` field
// is unknown until the caller stops writing, so the constructor reserves
// `HEADER_SIZE` zero bytes up front and `close()` seeks back to patch in
// the real total once it is known.

package app.skein.core.vault.blob

import java.io.OutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams plaintext into [raf] as an encrypted `SKAT` container under
 * [fileKey]. [fileKey] is only read during construction (to build a
 * [SecretKeySpec], which clones the bytes) -- the caller remains
 * responsible for wiping its own copy once this constructor returns.
 */
internal class SkeinAttachmentOutputStream(
    private val raf: RandomAccessFile,
    fileKey: ByteArray,
) : OutputStream() {
    private val secretKey = SecretKeySpec(fileKey, "AES")
    private val chunkBuffer = ByteArray(SkatFormat.CHUNK_SIZE)
    private var chunkBufferLen = 0
    private var chunkIndex = 0L
    private var closed = false

    /** Total plaintext bytes written so far; also the value patched into the header on [close]. */
    var totalPlaintextBytes: Long = 0L
        private set

    init {
        raf.setLength(0)
        raf.seek(0)
        raf.write(ByteArray(SkatFormat.HEADER_SIZE))
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
            val space = chunkBuffer.size - chunkBufferLen
            val n = minOf(space, remaining)
            System.arraycopy(b, offset, chunkBuffer, chunkBufferLen, n)
            chunkBufferLen += n
            offset += n
            remaining -= n
            totalPlaintextBytes += n
            if (chunkBufferLen == chunkBuffer.size) {
                flushChunk()
            }
        }
    }

    /** Encrypts and appends whatever is currently buffered as one chunk. No-op when the buffer is empty. */
    private fun flushChunk() {
        if (chunkBufferLen == 0) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(SkatFormat.GCM_TAG_BITS, SkatFormat.nonceFor(chunkIndex)),
        )
        cipher.updateAAD(SkatFormat.aadFor(chunkIndex))
        val ciphertext = cipher.doFinal(chunkBuffer, 0, chunkBufferLen)
        raf.write(ciphertext)
        chunkIndex++
        chunkBufferLen = 0
    }

    override fun flush() {
        // Deliberately NOT flushing a partial chunk here: a chunk boundary is only
        // meaningful once we know whether more bytes are still coming (a flush
        // mid-stream must not change the on-disk chunk framing that close() relies
        // on to size the final chunk correctly).
    }

    override fun close() {
        if (closed) return
        closed = true
        flushChunk()
        raf.seek(0)
        raf.write(SkatFormat.buildHeader(totalPlaintextBytes))
    }
}
