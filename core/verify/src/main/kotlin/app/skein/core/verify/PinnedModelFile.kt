// skein-v2s (plan `E3.I5`; POST_REVIEW_RESOLUTIONS §2.2 rule 2;
// MODEL_STORE.md §3): the fd-based load discipline, as a reusable piece.
//
// The rule the whole TOCTOU defence rests on is one sentence long: after the
// descriptor arrives, nothing may ever resolve the model by path again. Both
// halves of the dual-hash gate and the hand-off to llama.cpp must go through
// one descriptor obtained once, because a second `open(path)` — however
// innocent it looks — puts the attacker's rename back in play between the
// digest and the mapping, which is exactly the race that handing
// `/proc/self/fd/<n>` to the engine exists to close. (llama.cpp takes a path,
// not a descriptor; `/proc/self/fd/N` is a path that resolves to an
// already-open file description and therefore cannot be swapped underneath.)
//
// `PinnedModelFile` is that descriptor, made into a type so the rule is
// structural rather than a comment somebody has to remember:
//
//   * it is constructed from a *duplicate* of the received descriptor, so the
//     sender closing its `ParcelFileDescriptor` cannot pull the file out from
//     under a load in progress;
//   * it exposes a [FileChannel] and an [enginePath], and no path to the file
//     — there is nothing to re-open by accident;
//   * it stays open for the model's lifetime and closes on unload, because the
//     engine's `/proc/self/fd/<n>` path is only resolvable while it is open.
//
// The `dup` itself is supplied by the caller through [DescriptorDup] rather
// than performed here, for two reasons. `ParcelFileDescriptor` lives in
// `android.os` and the descriptor *number* is only reachable through it
// (`java.io.FileDescriptor` will not report its own fd), so doing the dup in
// here would drag an Android type into a class that is otherwise pure JVM and
// JVM-testable. The service (`skein-nxk`) supplies:
//
//     val duplicate = received.dup()
//     PinnedModelFile.pin { DupedDescriptor(duplicate.fileDescriptor, duplicate.fd) { duplicate.close() } }
//
// and must not close `duplicate` itself — closing the pin closes it.

package app.skein.core.verify

import app.skein.core.model.SkeinLog
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "PinnedModelFile"

/**
 * A descriptor duplicated from the one the loader received, with the number
 * the duplicate carries in this process's descriptor table.
 *
 * [onClose] releases whatever owns the duplicate — on Android
 * `ParcelFileDescriptor.close()`, whose `FileInputStream` wrapper does *not*
 * own the fd and so cannot close it for us. It is invoked exactly once, when
 * the [PinnedModelFile] built from this descriptor closes.
 */
class DupedDescriptor(
    val descriptor: FileDescriptor,
    val number: Int,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

/** Duplicates the received descriptor. See the file header for the Android implementation. */
fun interface DescriptorDup {
    @Throws(IOException::class)
    fun dup(): DupedDescriptor
}

/** Result of [PinnedModelFile.pin]. */
sealed interface PinResult {
    data class Pinned(
        val file: PinnedModelFile,
    ) : PinResult

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : PinResult
}

/**
 * Every descriptor one load was handed: the main file, plus any companion that
 * arrived as an fd rather than being read from the store directory.
 *
 * The wire `ManifestBinding` carries a `ManifestFileRef` — descriptor plus
 * expected digests — for *every* file, and §2.2 rule 3 is that every one of
 * them is verified before use. A companion given here is hashed through its
 * own descriptor, exactly like the main file; a companion absent from
 * [companions] falls back to the store path, which is right for the app-side
 * loader (it holds the shared lock and the directory is `0500`) and wrong for
 * anything that was handed descriptors it could pin instead.
 *
 * Closing the model closes every descriptor in it — one call on unload.
 */
class PinnedModel(
    val main: PinnedModelFile,
    val companions: Map<ModelFileRole, PinnedModelFile> = emptyMap(),
) : Closeable {
    /** The `/proc/self/fd/<n>` path for the main file, valid while this model is open. */
    val enginePath: String get() = main.enginePath

    /** True while every descriptor of this model is still open. */
    val isOpen: Boolean get() = main.isOpen && companions.values.all { it.isOpen }

    override fun close() {
        main.close()
        companions.values.forEach { it.close() }
    }
}

/**
 * Outcome of the fd load discipline: [ModelVerifier.verifyPinned].
 *
 * On [Ready] the caller hands [Ready.enginePath] to the native engine and
 * keeps the descriptors open until unload. On [Refused] they have already been
 * closed, so there is nothing left to hand over — a refused model cannot reach
 * `llama_model_load_from_file` even if the caller ignores the result.
 */
sealed interface PinnedLoad {
    data class Ready(
        val model: PinnedModel,
        val mapped: MappedByteBuffer,
    ) : PinnedLoad {
        /** The `/proc/self/fd/<n>` path to give the engine, valid while [model] is open. */
        val enginePath: String get() = model.enginePath
    }

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : PinnedLoad
}

/**
 * A model file pinned by descriptor for the model's lifetime.
 *
 * Not thread-safe with respect to [close]; the loader owns one pin per loaded
 * model and closes it on unload.
 */
class PinnedModelFile internal constructor(
    private val duplicate: DupedDescriptor,
    private val stream: FileInputStream,
) : Closeable {
    private var closed = false

    /** The duplicate's number in this process's descriptor table. */
    val descriptorNumber: Int get() = duplicate.number

    /**
     * The channel of the pinned descriptor.
     *
     * Every digest — pre-mmap SHA-256 and the mapping the post-mmap BLAKE3
     * reads — must come from this channel. It is the only reader this type
     * offers, on purpose.
     */
    val channel: FileChannel get() = stream.channel

    /**
     * The path the native engine is given, resolvable only while this pin is
     * open. Empty-string-safe by construction: it is derived, never parsed.
     */
    val enginePath: String get() = enginePathFor(descriptorNumber)

    /** True until [close]. A closed pin must never be handed to the engine. */
    val isOpen: Boolean get() = !closed

    /** Size of the pinned file description, read through the descriptor rather than the path. */
    @Throws(IOException::class)
    fun sizeBytes(): Long = channel.size()

    /**
     * Closes the duplicate. Idempotent, so a caller that closes on both the
     * refusal path and the unload path is correct.
     */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
        runCatching { duplicate.close() }
        SkeinLog.i(TAG, "pinned model descriptor closed")
    }

    companion object {
        /** `/proc/<pid>/fd` for the calling process — the engine resolves it in its own process. */
        const val PROC_SELF_FD: String = "/proc/self/fd"

        fun enginePathFor(number: Int): String = "$PROC_SELF_FD/$number"

        /**
         * Duplicates the received descriptor and pins it.
         *
         * A failing `dup` is a refusal, not an exception: the load flow
         * reports [ModelVerification] values, and "we could not duplicate the
         * descriptor" is an I/O failure on the main file like any other.
         */
        fun pin(source: DescriptorDup): PinResult {
            val duplicate =
                try {
                    source.dup()
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "cannot duplicate the model descriptor", e)
                    return PinResult.Refused(ModelVerification.IoFailure(ModelFileRole.MAIN, "dup"))
                }
            return PinResult.Pinned(PinnedModelFile(duplicate, FileInputStream(duplicate.descriptor)))
        }
    }
}
