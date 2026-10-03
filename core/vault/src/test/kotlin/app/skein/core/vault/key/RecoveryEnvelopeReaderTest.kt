package app.skein.core.vault.key

import app.skein.core.vault.key.recovery.RecoveryEnvelopeV2
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RecoveryEnvelopeReaderTest {
    @get:Rule val temp = TempDirRule()

    private val id = UUID.fromString("7987b5a4-e2f8-47ba-b741-27f5597af0fe")

    private fun envelope() =
        RecoveryEnvelopeV2(
            2,
            200,
            true,
            id,
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 3 }, ByteArray(12) { 4 }),
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 5 }, ByteArray(12) { 6 }),
        )

    private fun active(): File = FileMasterKeyStorage.envelopeFileIn(temp.root)

    private fun install(bytes: ByteArray): FileMasterKeyStorage {
        active().parentFile!!.mkdirs()
        active().writeBytes(bytes)
        return FileMasterKeyStorage(active())
    }

    private fun resign(bytes: ByteArray): ByteArray {
        val body = bytes.copyOf(bytes.size - 32)
        return body + FileMasterKeyStorage.digestOf(body)
    }

    @Test
    fun `v2 reader retains exact canonical metadata and factor wraps`() {
        val bytes = envelope().encode()
        val row = install(bytes).readActive()!!
        assertThat(row.keyVersion).isEqualTo(2)
        assertThat(row.createdAt).isEqualTo(200)
        assertThat(row.strongBoxBacked).isTrue()
        assertThat(row.wrappedBytesBiometric).isEqualTo(envelope().biometric.ciphertext)
        assertThat(row.wrapIvCredential).isEqualTo(envelope().credential.iv)
        assertThat(row.recoveryRecord!!.decode().encode()).isEqualTo(bytes)
        assertThat(active().readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `recovery record owns its bytes independently of every exposed array`() {
        val source = envelope()
        val record = RecoveryMasterKeyRecord(source)
        val expected = source.encode()
        source.biometric.ciphertext.fill(99)
        source.credential.iv.fill(88)
        record
            .decode()
            .biometric.ciphertext
            .fill(77)
        assertThat(record.decode().encode()).isEqualTo(expected)
        val input = expected.copyOf()
        val row = FileMasterKeyStorage.decodeActive(input)
        input.fill(0)
        row.wrappedBytesBiometric!!.fill(66)
        row.wrapIvCredential!!.fill(55)
        assertThat(row.recoveryRecord!!.decode().encode()).isEqualTo(expected)
    }

    @Test
    fun `legacy storage rewrap refuses v2 before changing any file`() {
        val bytes = envelope().encode()
        val storage = install(bytes)
        val error =
            assertThrows(MasterKeyStorageException::class.java) {
                storage.rewrap(2, VaultKeyProvider.Factor.BIOMETRIC, ByteArray(48), ByteArray(12), null, 300)
            }
        assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.IO)
        assertThat(active().readBytes()).isEqualTo(bytes)
        assertThat(File(active().path + FileMasterKeyStorage.TMP_SUFFIX).exists()).isFalse()
    }

    @Test
    fun `v2 row cannot be encoded or written as an initial legacy envelope`() {
        val row = FileMasterKeyStorage.decodeActive(envelope().encode())
        assertThrows(MasterKeyStorageException::class.java) { FileMasterKeyStorage.encode(row) }
        assertThrows(MasterKeyStorageException::class.java) { FileMasterKeyStorage(active()).writeInitial(row) }
        assertThat(active().parentFile!!.exists()).isFalse()
    }

    @Test
    fun `existing v2 blocks initial setup storage without replacing it`() {
        val bytes = envelope().encode()
        val storage = install(bytes)
        val error = assertThrows(MasterKeyStorageException::class.java) { storage.writeInitial(storage.readActive()!!) }
        assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.ALREADY_INITIALISED)
        assertThat(active().readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `unsupported formats corrupt checksums and malformed v2 metadata fail closed`() {
        val original = envelope().encode()
        val mutations =
            listOf(
                original.copyOf().also { it[9] = 3 }.let(::resign),
                original.copyOf().also { it[0] = 0 }.let(::resign),
                original.copyOf().also { it[13] = 1 }.let(::resign),
                original.copyOf().also { it[22] = 2 }.let(::resign),
                original.copyOf().also { it[23] = 1 }.let(::resign),
                original.copyOf().also { it[27] = 'X'.code.toByte() }.let(::resign),
                original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                original.copyOf(55),
                original + ByteArray(1),
            )
        mutations.forEach { bytes ->
            val storage = install(bytes)
            val error = assertThrows(MasterKeyStorageException::class.java) { storage.readActive() }
            assertThat(error.kind).isEqualTo(MasterKeyStorageException.Kind.CORRUPT)
            assertThat(error.message!!.length).isLessThan(120)
            assertThat(active().readBytes()).isEqualTo(bytes)
        }
    }

    @Test
    fun `both codec and bounded file reader reject oversized envelopes unchanged`() {
        val bytes = ByteArray(FileMasterKeyStorage.MAX_ENVELOPE_BYTES + 1)
        assertThrows(MasterKeyStorageException::class.java) { FileMasterKeyStorage.decodeActive(bytes) }
        val storage = install(bytes)
        assertThrows(MasterKeyStorageException::class.java) { storage.readActive() }
        assertThat(active().readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `record equality distinguishes metadata and ciphertext changes`() {
        val record = RecoveryMasterKeyRecord(envelope())
        assertThat(record).isEqualTo(RecoveryMasterKeyRecord(envelope()))
        assertThat(record).isNotEqualTo(RecoveryMasterKeyRecord(envelope().copy(generation = 3)))
        val changed = envelope().also { it.biometric.ciphertext[0] = 9 }
        assertThat(record).isNotEqualTo(RecoveryMasterKeyRecord(changed))
    }
}
