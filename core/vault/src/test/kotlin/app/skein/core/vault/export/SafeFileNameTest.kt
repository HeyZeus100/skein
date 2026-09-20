// JVM unit tests for `SafeFileName` (`E2.I10`): the zip-entry filename
// sanitizer + collision disambiguator `ExportServiceImpl.exportVaultZip`
// uses to turn a document's `title` into a safe `<safe title>.md` path
// (plan `E2.I10`, bd `skein-90d` acceptance criterion "duplicate titles
// disambiguated").

package app.skein.core.vault.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class SafeFileNameTest {
    // ---- sanitize ----

    @Test
    public fun `sanitize strips forward and back slashes`() {
        assertThat(SafeFileName.sanitize("a/b\\c")).isEqualTo("abc")
    }

    @Test
    public fun `sanitize strips control characters`() {
        assertThat(SafeFileName.sanitize("Title\u0000With\nControl\tChars")).isEqualTo("TitleWithControlChars")
    }

    @Test
    public fun `sanitize replaces a single non-ascii character with an underscore`() {
        assertThat(SafeFileName.sanitize("Café")).isEqualTo("Caf_")
    }

    @Test
    public fun `sanitize collapses a run of consecutive non-ascii characters into one underscore`() {
        // é (e-acute) and ö (o-umlaut) are adjacent non-ASCII code
        // points; they must collapse to a single "_", not "__".
        assertThat(SafeFileName.sanitize("aéöb")).isEqualTo("a_b")
    }

    @Test
    public fun `sanitize trims whitespace left behind after stripping`() {
        assertThat(SafeFileName.sanitize("  My Title  ")).isEqualTo("My Title")
    }

    @Test
    public fun `sanitize falls back to a default name when nothing survives`() {
        assertThat(SafeFileName.sanitize("///\u0000\u0000")).isEqualTo("untitled")
    }

    @Test
    public fun `sanitize leaves an already-safe ascii title unchanged`() {
        assertThat(SafeFileName.sanitize("Weekly Planning Notes")).isEqualTo("Weekly Planning Notes")
    }

    // ---- uniqueName ----

    @Test
    public fun `uniqueName returns the plain name on first use`() {
        val used = mutableSetOf<String>()

        assertThat(SafeFileName.uniqueName("Note", "md", used)).isEqualTo("Note.md")
    }

    @Test
    public fun `uniqueName disambiguates repeated base names with a numeric suffix`() {
        val used = mutableSetOf<String>()

        val first = SafeFileName.uniqueName("Note", "md", used)
        val second = SafeFileName.uniqueName("Note", "md", used)
        val third = SafeFileName.uniqueName("Note", "md", used)

        assertThat(listOf(first, second, third))
            .containsExactly(
                "Note.md",
                "Note (2).md",
                "Note (3).md",
            ).inOrder()
    }

    @Test
    public fun `uniqueName does not collide with a name that already carries a numeric suffix`() {
        val used = mutableSetOf<String>()
        SafeFileName.uniqueName("Note", "md", used) // "Note.md"
        used.add("Note (2).md") // pre-existing entry from a different title collision

        val third = SafeFileName.uniqueName("Note", "md", used)

        assertThat(third).isEqualTo("Note (3).md")
    }
}
