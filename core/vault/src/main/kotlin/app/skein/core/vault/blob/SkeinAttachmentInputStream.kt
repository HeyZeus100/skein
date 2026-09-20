// E2.I5 (skein-1nr): the read side of the `SKAT` container. Buffers one
// decrypted chunk at a time (never the whole attachment), verifies each
// chunk's GCM tag before handing bytes back to the caller, and turns the
// two failure modes the bd acceptance criteria name into typed exceptions:
// a bad tag becomes `AttachmentCorruptException`, running out of file
// before the header's recorded total is satisfied becomes
// `AttachmentTruncatedException`.

package app.skein.core.vault.blob

import us.aherrera.skein.core.model.DocId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams decrypted plaintext for [id] out of the `SKAT` container at
 * [file], using [fileKey] to derive per-chunk AES-256-GCM keys. As with
 * [SkeinAttachmentOutputStream], [fileKey] is only read during
 * construction; the caller wipes its own copy after this constructor
 * returns.
 */
internal class SkeinAttachmentInputStream(
    file: File,
    fileKey: ByteArray,
    private val id: DocId,
) : InputStream() {
    private val secretKey = SecretKeySpec(fileKey, "AES")
    private val raf = RandomAccessFile(file, "r")
    private val totalPlaintextBytes: Long
    private val chunkCount: Long
    private var nextChunkIndex = 0L
    private var currentChunk: ByteArray? = null
    private var currentChunkPos = 0
    private var closed = false

    init {
        val header = ByteArray(SkatFormat.HEADER_SIZE)
        val headerBytesRead = readFully(header)
        if (headerBytesRead < SkatFormat.HEADER_SIZE) {
            raf.close()
            throw AttachmentException.AttachmentTruncatedException(id)
        }
        if (!SkatFormat.hasValidMagicAndVersion(header)) {
            raf.close()
            throw AttachmentException.AttachmentCorruptException(id, IOException("not a SKAT container"))
        }
        totalPlaintextBytes = SkatFormat.readTotalPlaintextBytes(header)
        chunkCount = SkatFormat.chunkCount(totalPlaintextBytes)
    }

    /** Reads into [dest] until it is full or the file is exhausted; returns the number of bytes actually read. */
    private fun readFully(dest: ByteArray): Int {
        var readSoFar = 0
        while (readSoFar < dest.size) {
            val n = raf.read(dest, readSoFar, dest.size - readSoFar)
            if (n < 0) break
            readSoFar += n
        }
        return readSoFar
    }

    private fun loadNextChunk(): Boolean {
        if (nextChunkIndex >= chunkCount) return false
        val chunkIndex = nextChunkIndex
        val plainLen = SkatFormat.chunkPlainLength(chunkIndex, totalPlaintextBytes)
        val cipherLen = plainLen + SkatFormat.GCM_TAG_BYTES
        val ciphertext = ByteArray(cipherLen)
        val readSoFar = readFully(ciphertext)
        if (readSoFar < cipherLen) {
            throw AttachmentException.AttachmentTruncatedException(id)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(SkatFormat.GCM_TAG_BITS, SkatFormat.nonceFor(chunkIndex)),
        )
        cipher.updateAAD(SkatFormat.aadFor(chunkIndex))
        currentChunk =
            try {
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                throw AttachmentException.AttachmentCorruptException(id, e)
            }
        currentChunkPos = 0
        nextChunkIndex++
        return true
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        var chunk = currentChunk
        while (chunk == null || currentChunkPos >= chunk.size) {
            if (!loadNextChunk()) return -1
            chunk = currentChunk
        }
        val available = chunk.size - currentChunkPos
        val n = minOf(available, len)
        System.arraycopy(chunk, currentChunkPos, b, off, n)
        currentChunkPos += n
        if (currentChunkPos >= chunk.size) currentChunk = null
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        raf.close()
    }
}
