package app.skein.core.rag.tokenizers

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * bd skein-bpt (E5.I2) acceptance criterion #3: `TokenizerFactory.fromJson`
 * picks the model type from `model.type` and fails loudly on unsupported ones
 * (BPE is out of scope for v1 — the chat LLM tokenizes inside llama.cpp).
 */
class TokenizerFactoryTest {
    @Test
    fun picksWordPieceFromTheModelType() {
        val tokenizer = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        assertThat(tokenizer.encode("hello").tokens).containsExactly("[CLS]", "hello", "[SEP]").inOrder()
    }

    @Test
    fun picksUnigramFromTheModelType() {
        val tokenizer = TokenizerFixtures.tokenizer(TokenizerFixtures.GLINER)
        assertThat(tokenizer.encode("hello").tokens).containsExactly("[CLS]", "▁hello", "[SEP]").inOrder()
    }

    @Test
    fun rejectsBpeLoudly() {
        val error =
            assertThrows(UnsupportedTokenizerException::class.java) {
                load("""{"model":{"type":"BPE","vocab":{},"merges":[]}}""")
            }
        assertThat(error).hasMessageThat().contains("BPE")
        assertThat(error).hasMessageThat().contains("WordPiece and Unigram")
    }

    @Test
    fun rejectsAnUnknownModelTypeLoudly() {
        val error =
            assertThrows(UnsupportedTokenizerException::class.java) {
                load("""{"model":{"type":"SomethingElse","vocab":{}}}""")
            }
        assertThat(error).hasMessageThat().contains("SomethingElse")
    }

    @Test
    fun rejectsAnUnknownNormalizerLoudly() {
        val error =
            assertThrows(UnsupportedTokenizerException::class.java) {
                load(
                    """{"normalizer":{"type":"Precompiled","precompiled_charsmap":""},""" +
                        """"model":{"type":"WordPiece","unk_token":"[UNK]","vocab":{"[UNK]":0}}}""",
                )
            }
        assertThat(error).hasMessageThat().contains("Precompiled")
    }

    @Test
    fun rejectsMalformedJsonLoudly() {
        val error = assertThrows(UnsupportedTokenizerException::class.java) { load("not json at all") }
        assertThat(error).hasMessageThat().contains("Not a readable tokenizer.json")
    }

    @Test
    fun rejectsAWordPieceVocabWithoutItsUnkToken() {
        assertThrows(UnsupportedTokenizerException::class.java) {
            load("""{"model":{"type":"WordPiece","unk_token":"[UNK]","vocab":{"a":0}}}""")
        }
    }

    @Test
    fun readsAMinimalWordPieceTokenizerWithNoPipelineStages() {
        val tokenizer =
            load(
                """{"model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##",""" +
                    """"max_input_chars_per_word":100,"vocab":{"[UNK]":0,"ab":1,"##c":2}}}""",
            )
        assertThat(tokenizer.vocabSize).isEqualTo(3)
        // No pre-tokenizer means the whole input is one "word".
        assertThat(tokenizer.encode("abc").tokens).containsExactly("ab", "##c").inOrder()
        assertThat(tokenizer.encode("zz").ids.toList()).containsExactly(0)
    }

    @Test
    fun honoursTheTruncationBlockWhenTheFileDeclaresOne() {
        val tokenizer =
            load(
                """{"truncation":{"max_length":2,"direction":"Right","strategy":"LongestFirst"},""" +
                    """"model":{"type":"WordPiece","unk_token":"[UNK]","vocab":{"[UNK]":0,"a":1,"b":2}}}""",
            )
        assertThat(tokenizer.maxLength).isEqualTo(2)
    }

    // -------------------------------------------------------------- added tokens

    @Test
    fun literalAddedTokensInTheInputKeepTheirOwnIds() {
        val nomic = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        val encoding = nomic.encode("a [MASK] b")
        assertThat(encoding.ids.toList()).containsExactly(101, 1037, 103, 1038, 102).inOrder()
        assertThat(encoding.startOf(2)).isEqualTo(2)
        assertThat(encoding.endOf(2)).isEqualTo(8)
    }

    @Test
    fun literalAddedTokensSurviveTheUnigramPipelineToo() {
        val gliner = TokenizerFixtures.tokenizer(TokenizerFixtures.GLINER)
        val encoding = gliner.encode("a [MASK] b")
        assertThat(encoding.ids.toList()).containsExactly(1, 266, 128000, 2165, 2).inOrder()
        assertThat(encoding.tokens).containsExactly("[CLS]", "▁a", "[MASK]", "▁b", "[SEP]").inOrder()
        assertThat(encoding.startOf(3)).isEqualTo(8)
        assertThat(encoding.endOf(3)).isEqualTo(10)
    }

    @Test
    fun aLiteralClsInTheInputIsNotConfusedWithTheOneThePostProcessorAdds() {
        val nomic = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        val encoding = nomic.encode("[CLS] hello [SEP]")
        assertThat(encoding.ids.toList()).containsExactly(101, 101, 7592, 102, 102).inOrder()
        assertThat(encoding.startOf(1)).isEqualTo(0)
        assertThat(encoding.endOf(1)).isEqualTo(5)
    }

    @Test
    fun decodeSkipsSpecialTokensUnlessAskedToKeepThem() {
        val nomic = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        val ids = nomic.encode("hello").ids
        assertThat(nomic.decode(ids)).isEqualTo("hello")
        assertThat(nomic.decode(ids, skipSpecialTokens = false)).contains("[CLS]")
    }

    @Test
    fun truncateRejectsANonPositiveLimit() {
        val nomic = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        assertThrows(IllegalArgumentException::class.java) { nomic.truncate(0) }
    }

    private fun load(json: String): Tokenizer =
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { TokenizerFactory.fromJson(it) }
}
