// E2.I11 (bd skein-80m): filename via `SafeFileName` (task brief JVM test list).

package app.skein.core.export.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfFileNamingTest {
    @Test
    fun `appends the pdf extension to a sanitized title`() {
        assertThat(PdfFileNaming.fileName("Q3 Report")).isEqualTo("Q3 Report.pdf")
    }

    @Test
    fun `strips path separators via SafeFileName`() {
        assertThat(PdfFileNaming.fileName("notes/2026")).isEqualTo("notes2026.pdf")
    }

    @Test
    fun `falls back to untitled for a title with nothing left after sanitizing`() {
        assertThat(PdfFileNaming.fileName("///")).isEqualTo("untitled.pdf")
    }
}
