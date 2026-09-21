package app.skein.core.rag.tokenizers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E5.I10 (skein-7v3): the stand-in tokenizer the `Chunker` runs over until the embedder's own tokenizer is loaded. */
class ApproximateTokenizerTest {
    @Test
    fun `offsets are exact UTF-16 slices of the input`() {
        val text = "Hello, world! Extraordinarily long-ish words."

        val encoding = ApproximateTokenizer.encode(text)

        val pieces = (0 until encoding.size).map { encoding.surfaceOf(text, it) }
        assertThat(pieces.joinToString("")).isEqualTo(text.replace(" ", ""))
        assertThat(pieces).contains(",")
        assertThat(pieces).contains("!")
    }

    @Test
    fun `short words are one token and long words split into roughly five-character pieces`() {
        assertThat(ApproximateTokenizer.countTokens("the")).isEqualTo(1)
        assertThat(ApproximateTokenizer.countTokens("hello")).isEqualTo(1)
        assertThat(ApproximateTokenizer.countTokens("extraordinarily")).isEqualTo(3)
    }

    @Test
    fun `punctuation counts one token per character`() {
        assertThat(ApproximateTokenizer.countTokens("a, b; c!")).isEqualTo(6)
    }

    @Test
    fun `blank input has no tokens`() {
        assertThat(ApproximateTokenizer.countTokens("   \n\t")).isEqualTo(0)
    }

    @Test
    fun `truncate caps the encoding at maxLen`() {
        val truncated = ApproximateTokenizer.truncate(3)

        assertThat(truncated.maxLength).isEqualTo(3)
        assertThat(truncated.countTokens("one two three four five")).isEqualTo(3)
        assertThat(ApproximateTokenizer.countTokens("one two three four five")).isEqualTo(5)
    }

    @Test
    fun `decode is unsupported — there is no vocabulary`() {
        val thrown = runCatching { ApproximateTokenizer.decode(intArrayOf(0)) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UnsupportedOperationException::class.java)
    }
}
