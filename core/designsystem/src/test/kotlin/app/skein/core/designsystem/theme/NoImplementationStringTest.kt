package app.skein.core.designsystem.theme

import app.skein.core.designsystem.theme.GuardSupport.relativeToRepo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN_SYSTEM.md §14.11 (second half): no user-facing implementation
 * strings — a bead id (`skein-[a-z0-9]{3,}`, e.g. `skein-xtov.23.2`), the
 * literal "Coming in v" placeholder, or a raw `.gguf` model filename — in a
 * production Composable string literal. Users read app copy, not our issue
 * tracker or the on-disk name of a model file.
 *
 * skein-xtov.23.12 (DS14). Scope is `feature/` only: `core/designsystem`'s
 * only hits are `@Deprecated("... (skein-xtov.23.1)")`-style migration notes
 * on the theme's own typealiases (compiler-only, never rendered) — excluding
 * the whole module is simpler than chasing every one.
 */
class NoImplementationStringTest {
    /**
     * 2026-09-26, dated per DS14. Not a Wave 3/4 retirement — these are
     * permanent, narrow false positives against the doc's literal
     * `skein-[a-z0-9]{3,}` pattern, not implementation leaks:
     */
    private val allowlist =
        setOf(
            // `defaultRecoveryFileName()` returns "skein-recovery-YYYY-MM-DD.json"
            // (bd skein-v9g's spec'd export filename) — a deliberate product
            // string that happens to satisfy `skein-[a-z0-9]{3,}` ("recovery" is
            // an English word, not a bead id).
            "feature/settings/src/main/kotlin/app/skein/feature/settings/RecoveryKeyExportControls.kt",
        )

    @Test
    fun `no bead id, 'Coming in v' placeholder or gguf filename in a production string`() {
        val offenders =
            GuardSupport
                .productionKotlinFiles("feature")
                .filterNot { it.relativeToRepo() in allowlist }
                .flatMap { file ->
                    file
                        .readLines()
                        .filterNot(GuardSupport::isCommentLine)
                        .flatMap { line -> matches(line) }
                        .map { match -> file.path to match }
                }

        assertTrue(
            "implementation string (bead id / 'Coming in v' / .gguf filename) found in production UI (spec §14.11):\n" +
                offenders.joinToString("\n") { (path, match) -> "  $path: $match" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `detector self-test - bad snippets are caught, good ones are not`() {
        assertTrue(matches(""""clipping the text, same as skein-wr7m",""").isNotEmpty())
        assertTrue(matches("""Text("Coming in v1.1")""").isNotEmpty())
        assertTrue(matches("""Text("Import qwen2.5-3b-instruct.gguf to get started")""").isNotEmpty())

        assertTrue(
            "an internal, non-string-literal identifier must not be flagged (only quoted literals are scanned)",
            matches("""const val UNIQUE_WORK_NAME = SOME_CONSTANT""").isEmpty(),
        )
        assertTrue(matches("""Text("Skein could not confirm it is you")""").isEmpty())
    }

    companion object {
        private val beadId = Regex(""""[^"\n]*\bskein-[a-z0-9]{3,}\b[^"\n]*"""")
        private val comingInV = Regex(""""[^"\n]*[Cc]oming in v[^"\n]*"""")
        private val ggufFile = Regex(""""[^"\n]*\.gguf[^"\n]*"""", RegexOption.IGNORE_CASE)

        fun matches(line: String): List<String> =
            (beadId.findAll(line) + comingInV.findAll(line) + ggufFile.findAll(line)).map { it.value }.toList()
    }
}
