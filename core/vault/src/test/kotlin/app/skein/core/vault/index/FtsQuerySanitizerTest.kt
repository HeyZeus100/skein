// JVM unit tests for `FtsQuerySanitizer` (E2.I15 acceptance):
//   • Well-formed input tokenises predictably.
//   • The plan's acceptance query `it's a "quoted" (weird) query` (plus
//     19 further adversarial strings) never throws.
//   • A last-token prefix `*` is added exactly once, on the last quoted
//     token.
//   • Empty / whitespace input yields empty output (caller
//     short-circuits).

package app.skein.core.vault.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class FtsQuerySanitizerTest {
    @Test
    public fun `simple query is quoted and OR-joined with prefix on last term`() {
        val actual = FtsQuerySanitizer.sanitize("quick brown fox")
        assertThat(actual).isEqualTo("\"quick\" OR \"brown\" OR \"fox\"*")
    }

    @Test
    public fun `single token gets prefix marker`() {
        val actual = FtsQuerySanitizer.sanitize("hello")
        assertThat(actual).isEqualTo("\"hello\"*")
    }

    @Test
    public fun `empty query returns empty string`() {
        assertThat(FtsQuerySanitizer.sanitize("")).isEmpty()
    }

    @Test
    public fun `whitespace-only query returns empty string`() {
        assertThat(FtsQuerySanitizer.sanitize("   \t\n   ")).isEmpty()
    }

    @Test
    public fun `plan acceptance query does not throw and tokenises reasonably`() {
        // The plan's acceptance criterion cites this exact adversarial
        // input; the sanitizer must not throw and must produce a MATCH
        // payload FTS5 can parse. We assert only structure here (real
        // parse verification is in the instrumented contract test).
        val actual = FtsQuerySanitizer.sanitize("it's a \"quoted\" (weird) query")
        assertThat(actual).contains("\"it\"")
        assertThat(actual).contains("\"quoted\"")
        assertThat(actual).contains("\"weird\"")
        assertThat(actual).endsWith("*")
    }

    @Test
    public fun `twenty adversarial strings never throw`() {
        // The acceptance criterion is "does not throw" — no output
        // assertion beyond "the sanitizer returned a String".
        val adversarial =
            listOf(
                "it's a \"quoted\" (weird) query",
                "",
                "   ",
                "\"",
                "\"\"\"",
                "()",
                "( )",
                "-",
                "--",
                "AND OR NOT NEAR",
                "^^^",
                "***",
                "\u0000",
                "🚀 rocket 💩",
                "prefix: \"unterminated",
                "column:body AND text:foo",
                "col\u0000umn:body",
                "\\\\\\",
                "a b c d e f g h i j k l m n o p q r s t",
                "!@#$%^&*()_+-=[]{}|;':\",./<>?`~",
            )
        for (input in adversarial) {
            val sanitized = FtsQuerySanitizer.sanitize(input)
            assertThat(sanitized).isNotNull()
        }
    }

    @Test
    public fun `unicode tokens are stripped by ascii tokenizer`() {
        // The tokenizer intentionally splits on non-ASCII-alnum. This is
        // a fake-friendly baseline; the real FTS5 tokenizer runs on the
        // full input separately (`chunks_fts` uses the default unicode61
        // tokenizer). The sanitizer's job is to keep the QUERY string
        // syntactically safe, not to canonicalize corpus text.
        val actual = FtsQuerySanitizer.sanitize("café résumé naïve")
        // The non-ASCII letters are split points, so we get tokens
        // "caf", "r", "sum", "na", "ve" — none of which throw.
        assertThat(actual).contains("*")
        assertThat(actual).contains("OR")
    }
}
