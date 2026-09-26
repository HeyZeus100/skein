package app.skein.core.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance criterion (`E6.I1`, DESIGN_SYSTEM.md §14.9): no `Modifier.shadow`,
 * no `Brush.*Gradient`, no `Elevated*` components, no `FloatingActionButton`,
 * no direct `Snackbar(` outside `SkeinSnackbar`, and no non-zero literal
 * `shadowElevation`/`tonalElevation` in feature modules (spec §8.1/§5.3: no
 * gradients, no drop shadows, tone plus hairline instead of elevation).
 *
 * The plan names a custom Android Lint detector
 * (`build-logic/lint/.../NoShadowGradientDetector.kt`) for this. That
 * detector needs its own `build-logic` composite build and a `lint-checks`
 * dependency wired into every feature module — infrastructure `E6.I1` does
 * not otherwise touch and no other issue has stood up yet. Until that lands,
 * this source-scan test enforces the same rule, offline and in every
 * `./gradlew :core:designsystem:test` run. See `bd note skein-iru` for the
 * tracking note left for whichever issue builds the real Lint module.
 *
 * skein-xtov.23.12 (DS14): extended with the rest of §14.9's list. Four
 * `Surface(tonalElevation = <n>.dp)` overlays predate `SkeinSnackbar`/DS7 and
 * are allow-listed below rather than fixed here — this bead adds guards, it
 * doesn't refactor screens.
 */
class NoShadowOrGradientTest {
    private val forbidden =
        listOf(
            Regex("""Modifier\s*\.\s*shadow\s*\("""),
            Regex("""\.shadow\s*\("""),
            Regex("""Brush\s*\.\s*linearGradient"""),
            Regex("""linearGradient\s*\("""),
            Regex("""\bElevated[A-Za-z]*\s*\("""),
            Regex("""\b(Small|Large|Extended)?FloatingActionButton\s*\("""),
            // `\bSnackbar\(` alone (no lookaround needed): "SkeinSnackbar(" has no
            // word boundary between "Skein" and "Snackbar", so it never matches;
            // "SnackbarHost("/"SnackbarHostState(" have text between "Snackbar"
            // and "(", so they don't match either — only a direct `Snackbar(...)`
            // construction does.
            Regex("""\bSnackbar\s*\("""),
        )

    /** 2026-09-26, dated per DS14: pending DS7 (Wave 2, shadow-free overlays) — not a Wave 3/4 deletion. */
    private val pendingDs7ElevationLiteral =
        setOf(
            "feature/chat/src/main/kotlin/app/skein/feature/chat/AssistantBubble.kt",
            "feature/graph/src/main/kotlin/app/skein/feature/graph/GraphLegend.kt",
            "feature/editor/src/main/kotlin/app/skein/feature/editor/autocomplete/WikilinkAutocomplete.kt",
        )

    /** 2026-09-26, dated per DS14: retire in Wave 3/4 — timeline's legacy chrome (DS6 adoption 3-10), not fixed here. */
    private val retireWave34 =
        setOf(
            "feature/timeline/src/main/kotlin/app/skein/feature/timeline/TimelineScreen.kt",
        )

