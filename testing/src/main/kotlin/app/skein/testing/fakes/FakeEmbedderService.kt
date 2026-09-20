package app.skein.testing.fakes

import java.util.Random

/**
 * Deterministic stand-in for the (not-yet-landed) `EmbedderService` contract
 * (plan §4.6, `E0.I17`). "Embeddings" are a 256-byte vector seeded from the
 * input string's `hashCode()` — deterministic across runs for identical
 * input, but not a real embedding: semantically similar texts do not land
 * near each other. Use this only where a test needs *a* stable byte array
 * per input, not real nearest-neighbor behavior.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `EmbedderService`
 * interface once `E0.I17` lands.
 */
class FakeEmbedderService(
    val embedderId: String = "fake-embedder",
    val embedderVersion: Int = 1,
) {
    fun embedDocuments(texts: List<String>): List<ByteArray> = texts.map { deterministicVector(it) }

    fun embedQuery(text: String): ByteArray = deterministicVector(text)

    fun countTokens(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    /** A 256-byte vector seeded from [text]'s hash — deterministic, NOT semantically meaningful. */
    private fun deterministicVector(text: String): ByteArray {
        val random = Random(text.hashCode().toLong())
        return ByteArray(256) { (random.nextInt(255) - 127).toByte() }
    }
}
