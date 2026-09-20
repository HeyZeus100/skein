package app.skein.testing.fakes

/**
 * In-memory stand-in for the (not-yet-landed) `IndexStore` contract (plan
 * §4.2, `E0.I11`). [bm25] ranks by naive term-overlap counting — it is an
 * approximation for exercising call sequencing, not the real SQLite FTS5
 * BM25 ranking or the real cosine-over-int8 `knn`. Do not assert on ranking
 * quality against this fake.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `IndexStore`
 * interface once `E0.I11` lands.
 */
class FakeIndexStore {
    data class FakeChunk(
        val id: Long,
        val docId: String,
        val text: String,
    )

    private val chunks = mutableMapOf<Long, FakeChunk>()
    private val embeddings = mutableMapOf<Long, ByteArray>()
    private var nextChunkId = 1L

    fun replaceChunks(
        docId: String,
        texts: List<String>,
    ): List<Long> {
        chunks.values
            .filter { it.docId == docId }
            .map { it.id }
            .forEach { chunks.remove(it) }
        return texts.map { text ->
            val id = nextChunkId++
            chunks[id] = FakeChunk(id, docId, text)
            id
        }
    }

    fun putEmbedding(
        chunkId: Long,
        embedding: ByteArray,
    ) {
        embeddings[chunkId] = embedding
    }

    fun embeddingFor(chunkId: Long): ByteArray? = embeddings[chunkId]

    /** Naive term-overlap ranking — an approximation of the real BM25 index, not a faithful model. */
    fun bm25(query: String): List<FakeChunk> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return chunks.values
            .map { chunk -> chunk to terms.count { term -> chunk.text.lowercase().contains(term) } }
            .filter { (_, overlap) -> overlap > 0 }
            .sortedByDescending { (_, overlap) -> overlap }
            .map { (chunk, _) -> chunk }
    }

    fun getChunks(ids: Collection<Long>): Map<Long, FakeChunk> =
        ids
            .mapNotNull { id ->
                chunks[id]?.let {
                    id to it
                }
            }.toMap()
}
