package app.skein.core.designsystem.theme

import app.skein.core.designsystem.theme.GuardSupport.relativeToRepo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN_SYSTEM.md §14.11 (first half): no emoji or legacy glyph used as a
 * standalone icon in a production UI string — `📄 💬 📎 🤖 ✧ ✦ ⚹ ≡ ◐ ▤ ◈ ◂ ▸ ▾
 * ⧉ ● ⏸ ◌ ⏎ ■ ↗`, plus the `$` prompt as a standalone label. §9's icon set
 * (DS6) replaces these; until every surface is migrated, real icons belong
 * behind [SkeinIcons] (or `SkeinTokens.Glyphs` while that migration is
 * ongoing), never typed straight into a `Text(...)`.
 *
 * skein-xtov.23.12 (DS14). This only flags a quoted string literal that
 * *contains* one of the glyphs (so KDoc/`//` prose mentioning a glyph, and
 * `@Preview`/test fixture names, don't trip it) — not a full lexer, but
 * enough for a cheap guard.
 */
class NoLegacyGlyphIconTest {
    /**
     * 2026-09-26, dated per DS14: retire in Wave 3/4 — DS6's icon set replaces
     * these over its "adoption 3-10" waves; this bead adds a guard, it
     * doesn't do the icon migration. `SkeinTokens.kt` itself is not here: it
     * is the theme's own token registry (the glyphs' single source of
     * truth), a legitimate exception like the other theme files.
     */
    private val retireWave34 =
        setOf(
            "feature/chat/src/main/kotlin/app/skein/feature/chat/ChatBottomBar.kt",
            "feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/Tab.kt",
            "feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/RecentDropdown.kt",
            "feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBar.kt",
            "feature/shell/src/main/kotlin/app/skein/feature/shell/nav/SearchResults.kt",
            "feature/timeline/src/main/kotlin/app/skein/feature/timeline/TimelineRow.kt",
            "feature/timeline/src/main/kotlin/app/skein/feature/timeline/TimelineScreen.kt",
            "feature/timeline/src/main/kotlin/app/skein/feature/timeline/FilterBar.kt",
            "feature/timeline/src/main/kotlin/app/skein/feature/timeline/TimelineFormatting.kt",
            "feature/editor/src/main/kotlin/app/skein/feature/editor/frontmatter/FrontmatterChip.kt",
            "feature/editor/src/main/kotlin/app/skein/feature/editor/notetab/NoteTab.kt",
        )

    private val themeTokenFile = "core/designsystem/src/main/kotlin/app/skein/core/designsystem/theme/SkeinTokens.kt"

    @Test
    fun `no legacy glyph or standalone dollar-prompt in a production UI string`() {
        val offenders =
            GuardSupport
                .productionKotlinFiles("feature", "core/designsystem")
                .filterNot { it.relativeToRepo() in retireWave34 || it.relativeToRepo() == themeTokenFile }
                .flatMap { file ->
                    file
                        .readLines()
                        .filterNot(GuardSupport::isCommentLine)
                        .flatMap { line -> matches(line) }
                        .map { match -> file.path to match }
                }

        assertTrue(
            "legacy glyph icon or standalone \$ prompt found in a production UI string (spec §14.11):\n" +
                offenders.joinToString("\n") { (path, match) -> "  $path: $match" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `detector self-test - bad snippets are caught, good ones are not`() {
        assertTrue(matches("""Text("📄 New note")""").isNotEmpty())
        assertTrue(matches("""Text("💬")""").isNotEmpty())
        assertTrue(matches("""text = "◈ ${'$'}name",""").isNotEmpty())
        assertTrue(matches("""Text("${'$'}")""").isNotEmpty())

        // `matches()` alone only looks for a quoted glyph; comment prose that happens to
        // quote one (e.g. KDoc echoing a UI string) is excluded upstream by
        // `GuardSupport.isCommentLine`, which is what the real scan relies on:
        val commentLine = """ * the "Recent ▾" list surfaces what was just used"""
        assertTrue("expected this KDoc line to be recognized as a comment", GuardSupport.isCommentLine(commentLine))
        assertTrue(
            "a // line comment must also be recognized",
            GuardSupport.isCommentLine("""// Reuses the glyph (⚹) for Settings."""),
        )
        // A real icon reference (not a raw glyph literal) is fine either way.
        assertTrue(matches("""Icon(imageVector = SkeinIcons.Chat, contentDescription = null)""").isEmpty())
    }

    companion object {
        // Alternation of full graphemes, not a `[...]` character class: four of
        // these (the emoji) are outside the BMP, and a Java regex character
        // class splits a surrogate pair into two independent code-unit
        // members — which would also match unrelated emoji sharing a high
        // surrogate. Matching each glyph as its own alternative avoids that.
        private val glyphs =
            listOf(
                "📄",
                "💬",
                "📎",
                "🤖",
                "✧",
                "✦",
                "⚹",
                "≡",
                "◐",
                "▤",
                "◈",
                "◂",
                "▸",
                "▾",
                "⧉",
                "●",
                "⏸",
                "◌",
                "⏎",
                "■",
                "↗",
            )
        private val glyphAlternation = glyphs.joinToString("|") { Regex.escape(it) }
        private val glyphInString = Regex("\"[^\"\\n]*(?:$glyphAlternation)[^\"\\n]*\"")
        private val standaloneDollar = Regex("\"\\$\"")

        fun matches(line: String): List<String> =
            (glyphInString.findAll(line) + standaloneDollar.findAll(line)).map { it.value }.toList()
    }
}
