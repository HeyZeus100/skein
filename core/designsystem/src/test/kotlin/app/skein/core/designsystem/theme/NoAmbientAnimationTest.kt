package app.skein.core.designsystem.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN_SYSTEM.md §14.12: no ambient (always-running) animation.
 * `rememberInfiniteTransition` and an indeterminate `LinearProgressIndicator`
 * (no `progress` argument) are both banned everywhere except the allow-list
 * below — Material's circular indicator, wrapped in `SkeinPendingIndicator`,
 * is the one sanctioned always-on spinner (§10.22/DS8; that component
 * doesn't exist yet, so the allow-list is empty today and this guard
 * currently passes clean on every file).
 *
 * skein-xtov.23.12 (DS14).
 */
class NoAmbientAnimationTest {
    /** Files allowed to use `rememberInfiniteTransition` (none shipped yet — see DS8/`SkeinPendingIndicator`). */
    private val infiniteTransitionAllowlist = setOf<String>()

    @Test
    fun `no rememberInfiniteTransition or indeterminate LinearProgressIndicator outside the allow-list`() {
        val infiniteOffenders =
            GuardSupport
                .productionKotlinFiles("feature", "core/designsystem")
                .filter { it.name !in infiniteTransitionAllowlist }
                .flatMap { file ->
                    file
                        .readLines()
                        .filterNot(GuardSupport::isCommentLine)
                        .filter { it.contains("rememberInfiniteTransition") }
                        .map { file.path to it.trim() }
                }

        val indeterminateOffenders =
            GuardSupport
                .productionKotlinFiles("feature", "core/designsystem")
                .flatMap { file -> indeterminateLinearProgressIndicatorCalls(file.readText()).map { file.path to it } }

        val offenders = infiniteOffenders + indeterminateOffenders
        assertTrue(
            "ambient animation found (spec §14.12: no infinite transition, no indeterminate progress bar):\n" +
                offenders.joinToString("\n") { (path, match) -> "  $path: $match" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `detector self-test - bad snippets are caught, good ones are not`() {
        assertTrue(
            "an indeterminate call (no progress arg) must be caught",
            indeterminateLinearProgressIndicatorCalls(
                """
                @Composable
                fun Loading() {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                """.trimIndent(),
            ).isNotEmpty(),
        )
        assertTrue(
            "a determinate call (has a progress arg) must not be flagged",
            indeterminateLinearProgressIndicatorCalls(
                """
                @Composable
                fun Loading(fraction: Float) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                """.trimIndent(),
            ).isEmpty(),
        )
        assertTrue(
            "a bare rememberInfiniteTransition call must be recognized by the substring check",
            "val t = rememberInfiniteTransition(label = \"pulse\")".contains("rememberInfiniteTransition"),
        )
    }

    companion object {
        /**
         * Every `LinearProgressIndicator(...)` call in [text] whose balanced
         * argument list does not mention `progress` (the determinate overload's
         * only distinguishing parameter) — i.e. the indeterminate overload.
         * ponytail: brace-counting instead of a real parser; good enough since
         * Compose calls are never inside unbalanced string/char literals here.
         */
        fun indeterminateLinearProgressIndicatorCalls(text: String): List<String> {
            val callName = "LinearProgressIndicator("
            val results = mutableListOf<String>()
            var searchFrom = 0
            while (true) {
                val start = text.indexOf(callName, searchFrom)
                if (start == -1) break
                val argsStart = start + callName.length
                var depth = 1
                var i = argsStart
                while (i < text.length && depth > 0) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    i++
                }
                val args = text.substring(argsStart, (i - 1).coerceIn(argsStart, text.length))
                if (!args.contains("progress")) {
                    results += text.substring(start, i.coerceAtMost(text.length)).take(120).replace("\n", " ")
                }
                searchFrom = i
            }
            return results
        }
    }
}
