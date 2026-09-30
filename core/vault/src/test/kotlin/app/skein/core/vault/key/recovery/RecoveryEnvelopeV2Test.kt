package app.skein.core.vault.key.recovery

import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.MasterKeyStorageException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class RecoveryEnvelopeV2Test {
    @Test
    fun `new format round trips canonical factor aliases and exact wraps`() {
        val original = envelope()
        val decoded = RecoveryEnvelopeV2.decode(original.encode())
        assertThat(decoded.encode()).isEqualTo(original.encode())
        assertThat(decoded.alias(true)).isEqualTo("skein.vault.recovery.${original.transactionId}.bio")
        assertThat(decoded.alias(false)).isEqualTo("skein.vault.recovery.${original.transactionId}.cred")
    }

    @Test
    fun `legacy decoder refuses v2 rather than dropping dynamic alias names`() {
        assertThrows(MasterKeyStorageException::class.java) { FileMasterKeyStorage.decode(envelope().encode()) }
    }

    @Test
    fun `tamper truncated oversize and trailing bytes fail without disclosing payload`() {
        val valid = envelope().encode()
        val inputs =
            listOf(
                valid.copyOf(20),
                valid.copyOf().also { it[45] = 99 },
                ByteArray(1025),
                resign(
                    valid.dropLast(32).toByteArray() + 1,
                ),
            )
        inputs.forEach { input ->
            val failure = assertThrows(RecoveryEnvelopeFormatException::class.java) { RecoveryEnvelopeV2.decode(input) }
            assertThat(failure.message).isEqualTo("invalid recovery envelope")
        }
    }

    @Test
    fun `digest correct but cross-factor alias or unknown format is rejected`() {
        val encoded = envelope().encode()
        val body = encoded.dropLast(32).toByteArray()
        val name = envelope().alias(false).toByteArray()
        val offset = body.toString(Charsets.ISO_8859_1).indexOf(name.toString(Charsets.ISO_8859_1))
        assertThat(offset).isAtLeast(0)
        body[offset + "skein.vault.recovery.".length] = 'f'.code.toByte()
        assertThrows(RecoveryEnvelopeFormatException::class.java) { RecoveryEnvelopeV2.decode(resign(body)) }
        val oldFormat = encoded.dropLast(32).toByteArray().also { it[9] = 1 }
        assertThrows(RecoveryEnvelopeFormatException::class.java) { RecoveryEnvelopeV2.decode(resign(oldFormat)) }
    }

    @Test
    fun `invalid wrap sizes cannot be serialized`() {
        assertThrows(IllegalArgumentException::class.java) {
            envelope().copy(biometric = RecoveryEnvelopeV2.Wrap(ByteArray(47), ByteArray(12))).encode()
        }
    }

    private fun resign(body: ByteArray) = body + RecoveryEnvelopeV2.digest(body)

    private fun envelope() =
        RecoveryEnvelopeV2(
            2,
            1234,
            true,
            UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 1 }, ByteArray(12) { 2 }),
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 3 }, ByteArray(12) { 4 }),
        )
}
