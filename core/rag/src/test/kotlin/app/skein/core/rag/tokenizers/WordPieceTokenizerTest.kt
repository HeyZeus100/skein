package app.skein.core.rag.tokenizers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * bd skein-bpt (E5.I2) acceptance criterion #1: `WordPieceTokenizerTest` — ids
 * equal the golden for all 300 fixtures and `offsets` map each token to the
 * correct `[start, end)` in the input.
 *
 * Both BERT-uncased checkpoints Skein uses go through this model:
 * nomic-embed-text-v1.5 (the embedder) and ms-marco-MiniLM-L-6-v2 (the
 * conditional reranker).
 */
class WordPieceTokenizerTest {
    private val nomic get() = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
    private val msMarco get() = TokenizerFixtures.tokenizer(TokenizerFixtures.MS_MARCO)

    @Test
    fun nomicMatchesPythonGoldenForEveryFixture() {
        val golden = TokenizerFixtures.golden(TokenizerFixtures.NOMIC)
        assertThat(golden.cases).hasSize(300)
        TokenizerParity.assertMatches(TokenizerFixtures.NOMIC, nomic, golden.cases)
    }

    @Test
    fun msMarcoMatchesPythonGoldenForEveryFixture() {
        val golden = TokenizerFixtures.golden(TokenizerFixtures.MS_MARCO)
        assertThat(golden.cases).hasSize(300)
        TokenizerParity.assertMatches(TokenizerFixtures.MS_MARCO, msMarco, golden.cases)
    }

    @Test
    fun goldenFixturesCarryUtf16Offsets() {
        assertThat(TokenizerFixtures.golden(TokenizerFixtures.NOMIC).offsetUnits).isEqualTo("utf16")
    }

    // ------------------------------------------------------------ post-processing

    @Test
    fun wrapsEverySequenceInClsAndSep() {
        val encoding = nomic.encode("hello world")
        assertThat(encoding.tokens.first()).isEqualTo("[CLS]")
        assertThat(encoding.tokens.last()).isEqualTo("[SEP]")
        assertThat(encoding.ids.first()).isEqualTo(101)
        assertThat(encoding.ids.last()).isEqualTo(102)
    }

    @Test
    fun specialTokensCarryEmptyOffsets() {
        val encoding = nomic.encode("hello world")
        assertThat(encoding.startOf(0)).isEqualTo(0)
        assertThat(encoding.endOf(0)).isEqualTo(0)
        assertThat(encoding.startOf(encoding.size - 1)).isEqualTo(0)
        assertThat(encoding.endOf(encoding.size - 1)).isEqualTo(0)
    }

    @Test
    fun attentionMaskIsAllOnesBecauseNothingIsPadded() {
        val encoding = nomic.encode("padding is the caller's job")
        assertThat(encoding.attentionMask.toList()).containsNoneOf(0, -1)
        assertThat(encoding.attentionMask).hasLength(encoding.size)
    }

    @Test
    fun typeIdsAreZeroForASingleSequence() {
        val encoding = nomic.encode("single sequence only")
        assertThat(encoding.typeIds.toSet()).containsExactly(0)
    }

    // ------------------------------------------------------------- degenerate input

    @Test
    fun emptyInputYieldsOnlyTheSpecialTokens() {
        val encoding = nomic.encode("")
        assertThat(encoding.tokens).containsExactly("[CLS]", "[SEP]").inOrder()
    }

    @Test
    fun whitespaceOnlyInputYieldsOnlyTheSpecialTokens() {
        assertThat(nomic.encode("   ").tokens).containsExactly("[CLS]", "[SEP]").inOrder()
        assertThat(nomic.encode("\t\n  ").tokens).containsExactly("[CLS]", "[SEP]").inOrder()
    }

    @Test
    fun controlCharactersAreStrippedWithoutDisturbingOffsets() {
        val text = "\u0000� bad"
        val encoding = nomic.encode(text)
        assertThat(encoding.tokens).containsExactly("[CLS]", "bad", "[SEP]").inOrder()
        assertThat(encoding.surfaceOf(text, 1)).isEqualTo("bad")
    }

    // -------------------------------------------------------------------- [UNK]

    @Test
    fun charactersOutsideTheVocabularyBecomeUnk() {
        // U+4F60 is not in bert-base-uncased; U+4E16 is. `handle_chinese_chars`
        // pads each CJK ideograph so it becomes a word of its own.
        val text = "你好世界"
        val encoding = nomic.encode(text)
        assertThat(encoding.tokens).containsExactly("[CLS]", "[UNK]", "[UNK]", "世", "[UNK]", "[SEP]").inOrder()
        assertThat(encoding.surfaceOf(text, 1)).isEqualTo("你")
        assertThat(encoding.surfaceOf(text, 4)).isEqualTo("界")
    }

