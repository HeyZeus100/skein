package app.skein.feature.shell.split

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.skein.feature.shell.layout.AdaptiveLayoutState
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabsState

/**
 * Bridges the tab system ([TabsState]) and the adaptive layout
 * ([AdaptiveLayoutState]) to implement split view (spec §8.3, plan
 * `E6.I6`) without coupling those two state holders to each other
 * directly — each stays independently constructible and testable
 * (`TabsStateTest`, `AdaptiveLayoutStateTest`) with no knowledge the other
 * exists. Only this coordinator, and [app.skein.feature.shell.SkeinApp]
 * (where both states are already visible), know about both.
 *
 * @param primaryTabs the primary pane's tabs — the only pane that exists
 *   outside of split view, and the left pane once split is active.
 * @param secondaryTabs the split view's secondary (right) pane's tabs.
 *   Only ever populated while [AdaptiveLayoutState.splitEnabled] is true.
 * @param layoutState owns [AdaptiveLayoutState.splitEnabled] and the
 *   timeline auto-collapse/restore that [AdaptiveLayoutState.toggleSplit]
 *   already performs (spec §8.3: "Split auto-collapses timeline").
 */
class SplitCoordinator(
    private val primaryTabs: TabsState,
    private val secondaryTabs: TabsState,
    private val layoutState: AdaptiveLayoutState,
) {
    /**
     * `⧉` button / "Open in split" long-press menu action (spec §8.3):
     * turns split on (auto-collapsing the timeline) if it isn't already,
     * then moves [tabId] out of [primaryTabs] and into [secondaryTabs],
     * pinning it there and activating it. [primaryTabs] keeps whichever
     * tab was already active — [TabsState.close] only reassigns the active
     * tab when the one being closed was itself active, so a split
     * triggered from a non-active tab leaves the primary pane untouched.
     *
     * A no-op if [tabId] isn't currently open in [primaryTabs]. In
     * particular, this means invoking it for a tab that's already in the
     * secondary pane does nothing — which is what keeps split from
     * recursing into the right pane (`E6.I6` guardrail: no splitting
     * *within* the right pane in v1). `AdaptivePaneHost` also enforces this
     * by never offering the secondary `TabHost` a `splitAvailable = true`.
     */
    fun openInSplit(tabId: TabId) {
        val tab = primaryTabs.tabs.firstOrNull { it.id == tabId } ?: return
        if (!layoutState.splitEnabled) layoutState.toggleSplit()
        primaryTabs.close(tabId)
        secondaryTabs.openPinned(tab)
    }

    /**
     * Exits split view if [secondaryTabs] is now empty — wire this to the
     * secondary pane's `TabHost.onEmpty` callback. Closing the last tab in
     * the secondary pane (plan `E6.I6` acceptance: "Closing the only tab in
     * pane 2 → single pane, timeline restored") calls
     * [AdaptiveLayoutState.toggleSplit] again, which restores whatever
     * timeline mode was active before split was entered. A no-op if split
     * isn't currently enabled, so it's safe to call unconditionally.
     */
    fun exitSplitIfSecondaryEmpty() {
        if (layoutState.splitEnabled && secondaryTabs.tabs.isEmpty()) {
            layoutState.toggleSplit()
        }
    }
}

@Composable
fun rememberSplitCoordinator(
    primaryTabs: TabsState,
    secondaryTabs: TabsState,
    layoutState: AdaptiveLayoutState,
): SplitCoordinator =
    remember(primaryTabs, secondaryTabs, layoutState) {
        SplitCoordinator(primaryTabs, secondaryTabs, layoutState)
    }
