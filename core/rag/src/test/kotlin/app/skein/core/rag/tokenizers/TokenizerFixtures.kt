package app.skein.core.rag.tokenizers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Loads the pinned `tokenizer.json` artifacts and the golden fixtures generated
 * from them by Python `tokenizers`.
 *
 * Both live under `src/test/resources/tokenizers/`; `MANIFEST.md` there records
 * each artifact's Hugging Face repo, revision, SHA-256 and license, and
 * `README.md` records the exact command that produced the goldens.
 *
 * The `tokenizer.json` files are stored gzipped (9.5 MB → 2.1 MB); since
 * [TokenizerFactory.fromJson] takes an `InputStream`, the test simply wraps the
 * resource in a [GZIPInputStream] — which also exercises the fact that the
 * loader never assumes a seekable file.
 */
internal object TokenizerFixtures {
    const val NOMIC = "nomic-embed-text-v1.5"
    const val MS_MARCO = "ms-marco-MiniLM-L-6-v2"
    const val GLINER = "gliner-small-v2.5-deberta-v3"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Tokenizer>()
    private val goldenCache = HashMap<String, GoldenFile>()

    @Synchronized
    fun tokenizer(name: String): Tokenizer =
        cache.getOrPut(name) {
            openResource("/tokenizers/$name.tokenizer.json.gz").use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { TokenizerFactory.fromJson(it) }
            }
        }

    @Synchronized
    fun golden(name: String): GoldenFile =
        goldenCache.getOrPut(name) {
            openResource("/tokenizers/golden/$name.golden.json").use { stream ->
                json.decodeFromString(GoldenFile.serializer(), stream.readBytes().toString(Charsets.UTF_8))
            }
        }

    private fun openResource(path: String): InputStream =
        TokenizerFixtures::class.java.getResourceAsStream(path)
            ?: error("Missing test resource $path")
}

@Serializable
internal data class GoldenFile(
    val tokenizer: String,
    @SerialName("tokenizers_version") val tokenizersVersion: String,
    @SerialName("offset_units") val offsetUnits: String,
    val cases: List<GoldenCase>,
    val truncation: List<GoldenTruncationCase>,
)

@Serializable
internal data class GoldenCase(
    val text: String,
    val ids: List<Int>,
    val tokens: List<String>,
    val offsets: List<List<Int>>,
)

@Serializable
internal data class GoldenTruncationCase(
    val text: String,
    @SerialName("max_length") val maxLength: Int,
    val ids: List<Int>,
    val tokens: List<String>,
    val offsets: List<List<Int>>,
)

/** Shared parity assertions so both model families report failures the same way. */
internal object TokenizerParity {
    fun assertMatches(
        name: String,
        tokenizer: Tokenizer,
        cases: List<GoldenCase>,
    ) {
        val failures = StringBuilder()
        var checked = 0
        for (case in cases) {
            val actual = tokenizer.encode(case.text)
            checked++
            if (actual.ids.toList() != case.ids) {
                failures.appendLine(
                    "ids mismatch for ${quote(case.text)}\n" +
                        "  expected ${case.ids}\n  actual   ${actual.ids.toList()}\n" +
                        "  expected tokens ${case.tokens}\n  actual tokens   ${actual.tokens}",
                )
                continue
            }
            if (actual.tokens != case.tokens) {
                failures.appendLine(
                    "tokens mismatch for ${quote(case.text)}\n" +
                        "  expected ${case.tokens}\n  actual   ${actual.tokens}",
                )
                continue
            }
            val expectedOffsets = case.offsets.flatten()
            if (actual.offsets.toList() != expectedOffsets) {
                failures.appendLine(
                    "offsets mismatch for ${quote(case.text)}\n" +
                        "  tokens   ${case.tokens}\n" +
                        "  expected ${case.offsets}\n" +
                        "  actual   ${(0 until actual.size).map { listOf(actual.startOf(it), actual.endOf(it)) }}",
                )
            }
        }
        if (failures.isNotEmpty()) {
            val lines =
                failures.lines().count {
                    it.startsWith("ids ") ||
                        it.startsWith("tokens ") ||
                        it.startsWith("offsets ")
                }
            throw AssertionError("$name: $lines of $checked golden cases diverged\n$failures")
        }
    }

    fun quote(text: String): String = "\"" + text.replace("\n", "\\n").replace("\t", "\\t").take(160) + "\""
}
