// skein-txrh — JVM unit tests for [FileMasterKeyStorage], the production
// `MasterKeyStorage` backend (the key-envelope file that lives OUTSIDE the
// SQLCipher database it keys — `ATTACHMENT_ENCRYPTION.md` §3.4 amendment,
// `VAULT_FORMAT.md` §1). One behaviour per test, AAA structure.

package app.skein.core.vault.key

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File

class FileMasterKeyStorageTest {
    @get:Rule
    val tempDir = TempDirRule()

    private fun envelopeFile(): File = FileMasterKeyStorage.envelopeFileIn(tempDir.root)

    private fun storage(): FileMasterKeyStorage = FileMasterKeyStorage(envelopeFile())

    private fun row(
        keyVersion: Int = 1,
        bio: ByteArray? = ByteArray(48) { (it + 1).toByte() },
        bioIv: ByteArray? = ByteArray(12) { (0x10 + it).toByte() },
        cred: ByteArray? = ByteArray(48) { (0x40 + it).toByte() },
        credIv: ByteArray? = ByteArray(12) { (0x20 + it).toByte() },
        strongBox: Boolean = true,
    ) = MasterKeyRow(
        keyVersion = keyVersion,
        wrappedBytesBiometric = bio,
        wrapIvBiometric = bioIv,
        wrapTagBiometric = null,
        wrappedBytesCredential = cred,
        wrapIvCredential = credIv,
        wrapTagCredential = null,
        createdAt = 1_700_000_000_000L,
        strongBoxBacked = strongBox,
    )

    // ---- layout contract -----------------------------------------------

    @Test
    fun `envelope lives at keys slash key-envelope v1 under the vault dir`() {
        // Arrange / Act
        val file = FileMasterKeyStorage.envelopeFileIn(File("/vault"))
        // Assert — the `keys/` prefix is what the backup exclusion rules name.
        assertThat(file.path).isEqualTo("/vault/keys/key-envelope.v1")
    }

    // ---- missing -------------------------------------------------------

    @Test
    fun `readActive returns null when no envelope exists`() {
        // Arrange
        val storage = storage()
        // Act
        val active = storage.readActive()
        // Assert
        assertThat(active).isNull()
    }

    @Test
    fun `readActive returns null when the keys directory itself is missing`() {
        // Arrange — the vault dir exists but `keys/` was never created.
        val storage = storage()
        assertThat(File(tempDir.root, "keys").exists()).isFalse()
        // Act / Assert
        assertThat(storage.readActive()).isNull()
    }

    // ---- round trip ----------------------------------------------------

    @Test
    fun `writeInitial then readActive round-trips every field`() {
        // Arrange
        val storage = storage()
        val written = row()
        // Act
        val version = storage.writeInitial(written)
        // Assert
        assertThat(version).isEqualTo(1)
        assertThat(storage.readActive()).isEqualTo(written)
    }

    @Test
    fun `round trip preserves strongBoxBacked=false`() {
        // Arrange
        val storage = storage()
        // Act
        storage.writeInitial(row(strongBox = false))
        // Assert
        assertThat(storage.readActive()!!.strongBoxBacked).isFalse()
    }

    @Test
    fun `round trip preserves an absent factor as null`() {
        // Arrange — the credential factor is currently dead (§3.4 NULL semantics).
        val storage = storage()
        val written = row(cred = null, credIv = null)
        // Act
        storage.writeInitial(written)
        // Assert
        val read = storage.readActive()!!
        assertThat(read.wrappedBytesCredential).isNull()
        assertThat(read.wrapIvCredential).isNull()
        assertThat(read).isEqualTo(written)
    }

    @Test
    fun `writeInitial creates the keys directory`() {
        // Arrange
        val storage = storage()
        // Act
        storage.writeInitial(row())
        // Assert
        assertThat(envelopeFile().isFile).isTrue()
    }

    @Test
    fun `a fresh storage instance over the same file reads what another wrote`() {
        // Arrange
        storage().writeInitial(row())
        // Act
        val read = storage().readActive()
        // Assert — no in-memory caching is load-bearing.
        assertThat(read).isEqualTo(row())
    }

    // ---- writeInitial guards -------------------------------------------

