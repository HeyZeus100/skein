package app.skein.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * skein-xtov.23.4 (DS4): the type scale is exactly `docs/ux/DESIGN_SYSTEM.md`
 * §3.2 / §3.3 — every Material role in [SkeinSans], only the mono styles in
 * [SkeinMono] — and the bundled files meet §14.8 (local, renamed, in budget).
 * Rendering (variable weights, tabular figures, font scale 2.0) is checked by
 * `SkeinTypeRenderingTest` in `:feature:shell`, which has Robolectric.
 */
class SkeinTypographyTest {
    private data class Role(
        val name: String,
        val style: TextStyle,
        val size: Int,
        val lineHeight: Int,
        val weight: Int,
        val tracking: Double,
    )

    private val roles =
        with(SkeinTypography) {
            listOf(
                Role("displayLarge", displayLarge, 45, 52, 400, -0.25),
                Role("displayMedium", displayMedium, 36, 44, 400, 0.0),
                Role("displaySmall", displaySmall, 32, 40, 500, 0.0),
                Role("headlineLarge", headlineLarge, 28, 36, 500, 0.0),
                Role("headlineMedium", headlineMedium, 24, 32, 500, 0.0),
                Role("headlineSmall", headlineSmall, 22, 28, 500, 0.0),
                Role("titleLarge", titleLarge, 20, 28, 600, 0.0),
                Role("titleMedium", titleMedium, 17, 24, 600, 0.0),
                Role("titleSmall", titleSmall, 14, 20, 600, 0.1),
                Role("bodyLarge", bodyLarge, 16, 24, 400, 0.0),
                Role("bodyMedium", bodyMedium, 14, 20, 400, 0.1),
                Role("bodySmall", bodySmall, 13, 18, 400, 0.1),
                Role("labelLarge", labelLarge, 14, 20, 600, 0.1),
                Role("labelMedium", labelMedium, 13, 18, 500, 0.2),
                Role("labelSmall", labelSmall, 12, 16, 500, 0.3),
            )
        }

    private val monoRoles =
        with(SkeinMonoTypography) {
            listOf(
                Role("codeBlock", codeBlock, 13, 20, 400, 0.0),
                Role("monoBody", monoBody, 14, 20, 400, 0.0),
                Role("monoLabel", monoLabel, 12, 16, 400, 0.0),
            )
        }

    @Test
    fun `every Material role matches the type scale in Skein Sans`() {
        assertEquals(15, roles.size)
        roles.forEach { assertRole(it, SkeinSans) }
    }

    @Test
    fun `mono styles are Skein Mono 400`() {
        monoRoles.forEach { assertRole(it, SkeinMono) }
        with(SkeinMonoTypography.codeInline) {
            assertEquals(SkeinMono, fontFamily)
            assertEquals(FontWeight.W400, fontWeight)
            assertEquals(0.9.em, fontSize)
        }
    }

    @Test
    fun `nothing is set below 12 sp`() {
        (roles + monoRoles).forEach { assertTrue(it.name, it.style.fontSize.value >= 12f) }
    }

    @Test
    fun `the two families are distinct bundled families`() {
        assertNotEquals(SkeinSans, SkeinMono)
        listOf(SkeinSans, SkeinMono).forEach {
            assertNotEquals(FontFamily.Default, it)
            assertNotEquals(FontFamily.Monospace, it)
        }
    }

    /** §14.8: three local files, ≤ 300 KB together, and no "Plex" left in them (OFL Reserved Font Name). */
    @Test
    fun `bundled fonts are the renamed subsets and within budget`() {
        val files = File("src/main/res/font").listFiles().orEmpty().sortedBy { it.name }
        assertEquals(listOf("skein_mono.ttf", "skein_sans.ttf", "skein_sans_italic.ttf"), files.map { it.name })
        assertTrue(files.sumOf { it.length() } <= 300 * 1024)
        val ascii = "Plex".toByteArray(Charsets.US_ASCII)
        val utf16 = "Plex".toByteArray(Charsets.UTF_16BE)
        files.forEach { file ->
            val bytes = file.readBytes()
            assertFalse(file.name, bytes.contains(ascii) || bytes.contains(utf16))
        }
    }

    private fun assertRole(
        role: Role,
        family: FontFamily,
    ) {
        with(role.style) {
            assertEquals(role.name, family, fontFamily)
            assertEquals(role.name, FontWeight(role.weight), fontWeight)
            assertEquals(role.name, role.size.toFloat(), fontSize.value)
            // §3.5: `em`, so non-linear font scaling can never make a line box shorter than its glyphs.
            assertEquals(role.name, TextUnitType.Em, lineHeight.type)
            assertEquals(role.name, role.lineHeight.toFloat(), lineHeight.value * role.size, 0.001f)
            assertEquals(role.name, role.tracking.toFloat(), letterSpacing.value, 0.0001f)
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
