// E2.I5 (skein-1nr) + skein-0nh8: the read side of the `SKAT` container.
//
// Buffers one decrypted chunk at a time (never the whole attachment) and
// verifies each chunk's GCM tag before handing any of its bytes back.
//
// Termination is decided by the *authenticated* final flag in each chunk's
// AAD, never by the header's `total_plaintext_length`. That field is not
// covered by any tag, so v1's habit of computing the chunk count from it let
// an attacker who decreased it cut the plaintext short without ever failing a
// tag check. This reader does not read it at all:
//
//   - a chunk decrypts with the final flag set, and file bytes remain
//       -> AttachmentCorruptException("data after final chunk")
//   - EOF is reached without any chunk having carried the final flag
//       -> AttachmentTruncatedException
//   - a frame header promises more bytes than the file holds
//       -> AttachmentTruncatedException
//   - anything else fails the tag (the frame header is in the AAD too)
//       -> AttachmentCorruptException

package app.skein.core.vault.blob

import app.skein.core.model.DocId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams decrypted plaintext for [id] out of the `SKAT` container at [file].
 *
 * [header] is the already-read, already-version-checked 48-byte header;
 * [fileKey] is the key `FileAttachmentStore` re-derived from the salt inside
 * it. This stream copies [fileKey] and wipes its copy (and its static-header
 * copy, and the decrypted chunk buffer) on [close]; the caller still wipes
 * the arrays it passed in.
 */
internal class SkeinAttachmentInputStream(
    file: File,
    fileKey: ByteArray,
    header: ByteArray,
    private val id: DocId,
) : InputStream() {
    private val keyBytes = fileKey.copyOf()
    private val staticHeader = SkatFormat.staticHeaderOf(header)
    private val raf = RandomAccessFile(file, "r")
    private val fileLength = raf.length()
    private var nextChunkIndex = 0L
    private var currentChunk: ByteArray? = null
    private var currentChunkPos = 0
    private var sawFinalChunk = false
    private var closed = false

    init {
        raf.seek(SkatFormat.HEADER_SIZE.toLong())
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

    private fun corrupt(reason: String): Nothing =
        throw AttachmentException.AttachmentCorruptException(
            id,
            IOException(reason),
        )

    /** Decrypts the next chunk into [currentChunk]; `false` once the final chunk has already been consumed. */
    private fun loadNextChunk(): Boolean {
        if (sawFinalChunk) return false
        val remaining = fileLength - raf.filePointer
        // No final-flagged chunk was ever seen, so the container does not end
        // here -- whatever else is true, bytes are missing.
        if (remaining < SkatFormat.FRAME_HEADER_SIZE + SkatFormat.GCM_TAG_BYTES) {
            throw AttachmentException.AttachmentTruncatedException(id)
        }

        val frame = ByteArray(SkatFormat.FRAME_HEADER_SIZE)
        if (readFully(frame) < frame.size) throw AttachmentException.AttachmentTruncatedException(id)
        val flag = frame[0]
        val plainLen = SkatFormat.readIntLe(frame, 1)
        val isFinal =
            when (flag) {
                SkatFormat.FLAG_FINAL -> true
                SkatFormat.FLAG_INTERIOR -> false
                else -> corrupt("invalid chunk frame")
            }
        if (plainLen < 0 || plainLen > SkatFormat.CHUNK_SIZE) corrupt("invalid chunk frame")
        // Only the last chunk may be short; a short interior chunk is framing
        // this writer never produces.
        if (!isFinal && plainLen != SkatFormat.CHUNK_SIZE) corrupt("invalid chunk frame")

        val ciphertext = ByteArray(plainLen + SkatFormat.GCM_TAG_BYTES)
        if (readFully(ciphertext) < ciphertext.size) throw AttachmentException.AttachmentTruncatedException(id)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(SkatFormat.GCM_TAG_BITS, SkatFormat.nonceFor(nextChunkIndex)),
        )
        // The frame header steers parsing, so it is authenticated alongside the
        // static header (magic|version|chunk size|file salt) and the chunk index.
        cipher.updateAAD(SkatFormat.aadFor(staticHeader, nextChunkIndex, flag, plainLen))
        currentChunk =
            try {
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                throw AttachmentException.AttachmentCorruptException(id, e)
            }
        currentChunkPos = 0
        nextChunkIndex++
        if (isFinal) {
            sawFinalChunk = true
            // The flag is now authenticated, so trailing bytes are not a
            // legitimate continuation of this container.
            if (raf.filePointer < fileLength) corrupt("data after final chunk")
        }
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
        try {
            raf.close()
        } finally {
            keyBytes.fill(0)
            staticHeader.fill(0)
            currentChunk?.fill(0)
            currentChunk = null
        }
    }

    /**
     * Test hook (skein-yn8d): `true` once every buffer holding key material or
     * plaintext has been wiped. Only meaningful after [close].
     */
    internal fun sensitiveBuffersAreZeroed(): Boolean =
        keyBytes.all { it == 0.toByte() } &&
            staticHeader.all { it == 0.toByte() } &&
            currentChunk == null
}