    @Test
    fun `writeInitial refuses to overwrite an existing envelope`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        // Act
        val error =
            assertThrows(MasterKeyStorageException::class.java) {
                storage.writeInitial(row(bio = ByteArray(48) { 0x7f }))
            }
        // Assert — typed, and the original envelope is untouched.
        assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.ALREADY_INITIALISED)
        assertThat(storage.readActive()).isEqualTo(row())
    }

    // ---- rewrap --------------------------------------------------------

    @Test
    fun `rewrap bumps the version and replaces only the rewrapped factor`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        val newWrapped = ByteArray(48) { (0x60 + it).toByte() }
        val newIv = ByteArray(12) { (0x30 + it).toByte() }
        // Act
        val newVersion =
            storage.rewrap(
                currentVersion = 1,
                rewrappedFactor = VaultKeyProvider.Factor.BIOMETRIC,
                wrappedBytes = newWrapped,
                iv = newIv,
                tag = null,
                now = 1_700_000_001_000L,
            )
        // Assert
        assertThat(newVersion).isEqualTo(2)
        val read = storage.readActive()!!
        assertThat(read.keyVersion).isEqualTo(2)
        assertThat(read.wrappedBytesBiometric).isEqualTo(newWrapped)
        assertThat(read.wrapIvBiometric).isEqualTo(newIv)
        // The surviving factor is copied forward byte-for-byte (§3.5 step 8).
        assertThat(read.wrappedBytesCredential).isEqualTo(row().wrappedBytesCredential)
        assertThat(read.wrapIvCredential).isEqualTo(row().wrapIvCredential)
    }

    @Test
    fun `rewrap of the credential factor keeps the biometric factor`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        val newWrapped = ByteArray(48) { (0x60 + it).toByte() }
        // Act
        storage.rewrap(1, VaultKeyProvider.Factor.DEVICE_CREDENTIAL, newWrapped, ByteArray(12), null, 5L)
        // Assert
        val read = storage.readActive()!!
        assertThat(read.wrappedBytesCredential).isEqualTo(newWrapped)
        assertThat(read.wrappedBytesBiometric).isEqualTo(row().wrappedBytesBiometric)
    }

    @Test
    fun `rewrap with a stale currentVersion is rejected and leaves the envelope unchanged`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        // Act
        assertThrows(IllegalStateException::class.java) {
            storage.rewrap(99, VaultKeyProvider.Factor.BIOMETRIC, ByteArray(48), ByteArray(12), null, 5L)
        }
        // Assert
        assertThat(storage.readActive()).isEqualTo(row())
    }

    @Test
    fun `rewrap without an envelope is rejected`() {
        // Arrange
        val storage = storage()
        // Act / Assert
        assertThrows(IllegalStateException::class.java) {
            storage.rewrap(1, VaultKeyProvider.Factor.BIOMETRIC, ByteArray(48), ByteArray(12), null, 5L)
        }
    }

    // ---- atomic replace ------------------------------------------------

    @Test
    fun `writes leave no temp file behind`() {
        // Arrange
        val storage = storage()
        // Act
        storage.writeInitial(row())
        storage.rewrap(1, VaultKeyProvider.Factor.BIOMETRIC, ByteArray(48), ByteArray(12), null, 5L)
        // Assert — only the envelope itself is in `keys/`.
        val names = envelopeFile().parentFile!!.list()!!.toList()
        assertThat(names).containsExactly(FileMasterKeyStorage.ENVELOPE_FILE_NAME)
    }

    @Test
    fun `a stale temp file from an interrupted write is replaced, not read`() {
        // Arrange — simulate a crash after the temp file was created but
        // before the rename: garbage sits at the temp path.
        val storage = storage()
        val tmp = File(envelopeFile().parentFile, FileMasterKeyStorage.ENVELOPE_FILE_NAME + ".tmp")
        tmp.parentFile!!.mkdirs()
        tmp.writeBytes(ByteArray(7) { 0x55 })
        // Act
        assertThat(storage.readActive()).isNull()
        storage.writeInitial(row())
        // Assert
        assertThat(tmp.exists()).isFalse()
        assertThat(storage.readActive()).isEqualTo(row())
    }

    @Test
    fun `an interrupted rewrap leaves the previous envelope readable`() {
        // Arrange — the old envelope is on disk; a temp file with partial
        // new content exists but was never renamed into place.
        val storage = storage()
        storage.writeInitial(row())
        val tmp = File(envelopeFile().parentFile, FileMasterKeyStorage.ENVELOPE_FILE_NAME + ".tmp")
        tmp.writeBytes(envelopeFile().readBytes().copyOf(20))
        // Act
        val read = storage.readActive()
        // Assert — the active generation is the last fully-renamed one.
        assertThat(read).isEqualTo(row())
    }

    // ---- corruption ----------------------------------------------------

    private fun assertCorrupt(mutate: (ByteArray) -> ByteArray) {
        val storage = storage()
        storage.writeInitial(row())
        envelopeFile().writeBytes(mutate(envelopeFile().readBytes()))
        val error = assertThrows(MasterKeyStorageException::class.java) { storage.readActive() }
        assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.CORRUPT)
    }

    @Test
    fun `a truncated envelope is reported as CORRUPT`() = assertCorrupt { it.copyOf(it.size / 2) }

    @Test
    fun `an empty envelope file is reported as CORRUPT`() = assertCorrupt { ByteArray(0) }

    @Test
    fun `a flipped byte in the body is reported as CORRUPT`() =
        assertCorrupt { bytes -> bytes.copyOf().also { it[40] = (it[40].toInt() xor 0x01).toByte() } }

    @Test
    fun `a flipped byte in the integrity digest is reported as CORRUPT`() =
        assertCorrupt { bytes -> bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() } }

    @Test
    fun `a wrong magic is reported as CORRUPT`() =
        assertCorrupt { bytes ->
            bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        }

    @Test
    fun `an unknown format version is reported as CORRUPT`() =
        assertCorrupt { bytes ->
            // Bump the u16 format field at offset 8 and re-sign so only the
            // version check can reject it.
            val body = bytes.copyOf(bytes.size - 32).also { it[9] = 0x02 }
            body + FileMasterKeyStorage.digestOf(body)
        }

    @Test
    fun `trailing garbage after the digest is reported as CORRUPT`() = assertCorrupt { it + ByteArray(3) }

    @Test
    fun `a corrupt envelope blocks writeInitial rather than being silently replaced`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        envelopeFile().writeBytes(ByteArray(5))
        // Act
        val error = assertThrows(MasterKeyStorageException::class.java) { storage.writeInitial(row()) }
        // Assert — a user-initiated reset is the only way past a corrupt envelope.
        assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.CORRUPT)
    }

    @Test
    fun `corruption errors never carry envelope bytes`() {
        // Arrange
        val storage = storage()
        storage.writeInitial(row())
        val hex = envelopeFile().readBytes().joinToString("") { "%02x".format(it) }
        envelopeFile().writeBytes(envelopeFile().readBytes().copyOf(30))
        // Act
        val error = assertThrows(MasterKeyStorageException::class.java) { storage.readActive() }
        // Assert
        assertThat(error.message).isNotNull()
        assertThat(error.message!!.length).isLessThan(120)
        assertThat(hex).doesNotContain(error.message!!)
    }

    // ---- no plaintext master on disk ----------------------------------

    @Test
    fun `the envelope holds the wrapped bytes and never the plaintext master`() =
        runTest {
            // Arrange — the real orchestration over the fake Keystore and the
            // real file backend.
            val storage = storage()
            val provider =
                VaultKeyProviderImpl(
                    keystore = FakeKeystoreFacade(strongBoxAvailable = true),
                    biometric = FakeBiometricAuthenticator(),
                    storage = storage,
                )
            // Act
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val master = provider.currentKey()!!.copyOf()
            provider.lock()
            // Assert — the file contains BOTH wrapped blobs verbatim ...
            val fileBytes = envelopeFile().readBytes()
            val active = storage.readActive()!!
            assertThat(fileBytes.indexOfSubArray(active.wrappedBytesBiometric!!)).isNotEqualTo(-1)
            assertThat(fileBytes.indexOfSubArray(active.wrappedBytesCredential!!)).isNotEqualTo(-1)
            // ... and no window of the master key (any 8-byte run) anywhere.
            for (start in 0..master.size - 8) {
                assertThat(fileBytes.indexOfSubArray(master.copyOfRange(start, start + 8))).isEqualTo(-1)
            }
        }

    @Test
    fun `unlock via the file backend recovers the same master through either factor`() =
        runTest {
            // Arrange
            val provider =
                VaultKeyProviderImpl(
                    keystore = FakeKeystoreFacade(strongBoxAvailable = false),
                    biometric = FakeBiometricAuthenticator(),
                    storage = storage(),
                )
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val viaBio = provider.currentKey()!!.copyOf()
            provider.lock()
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            // Assert
            assertThat(result).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).isEqualTo(viaBio)
        }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
