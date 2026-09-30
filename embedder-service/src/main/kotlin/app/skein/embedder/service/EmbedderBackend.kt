package app.skein.embedder.service

import app.skein.ipc.EntitySpanParcel
import java.io.Closeable

/**
 * The approved native adapter must implement exact tokenizer counts and per-chunk
 * cancellation, with no retained plaintext after a call. No production adapter is
 * selected until skein-5hr's measured backend decision is approved.
 */
internal interface EmbedderBackend : Closeable {
    fun embed(
        texts: List<String>,
        isQuery: Boolean,
        cancellation: EmbedderCancellation,
    ): ByteArray

    fun extractEntities(
        text: String,
        labels: List<String>,
        cancellation: EmbedderCancellation,
    ): List<EntitySpanParcel>

    fun rerank(
        query: String,
        candidates: List<String>,
        cancellation: EmbedderCancellation,
    ): FloatArray

    fun tokenCount(
        text: String,
        cancellation: EmbedderCancellation,
    ): Int
}

internal fun interface EmbedderBackendFactory {
    /** Must use only the verified descriptors/views, never caller-selected store paths. */
    fun create(
        models: VerifiedEmbedderModels,
        format: String,
        threads: Int,
        cancellation: EmbedderCancellation,
    ): EmbedderBackend
}
