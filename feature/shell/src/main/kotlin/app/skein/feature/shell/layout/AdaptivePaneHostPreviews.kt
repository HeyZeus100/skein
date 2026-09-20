package app.skein.feature.shell.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import app.skein.feature.shell.tabs.Tab
import app.skein.feature.shell.tabs.TabHost
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabState
import app.skein.feature.shell.tabs.TabsState
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode

/**
 * Mock timeline content for previews only — real timeline is `E6.I4`.
 * Just enough to see the 30%-width pane vs. the 40 dp rail.
 */
@Composable
private fun MockTimeline() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Timeline", style = MaterialTheme.typography.titleMedium)
            repeat(5) { i -> Text("• entry $i", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Three demo tabs (one of each kind) for the previews below — real tab system is `E6.I5`. */
private fun demoTabs(
    idPrefix: String,
    activeState: TabState,
): List<Tab> =
    listOf(
        Tab(TabId("$idPrefix-1"), docId = "doc-1", title = "Welcome", kind = TabKind.NOTE, state = TabState.PINNED),
        Tab(
            TabId("$idPrefix-2"),
            docId = "doc-2",
            title = "Research thread",
            kind = TabKind.CHAT,
            state = TabState.PINNED,
        ),
        Tab(TabId("$idPrefix-3"), docId = "doc-3", title = "spec.pdf", kind = TabKind.ATTACHMENT, state = activeState),
    )

@Composable
private fun PreviewScaffold(
    windowSizeClass: WindowSizeClass,
    layoutState: AdaptiveLayoutState = AdaptiveLayoutState(),
    primaryTabs: List<Tab> = demoTabs("p", TabState.PREVIEW),
    secondaryTabs: List<Tab> = demoTabs("s", TabState.PINNED),
) {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            AdaptivePaneHost(
                layoutState = layoutState,
                windowSizeClass = windowSizeClass,
                posture = FoldPosture.Unknown,
                timeline = { MockTimeline() },
                primary = { splitAvailable ->
                    TabHost(
                        tabsState =
                            remember {
                                TabsState(
                                    initialTabs = primaryTabs,
                                    initialActiveId = primaryTabs.lastOrNull()?.id,
                                )
                            },
                        windowSizeClass = windowSizeClass,
                        splitAvailable = splitAvailable,
                    )
                },
                secondary = { splitAvailable ->
                    TabHost(
                        tabsState =
                            remember {
                                TabsState(
                                    initialTabs = secondaryTabs,
                                    initialActiveId = secondaryTabs.lastOrNull()?.id,
                                )
                            },
                        windowSizeClass = windowSizeClass,
                        splitAvailable = splitAvailable,
                    )
                },
            )
        }
    }
}

/**
 * < 600 dp: single pane, no timeline (acceptance: "width 400 dp → only the
 * right pane"); `TabHost` itself picks the "Recent ▾" dropdown at this width
 * (spec §8.3: "Folded phone: tabs → 'Recent ▾' dropdown"), active tab shown
 * italic since it's still a preview.
 */
@Preview(name = "Single pane + Recent dropdown — 400dp (compact)", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
private fun SinglePanePreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(400f, 800f))
}

/** 600–840 dp: single pane, wide enough that `TabHost` shows the full strip instead of the dropdown. */
@Preview(name = "Single pane + tab strip — 700dp (medium)", widthDp = 700, heightDp = 900, showBackground = true)
@Composable
private fun MediumWithStripPreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(700f, 900f))
}

/** Empty `TabHost`: no tabs open, content region shows the "back to timeline" placeholder, no strip drawn. */
@Preview(name = "Empty tab strip — 1000dp (expanded)", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun EmptyTabsPreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(1000f, 900f), primaryTabs = emptyList())
}

/** >= 840 dp: dual pane, timeline at 30%, tab strip with 3 tabs and an active *preview* tab (italic). */
@Preview(name = "Dual pane — active preview — 1000dp (expanded)", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun DualPanePreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(1000f, 900f))
}

/**
 * >= 840 dp with split toggled (`E6.I2`'s `SplitHost`): timeline
 * auto-collapses to rail, each side hosts its own `TabHost` — left shows 3
 * tabs with an active preview (italic), right shows 3 tabs with an active
 * pinned tab (solid) — exercising both preview and pinned rendering and the
 * "Open in split" menu item (`splitAvailable = true`) at once.
 */
@Preview(
    name = "Split dual — active pinned on right — 1000dp (expanded, split)",
    widthDp = 1000,
    heightDp = 900,
    showBackground = true,
)
@Composable
private fun SplitDualPreview() {
    PreviewScaffold(
        windowSizeClass = WindowSizeClass(1000f, 900f),
        layoutState = AdaptiveLayoutState(initialSplitEnabled = true),
        secondaryTabs = demoTabs("s", TabState.PINNED),
    )
}

/** >= 840 dp with timeline manually collapsed to the icon rail (no split). */
@Preview(name = "Dual pane — rail collapsed", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun DualPaneRailPreview() {
    PreviewScaffold(
        windowSizeClass = WindowSizeClass(1000f, 900f),
        layoutState = AdaptiveLayoutState(initialTimelineMode = TimelineMode.RAIL),
    )
}
