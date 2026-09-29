package app.skein.core.rag.embed

/** Validate the fixed EmbedderService result contract before a vector crosses into the index. */
internal object EmbeddingVectors {
    fun validate(vector: ByteArray) {
        check(vector.size == 256) { "Embedding response must have 256 dimensions" }
        check(vector.none { it == Byte.MIN_VALUE }) { "Embedding response is outside the quantization range" }
        check(vector.any { it != 0.toByte() }) { "Embedding response has zero magnitude" }
    }
}
