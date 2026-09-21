// E2.I12 (bd skein-jq8): `XmlEscaper` — the writer's only place that turns
// arbitrary user text into safe XML content/attribute values.

package app.skein.core.vault.export.docx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XmlEscaperTest {
    @Test
    fun `escapeText escapes ampersand less-than and greater-than`() {
        assertThat(XmlEscaper.escapeText("a & b < c > d")).isEqualTo("a &amp; b &lt; c &gt; d")
    }

    @Test
    fun `escapeText leaves quotes untouched since they are not special in element content`() {
        assertThat(XmlEscaper.escapeText("""she said "hi" and 'bye'""")).isEqualTo("""she said "hi" and 'bye'""")
    }

    @Test
    fun `escapeAttribute additionally escapes double and single quotes`() {
        assertThat(XmlEscaper.escapeAttribute("""a "quoted" 'value'"""))
            .isEqualTo("a &quot;quoted&quot; &apos;value&apos;")
    }

    @Test
    fun `escapeText passes emoji through untouched as ordinary characters`() {
        val emoji = "sparkles 🎉 party" // "sparkles 🎉 party"
        assertThat(XmlEscaper.escapeText(emoji)).isEqualTo(emoji)
    }

    @Test
    fun `escapeText passes RTL text through untouched`() {
        val arabic = "مرحبا" // "مرحبا" (hello)
        assertThat(XmlEscaper.escapeText(arabic)).isEqualTo(arabic)
    }

    @Test
    fun `escapeText strips disallowed control characters but keeps tab and newline`() {
        assertThat(XmlEscaper.escapeText("a\u0000b\tc\nd\u0007e")).isEqualTo("ab\tc\nde")
    }

    @Test
    fun `escapeAttribute escapes carriage return newline and tab as numeric character references`() {
        assertThat(XmlEscaper.escapeAttribute("a\rb\nc\td")).isEqualTo("a&#13;b&#10;c&#9;d")
    }
}
