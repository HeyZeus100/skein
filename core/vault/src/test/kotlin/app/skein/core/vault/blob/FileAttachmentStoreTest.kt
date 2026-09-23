// JVM unit tests for `FileAttachmentStore` (`E2.I5`, skein-1nr; hardened by
// skein-yn8d + skein-0nh8). Everything here runs on the host JVM --
// `javax.crypto` needs no Android runtime.
//
// The original skein-1nr acceptance criteria, one-for-one:
//   1. round-trip byte-size matrix
//   2. bit-flip corruption -> AttachmentCorruptException(cause=AEADBadTagException)
//   3. truncation -> AttachmentTruncatedException
//   4. no plaintext marker survives on disk
//   5. HKDF derives distinct per-id keys
//   6. the master key is never written to disk
//
// plus the `SKAT` v2 hardening:
//   7. (skein-yn8d) a fresh random file salt per write, mixed into HKDF and
//      authenticated by every chunk tag
//   8. (skein-0nh8) termination authenticated by a per-chunk final flag, so
//      `total_plaintext_length` can be forged without truncating a read

package app.skein.core.vault.blob

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class FileAttachmentStoreTest {
    @get:Rule
    val tempDir = TempDirRule()

    private fun master(fill: Byte = 0x7A): ByteArray = ByteArray(32) { fill }

    private fun newStore(masterKey: () -> ByteArray = { master() }): FileAttachmentStore =
        FileAttachmentStore(dir = tempDir.newDir("attachments"), masterKey = masterKey)

    private fun randomBytes(size: Int): ByteArray = Random(seed = size.toLong()).nextBytes(size)

    /** On-disk offset of the `finalFlag || plainLen` frame header of chunk [index], for a file whose chunks before it are full. */
    private fun frameOffset(index: Int): Long =
        SkatFormat.HEADER_SIZE.toLong() +
            index.toLong() * (SkatFormat.FRAME_HEADER_SIZE + SkatFormat.CHUNK_SIZE + SkatFormat.GCM_TAG_BYTES)

    // ---- acceptance criterion 1: round-trip byte-size matrix ----

    @Test
    fun `round trips 0B, 1B, 1MiB-1, 1MiB and 5MiB+3 bytes exactly`() =
        runTest {
            val store = newStore()
            val sizes = listOf(0, 1, 1_048_576 - 1, 1_048_576, 5 * 1_048_576 + 3)

            for (size in sizes) {
                val id = "doc-size-$size"
                val content = randomBytes(size)

                val written = store.write(id) { out -> out.write(content) }
                val readBack = store.open(id).use { it.readBytes() }
                val reportedSize = store.size(id)

                assertThat(written).isEqualTo(size.toLong())
                assertThat(reportedSize).isEqualTo(size.toLong())
                assertThat(readBack).isEqualTo(content)
            }
        }

    // ---- write-once invariant: overwrite is refused, not silently re-encrypted ----

    @Test
    fun `writing the same id twice throws AlreadyExists and leaves the first write untouched`() =
        runTest {
            val store = newStore()
            val id = "doc-write-once"
            val firstContent = randomBytes(4096)

            store.write(id) { out -> out.write(firstContent) }

            assertThrows<AttachmentException.AlreadyExists> {
                store.write(id) { out -> out.write(randomBytes(2048)) }
            }
            val readBack = store.open(id).use { it.readBytes() }
            assertThat(readBack).isEqualTo(firstContent)
        }

    // ---- acceptance criterion 2: bit-flip corruption ----

    @Test
    fun `flipping one ciphertext byte throws AttachmentCorruptException wrapping AEADBadTagException`() =
        runTest {
            val store = newStore()
            val id = "doc-tamper"
            store.write(id) { out -> out.write(randomBytes(4096)) }
            val file = tempDir.root.resolve("attachments/$id")
            flipByteAt(file, file.length() - 1)

            val thrown =
                assertThrows<AttachmentException.AttachmentCorruptException> {
                    store.open(id).use { it.readBytes() }
                }

            assertThat(thrown.cause).isInstanceOf(AEADBadTagException::class.java)
        }

    // ---- acceptance criterion 3: truncation ----

    @Test
    fun `truncating the file to a chunk boundary throws AttachmentTruncatedException`() =
        runTest {
            val store = newStore()
            val id = "doc-truncate"
            // Two full chunks plus a partial third, so there is a real chunk
            // boundary strictly before the container's true end.
            store.write(id) { out -> out.write(randomBytes(2 * 1_048_576 + 512)) }
            val file = tempDir.root.resolve("attachments/$id")
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(frameOffset(1)) }

            assertThrows<AttachmentException.AttachmentTruncatedException> {
                store.open(id).use { it.readBytes() }
            }
        }

    // ---- acceptance criterion 4: no plaintext marker on disk ----

    @Test
    fun `raw file does not contain the plaintext marker`() =
        runTest {
            val store = newStore()
            val id = "doc-marker"
            val marker = "hello world skein".let { it.repeat((64 / it.length) + 1) }.substring(0, 64)
            check(marker.length == 64)

            store.write(id) { out -> out.write(marker.toByteArray(Charsets.UTF_8)) }
            val rawBytes = tempDir.root.resolve("attachments/$id").readBytes()

            assertThat(String(rawBytes, Charsets.ISO_8859_1)).doesNotContain(marker)
        }

    // ---- acceptance criterion 5: HKDF distinctness ----

    @Test
    fun `HKDF derives different file keys for different ids under the same master`() {
        val masterKey = master()
        val fileSalt = SkatFormat.newFileSalt()

        val keyA = deriveFileKey(masterKey, "attachment-a", fileSalt)
        val keyB = deriveFileKey(masterKey, "attachment-b", fileSalt)

        assertThat(keyA).isNotEqualTo(keyB)
    }

    @Test
    fun `HKDF re-derives the same key for the same id and file salt`() {
        val masterKey = master()
        val fileSalt = SkatFormat.newFileSalt()

        val first = deriveFileKey(masterKey, "attachment-a", fileSalt)
        val second = deriveFileKey(masterKey, "attachment-a", fileSalt)

        assertThat(first).isEqualTo(second)
    }

    // ---- acceptance criterion 6: master key never touches disk ----

    @Test
    fun `master key bytes never appear verbatim in any file under dir`() =
        runTest {
            val masterBytes = ByteArray(32) { SecureRandom().nextInt().toByte() }
            val store = newStore { masterBytes.copyOf() }
            val id = "doc-master-check"

            store.write(id) { out -> out.write(randomBytes(3 * 1_048_576)) }
            store.open(id).use { it.readBytes() }

            val rawFile = tempDir.root.resolve("attachments/$id").readBytes()
            assertThat(containsSubsequence(rawFile, masterBytes)).isFalse()
        }

    /**
     * skein-7yy2 — the `masterKey` supplier's own contract (this class's
     * KDoc: the store "takes the returned array back as its own to wipe
     * (`fill(0)`) the moment the per-file key has been derived"). It is the
     * production-side half of `DeviceVaultOpener.keyCopy()`'s promise that no
     * copy of the master key it hands out survives the call, and the property
     * `AtRestEncryptionInstrumentedTest` could not assert on device (the copy
     * the app makes there is never visible to the test). Every array the
     * supplier hands out — one per `write`, one per `open` — is recorded here
     * and checked after both paths have run.
     */
    @Test
    fun `every master key copy the supplier hands out is wiped after the file key is derived`() =
        runTest {
            // Arrange
            val handedOut = mutableListOf<ByteArray>()
            val store = newStore { master().also(handedOut::add) }
            val id = "doc-master-wipe"

            // Act — both call paths that derive a file key.
            store.write(id) { out -> out.write(randomBytes(64)) }
            store.open(id).use { it.readBytes() }

            // Assert
            check(handedOut.size >= 2) { "expected a master key copy per write and per open, got ${handedOut.size}" }
            assertThat(handedOut.all { copy -> copy.all { it == 0.toByte() } }).isTrue()
        }

    // ---- skein-yn8d: random per-write file salt ----

    @Test
    fun `HKDF derives different file keys for the same id under different file salts`() {
        val masterKey = master()

        val keyA = deriveFileKey(masterKey, "attachment-a", SkatFormat.newFileSalt())
        val keyB = deriveFileKey(masterKey, "attachment-a", SkatFormat.newFileSalt())

        assertThat(keyA).isNotEqualTo(keyB)
    }

    @Test
    fun `two writes to the same id draw different file salts`() =
        runTest {
            val store = newStore()
            val id = "doc-salt-rotation"
            val content = randomBytes(4096)

            store.write(id) { out -> out.write(content) }
            val firstSalt = fileSaltOnDisk(id)
            // Bypasses the write-once check on purpose: this is the out-of-band
            // overwrite (backup restore, file manager) skein-yn8d defends against.
            store.writeContainer(id, allowOverwrite = true) { out -> out.write(content) }

            assertThat(fileSaltOnDisk(id)).isNotEqualTo(firstSalt)
        }

    @Test
    fun `two writes of identical bytes to the same id produce different ciphertext`() =
        runTest {
            val store = newStore()
            val id = "doc-salt-ciphertext"
            val content = randomBytes(4096)

            store.write(id) { out -> out.write(content) }
            val firstBody = containerBody(id)
            store.writeContainer(id, allowOverwrite = true) { out -> out.write(content) }

            assertThat(containerBody(id)).isNotEqualTo(firstBody)
        }

    @Test
    fun `the reader re-derives the file key from the header salt of the container on disk`() =
        runTest {
            val store = newStore()
            val id = "doc-salt-rederive"
            val second = randomBytes(2 * 1_048_576 + 7)

            store.write(id) { out -> out.write(randomBytes(4096)) }
            store.writeContainer(id, allowOverwrite = true) { out -> out.write(second) }

            assertThat(store.open(id).use { it.readBytes() }).isEqualTo(second)
        }

    @Test
    fun `tampering with the header file salt throws AttachmentCorruptException`() =
        runTest {
            val store = newStore()
            val id = "doc-salt-tamper"
            store.write(id) { out -> out.write(randomBytes(4096)) }
            val file = tempDir.root.resolve("attachments/$id")
            flipByteAt(file, SkatFormat.FILE_SALT_OFFSET.toLong())

            assertThrows<AttachmentException.AttachmentCorruptException> {
                store.open(id).use { it.readBytes() }
            }
        }

    // ---- skein-0nh8: authenticated termination ----

    @Test
    fun `a forged total plaintext length does not truncate the bytes a read returns`() =
        runTest {
            val store = newStore()
            val id = "doc-forged-length"
            val content = randomBytes(3 * 1_048_576 + 11)
            store.write(id) { out -> out.write(content) }
            val file = tempDir.root.resolve("attachments/$id")
            // Claim the attachment is 1 byte long: v1 would have handed back
            // exactly that one byte, silently.
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(SkatFormat.TOTAL_LENGTH_OFFSET.toLong())
                val forged = ByteArray(8)
                SkatFormat.writeLongLe(1L, forged, 0)
                raf.write(forged)
            }

            assertThat(store.open(id).use { it.readBytes() }).isEqualTo(content)
        }

    @Test
    fun `forging an interior chunk's final flag throws AttachmentCorruptException`() =
        runTest {
            val store = newStore()
            val id = "doc-forged-flag"
            store.write(id) { out -> out.write(randomBytes(2 * 1_048_576 + 512)) }
            val file = tempDir.root.resolve("attachments/$id")
            // Chunk 0 was sealed as interior; claiming it is the final chunk
            // contradicts its AAD.
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(frameOffset(0))
                raf.write(SkatFormat.FLAG_FINAL.toInt())
            }

            assertThrows<AttachmentException.AttachmentCorruptException> {
                store.open(id).use { it.readBytes() }
            }
        }

    @Test
    fun `dropping the final chunk throws AttachmentTruncatedException`() =
        runTest {
            val store = newStore()
            val id = "doc-dropped-final"
            store.write(id) { out -> out.write(randomBytes(2 * 1_048_576 + 512)) }
            val file = tempDir.root.resolve("attachments/$id")
            // Keep both interior chunks byte-for-byte; remove only the final
            // frame. Every surviving tag still verifies -- the file is short,
            // not corrupt.
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(frameOffset(2)) }

            assertThrows<AttachmentException.AttachmentTruncatedException> {
                store.open(id).use { it.readBytes() }
            }
        }

    @Test
    fun `appending data after the final chunk throws AttachmentCorruptException`() =
        runTest {
            val store = newStore()
            val id = "doc-trailing-data"
            store.write(id) { out -> out.write(randomBytes(4096)) }
            val file = tempDir.root.resolve("attachments/$id")
            file.appendBytes(randomBytes(64))

            val thrown =
                assertThrows<AttachmentException.AttachmentCorruptException> {
                    store.open(id).use { it.readBytes() }
                }

            assertThat(thrown.cause).hasMessageThat().isEqualTo("data after final chunk")
        }

    // ---- compatibility: v1 containers are refused, not read ----

    @Test
    fun `a version 1 container is refused with UnsupportedVersion`() =
        runTest {
            val store = newStore()
            val id = "doc-v1"
            val v1 = ByteArray(SkatFormat.HEADER_SIZE + 64)
            SkatFormat.MAGIC.toByteArray(Charsets.US_ASCII).copyInto(v1, 0)
            v1[4] = 1
            tempDir.root.resolve("attachments/$id").writeBytes(v1)

            val thrown = assertThrows<AttachmentException.UnsupportedVersion> { store.open(id) }

            assertThat(thrown.version).isEqualTo(1)
        }

    // ---- zeroization ----

    @Test
    fun `the writer zeroes its key, salt and plaintext buffers on close`() =
        runTest {
            val file = tempDir.newDir("zeroize-write").resolve("container")
            val out =
                RandomAccessFile(file, "rw").use { raf ->
                    SkeinAttachmentOutputStream(raf, ByteArray(32) { 0x11 }, SkatFormat.newFileSalt()).apply {
                        write(randomBytes(4096))
                        close()
                    }
                }

            assertThat(out.sensitiveBuffersAreZeroed()).isTrue()
        }

    @Test
    fun `the reader zeroes its key and plaintext buffers on close`() =
        runTest {
            val store = newStore()
            val id = "doc-zeroize-read"
            store.write(id) { out -> out.write(randomBytes(4096)) }

            val input = store.open(id) as SkeinAttachmentInputStream
            input.readBytes()
            input.close()

            assertThat(input.sensitiveBuffersAreZeroed()).isTrue()
        }

    // ---- helpers ----

    private fun deriveFileKey(
        masterKey: ByteArray,
        id: String,
        fileSalt: ByteArray,
    ): ByteArray =
        Hkdf.deriveKey(
            salt = masterKey,
            ikm = id.toByteArray(),
            info = SkatFormat.infoFor(fileSalt),
            length = 32,
        )

    private fun fileSaltOnDisk(id: String): ByteArray {
        val raw = tempDir.root.resolve("attachments/$id").readBytes()
        return SkatFormat.readFileSalt(raw.copyOf(SkatFormat.HEADER_SIZE))
    }

    /** Everything after the header: the chunk frames, i.e. the part a fresh salt must re-randomize. */
    private fun containerBody(id: String): ByteArray {
        val raw = tempDir.root.resolve("attachments/$id").readBytes()
        return raw.copyOfRange(SkatFormat.HEADER_SIZE, raw.size)
    }

    private fun flipByteAt(
        file: java.io.File,
        offset: Long,
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val original = raf.read()
            raf.seek(offset)
            raf.write(original xor 0x01)
        }
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }

    private inline fun <reified T : Throwable> assertThrows(body: () -> Unit): T {
        try {
            body()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.name} but got ${t::class.java.name}", t)
        }
        throw AssertionError("expected ${T::class.java.name} but nothing was thrown")
    }
}
