// skein-xtov.23.19 (UT-5, docs/ux/UX_TEST_PLAN.md §9 / §14): the token-level
// contrast helper the bead asks for. It reuses `WcagContrast.ratio`
// (`:core:designsystem`, widened from `internal` to public for exactly this)
// instead of re-deriving WCAG relative-luminance math a second time —
// `SkeinColorContrastTest` in that module already owns the full pair table
// for the shipped theme; this helper is for other callers with their own
// (screen-specific, or deliberately-bad-fixture) colour pairs.
package app.skein.testing.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.skein.core.designsystem.theme.WcagContrast

/** DESIGN_SYSTEM.md §14 / UX_TEST_PLAN.md §9: the two WCAG thresholds this repo checks against. */
const val TEXT_CONTRAST_MIN = 4.5
const val UI_CONTRAST_MIN = 3.0

/** One colour pair to check, and why it matters (named in a failure message). */
data class ContrastCheck(
    val foreground: Color,
    val background: Color,
    val minRatio: Double,
    val label: String,
)

/**
 * Checks every [checks] pair against its own [ContrastCheck.minRatio],
 * throwing [AssertionError] naming every pair that falls short. Colours come
 * from wherever the caller resolved them — typically a `ColorScheme`/
 * `SkeinExtendedColors` role by name, the same way `SkeinColorContrastTest`
 * resolves `PAIRS` — this helper only does the ratio math and the assertion.
 */
fun assertContrast(checks: List<ContrastCheck>) {
    val failures =
        checks.mapNotNull { check ->
            val ratio = WcagContrast.ratio(check.foreground.argb(), check.background.argb())
            if (ratio < check.minRatio) {
                "${check.label}: ${"%.2f".format(ratio)}, needs ${check.minRatio} " +
                    "(fg=${check.foreground}, bg=${check.background})"
            } else {
                null
            }
        }
    if (failures.isNotEmpty()) {
        throw AssertionError(
            "assertContrast: ${failures.size} pair(s) below threshold:\n" + failures.joinToString("\n"),
        )
    }
}

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL
