// skein-xtov.23.19 (UT-5, docs/ux/UX_TEST_PLAN.md §12 / §14 UT-17): a small
// recomposition counter for the later streaming-cost tests (each token
// arriving should recompose only the message being streamed, not the whole
// list).
package app.skein.testing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/**
 * Counts recompositions of whatever's composed inside [Track]. `SideEffect`
 * runs once per successful (non-skipped) commit of the scope that declares
 * it — including the first composition — so [count] starts at 1 the moment
 * [Track] is first composed, and goes up by one per recomposition after.
 *
 * **Call [Track] as the direct wrapper around the composable you're
 * measuring** — e.g. `counter.Track { MessageList(state) }` — not several
 * calls removed from it. [Track]'s own scope is what gets counted; if
 * `content` is a lambda Compose can skip independently of [Track]'s call
 * site (a `remember`-ed, stable lambda threaded down through other
 * composables), this undercounts. It's a scope counter, not an omniscient
 * one — see the self-test for exactly where that line falls.
 */
class RecompositionCounter {
    var count: Int = 0
        private set

    @Composable
    fun Track(content: @Composable () -> Unit) {
        SideEffect { count++ }
        content()
    }
}
