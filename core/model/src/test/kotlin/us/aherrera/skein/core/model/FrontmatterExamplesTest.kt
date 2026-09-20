// `E0.I14` acceptance criterion: a JVM test pins the canonical frontmatter
// example text used by `docs/VAULT_FORMAT.md` (`E9.I5`) as
// `FrontmatterExamples.NOTE`, so that doc and the frontmatter codec
// (`E2.I3`) are authored against — and can be checked against — one fixture
// instead of independently-typed copies that can silently drift apart.

package us.aherrera.skein.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

public class FrontmatterExamplesTest {
    @Test
    public fun note_example_starts_with_yaml_frontmatter_fence() {
        val firstLine = FrontmatterExamples.NOTE.lines().first()

        assertTrue(
            "expected the fixture to open with the YAML frontmatter fence `---`, was `$firstLine`",
            firstLine == "---",
        )
    }

    @Test
    public fun note_example_contains_an_id_key() {
        assertTrue(
            "expected the fixture to contain an `${FrontmatterKeys.ID}: ` frontmatter line",
            FrontmatterExamples.NOTE.contains("${FrontmatterKeys.ID}: "),
        )
    }

    @Test
    public fun note_example_has_a_closing_fence_and_a_body() {
        val lines = FrontmatterExamples.NOTE.lines()
        val closingFenceIndex = lines.drop(1).indexOfFirst { it == "---" } + 1

        assertTrue(
            "expected a second `---` fence closing the frontmatter block",
            closingFenceIndex > 0,
        )
        assertTrue(
            "expected a non-blank body after the closing fence",
            lines.drop(closingFenceIndex + 1).any { it.isNotBlank() },
        )
    }

    @Test
    public fun note_example_carries_every_frontmatter_key_except_the_optional_source() {
        val body = FrontmatterExamples.NOTE

        assertTrue(body.contains("${FrontmatterKeys.ID}: "))
        assertTrue(body.contains("${FrontmatterKeys.KIND}: "))
        assertTrue(body.contains("${FrontmatterKeys.TITLE}: "))
        assertTrue(body.contains("${FrontmatterKeys.CREATED}: "))
        assertTrue(body.contains("${FrontmatterKeys.UPDATED}: "))
        assertTrue(body.contains("${FrontmatterKeys.PERSONA}: "))
        assertTrue(body.contains("${FrontmatterKeys.TAGS}: "))
    }
}
