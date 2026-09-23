// M0.5 contract file (`E0.I17`): lands plan § 4.6 (`docs/superpowers/plans/
// 2026-09-19-skein-v1-plan.md`) — the app-side `EmbedderService` interface —
// verbatim, plus the value types it speaks in.
//
// `:core:model` is pure Kotlin/JVM (no Android imports), so this contract is
// shared by every process (`:app`, `:embedder`, `:testing`). The real
// implementation (`EmbedderServiceImpl`, `core/rag`, `E5.I3`) wraps the
// `IEmbedderService` AIDL binder client (§4.7, `E0.I16`) behind this
// interface: batching (≤ 32 texts per Binder call), `embedderId`/
// `embedderVersion` constants, and `RemoteException` mapping all live there,
// not here.
//
// This file intentionally carries no measurement-dependent constants: the
// only numeric locked here (256-d Matryoshka truncation, the int8
// quantization formula) is a spec default, cited inline.

package app.skein.core.model

/** One span found by [EmbedderService.extractEntities]. */
public data class EntitySpan(
    val start: Int,
    val end: Int,
    val text: String,
    val label: String,
    val score: Float,
)

/** Default entity labels passed to [EmbedderService.extractEntities] when the caller doesn't override them. */
public object DefaultEntityLabels {
    public val value: List<String> =
        listOf("person", "organization", "location", "project", "product", "event", "date", "technology")
}

/**
 * Verbatim from plan § 4.6. `:embedder`'s GLiNER/ONNX pipeline is behind
 * this interface; `:app` never talks AIDL directly.
 */
public interface EmbedderService {
    public suspend fun load(
        embed: Model,
        ner: Model?,
        rerank: Model?,
    ): Result<Unit>

    /** Applies "search_document: " prefix, truncates to 256-d (Matryoshka), L2-normalizes, int8-quantizes. 256 bytes each. */
    public suspend fun embedDocuments(texts: List<String>): List<ByteArray>

    /** Same pipeline with "search_query: " prefix. */
    public suspend fun embedQuery(text: String): ByteArray

    public suspend fun extractEntities(
        text: String,
        labels: List<String> = DefaultEntityLabels.value,
    ): List<EntitySpan>

    /** Cross-encoder scores, one per candidate. Throws [UnsupportedOperationException] if no rerank model is loaded. */
    public suspend fun rerank(
        query: String,
        candidates: List<String>,
    ): FloatArray

    public suspend fun countTokens(text: String): Int

    public suspend fun unload()

    /** e.g. "nomic-embed-text-v1.5" */
    public val embedderId: String

    /** Bump → `E5.I18` re-embed migration. */
    public val embedderVersion: Int
}
