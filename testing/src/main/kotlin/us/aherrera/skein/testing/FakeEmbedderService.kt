// The `E0.I17` `FakeEmbedderService`: a scripted, JVM-only stand-in for the
// `EmbedderService` contract locked in `core/model/…/Embedder.kt`. Powers
// `EmbedderContractTest` on the JVM and is what every consumer (`E10.I2`
// RAG/ingest unit tests) uses in place of the real GLiNER/ONNX pipeline.
//
// Faithful to the contract:
//   • `embedDocuments`/`embedQuery` each return exactly 256 bytes per text
//     (plan § 4.6's fixed dimension).
//   • Equal texts (with the same prefix) always embed to the identical
//     vector — deterministic, seeded from the prefixed text's characters.
//   • `embedQuery(text)` and `embedDocuments(listOf(text))[0]` differ,
//     because the "search_query: " / "search_document: " prefixes (plan
//     § 4.6) are mixed into the seed before the two otherwise-identical
//     texts are embedded.
//   • `rerank` throws `UnsupportedOperationException` when no rerank model
//     was passed to `load` (plan § 4.6's documented contract).
//
// NOT faithful (approximation only — see `skein-0j1`'s KDoc mandate):
//   • The "embedding" is a deterministic hash-based unit vector, not a real
//     nomic-embed-text output: it has no semantic locality guarantee beyond
//     "texts that share more characters land closer," which is a byproduct
//     of summing one pseudo-random vector per character rather than a
//     learned encoder.
//   • `extractEntities` is a regex over capitalized words, not GLiNER.
//   • `rerank`'s scores are a deterministic function of the same
//     character-sum vectors, not a cross-encoder.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.DefaultEntityLabels
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.EntitySpan
import us.aherrera.skein.core.model.Int8Quantizer
import us.aherrera.skein.core.model.Model
import kotlin.math.sqrt
import kotlin.random.Random

public class FakeEmbedderService(
    override val embedderId: String = "fake-embedder",
    override val embedderVersion: Int = 1,
) : EmbedderService {
    @Volatile private var embedModel: Model? = null

    @Volatile private var rerankModel: Model? = null

    override suspend fun load(
        embed: Model,
        ner: Model?,
        rerank: Model?,
    ): Result<Unit> {
        embedModel = embed
        rerankModel = rerank
        return Result.success(Unit)
    }

    override suspend fun embedDocuments(texts: List<String>): List<ByteArray> {
        checkLoaded()
        return texts.map { vectorFor(DOCUMENT_PREFIX + it) }
    }

    override suspend fun embedQuery(text: String): ByteArray {
        checkLoaded()
        return vectorFor(QUERY_PREFIX + text)
    }

    override suspend fun extractEntities(
        text: String,
        labels: List<String>,
    ): List<EntitySpan> {
        checkLoaded()
        val label = labels.firstOrNull() ?: DefaultEntityLabels.value.first()
        return CAPITALIZED_WORD
            .findAll(text)
            .map { match ->
                EntitySpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    text = match.value,
                    label = label,
                    score = 1f,
                )
            }.toList()
    }

    override suspend fun rerank(
        query: String,
        candidates: List<String>,
    ): FloatArray {
        checkLoaded()
        if (rerankModel == null) {
            throw UnsupportedOperationException("no rerank model loaded")
        }
        val queryVector = rawVectorFor(QUERY_PREFIX + query)
        return FloatArray(candidates.size) { i ->
            cosine(queryVector, rawVectorFor(DOCUMENT_PREFIX + candidates[i]))
        }
    }

    override suspend fun countTokens(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    override suspend fun unload() {
        embedModel = null
        rerankModel = null
    }

    private fun checkLoaded() {
        checkNotNull(embedModel) { "embedder not loaded" }
    }

    /** L2-normalizes [rawVectorFor]'s output, then int8-quantizes (spec's fixed rule). */
    private fun vectorFor(seedText: String): ByteArray = Int8Quantizer.quantize(normalize(rawVectorFor(seedText)))

    /** Sum of one deterministic pseudo-random vector per character — order-independent, hash-based, not semantic. */
    private fun rawVectorFor(seedText: String): FloatArray {
        val accumulator = FloatArray(EMBED_DIM)
        for (ch in seedText) {
            val charVector = charVector(ch)
            for (i in 0 until EMBED_DIM) accumulator[i] += charVector[i]
        }
        return accumulator
    }

    private fun charVector(ch: Char): FloatArray {
        val random = Random(ch.code.toLong() * SEED_PRIME)
        return FloatArray(EMBED_DIM) { random.nextFloat() * 2f - 1f }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm == 0f) return vector
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        val normA = sqrt(a.sumOf { (it * it).toDouble() }).toFloat()
        val normB = sqrt(b.sumOf { (it * it).toDouble() }).toFloat()
        if (normA == 0f || normB == 0f) return 0f
        return dot / (normA * normB)
    }

    public companion object {
        private const val EMBED_DIM: Int = 256
        private const val DOCUMENT_PREFIX: String = "search_document: "
        private const val QUERY_PREFIX: String = "search_query: "
        private const val SEED_PRIME: Long = 2654435761L
        private val CAPITALIZED_WORD: Regex = Regex("\\b[A-Z][a-zA-Z]*\\b")
        private val WHITESPACE: Regex = Regex("\\s+")
    }
}
