package app.skein.core.designsystem.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN_SYSTEM.md §14.10: no hard-coded colour literals (`Color(0x…)`)
 * outside the theme package, including `core:markdown` — every colour a
 * screen shows must come from [SkeinColors]/[SkeinExtendedColors], never a
 * one-off hex baked into a composable.
 *
 * skein-xtov.23.12 (DS14). `core/designsystem` itself needs no allow-list
 * here: its palette is stored as raw `0xAARRGGBB` `Long`s in
 * [SkeinColorHex]/`SkeinPalette` and only ever wrapped as `Color(p.someRole)`
 * (a variable, not a literal), so this guard never fires on the theme files.
 *
 * `core/markdown/.../MarkdownStyle.kt` no longer needs an allow-list entry
 * either: skein-xtov.23.10 (DS10, Wave 2 markdown tokens) moved its colours
 * out to `rememberSkeinMarkdownStyle()` here and left only named framework
 * constants ([androidx.compose.ui.graphics.Color.Gray] etc.) in
 * `MarkdownStyle.Default`, so this guard already covers it with no exception.
 */
class NoHardCodedColorTest {
    @Test
    fun `no hard-coded Color(0x…) literal outside the theme`() {
        val offenders =
            GuardSupport
                .productionKotlinFiles("feature", "core/designsystem", "core/markdown")
                .flatMap { file ->
                    file
                        .readLines()
                        .filterNot(GuardSupport::isCommentLine)
                        .flatMap { line -> hardCodedColorLiterals.findAll(line).map { it.value } }
                        .map { match -> file.path to match }
                }

        assertTrue(
            "hard-coded Color(0x…) literal found outside the theme (spec §14.10):\n" +
                offenders.joinToString("\n") { (path, match) -> "  $path: $match" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `detector self-test - bad snippets are caught, good ones are not`() {
        assertTrue(hardCodedColorLiterals.containsMatchIn("""val bg: Color = Color(0xFF112233)"""))
        assertTrue(hardCodedColorLiterals.containsMatchIn("""Text(color = Color(0xFFAABBCC))"""))
        assertFalse(hardCodedColorLiterals.containsMatchIn("""Color(p.primary)"""))
        assertFalse(hardCodedColorLiterals.containsMatchIn("""MaterialTheme.colorScheme.primary"""))
        assertFalse(hardCodedColorLiterals.containsMatchIn("""val mask: Long = 0xFF112233 // not a Color(...) call"""))
    }

    companion object {
        private val hardCodedColorLiterals = Regex("""Color\s*\(\s*0[xX][0-9A-Fa-f]+""")
    }
}
