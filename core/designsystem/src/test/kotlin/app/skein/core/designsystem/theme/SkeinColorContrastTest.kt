package app.skein.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.round

/**
 * skein-xtov.23.2 (DS2): DESIGN_SYSTEM.md §14 items 1 and 2.
 *
 * [PAIRS] mirrors `PAIRS` in `docs/ux/tools/contrast.py` row for row and is
 * measured against the colours that actually ship ([SkeinColors]'s schemes
 * and extended colours, resolved by role name exactly as the script resolves
 * its `TOKENS`/`ALIASES`). A drift test reads the script itself, so the
 * document, the script and the theme cannot disagree silently.
 */
class SkeinColorContrastTest {
    @Test
    fun `every pair in contrast-py meets its threshold in both themes`() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (theme in THEMES) {
            for ((fg, bg, need, use) in PAIRS) {
                if (need == INFO) continue
                checked++
                val ratio = WcagContrast.ratio(resolve(theme, fg), resolve(theme, bg))
                if (ratio < need) failures += "$theme $fg on $bg = ${"%.2f".format(ratio)}, needs $need ($use)"
            }
        }
        assertEquals("thresholded pairs (54 per theme)", 108, checked)
        assertTrue("contrast regressions:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the shipped theme matches contrast-py's TOKENS, ALIASES and PAIRS`() {
        val script = File(repoRoot(), "docs/ux/tools/contrast.py").readText()

        val pairsBlock = script.substringAfter("PAIRS = [").substringBefore("\n]")
        val scriptPairs =
            Regex("""\(\s*"([^"]+)",\s*"([^"]+)",\s*(TEXT|LARGE|UI|INFO),""")
                .findAll(pairsBlock)
                .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                .toList()
        assertEquals(PAIRS.map { Triple(it.fg, it.bg, LABELS.getValue(it.need)) }, scriptPairs)

        // TOKENS then ALIASES, each keyed "dark"/"light": hex values, or alias → token name.
        val tokens = mutableMapOf<String, MutableMap<String, String>>()
        var theme = ""
        for (line in script.substringAfter("TOKENS = {").substringBefore("PAIRS = [").lines()) {
            Regex("""^\s*"(dark|light)": \{""").find(line)?.let { theme = it.groupValues[1] }
            Regex("""^\s*"(\w+)": "([^"]+)"""").find(line)?.let {
                tokens.getOrPut(theme) { mutableMapOf() }[it.groupValues[1]] = it.groupValues[2]
            }
        }
        assertEquals("tokens + aliases per theme", listOf(43, 43), THEMES.map { tokens.getValue(it).size })
        val mismatches =
            THEMES.flatMap { t ->
                val table = tokens.getValue(t)
                table.mapNotNull { (name, value) ->
                    val expected = if (value.startsWith("#")) value else table.getValue(value)
                    val actual = "#%06X".format(argb(PALETTES.getValue(t).getValue(name)) and 0xFFFFFF)
                    "$t $name: script $expected, theme $actual".takeIf { actual != expected }
                }
            }
        assertTrue("theme drifted from contrast.py:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun `no dark ColorScheme role keeps Material's baseline value`() =
        assertNoBaselineLeak(SkeinColors.dark, darkColorScheme(), sameByDesign = emptySet())

    @Test
    fun `no light ColorScheme role keeps Material's baseline value`() =
        assertNoBaselineLeak(
            SkeinColors.light,
            lightColorScheme(),
            // #FFFFFF by design (§6.2), which happens to be Material's value too.
            sameByDesign = setOf("onPrimary", "onSecondary", "onTertiary", "onError", "surfaceContainerLowest"),
        )

    @Test
    fun `composited backgrounds match contrast-py's measured hex`() {
        // DESIGN_SYSTEM.md §6.5: selection highlight and disabled text, both themes.
        assertEquals(0xFF2E4850, resolve("dark", "surface+primary@0.30"))
        assertEquals(0xFFB2CED7, resolve("light", "surface+primary@0.30"))
        assertEquals(0xFF5F6264, resolve("dark", "onSurface@0.38"))
        assertEquals(0xFFA2A5A8, resolve("light", "onSurface@0.38"))
    }

    @Test
    fun `identical colors have a contrast ratio of exactly 1`() {
        val surface = argb(SkeinColors.dark.surface)
        assertTrue(WcagContrast.ratio(surface, surface) == 1.0)
    }

    private fun assertNoBaselineLeak(
        ours: ColorScheme,
        baseline: ColorScheme,
        sameByDesign: Set<String>,
    ) {
        val mine = roles(ours)
        val material = roles(baseline)
        assertEquals("ColorScheme roles (did a Material upgrade add one? map it)", 48, mine.size)
        val same = mine.filter { (role, colour) -> colour == material[role] }.keys
        assertEquals("roles equal to Material's baseline", sameByDesign, same)
    }

    private data class ContrastPair(
        val fg: String,
        val bg: String,
        val need: Double,
        val use: String,
    )

    private companion object {
        const val TEXT = 4.5
        const val UI = 3.0
        const val INFO = 0.0
        val LABELS = mapOf(TEXT to "TEXT", UI to "UI", INFO to "INFO")
        val THEMES = listOf("dark", "light")

        /** `PAIRS` from `docs/ux/tools/contrast.py`, in its order. */
        val PAIRS =
            listOf(
                // Body and secondary text on every surface level it can land on.
                ContrastPair("onSurface", "surface", TEXT, "Message and note body, titles"),
                ContrastPair("onSurface", "surfaceContainerLowest", TEXT, "Text on the lowest container"),
                ContrastPair("onSurface", "surfaceContainerLow", TEXT, "Drawer, sheets, list pane, reasoning lane"),
                ContrastPair("onSurface", "surfaceContainer", TEXT, "Menus"),
                ContrastPair("onSurface", "surfaceContainerHigh", TEXT, "Composer text, dialogs"),
                ContrastPair("onSurface", "surfaceContainerHighest", TEXT, "Inline code, table header"),
                ContrastPair("onSurfaceVariant", "surface", TEXT, "Metadata, subtitles, activity steps"),
                ContrastPair("onSurfaceVariant", "surfaceContainerLow", TEXT, "History previews in drawer/list pane"),
                ContrastPair("onSurfaceVariant", "surfaceContainer", TEXT, "Menu supporting text"),
                ContrastPair("onSurfaceVariant", "surfaceContainerHigh", TEXT, "Composer placeholder, dialog body"),
                ContrastPair("onSurfaceVariant", "surfaceContainerHighest", TEXT, "Muted text, highest container"),
                // Accent.
                ContrastPair("primary", "surface", TEXT, "Text buttons, links, selected-row accents"),
                ContrastPair("primary", "surfaceContainerLow", TEXT, "Links in sheets and list pane"),
                ContrastPair("primary", "surfaceContainerHigh", TEXT, "Text buttons in dialogs"),
                ContrastPair("primary", "surfaceContainerHighest", TEXT, "Links on the highest container"),
                ContrastPair("primary", "userMessageContainer", TEXT, "Links inside a user message"),
                ContrastPair("onPrimary", "primary", TEXT, "Filled button label, Send/Stop icon"),
                ContrastPair("onPrimaryContainer", "primaryContainer", TEXT, "Citation marker, accent tonal button"),
                ContrastPair("onSecondaryContainer", "secondaryContainer", TEXT, "User message, selected nav item"),
                ContrastPair("onSurfaceVariant", "secondaryContainer", TEXT, "Metadata inside a selected row"),
                ContrastPair("tertiary", "surface", TEXT, "AI output label"),
                ContrastPair("onTertiaryContainer", "tertiaryContainer", TEXT, "AI-output badge"),
                // Status and destructive.
                ContrastPair("error", "surface", TEXT, "Inline error text, field error"),
                ContrastPair("error", "surfaceContainer", TEXT, "Delete menu item"),
                ContrastPair("error", "surfaceContainerHigh", TEXT, "Delete dialog button"),
                ContrastPair("onError", "error", TEXT, "Filled destructive button"),
                ContrastPair("onErrorContainer", "errorContainer", TEXT, "Error card / banner"),
                ContrastPair("success", "surface", TEXT, "Success text (Ready)"),
                ContrastPair("onSuccessContainer", "successContainer", TEXT, "Success card"),
                ContrastPair("warning", "surface", TEXT, "Warning text (Running out of room)"),
                ContrastPair("onWarningContainer", "warningContainer", TEXT, "Warning card"),
                ContrastPair("inverseOnSurface", "inverseSurface", TEXT, "Snackbar message"),
                ContrastPair("inversePrimary", "inverseSurface", TEXT, "Snackbar action (Undo)"),
                // Component surfaces.
                ContrastPair("onSurface", "codeBlockContainer", TEXT, "Code block text"),
                ContrastPair("onSurfaceVariant", "codeBlockContainer", TEXT, "Code block language label"),
                ContrastPair("onSurface", "codeInlineContainer", TEXT, "Inline code"),
                ContrastPair("onSurfaceVariant", "reasoningContainer", TEXT, "Model's reasoning lane"),
                ContrastPair("onSurface", "surface+primary@0.30", TEXT, "Text under selection highlight"),
                ContrastPair("onSurface", "surface+onSurface@0.08", TEXT, "Hovered row"),
                ContrastPair("onSurface", "surfaceContainerLow+onSurface@0.10", TEXT, "Pressed / focused drawer row"),
                ContrastPair(
                    "onSecondaryContainer",
                    "secondaryContainer+onSecondaryContainer@0.10",
                    TEXT,
                    "Pressed selected row",
                ),
                // Non-text UI (WCAG 1.4.11).
                ContrastPair("outline", "surface", UI, "Text-field border, unchecked box, switch outline"),
                ContrastPair("outline", "surfaceContainerHigh", UI, "Field border inside a dialog"),
                ContrastPair("focusRing", "surface", UI, "Keyboard focus ring"),
                ContrastPair("focusRing", "surfaceContainerLow", UI, "Focus ring in drawer / list pane"),
                ContrastPair("focusRing", "surfaceContainerHigh", UI, "Focus ring in dialogs / composer"),
                ContrastPair("primary", "surfaceContainerHigh", UI, "Send button against the composer"),
                ContrastPair("primary", "surfaceContainerHighest", UI, "Progress indicator against its track"),
                ContrastPair("primary", "surface", UI, "Running-step dot, switch on, graph note node"),
                ContrastPair("secondary", "surface", UI, "Graph chat node"),
                ContrastPair("tertiary", "surface", UI, "Graph AI-output node"),
                ContrastPair("onSurfaceVariant", "surface", UI, "Graph file node, icons"),
                ContrastPair("outline", "surface", UI, "Graph edges and tag nodes"),
                ContrastPair("error", "surface", UI, "Error icon"),
                // Informational only.
                ContrastPair("onSurface@0.38", "surface", INFO, "Disabled text (exempt; must carry a reason)"),
                ContrastPair("outlineVariant", "surface", INFO, "Dividers (decorative)"),
                ContrastPair("userMessageContainer", "surface", INFO, "User-message edge (not a control)"),
            )

        /** Every `Color` property of [holder] by name — the 48 roles of a [ColorScheme], say. */
        fun roles(holder: Any): Map<String, Color> =
            holder.javaClass.methods
                .filter { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
                .filter { it.name.startsWith("get") }
                .associate { m ->
                    // `getOnPrimary-0d7_KjU` (a value-class getter) → `onPrimary`.
                    val name = m.name.removePrefix("get").substringBefore('-')
                    name.replaceFirstChar(Char::lowercaseChar) to Color((m.invoke(holder) as Long).toULong())
                }

        /** What ships per theme, by contrast.py's names: Material roles plus Skein's extended roles. */
        val PALETTES =
            mapOf(
                "dark" to roles(SkeinColors.dark) + roles(SkeinColors.darkExtended),
                "light" to roles(SkeinColors.light) + roles(SkeinColors.lightExtended),
            )

        fun argb(colour: Color): Long = colour.toArgb().toLong() and 0xFFFFFFFFL

        /** contrast.py's `resolve`: `token`, `token@alpha` (over surface) or `base+token@alpha`. */
        fun resolve(
            theme: String,
            name: String,
        ): Long {
            val colours = PALETTES.getValue(theme)
            if ('@' !in name) return argb(colours.getValue(name))
            val base = if ('+' in name) name.substringBefore('+') else "surface"
            val (token, alpha) = name.substringAfter('+').split('@')
            return blend(argb(colours.getValue(token)), alpha.toDouble(), argb(colours.getValue(base)))
        }

        /** contrast.py's `_blend`: per 8-bit channel, rounded half to even like Python's `round`. */
        fun blend(
            fg: Long,
            alpha: Double,
            bg: Long,
        ): Long =
            listOf(16, 8, 0).fold(0xFF000000L) { acc, shift ->
                val f = (fg shr shift) and 0xFF
                val b = (bg shr shift) and 0xFF
                acc or (round(alpha * f + (1 - alpha) * b).toLong() shl shift)
            }

        fun repoRoot(): File =
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .first { File(it, "docs/ux/tools/contrast.py").isFile }
    }
}
