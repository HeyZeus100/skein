// JVM unit tests for `WikilinkExtractor` (skein-ys2, E5.I8 acceptance
// criterion: "Extractor unit tests: the four link forms, tags, exclusion
// inside code, unicode titles").

package app.skein.core.vault.extract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class WikilinkExtractorTest {
    @Test
    public fun `bare wikilink extracts the title with no alias or heading`() {
        val links = WikilinkExtractor.extract("See [[Project Plan]] for details.")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Project Plan")
        assertThat(links[0].alias).isNull()
        assertThat(links[0].heading).isNull()
    }

    @Test
    public fun `aliased wikilink extracts target and alias`() {
        val links = WikilinkExtractor.extract("[[Project Plan|the plan]]")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Project Plan")
        assertThat(links[0].alias).isEqualTo("the plan")
        assertThat(links[0].heading).isNull()
    }

    @Test
    public fun `heading-anchored wikilink extracts target and heading, no alias`() {
        val links = WikilinkExtractor.extract("[[Project Plan#Milestones]]")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Project Plan")
        assertThat(links[0].heading).isEqualTo("Milestones")
        assertThat(links[0].alias).isNull()
    }

    @Test
    public fun `combined heading and alias wikilink extracts all three parts`() {
        val links = WikilinkExtractor.extract("[[Project Plan#Milestones|the plan]]")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Project Plan")
        assertThat(links[0].heading).isEqualTo("Milestones")
        assertThat(links[0].alias).isEqualTo("the plan")
    }

    @Test
    public fun `multiple wikilinks are all extracted with correct positions`() {
        val text = "[[A]] and [[B]]"

        val links = WikilinkExtractor.extract(text)

        assertThat(links).hasSize(2)
        assertThat(text.substring(links[0].start, links[0].end)).isEqualTo("[[A]]")
        assertThat(text.substring(links[1].start, links[1].end)).isEqualTo("[[B]]")
    }

    @Test
    public fun `unicode title is extracted verbatim`() {
        val links = WikilinkExtractor.extract("[[日本語のノート]]")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("日本語のノート")
    }

    @Test
    public fun `wikilink inside a fenced code block is ignored`() {
        val text =
            """
            Before
            ```
            [[Not A Link]]
            ```
            After [[Real Link]]
            """.trimIndent()

        val links = WikilinkExtractor.extract(text)

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Real Link")
    }

    @Test
    public fun `wikilink inside an inline code span is ignored`() {
        val links = WikilinkExtractor.extract("Use `[[Not A Link]]` syntax, or [[Real Link]].")

        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Real Link")
    }

    @Test
    public fun `empty target is not extracted as a wikilink`() {
        val links = WikilinkExtractor.extract("[[]]")

        assertThat(links).isEmpty()
    }

    @Test
    public fun `unclosed wikilink is not extracted`() {
        val links = WikilinkExtractor.extract("[[Not closed")

        assertThat(links).isEmpty()
    }

    @Test
    public fun `no wikilinks in plain text yields an empty list`() {
        val links = WikilinkExtractor.extract("Just plain text with no links at all.")

        assertThat(links).isEmpty()
    }
}
