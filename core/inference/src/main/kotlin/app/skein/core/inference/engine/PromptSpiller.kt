// skein-1uw (E4.I4) + skein-0rkg — the client half of the per-message spill.
//
// WHY PER MESSAGE
// ============================================================================
//
// `TransportRules`' header records the decision: `GenerateRequest` never
// carries more than `INLINE_BUDGET_BYTES` inline, and a prompt above it spills
// PER MESSAGE through `ChatMessageParcel.contentFd`. `skein-0rkg` measured that
// a normal prompt (persona + history + ~3K tokens of retrieved context)
// marshals to roughly 120 KB, so spilling is the common case, not an edge one.
//
// Per MESSAGE rather than per REQUEST is a security property, not an
// optimisation. `ChatMessageParcel.role` is the only place the
// data/instruction boundary survives onto the wire; a spill that concatenated
// every turn into one descriptor would erase it, and retrieved document text
// could then be reassembled into the `system` turn. This class therefore never
// merges messages, never reorders them, and never moves a role: it replaces one
// message's `content` with one descriptor carrying exactly that message's
// bytes, leaving `role` and list position untouched.
//
// WHERE THE BYTES GO
// ============================================================================
//
// `SharedMemRef`'s KDoc sanctions "ashmem (`MemoryFile`) or an app-private
// tmpfile". This uses the tmpfile: `MemoryFile.getFileDescriptor()` is not
// public API and reaching it needs reflection, which is not a thing to do on
// the path that carries the user's prompt.
//
// The file is UNLINKED before the Binder transaction is built. After
// `File.delete()` the bytes are reachable only through the two open
// descriptors (ours and, once the transaction lands, the service's) and are
// freed by the kernel when the last one closes — so no prompt plaintext is
// left behind at a path, not even for the duration of the call, and a crash
// mid-generation cannot strand it. The service owns and closes the descriptor
// it receives (§3.2 rule 3), and this class closes every descriptor it opened
// if the request is abandoned before it is sent.

package app.skein.core.inference.engine

import android.os.ParcelFileDescriptor
import app.skein.core.model.ChatMessage
import app.skein.core.model.InferenceException
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.GenerateRequest
import app.skein.ipc.SamplingParcel
import app.skein.ipc.SharedMemRef
import app.skein.ipc.TransportRules
import java.io.File
import java.io.IOException

/**
 * Builds the `GenerateRequest` for one turn, spilling whatever does not fit.
 *
 * @param spillDir an app-private directory to stage spilled message bodies in
 *   (`context.cacheDir` in production). Files in it exist only between
 *   `write` and `delete`, both inside [encode].
 */
internal class PromptSpiller(
    private val spillDir: File,
) {
    /**
     * @throws InferenceException.TransactionTooLarge when even a fully spilled
     *   request cannot be expressed inline — thousands of messages, which no
     *   caller in this app produces and which `TransportRules` calls "a caller
     *   that is simply wrong".
     * @throws InferenceException.Internal when a spill file cannot be written.
     */
    fun encode(
        requestId: Int,
        messages: List<ChatMessage>,
        sampling: SamplingParcel,
        sessionEpoch: Long,
    ): GenerateRequest {
        val parcels = messages.mapTo(mutableListOf()) { ChatMessageParcel(role = it.role.wire, content = it.content) }
        val opened = mutableListOf<ParcelFileDescriptor>()
        try {
            // 1. Any single message that cannot travel inline on its own.
            for (index in parcels.indices) {
                if (TransportRules.mustSpill(TransportRules.marshalledSize(parcels[index]))) {
                    parcels[index] = spill(parcels[index], opened)
                }
            }
            // 2. Many medium messages can exceed the budget together while none
            //    exceeds it alone. Spill the largest remaining inline message
            //    until the whole request fits; this terminates because a
            //    spilled message's parcel is a few hundred bytes.
            while (TransportRules.mustSpill(requestSize(requestId, parcels, sampling, sessionEpoch))) {
                val largest =
                    parcels
                        .withIndex()
                        .filter { it.value.contentFd == null && it.value.content.isNotEmpty() }
                        .maxByOrNull { it.value.content.length }
                        ?: break
                parcels[largest.index] = spill(largest.value, opened)
            }

            val request = GenerateRequest(requestId, parcels.toList(), emptyList(), sampling, sessionEpoch)
            val size = TransportRules.marshalledSize(request)
            if (TransportRules.refuses(size)) {
                throw InferenceException.TransactionTooLarge("$size bytes after spilling every message")
            }
            return request
        } catch (_: IOException) {
            closeAll(opened)
            // Fixed text: an IOException's own message carries the staging path.
            throw InferenceException.Internal("could not stage a message for transport")
        } catch (e: Throwable) {
            closeAll(opened)
            throw e
        }
    }

    private fun requestSize(
        requestId: Int,
        parcels: List<ChatMessageParcel>,
        sampling: SamplingParcel,
        sessionEpoch: Long,
    ): Int = TransportRules.marshalledSize(GenerateRequest(requestId, parcels, emptyList(), sampling, sessionEpoch))

    @Throws(IOException::class)
    private fun spill(
        message: ChatMessageParcel,
        opened: MutableList<ParcelFileDescriptor>,
    ): ChatMessageParcel {
        val bytes = message.content.toByteArray(Charsets.UTF_8)
        if (!spillDir.isDirectory && !spillDir.mkdirs()) {
            throw IOException("spill directory unavailable")
        }
        val staged = File.createTempFile("skein-msg", ".bin", spillDir)
        val fd =
            try {
                staged.writeBytes(bytes)
                ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                // Unlink on every path: on success the descriptor keeps the
                // bytes alive, on failure there is nothing to keep.
                staged.delete()
            }
        opened += fd
        return ChatMessageParcel(
            role = message.role,
            content = "",
            contentFd =
                SharedMemRef(
                    fd = fd,
                    sizeBytes = bytes.size.toLong(),
                    mimeHint = MIME_TEXT,
                    role = TransportRules.ROLE_MESSAGE,
                ),
        )
    }

    private fun closeAll(fds: List<ParcelFileDescriptor>) {
        fds.forEach { fd -> runCatching { fd.close() } }
    }

    private companion object {
        const val MIME_TEXT = "text/plain; charset=utf-8"
    }
}
