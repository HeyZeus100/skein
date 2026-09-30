package app.skein.embedder.service

import android.os.ParcelFileDescriptor
import app.skein.ipc.SharedMemRef
import app.skein.ipc.TransportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedderTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun realDescriptorPreservesEmptyNulAndUnicodeRecords() {
        val texts = listOf("", "one\u0000two", "café 日本語")
        val spill = descriptor(encode(texts))
        try {
            assertEquals(texts, EmbedderTransport.texts(emptyList(), spill, EmbedderCancellation()))
        } finally {
            spill.fd.close()
        }
    }

    @Test
    fun malformedLengthsCountsAndTrailingBytesAreRefused() {
        val valid = encode(listOf("a"))
        val invalid =
            listOf(
                byteArrayOf(),
                ByteBuffer.allocate(4).putInt(0).array(),
                ByteBuffer.allocate(4).putInt(33).array(),
                ByteBuffer
                    .allocate(8)
                    .putInt(1)
                    .putInt(-1)
                    .array(),
                ByteBuffer
                    .allocate(8)
                    .putInt(1)
                    .putInt(Int.MAX_VALUE)
                    .array(),
                valid.dropLast(1).toByteArray(),
                valid + byteArrayOf(0),
            )
        invalid.forEach {
            assertThrows(
                EmbedderRequestException::class.java,
            ) { EmbedderTransport.decode(it, 32, EmbedderCancellation()) }
        }
    }

    @Test
    fun malformedUtf8CannotBeSilentlyReplaced() {
        val bytes =
            ByteBuffer
                .allocate(9)
                .putInt(1)
                .putInt(1)
                .put(0x80.toByte())
                .array()
        assertThrows(
            CharacterCodingException::class.java,
        ) { EmbedderTransport.decode(bytes, 32, EmbedderCancellation()) }
    }

    @Test
    fun descriptorSizeMetadataAndCompetingInlinePayloadAreRefused() {
        val spill = descriptor(encode(listOf("synthetic")))
        try {
            listOf(
                spill.copy(sizeBytes = spill.sizeBytes + 1),
                spill.copy(sizeBytes = EmbedderTransport.MAX_SPILL_BYTES + 1L),
                spill.copy(role = "message"),
                spill.copy(mimeHint = "text/plain"),
            ).forEach {
                assertThrows(
                    EmbedderRequestException::class.java,
                ) { EmbedderTransport.texts(emptyList(), it, EmbedderCancellation()) }
            }
            assertThrows(EmbedderRequestException::class.java) {
                EmbedderTransport.texts(listOf("inline"), spill, EmbedderCancellation())
            }
        } finally {
            spill.fd.close()
        }
    }

    @Test
    fun cancellationPrecedesDescriptorAccess() {
        val spill = descriptor(encode(listOf("synthetic")))
        spill.fd.close()
        val cancellation = EmbedderCancellation().apply { cancel() }
        assertEquals(
            EmbedderRequestFailure.CANCELLED,
            assertThrows(EmbedderRequestException::class.java) {
                EmbedderTransport.texts(emptyList(), spill, cancellation)
            }.failure,
        )
    }

    private fun descriptor(bytes: ByteArray): SharedMemRef {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return SharedMemRef(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            bytes.size.toLong(),
            EmbedderTransport.MIME,
            TransportRules.ROLE_INPUT_TEXTS,
        )
    }

    private fun encode(texts: List<String>): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(texts.size)
                    texts.forEach {
                        val encoded = it.toByteArray(Charsets.UTF_8)
                        output.writeInt(encoded.size)
                        output.write(encoded)
                    }
                }
            }.toByteArray()
}
