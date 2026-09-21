package app.skein.core.rag.tokenizers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * bd skein-bpt (E5.I2) acceptance criterion #2: `UnigramTokenizerTest` — ids
 * equal the golden for all 300 fixtures, offsets are correct, and a 10 000-char
 * input tokenizes in under 50 ms on the JVM.
 *
 * The model under test is DeBERTa-v3's SentencePiece Unigram vocabulary, which
 * is what GLiNER (bd skein-eq1) decodes entity spans against — so the offsets
 * this produces are what turns a predicted token span back into a character
 * range in the note.
 */
class UnigramTokenizerTest {
    private val gliner get() = TokenizerFixtures.tokenizer(TokenizerFixtures.GLINER)

    @Test
    fun matchesPythonGoldenForEveryFixture() {
        val golden = TokenizerFixtures.golden(TokenizerFixtures.GLINER)
        assertThat(golden.cases).hasSize(300)
        TokenizerParity.assertMatches(TokenizerFixtures.GLINER, gliner, golden.cases)
    }

    @Test
    fun offsetsRoundTripBackToTheOriginalText() {
        // For plain-ASCII input the DeBERTa normalizer is the identity apart
        // from whitespace collapsing, so every token's source slice must be
        // exactly the token text with `▁` standing in for the leading space.
        val golden = TokenizerFixtures.golden(TokenizerFixtures.GLINER)
        var checked = 0
        for (case in golden.cases) {
            val text = case.text
            if (text.isBlank() || !text.all { it.code in 0x20..0x7E } || text.contains("  ")) continue
            val encoding = gliner.encode(text)
            for (i in 0 until encoding.size) {
                val token = encoding.tokens[i]
                if (token == "[CLS]" || token == "[SEP]" || token == "[UNK]") continue
                val expected = token.replace('▁', ' ')
                assertThat(encoding.surfaceOf(text, i)).isEqualTo(
                    expected.trimStart().let { trimmed ->
                        if (expected.startsWith(" ") && encoding.startOf(i) > 0) " $trimmed" else trimmed
                    },
                )
                checked++
            }
        }
        assertThat(checked).isGreaterThan(1000)
    }

    @Test
    fun offsetsStayInsideTheInputAndNeverGoBackwards() {
        val golden = TokenizerFixtures.golden(TokenizerFixtures.GLINER)
        for (case in golden.cases) {
            val encoding = gliner.encode(case.text)
            var previousStart = 0
            for (i in 0 until encoding.size) {
                val start = encoding.startOf(i)
                val end = encoding.endOf(i)
                assertThat(start).isAtLeast(0)
                assertThat(end).isAtMost(case.text.length)
                assertThat(end).isAtLeast(start)
                if (encoding.tokens[i] != "[CLS]" && encoding.tokens[i] != "[SEP]") {
                    assertThat(start).isAtLeast(previousStart)
                    previousStart = start
                }
            }
        }
    }

    @Test
    fun metaspaceTokensAbsorbThePrecedingSpace() {
        val text = "Hello world"
        val encoding = gliner.encode(text)
        assertThat(encoding.tokens).containsExactly("[CLS]", "▁Hello", "▁world", "[SEP]").inOrder()
        assertThat(encoding.surfaceOf(text, 1)).isEqualTo("Hello")
        assertThat(encoding.surfaceOf(text, 2)).isEqualTo(" world")
    }

    @Test
    fun wrapsEverySequenceInClsAndSep() {
        val encoding = gliner.encode("entities live here")
        assertThat(encoding.ids.first()).isEqualTo(1)
        assertThat(encoding.ids.last()).isEqualTo(2)
        assertThat(encoding.tokens.first()).isEqualTo("[CLS]")
        assertThat(encoding.tokens.last()).isEqualTo("[SEP]")
    }

