// JVM unit tests for `TagExtractor` (skein-ys2, E5.I8 acceptance criterion:
// "Extractor unit tests: ... tags, exclusion inside code, unicode titles").

package app.skein.core.vault.extract

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

public class TagExtractorTest {
    @Test
    public fun `word-boundary-anchored tag is extracted lowercased`() {
        val tags = TagExtractor.extract("Some notes #Project here.")

        assertThat(tags).containsExactly("project")
    }

    @Test
    public fun `hash attached to a preceding word is not a tag`() {
        val tags = TagExtractor.extract("See word#notatag over here.")

        assertThat(tags).isEmpty()
    }

    @Test
    public fun `url fragment is not extracted as a tag`() {
        val tags = TagExtractor.extract("See https://example.com/page#section for details.")

        assertThat(tags).isEmpty()
    }

    @Test
    public fun `tag inside a fenced code block is excluded`() {
        val text =
            """
            Before
            ```
            #nottag
            ```
            After #realtag
            """.trimIndent()

        val tags = TagExtractor.extract(text)

        assertThat(tags).containsExactly("realtag")
    }

    @Test
    public fun `tag inside an inline code span is excluded`() {
        val tags = TagExtractor.extract("Use `#nottag` here, but #realtag works.")

        assertThat(tags).containsExactly("realtag")
    }

    @Test
    public fun `hex color literals are excluded`() {
        val tags = TagExtractor.extract("Colors: #fff #ffff #ff0000 #a1b2c3d4 but #notacolor stays.")

        assertThat(tags).containsExactly("notacolor")
    }

    @Test
    public fun `duplicate tags of differing case dedup to one lowercase entry`() {
        val tags = TagExtractor.extract("#foo #FOO #Foo")

        assertThat(tags).containsExactly("foo")
    }

    @Test
    public fun `nested tag with a slash is extracted whole`() {
        val tags = TagExtractor.extract("#project/alpha")

        assertThat(tags).containsExactly("project/alpha")
    }

    @Test
    public fun `body tags merge with frontmatter tags array, deduplicated`() {
        val text =
            """
            ---
            tags: [Alpha, beta]
            ---
            Body mentions #gamma and #alpha again.
            """.trimIndent()

        val tags = TagExtractor.extract(text)

        assertThat(tags).containsExactly("alpha", "beta", "gamma")
    }

    @Test
    public fun `frontmatter tags entries with a leading hash are normalized`() {
        val text =
            """
            ---
            tags: [#alpha, beta]
            ---
            body
            """.trimIndent()

        val tags = TagExtractor.extract(text)

        assertThat(tags).containsExactly("alpha", "beta")
    }

    @Test
    public fun `no tags anywhere yields an empty set`() {
        val tags = TagExtractor.extract("Just plain text.")

        assertThat(tags).isEmpty()
    }

    @Test
    public fun `heading markers are not extracted as tags`() {
        val tags = TagExtractor.extract("# Heading One\n## Heading Two")

        assertThat(tags).isEmpty()
    }

    @Test
    public fun `body plus already-parsed frontmatter overload merges tags without a header re-parse`() {
        val frontmatter =
            JsonObject(mapOf("tags" to JsonArray(listOf(JsonPrimitive("Alpha"), JsonPrimitive("#beta")))))

        val tags = TagExtractor.extract("Body #gamma and #alpha", frontmatter)

        assertThat(tags).containsExactly("alpha", "beta", "gamma")
    }
}
