// skein-xtov.23.19 (UT-5): a findings-not-a-gate runner for the real-screen
// smokes (docs/ux/UX_TEST_PLAN.md §14 UT-5's "report findings ... for Wave 11,
// WITHOUT fixing product code"). Shared so `feature/settings` and
// `feature/chat`'s smoke tests don't each grow their own copy of the
// run-and-catch loop.
package app.skein.testing.ui

/** One named check's outcome from [runSweep]. [detail] is the failure message, or null on a pass. */
data class SweepResult(
    val name: String,
    val passed: Boolean,
    val detail: String?,
)

/**
 * Runs each `(name, check)` pair, catching [AssertionError] instead of
 * letting it propagate — a screen with known accessibility gaps should
 * still show a green build here; the point of a Wave 2 smoke is to surface
 * what Wave 11 needs to fix, not to block every other module's build on
 * issues this bead is explicitly not fixing.
 *
 * This file is `src/main`, so the `NoRawLogging` guard applies: it returns
 * results instead of printing them. Callers (test-sourceSet smoke tests,
 * which the guard doesn't scan) print however they like, e.g.:
 * ```
 * runSweep(checks).forEach { r -> println(if (r.passed) "PASS ${r.name}" else "FAIL ${r.name}\n${r.detail}") }
 * ```
 */
fun runSweep(checks: List<Pair<String, () -> Unit>>): List<SweepResult> =
    checks.map { (name, run) ->
        try {
            run()
            SweepResult(name, passed = true, detail = null)
        } catch (e: AssertionError) {
            SweepResult(name, passed = false, detail = e.message)
        }
    }
