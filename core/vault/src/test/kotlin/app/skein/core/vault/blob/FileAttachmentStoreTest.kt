// JVM unit tests for `FileAttachmentStore` (`E2.I5`, skein-1nr). Everything
// here runs on the host JVM -- `javax.crypto` needs no Android runtime -- and
// matches the bd acceptance criteria for skein-1nr one-for-one:
//   1. round-trip byte-size matrix
//   2. bit-flip corruption -> AttachmentCorruptException(cause=AEADBadTagException)
//   3. truncation -> AttachmentTruncatedException
//   4. no plaintext marker survives on disk
//   5. HKDF derives distinct per-id keys
//   6. the master key is never written to disk

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
    // (deterministic per-id HKDF key + per-chunk nonce sequence means a silent
    // overwrite would reuse an AES-GCM (key, nonce) pair for different plaintext)

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
            flipLastByte(file)

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
            truncateAfterFirstChunk(file)

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

        val keyA =
            Hkdf.deriveKey(
                salt = masterKey,
                ikm = "attachment-a".toByteArray(),
                info = SkatFormat.INFO,
                length = 32,
            )
        val keyB =
            Hkdf.deriveKey(
                salt = masterKey,
                ikm = "attachment-b".toByteArray(),
                info = SkatFormat.INFO,
                length = 32,
            )

        assertThat(keyA).isNotEqualTo(keyB)
    }

    @Test
    fun `HKDF re-derives the same key for the same id (deterministic retry)`() {
        val masterKey = master()

        val first =
            Hkdf.deriveKey(
                salt = masterKey,
                ikm = "attachment-a".toByteArray(),
                info = SkatFormat.INFO,
                length = 32,
            )
        val second =
            Hkdf.deriveKey(
                salt = masterKey,
                ikm = "attachment-a".toByteArray(),
                info = SkatFormat.INFO,
                length = 32,
            )

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

    // ---- helpers ----

    private fun flipLastByte(file: java.io.File) {
        RandomAccessFile(file, "rw").use { raf ->
            val lastIndex = raf.length() - 1
            raf.seek(lastIndex)
            val original = raf.read()
            raf.seek(lastIndex)
            raf.write(original xor 0x01)
        }
    }

    private fun truncateAfterFirstChunk(file: java.io.File) {
        // Header (32B) + exactly one full encrypted chunk (chunk + 16B GCM tag);
        // this is a genuine chunk boundary, not a mid-chunk cut.
        val boundary = SkatFormat.HEADER_SIZE + 1_048_576 + SkatFormat.GCM_TAG_BYTES
        RandomAccessFile(file, "rw").use { raf -> raf.setLength(boundary.toLong()) }
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
