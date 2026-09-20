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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
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

/** Mock tab content for previews only — real tab system is `E6.I5`. */
@Composable
private fun MockTab(label: String) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                "placeholder mock content",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewScaffold(
    windowSizeClass: WindowSizeClass,
    layoutState: AdaptiveLayoutState = AdaptiveLayoutState(),
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
                primary = { MockTab("💬 chat") },
                secondary = { MockTab("📄 note") },
            )
        }
    }
}

/** < 600 dp: single pane, no timeline, no dropdown (acceptance: "width 400 dp → only the right pane"). */
@Preview(name = "Single pane — 400dp (compact)", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
private fun SinglePanePreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(400f, 800f))
}

/** 600–840 dp: single pane, "Recent ▾" dropdown shown in place of tabs. */
@Preview(name = "Single pane + dropdown — 700dp (medium)", widthDp = 700, heightDp = 900, showBackground = true)
@Composable
private fun MediumWithDropdownPreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(700f, 900f))
}

/** >= 840 dp: dual pane, timeline at 30%, tabbed pane at 70%. */
@Preview(name = "Dual pane — 1000dp (expanded)", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun DualPanePreview() {
    PreviewScaffold(windowSizeClass = WindowSizeClass(1000f, 900f))
}

/** >= 840 dp with split toggled: timeline auto-collapses to rail, right zone shows two tabs. */
@Preview(name = "Split dual — 1000dp (expanded, split)", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun SplitDualPreview() {
    PreviewScaffold(
        windowSizeClass = WindowSizeClass(1000f, 900f),
        layoutState = AdaptiveLayoutState(initialSplitEnabled = true),
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