    @Test
    fun emptyAndWhitespaceOnlyInputYieldOnlyTheSpecialTokens() {
        assertThat(gliner.encode("").tokens).containsExactly("[CLS]", "[SEP]").inOrder()
        assertThat(gliner.encode("   ").tokens).containsExactly("[CLS]", "[SEP]").inOrder()
        assertThat(gliner.encode("\n\t\n").tokens).containsExactly("[CLS]", "[SEP]").inOrder()
    }

    @Test
    fun runsOfUnknownCharactersFuseIntoASingleUnk() {
        // `fuse_unk` is always on for a tokenizer.json-loaded Unigram, so a run
        // of unknown characters becomes one `[UNK]` spanning all of them rather
        // than one `[UNK]` per character (which is what WordPiece does).
        // U+F0000..U+F0002 are supplementary private-use characters, absent from
        // the DeBERTa-v3 vocabulary. The fused piece keeps the *source* text as
        // its value while carrying the `[UNK]` id, exactly as `tokenizers` does.
        val unknowns = "\udb80\udc00\udb80\udc01\udb80\udc02"
        val text = "ok $unknowns ok"
        val encoding = gliner.encode(text)
        val unkId = 3
        val unkIndices = (0 until encoding.size).filter { encoding.ids[it] == unkId }
        assertThat(unkIndices).hasSize(1)
        val i = unkIndices.single()
        assertThat(encoding.surfaceOf(text, i)).isEqualTo(unknowns)
        assertThat(encoding.tokens[i]).isEqualTo(unknowns)
    }

    @Test
    fun normalizerCollapsesRunsOfWhitespaceAndStripsTheTail() {
        // The DeBERTa pipeline normalizes with Replace(\s{2,}|[\n\r\t] -> " "),
        // then NFC, then a right-hand Strip.
        val spaced = gliner.encode("a    b")
        val single = gliner.encode("a b")
        assertThat(spaced.ids.toList()).isEqualTo(single.ids.toList())
        assertThat(gliner.encode("trailing   ").ids.toList()).isEqualTo(gliner.encode("trailing").ids.toList())
    }

    @Test
    fun decomposedAccentsAreRecomposedByNfc() {
        // Escaped on purpose — the two literals render identically but differ
        // in bytes, which is exactly what NFC is being asked to reconcile.
        val precomposed = gliner.encode("caf\u00e9").ids.toList()
        assertThat(precomposed).isNotEmpty()
        assertThat(gliner.encode("cafe\u0301").ids.toList()).isEqualTo(precomposed)
    }

    @Test
    fun truncationMatchesPythonGolden() {
        val golden = TokenizerFixtures.golden(TokenizerFixtures.GLINER)
        assertThat(golden.truncation).isNotEmpty()
        for (case in golden.truncation) {
            val actual = gliner.truncate(case.maxLength).encode(case.text)
            assertThat(actual.ids.toList()).isEqualTo(case.ids)
            assertThat(actual.offsets.toList()).isEqualTo(case.offsets.flatten())
        }
    }

    @Test
    fun tenThousandCharactersTokenizeWellUnderFiftyMilliseconds() {
        val text =
            buildString {
                while (length < 10_000) {
                    append("The ingest pipeline chunks a note, embeds it, and extracts entities with GLiNER. ")
                }
            }.substring(0, 10_000)

        // Warm the JIT and the prefix index before measuring.
        repeat(5) { gliner.encode(text) }
        val elapsed = (1..5).minOf { measureTimeMillis { gliner.encode(text) } }
        println("UnigramTokenizer: 10 000 chars in $elapsed ms")
        assertThat(elapsed).isLessThan(50L)
    }

    @Test
    fun countTokensAgreesWithEncode() {
        val text = "the chunker budgets against exactly this number"
        assertThat(gliner.countTokens(text)).isEqualTo(gliner.encode(text).size)
    }

    @Test
    fun vocabSizeIsTheDebertaV3Vocabulary() {
        assertThat(gliner.vocabSize).isEqualTo(128_000)
    }

    @Test
    fun decodeRoundTripsPlainText() {
        val text = "GLiNER extracts entities"
        assertThat(gliner.decode(gliner.encode(text).ids)).isEqualTo(text)
    }
}
