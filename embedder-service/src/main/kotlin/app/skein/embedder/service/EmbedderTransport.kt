package app.skein.embedder.service

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import app.skein.ipc.EmbedderTransportContract
import app.skein.ipc.SharedMemRef
import app.skein.ipc.TransportRules
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/**
 * Embedding-only spill format: big-endian int32 count, then int32 UTF-8 byte length
 * and bytes per record. Empty records and NUL are preserved; malformed UTF-8 is refused.
 * This is not inference-service's legacy NUL-separated spill format.
 */
internal object EmbedderTransport {
    const val MIME = EmbedderTransportContract.MIME

    // An explicit bound for this new service, not an inference from Binder's inline cap.
    const val MAX_SPILL_BYTES = EmbedderTransportContract.MAX_SPILL_BYTES

    fun checkParcel(request: Parcelable) {
        if (TransportRules.marshalledSize(request) > TransportRules.INLINE_BUDGET_BYTES) invalid()
    }

    /** Caller must authorize before entering and owns [spill] on every return path. */
    fun texts(
        inline: List<String>,
        spill: SharedMemRef?,
        cancellation: EmbedderCancellation,
        maximum: Int = TransportRules.MAX_EMBED_TEXTS,
    ): List<String> {
        cancellation.check()
        if (spill == null) {
            if (inline.isEmpty() || inline.size > maximum) invalid()
            return inline
        }
        if (inline.isNotEmpty() || spill.role != TransportRules.ROLE_INPUT_TEXTS || spill.mimeHint != MIME) invalid()
        if (spill.sizeBytes !in 4L..MAX_SPILL_BYTES.toLong()) invalid()
        // Seekable, exactly sized descriptors only. Pipes cannot block this worker on read.
        val bytes = ByteArray(spill.sizeBytes.toInt())
        try {
            ParcelFileDescriptor.AutoCloseInputStream(spill.fd.dup()).use { input ->
                val channel = input.channel
                if (channel.size() != spill.sizeBytes) invalid()
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    cancellation.check()
                    if (channel.read(buffer, buffer.position().toLong()) <= 0) invalid()
                }
            }
            return decode(bytes, maximum, cancellation)
        } finally {
            bytes.fill(0)
        }
    }

    internal fun decode(
        bytes: ByteArray,
        maximum: Int,
        cancellation: EmbedderCancellation,
    ): List<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 4) invalid()
        val count = buffer.int
        if (count !in 1..maximum) invalid()
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = ArrayList<String>(count)
        repeat(count) {
            cancellation.check()
            if (buffer.remaining() < 4) invalid()
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) invalid()
            val record = buffer.slice().apply { limit(length) }
            result += decoder.decode(record).toString()
            buffer.position(buffer.position() + length)
        }
        if (buffer.hasRemaining()) invalid()
        cancellation.check()
        return result
    }

    private fun invalid(): Nothing = throw EmbedderRequestException(EmbedderRequestFailure.INVALID_REQUEST)
}
