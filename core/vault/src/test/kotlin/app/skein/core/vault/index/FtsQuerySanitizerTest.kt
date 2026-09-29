package app.skein.core.vault.index

import app.skein.core.model.LexicalQueryLimits
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Query grammar tests only; actual tokenizer behavior is checked against real SQLite. */
public class FtsQuerySanitizerTest {
    @Test
    public fun `terms are quoted and OR joined with prefix only on last term`() {
        assertThat(FtsQuerySanitizer.sanitize(listOf("quick", "brown", "fox")))
            .isEqualTo("\"quick\" OR \"brown\" OR \"fox\"*")
        assertThat(FtsQuerySanitizer.sanitize(listOf("hello"))).isEqualTo("\"hello\"*")
        assertThat(FtsQuerySanitizer.sanitize(emptyList())).isEmpty()
    }

    @Test
    public fun `unicode tokenizer output is preserved rather than split into ascii fragments`() {
        assertThat(FtsQuerySanitizer.sanitize(listOf("中文english", "cafe", "резюме", "\uE000secret")))
            .isEqualTo("\"中文english\" OR \"cafe\" OR \"резюме\" OR \"\uE000secret\"*")
    }

    @Test
    public fun `quoted terms cannot inject FTS grammar even if caller violates tokenizer contract`() {
        assertThat(FtsQuerySanitizer.sanitize(listOf("AND", "OR", "x\" OR text:secret*")))
            .isEqualTo("\"AND\" OR \"OR\" OR \"x\"\" OR text:secret*\"*")
    }

    @Test
    public fun `query count and utf8 term sizes are bounded without truncating tokens`() {
        val max = LexicalQueryLimits.MAX_TERM_UTF8_BYTES
        val terms = listOf("", "界".repeat(max), "x".repeat(max)) + List(200) { "t$it" }
        val result = FtsQuerySanitizer.sanitize(terms)
        assertThat(result.split(" OR ")).hasSize(LexicalQueryLimits.MAX_TERMS)
        assertThat(result).startsWith("\"${"x".repeat(max)}\"")
        assertThat(result).endsWith("\"t126\"*")
        assertThat(result).doesNotContain("界")
    }
}
