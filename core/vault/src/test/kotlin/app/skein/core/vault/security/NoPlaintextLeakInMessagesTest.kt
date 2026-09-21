// E10.I17 (skein-vvb0) — regression suite asserting that every exception
// type the vault's crypto layers can throw stays free of plaintext, raw
// key bytes, or key-hex in its message. `AttachmentException`'s own KDoc
// already states this as a non-negotiable (`app/.../blob/AttachmentException.kt`
// header); this file is the automated check that holds it to that,
// alongside the sibling `SkeinSQLiteException` / `MasterKeyStorageException`
// paths `SkeinSQLiteDriverTest` / `FileMasterKeyStorageTest` /
// `VaultKeyProviderImplTest` don't already cover (those assert wrapped-bytes
// hex is absent from `UnlockResult.Failed` reasons; this file targets raw
// exception messages/causes thrown out of the store and driver layers
// directly, and the wrong-key path specifically).

package app.skein.core.vault.security

import app.skein.core.vault.blob.AttachmentException
import app.skein.core.vault.blob.FileAttachmentStore
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.SkeinSQLiteException
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.MasterKeyRow
import app.skein.core.vault.key.MasterKeyStorageException
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.RandomAccessFile
import java.security.SecureRandom

class NoPlaintextLeakInMessagesTest {
    @get:Rule
    val tempDir = TempDirRule()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun fullMessage(t: Throwable): String = (t.message.orEmpty()) + (t.cause?.message.orEmpty())

    // ---- FileAttachmentStore / SkatFormat exceptions ---------------------

    @Test
    fun `AttachmentCorruptException from a bit-flip carries neither the plaintext marker nor the master key hex`() =
        runTest {
            // Arrange
            val dir = tempDir.newDir("attach-leak-corrupt")
            val master = ByteArray(32).also(SecureRandom()::nextBytes)
            val masterHex = hex(master)
            val store = FileAttachmentStore(dir) { master.copyOf() }
            val marker = "top-secret-marker-value".repeat(3)
            val id = "doc-leak-corrupt"
            store.write(id) { out -> out.write(marker.toByteArray(Charsets.UTF_8)) }
            val file = dir.resolve(id)
            RandomAccessFile(file, "rw").use { raf ->
                val offset = file.length() - 1
                raf.seek(offset)
                val original = raf.read()
                raf.seek(offset)
                raf.write(original xor 0x01)
            }

            // Act
            val thrown = runCatching { store.open(id).use { it.readBytes() } }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(AttachmentException.AttachmentCorruptException::class.java)
            val message = fullMessage(thrown!!)
            assertThat(message).doesNotContain(marker)
            assertThat(message).doesNotContain(masterHex)
        }

    @Test
    fun `AttachmentTruncatedException carries no plaintext marker or master key hex`() =
        runTest {
            // Arrange
            val dir = tempDir.newDir("attach-leak-truncate")
            val master = ByteArray(32).also(SecureRandom()::nextBytes)
            val masterHex = hex(master)
            val store = FileAttachmentStore(dir) { master.copyOf() }
            val marker = "another-secret-marker".repeat(4)
            val id = "doc-leak-truncate"
            store.write(id) { out -> out.write(marker.toByteArray(Charsets.UTF_8)) }
            val file = dir.resolve(id)
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(file.length() - 4) }

            // Act
            val thrown = runCatching { store.open(id).use { it.readBytes() } }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(AttachmentException.AttachmentTruncatedException::class.java)
            val message = fullMessage(thrown!!)
            assertThat(message).doesNotContain(marker)
            assertThat(message).doesNotContain(masterHex)
        }

    @Test
    fun `UnsupportedVersion exception carries no key material`() =
        runTest {
            // Arrange
            val dir = tempDir.newDir("attach-leak-version")
            val master = ByteArray(32).also(SecureRandom()::nextBytes)
            val masterHex = hex(master)
            val id = "doc-v1"
            val v1 = ByteArray(48 + 64)
            "SKAT".toByteArray(Charsets.US_ASCII).copyInto(v1, 0)
            v1[4] = 1
            dir.mkdirs()
            dir.resolve(id).writeBytes(v1)
            val store = FileAttachmentStore(dir) { master.copyOf() }

            // Act
            val thrown = runCatching { store.open(id) }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(AttachmentException.UnsupportedVersion::class.java)
            assertThat(fullMessage(thrown!!)).doesNotContain(masterHex)
        }

    // ---- SkeinSQLiteDriver exceptions -------------------------------------

    @Test
    fun `wrong-key SkeinSQLiteException never contains the hex of the offered key`() {
        // Arrange
        val fake =
            FakeSkeinSQLiteNative().apply {
                cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
            }
        val wrongKey = ByteArray(32) { 0xEE.toByte() }
        val wrongKeyHex = hex(wrongKey)
        val driver = SkeinSQLiteDriver(fake, wrongKey)

        // Act
        val thrown = runCatching { driver.open("vault.db") }.exceptionOrNull()

        // Assert
        assertThat(thrown).isInstanceOf(SkeinSQLiteException::class.java)
        assertThat(thrown!!.message.orEmpty()).doesNotContain(wrongKeyHex)
        // The constructor's key copy is zeroed regardless of the outcome.
        assertThat(wrongKey.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `EncryptedDatabaseWithoutKeyException never contains any key hex`() {
        // Arrange — no key supplied at all; NOTADB on an encrypted file
        // heuristically maps to this typed exception (SkeinSQLiteDriver KDoc).
        val fake =
            FakeSkeinSQLiteNative().apply {
                cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
            }
        val driver = SkeinSQLiteDriver(fake)

        // Act
        val thrown = runCatching { driver.open("vault.db") }.exceptionOrNull()

        // Assert — a generic, bounded diagnostic phrase only.
        assertThat(thrown!!.message.orEmpty()).matches("(?s)database appears encrypted.*")
    }

    // ---- FileMasterKeyStorage exceptions -----------------------------------

    @Test
    fun `corrupt envelope exception never contains the wrapped-bytes hex`() =
        runTest {
            // Arrange
            val vaultDir = tempDir.newDir("envelope-leak")
            val envelopeFile = FileMasterKeyStorage.envelopeFileIn(vaultDir)
            val storage = FileMasterKeyStorage(envelopeFile)
            val wrapped = ByteArray(48).also(SecureRandom()::nextBytes)
            val wrappedHex = hex(wrapped)
            storage.writeInitial(
                MasterKeyRow(
                    keyVersion = 1,
                    wrappedBytesBiometric = wrapped,
                    wrapIvBiometric = ByteArray(12) { 1 },
                    wrapTagBiometric = null,
                    wrappedBytesCredential = ByteArray(48) { 2 },
                    wrapIvCredential = ByteArray(12) { 3 },
                    wrapTagCredential = null,
                    createdAt = 1L,
                    strongBoxBacked = true,
                ),
            )
            // Corrupt: flip the last byte of the trailing SHA-256 digest.
            val bytes = envelopeFile.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
            envelopeFile.writeBytes(bytes)

            // Act
            val thrown = runCatching { storage.readActive() }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(MasterKeyStorageException::class.java)
            assertThat(thrown!!.message.orEmpty()).doesNotContain(wrappedHex)
        }
}