    @Test
    fun aWordLongerThanMaxInputCharsPerWordCollapsesToASingleUnk() {
        val text = "a".repeat(200)
        val encoding = nomic.encode(text)
        assertThat(encoding.tokens).containsExactly("[CLS]", "[UNK]", "[SEP]").inOrder()
        assertThat(encoding.startOf(1)).isEqualTo(0)
        assertThat(encoding.endOf(1)).isEqualTo(200)
    }

    // ------------------------------------------------------------- normalization

    @Test
    fun accentsAreStrippedButOffsetsStillCoverTheAccentedSource() {
        val text = "café naïve"
        val encoding = nomic.encode(text)
        assertThat(encoding.tokens).containsExactly("[CLS]", "cafe", "naive", "[SEP]").inOrder()
        assertThat(encoding.surfaceOf(text, 1)).isEqualTo("café")
        assertThat(encoding.surfaceOf(text, 2)).isEqualTo("naïve")
    }

    @Test
    fun decomposedAndPrecomposedAccentsProduceTheSameIds() {
        // The escapes are deliberate: these two strings render identically and
        // the whole point of the test is that they are different byte sequences.
        val precomposed = nomic.encode("caf\u00e9")
        val decomposed = nomic.encode("cafe\u0301")
        assertThat(precomposed.ids.toList()).isNotEmpty()
        assertThat(decomposed.ids.toList()).isEqualTo(precomposed.ids.toList())
    }

    @Test
    fun fullWidthAndLigatureFormsSurviveNormalization() {
        // BertNormalizer does no compatibility folding, so these stay distinct
        // from their ASCII equivalents — the golden corpus pins the exact ids.
        assertThat(nomic.encode("ａｂｃ").size).isGreaterThan(2)
        assertThat(nomic.encode("ﬁ").size).isGreaterThan(2)
    }

    @Test
    fun continuationSubwordsUseTheHashHashPrefixAndContiguousOffsets() {
        val text = "supercalifragilisticexpialidocious"
        val encoding = nomic.encode(text)
        assertThat(encoding.tokens[1]).isEqualTo("super")
        assertThat(encoding.tokens[2]).startsWith("##")
        var cursor = 0
        for (i in 1 until encoding.size - 1) {
            assertThat(encoding.startOf(i)).isEqualTo(cursor)
            cursor = encoding.endOf(i)
        }
        assertThat(cursor).isEqualTo(text.length)
    }

    // ------------------------------------------------------------------ truncation

    @Test
    fun truncationMatchesPythonGoldenIncludingSpecialTokenAccounting() {
        for (name in listOf(TokenizerFixtures.NOMIC, TokenizerFixtures.MS_MARCO)) {
            val golden = TokenizerFixtures.golden(name)
            assertThat(golden.truncation).isNotEmpty()
            for (case in golden.truncation) {
                val actual = TokenizerFixtures.tokenizer(name).truncate(case.maxLength).encode(case.text)
                assertThat(actual.ids.toList()).isEqualTo(case.ids)
                assertThat(actual.offsets.toList()).isEqualTo(case.offsets.flatten())
            }
        }
    }

    @Test
    fun truncationCapsTheTotalLengthAndKeepsTheClosingSep() {
        val truncated = nomic.truncate(8)
        val encoding = truncated.encode("the quick brown fox jumps over the lazy dog many times over")
        assertThat(encoding.size).isEqualTo(8)
        assertThat(encoding.tokens.first()).isEqualTo("[CLS]")
        assertThat(encoding.tokens.last()).isEqualTo("[SEP]")
    }

    @Test
    fun truncateIsAViewAndLeavesTheOriginalUntouched() {
        val base = nomic
        val short = base.truncate(4)
        assertThat(base.maxLength).isNull()
        assertThat(short.maxLength).isEqualTo(4)
        assertThat(base.encode("one two three four five six").size).isGreaterThan(4)
    }

    // --------------------------------------------------------------------- decode

    @Test
    fun decodeRoundTripsLowercasedAsciiText() {
        val text = "the quick brown fox"
        assertThat(nomic.decode(nomic.encode(text).ids)).isEqualTo(text)
    }

    @Test
    fun countTokensAgreesWithEncode() {
        val text = "the chunker budgets against exactly this number"
        assertThat(nomic.countTokens(text)).isEqualTo(nomic.encode(text).size)
    }

    @Test
    fun vocabSizeIsTheBertUncasedVocabulary() {
        assertThat(nomic.vocabSize).isEqualTo(30522)
        assertThat(msMarco.vocabSize).isEqualTo(30522)
    }
}
