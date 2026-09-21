package app.skein.core.rag.recall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueryNgramsTest {
    @Test
    fun `empty query yields no ngrams`() {
        assertThat(QueryNgrams.extract("")).isEmpty()
    }

    @Test
    fun `all-lowercase query yields no ngrams`() {
        assertThat(QueryNgrams.extract("what did i say about the vault yesterday")).isEmpty()
    }

    @Test
    fun `single capitalized word is its own ngram`() {
        assertThat(QueryNgrams.extract("tell me about Zamboni please")).contains("Zamboni")
    }

    @Test
    fun `run of three capitalized words yields all contiguous sub-spans up to length 3`() {
        val ngrams = QueryNgrams.extract("who works at Skein Labs Incorporated these days")

        assertThat(ngrams).containsAtLeast(
            "Skein Labs Incorporated",
            "Skein Labs",
            "Labs Incorporated",
            "Skein",
            "Labs",
            "Incorporated",
        )
    }

    @Test
    fun `run of four capitalized words never yields a length-4 ngram`() {
        val ngrams = QueryNgrams.extract("New Talvera Port Elara border dispute")

        assertThat(ngrams).doesNotContain("New Talvera Port Elara")
        assertThat(ngrams).contains("New Talvera Port")
        assertThat(ngrams).contains("Talvera Port Elara")
    }

    @Test
    fun `non-capitalized token breaks a run`() {
        val ngrams = QueryNgrams.extract("Marisol and Declan met")

        assertThat(ngrams).containsExactly("Marisol", "Declan")
    }

    @Test
    fun `apostrophes and hyphens stay inside one token`() {
        val ngrams = QueryNgrams.extract("ask O'Brien about the co-op")

        assertThat(ngrams).contains("O'Brien")
    }
}