    @Test
    fun `no feature module uses Modifier-shadow, gradients, Elevated components, FAB, Snackbar or elevation`() {
        val repoRoot = GuardSupport.repoRoot()
        // skein-xtov.23.1: the theme left `feature/` for `:core:designsystem`;
        // keep scanning it too.
        val files =
            listOf(File(repoRoot, "feature"), File(repoRoot, "core/designsystem"))
                .asSequence()
                .flatMap { it.walkTopDown() }
                .filter { it.isFile && it.extension == "kt" }
                .filter { !it.path.contains("${File.separator}build${File.separator}") }
                .filter { it.name != "NoShadowOrGradientTest.kt" }
                .map { it to it.path.removePrefix(repoRoot.path + File.separator).replace(File.separatorChar, '/') }
                .toList()

        val patternOffenders =
            files
                .filterNot { (_, rel) -> rel in retireWave34 }
                .flatMap { (file, _) ->
                    val text = file.readText()
                    forbidden.filter { it.containsMatchIn(text) }.map { file.path to it.pattern }
                }

        val elevationOffenders =
            files
                .filterNot { (_, rel) -> rel in retireWave34 || rel in pendingDs7ElevationLiteral }
                .flatMap { (file, _) ->
                    file
                        .readLines()
                        .filterNot(GuardSupport::isCommentLine)
                        .flatMap { line -> nonZeroElevationLiterals(line) }
                        .map { match -> file.path to match }
                }

        val offenders = patternOffenders + elevationOffenders
        assertTrue(
            "forbidden shadow/gradient/elevation usage found (spec §8.1, §14.9):\n" +
                offenders.joinToString("\n") { (path, pattern) -> "  $path matched $pattern" },
            offenders.isEmpty(),
        )
    }

    // --- self-test: proves the detectors above actually catch bad code, on
    // synthetic snippets rather than by editing product files. ---

    @Test
    fun `detector self-test - bad snippets are caught, good ones are not`() {
        val bad =
            listOf(
                """Modifier.shadow(4.dp)""",
                """Brush.linearGradient(listOf(Color.Red, Color.Blue))""",
                """ElevatedCard(modifier = Modifier) { }""",
                """FloatingActionButton(onClick = {}) { }""",
                """SmallFloatingActionButton(onClick = {}) { }""",
                """Snackbar(modifier = Modifier) { Text("x") }""",
            )
        bad.forEach { snippet ->
            assertTrue(
                "expected `$snippet` to match a forbidden pattern",
                forbidden.any { it.containsMatchIn(snippet) },
            )
        }

        val good =
            listOf(
                """SkeinSnackbar(modifier = Modifier) { Text("x") }""",
                """SnackbarHost(hostState = hostState)""",
                """val duration = SnackbarDuration.Short""",
            )
        good.forEach { snippet ->
            assertFalse(
                "expected `$snippet` not to match any forbidden pattern",
                forbidden.any { it.containsMatchIn(snippet) },
            )
        }

        assertEquals(listOf("tonalElevation = 2.dp"), nonZeroElevationLiterals("""Surface(tonalElevation = 2.dp) {"""))
        assertEquals(
            listOf("shadowElevation = 8.dp"),
            nonZeroElevationLiterals("""Surface(shadowElevation = 8.dp) {"""),
        )
        assertTrue(
            "a zero literal must not be flagged",
            nonZeroElevationLiterals("""Surface(tonalElevation = 0.dp) {""").isEmpty(),
        )
        assertTrue(
            "a symbolic (non-literal) elevation must not be flagged — only literals are banned",
            nonZeroElevationLiterals("""Surface(tonalElevation = SkeinTokens.Elevation.none) {""").isEmpty(),
        )
    }

    companion object {
        private val elevationAssignment = Regex("""\b(?:tonalElevation|shadowElevation)\s*=\s*([^,\n)]+)""")
        private val numericLiteral = Regex("""^-?\d+(?:\.\d+)?f?(?:\.dp)?$""")
        private val zeroLiterals = setOf("0", "0f", "0.0f", "0.dp", "0.0.dp", "0f.dp", "0.0")

        /**
         * Every `tonalElevation = <x>`/`shadowElevation = <x>` on [line] where
         * `<x>` is a *literal* number (not a token/expression reference) and
         * that literal isn't zero. Returns the matched `name = value` text.
         */
        fun nonZeroElevationLiterals(line: String): List<String> =
            elevationAssignment
                .findAll(line)
                .filter { m ->
                    val v = m.groupValues[1].trim()
                    numericLiteral.matches(v) && v !in zeroLiterals
                }.map { it.value.trim() }
                .toList()
    }
}
