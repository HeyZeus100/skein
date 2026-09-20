package app.skein.feature.shell.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Divides the right zone into two side-by-side panes (spec §8.3: `⧉` split
 * view; "no pane splitting *within* the right pane in v1" per this issue —
 * this is the one, top-level split, not a recursive splitter). Drag the
 * 4 dp divider to resize; the split fraction is remembered across
 * recomposition and config change.
 *
 * v1 scope note: this only ever hosts two slots side by side. A future
 * follow-up may let each slot itself hold a tab; this host doesn't know or
 * care what's inside — it just lays out two composables.
 */
@Composable
fun SplitHost(
    modifier: Modifier = Modifier,
    initialSplitFraction: Float = 0.5f,
    minFraction: Float = 0.25f,
    maxFraction: Float = 0.75f,
    dividerWidth: Dp = 4.dp,
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
) {
    var splitFraction by rememberSaveable { mutableFloatStateOf(initialSplitFraction) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val fraction = splitFraction.coerceIn(minFraction, maxFraction)

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(fraction).fillMaxHeight()) {
                leftContent()
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(dividerWidth)
                        .background(MaterialTheme.colorScheme.outline)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state =
                                rememberDraggableState { delta ->
                                    if (totalWidthPx > 0f) {
                                        splitFraction =
                                            (splitFraction + delta / totalWidthPx).coerceIn(minFraction, maxFraction)
                                    }
                                },
                        ),
            )
            Box(modifier = Modifier.weight(1f - fraction).fillMaxHeight()) {
                rightContent()
            }
        }
    }
}
