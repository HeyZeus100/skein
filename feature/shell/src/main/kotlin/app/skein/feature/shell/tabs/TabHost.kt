package app.skein.feature.shell.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import app.skein.feature.shell.layout.LayoutWidthClass
import app.skein.feature.shell.layout.classifyWidth
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Orchestrator for the tab system (spec §8.3, plan `E6.I5`): picks
 * [TabStrip] (wide) vs. [RecentDropdown] (folded/narrow) from
 * [windowSizeClass], then hosts the active tab's content below it. This is
 * what fills the right pane's `primary`/`secondary` slot in
 * `AdaptivePaneHost` — each split side gets its own [TabHost] + [TabsState].
 *
 * Content is mocked (`E6.I6`+ wires up real chat/note/attachment screens);
 * [content] defaults to a placeholder that just names the kind and doc key.
 */
@Composable
fun TabHost(
    modifier: Modifier = Modifier,
    tabsState: TabsState = rememberTabsState(),
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    splitAvailable: Boolean = false,
    onOpenInSplit: (TabId) -> Unit = {},
    /** Fires when the active tab becomes `null` (last tab closed) — the host screen returns focus to the timeline. */
    onEmpty: () -> Unit = {},
    content: @Composable (Tab) -> Unit = { tab -> MockTabContent(tab) },
) {
    val tokens = LocalSkeinTokens.current
    val useDropdown = classifyWidth(windowSizeClass) == LayoutWidthClass.COMPACT
    val activeTab = tabsState.activeTab

    LaunchedEffect(activeTab == null) {
        if (activeTab == null) onEmpty()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (tabsState.tabs.isNotEmpty()) {
            if (useDropdown) {
                RecentDropdown(
                    tabs = tabsState.recentDropdown(),
                    activeId = tabsState.activeId,
                    onSelect = tabsState::activate,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TabStrip(
                    tabs = tabsState.tabs,
                    activeId = tabsState.activeId,
                    onActivate = tabsState::activate,
                    onPin = tabsState::pin,
                    onClose = tabsState::close,
                    onCloseOthers = tabsState::closeOthers,
                    onOpenInSplit = onOpenInSplit,
                    splitAvailable = splitAvailable,
                    modifier = Modifier.fillMaxWidth().height(tokens.tabHeight),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeTab != null) {
                content(activeTab)
            } else {
                EmptyTabHostPlaceholder()
            }
        }
    }
}

/** Spec §8.3: "Closing preview when it was the only tab returns focus to timeline." Mock-only visual here. */
@Composable
private fun EmptyTabHostPlaceholder(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No tabs open — back to timeline",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Placeholder content by [Tab.kind] — real screens land in `E6.I6` (attachment
 * viewer), `E6.I8` (chat), `E6.I9` (note). This issue only needs *something*
 * to fill the content region so the tab chrome is visibly wired up.
 */
@Composable
fun MockTabContent(
    tab: Tab,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "${tab.kind.glyph} ${tab.title}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "doc: ${tab.docId} · ${tab.state.name.lowercase()} · mock content",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
