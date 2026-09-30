package app.skein.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Exact count through the loaded embedder's verified tokenizer. IDs are positive and
 * strictly increasing within an epoch, shared across every embedder request kind.
 * Exactly one text is encoded, inline or as one length-prefixed UTF-8 record in [inputFd].
 */
@Parcelize
data class EmbedderTokenCountRequest(
    val text: String,
    val sessionEpoch: Long,
    val requestId: Int,
    val inputFd: SharedMemRef? = null,
) : Parcelable

/** Wire format for the new embedder boundary; inference's existing spill encoding is separate. */
object EmbedderTransportContract {
    const val MIME: String = "application/x-skein-texts"

    /** Bounded seekable spill: big-endian int32 count, then int32 length and strict UTF-8 bytes per record. */
    const val MAX_SPILL_BYTES: Int = 128 * 1024
}
