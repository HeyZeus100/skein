package app.skein.core.vault.key.recovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Unwired format: no existing vault reader or writer calls this codec. */
internal data class RecoveryEnvelopeV2(
    val generation: Int,
    val createdAt: Long,
    val strongBoxBacked: Boolean,
    val transactionId: UUID,
    val biometric: Wrap,
    val credential: Wrap,
) {
    internal data class Wrap(
        val ciphertext: ByteArray,
        val iv: ByteArray,
    )

    fun alias(biometric: Boolean): String = alias(transactionId, biometric)

    fun encode(): ByteArray {
        require(generation > 1 && createdAt >= 0) { "invalid recovery generation" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeShort(2)
            out.writeInt(generation)
            out.writeLong(createdAt)
            out.writeByte(if (strongBoxBacked) 1 else 0)
            out.writeByte(2)
            listOf(true to biometric, false to credential).forEach { (isBiometric, wrap) ->
                require(wrap.ciphertext.size == 48 && wrap.iv.size == 12) { "invalid recovery wrap" }
                out.writeByte(if (isBiometric) 1 else 2)
                field(out, alias(isBiometric).toByteArray(Charsets.UTF_8))
                field(out, wrap.ciphertext)
                field(out, wrap.iv)
                field(out, ByteArray(0)) // GCM tag is appended to ciphertext.
            }
        }
        val body = bytes.toByteArray()
        return body + digest(body)
    }

    companion object {
        private val MAGIC = "SKEINKEY".toByteArray(Charsets.US_ASCII)
        private const val PREFIX = "skein.vault.recovery."
        const val MAX_BYTES = 1024

        fun alias(
            id: UUID,
            biometric: Boolean,
        ): String = "$PREFIX$id.${if (biometric) "bio" else "cred"}"

        fun decode(bytes: ByteArray): RecoveryEnvelopeV2 {
            fun bad(): Nothing = throw RecoveryEnvelopeFormatException()
            if (bytes.size !in 56..MAX_BYTES) bad()
            val body = bytes.copyOf(bytes.size - 32)
            if (!MessageDigest.isEqual(digest(body), bytes.copyOfRange(body.size, bytes.size))) bad()
            try {
                DataInputStream(ByteArrayInputStream(body)).use { input ->
                    if (!ByteArray(8).also(input::readFully).contentEquals(MAGIC)) bad()
                    if (input.readUnsignedShort() != 2) bad()
                    val generation = input.readInt()
                    val createdAt = input.readLong()
                    val flags = input.readUnsignedByte()
                    if (generation <= 1 || createdAt < 0 || flags !in 0..1 || input.readUnsignedByte() != 2) bad()
                    var transactionId: UUID? = null
                    val wraps =
                        (1..2).map { factor ->
                            if (input.readUnsignedByte() != factor) bad()
                            val name = readField(input).toString(Charsets.UTF_8)
                            val suffix = if (factor == 1) ".bio" else ".cred"
                            if (!name.startsWith(PREFIX) || !name.endsWith(suffix)) bad()
                            val idText = name.removePrefix(PREFIX).removeSuffix(suffix)
                            val id =
                                try {
                                    UUID.fromString(idText)
                                } catch (_: IllegalArgumentException) {
                                    bad()
                                }
                            // UUID.fromString accepts some abbreviated forms; only canonical names are valid.
                            if (id.toString() != idText || name != alias(id, factor == 1)) bad()
                            if (transactionId != null && transactionId != id) bad()
                            transactionId = id
                            val ciphertext = readField(input)
                            val iv = readField(input)
                            val tag = readField(input)
                            if (ciphertext.size != 48 || iv.size != 12 || tag.isNotEmpty()) bad()
                            Wrap(ciphertext, iv)
                        }
                    if (input.available() != 0) bad()
                    return RecoveryEnvelopeV2(
                        generation,
                        createdAt,
                        flags == 1,
                        checkNotNull(transactionId),
                        wraps[0],
                        wraps[1],
                    )
                }
            } catch (_: IOException) {
                bad()
            }
        }

        private fun field(
            out: DataOutputStream,
            bytes: ByteArray,
        ) {
            out.writeShort(bytes.size)
            out.write(bytes)
        }

        private fun readField(input: DataInputStream): ByteArray {
            val size = input.readUnsignedShort()
            if (size > MAX_BYTES || size > input.available()) throw RecoveryEnvelopeFormatException()
            return ByteArray(size).also(input::readFully)
        }

        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

internal class RecoveryEnvelopeFormatException : IllegalArgumentException("invalid recovery envelope")
