package app.skein.feature.shell.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode

/**
 * `E6.I6` previews for the two ways into split view (spec §8.3): the `⧉`
 * button at the tab strip's trailing edge, and "Open in split" in the
 * long-press [TabContextMenu]. `AdaptivePaneHostPreviews`' "Split dual"
 * preview already shows the *result* (two panes, each with a different mix
 * of tab kinds); these previews isolate the *entry points* themselves —
 * `splitAvailable = true` is hardcoded rather than derived from a forced
 * window width, since [TabStrip] doesn't know about width itself.
 */
private fun demoTabs(): List<Tab> =
    listOf(
        Tab(TabId("n"), docId = "doc-note", title = "Welcome", kind = TabKind.NOTE, state = TabState.PINNED),
        Tab(TabId("c"), docId = "doc-chat", title = "Research thread", kind = TabKind.CHAT, state = TabState.PINNED),
        Tab(
            TabId("a"),
            docId = "doc-attachment",
            title = "spec.pdf",
            kind = TabKind.ATTACHMENT,
            state = TabState.PREVIEW,
        ),
    )

/** The `⧉` button rendered at the strip's trailing edge — `splitAvailable = true`, an active pinned tab. */
@Preview(name = "Tab strip — ⧉ button visible (splitAvailable)", widthDp = 900, heightDp = 120, showBackground = true)
@Composable
private fun TabStripWithSplitButtonPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        val tabsState = remember { TabsState(initialTabs = demoTabs(), initialActiveId = TabId("c")) }
        Surface(color = MaterialTheme.colorScheme.background) {
            TabStrip(
                tabs = tabsState.tabs,
                activeId = tabsState.activeId,
                onActivate = tabsState::activate,
                onPin = tabsState::pin,
                onClose = tabsState::close,
                onCloseOthers = tabsState::closeOthers,
                onOpenInSplit = {},
                splitAvailable = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * No `⧉` at all — `splitAvailable = false` (compact width, or the secondary
 * pane, per `AdaptivePaneHost`'s guardrail against splitting within the
 * right pane). Otherwise identical to [TabStripWithSplitButtonPreview].
 */
@Preview(name = "Tab strip — ⧉ absent (splitAvailable = false)", widthDp = 900, heightDp = 120, showBackground = true)
@Composable
private fun TabStripWithoutSplitButtonPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        val tabsState = remember { TabsState(initialTabs = demoTabs(), initialActiveId = TabId("c")) }
        Surface(color = MaterialTheme.colorScheme.background) {
            TabStrip(
                tabs = tabsState.tabs,
                activeId = tabsState.activeId,
                onActivate = tabsState::activate,
                onPin = tabsState::pin,
                onClose = tabsState::close,
                onCloseOthers = tabsState::closeOthers,
                onOpenInSplit = {},
                splitAvailable = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The long-press menu on the active tab, forced open (real long-press is a
 * gesture [TabContextMenu] itself doesn't drive — [TabStrip]'s `TabChip`
 * owns that `rememberSaveable` state), `splitAvailable = true` so all four
 * actions from plan `E6.I5`'s acceptance criterion are visible at once,
 * including "Open in split ⧉" (`E6.I6`).
 */
@Preview(
    name = "Long-press menu — active pinned tab, split available",
    widthDp = 400,
    heightDp = 300,
    showBackground = true,
)
@Composable
private fun TabContextMenuOpenPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                TabContextMenu(
                    expanded = true,
                    tab = demoTabs()[1],
                    canCloseOthers = true,
                    splitAvailable = true,
                    onDismiss = {},
                    onPin = {},
                    onClose = {},
                    onCloseOthers = {},
                    onOpenInSplit = {},
                )
            }
        }
    }
}
