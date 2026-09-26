package app.skein.feature.shell.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * How the timeline (left) pane is rendered when it's not hidden entirely by
 * a narrow width (spec §8.2): the full 30%-width pane, or collapsed away
 * ([RAIL], forced while split view is on).
 */
enum class TimelineMode { FULL, RAIL, HIDDEN }

/**
 * User-driven adaptive-layout preferences (spec `E6.I2`): timeline
 * collapse state, split-view toggle, and the active tab. Deliberately
 * independent of window width / fold posture — those are read separately
 * by [computeAdaptiveLayout] on every recomposition, so a posture change
 * (fold/unfold) never resets anything held here.
 */
@Stable
class AdaptiveLayoutState(
    initialTimelineMode: TimelineMode = TimelineMode.FULL,
    initialSplitEnabled: Boolean = false,
    initialActiveTabId: String? = null,
    private var timelineModeBeforeSplit: TimelineMode = initialTimelineMode,
) {
    var timelineMode: TimelineMode by mutableStateOf(initialTimelineMode)
        private set

    var splitEnabled: Boolean by mutableStateOf(initialSplitEnabled)
        private set

    var activeTabId: String? by mutableStateOf(initialActiveTabId)
        private set

    /** `◂` — collapses `FULL` to `RAIL` and back. No-op while split is active (rail is forced). */
    fun toggleTimeline() {
        if (splitEnabled) return
        timelineMode =
            when (timelineMode) {
                TimelineMode.FULL -> TimelineMode.RAIL
                TimelineMode.RAIL, TimelineMode.HIDDEN -> TimelineMode.FULL
            }
    }

    /** `⧉` — entering split remembers and force-collapses the timeline; leaving restores it. */
    fun toggleSplit() {
        if (!splitEnabled) {
            timelineModeBeforeSplit = timelineMode
            timelineMode = TimelineMode.RAIL
            splitEnabled = true
        } else {
            splitEnabled = false
            timelineMode = timelineModeBeforeSplit
        }
    }

    /** Preview-tab open (mock; the real tab system is `E6.I5`). Single-click-replaces semantics land there. */
    fun openTab(id: String) {
        activeTabId = id
    }

    companion object {
        val Saver: Saver<AdaptiveLayoutState, *> =
            Saver(
                save = {
                    listOf(
                        it.timelineMode.name,
                        it.splitEnabled,
                        it.activeTabId,
                        it.timelineModeBeforeSplit.name,
                    )
                },
                restore = { saved ->
                    @Suppress("UNCHECKED_CAST")
                    val values = saved as List<Any?>
                    AdaptiveLayoutState(
                        initialTimelineMode = TimelineMode.valueOf(values[0] as String),
                        initialSplitEnabled = values[1] as Boolean,
                        initialActiveTabId = values[2] as String?,
                        timelineModeBeforeSplit = TimelineMode.valueOf(values[3] as String),
                    )
                },
            )
    }
}

@Composable
fun rememberAdaptiveLayoutState(
    initialTimelineMode: TimelineMode = TimelineMode.FULL,
    initialSplitEnabled: Boolean = false,
): AdaptiveLayoutState =
    rememberSaveable(saver = AdaptiveLayoutState.Saver) {
        AdaptiveLayoutState(
            initialTimelineMode = initialTimelineMode,
            initialSplitEnabled = initialSplitEnabled,
        )
    }
