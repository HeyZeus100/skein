package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.math.roundToInt

/** Reserves any gap below a peek when the lower hinge partition cannot fit its touch target. */
@Composable
internal fun HingeSafePeek(
    partitions: List<DpRect>?,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    var bottom by remember { mutableStateOf<Float?>(null) }
    val positioned = partitions == null || origin != null
    Layout(
        modifier =
            Modifier
                .onGloballyPositioned {
                    origin = it.positionInWindow()
                    bottom = it.positionInWindow().y + it.size.height
                }.graphicsLayer { alpha = if (positioned) 1f else 0f },
        content = { Box(Modifier.clipToBounds()) { content() } },
    ) { measurables, constraints ->
        val preferred = partitions?.lastOrNull()
        val width = minOf(constraints.maxWidth, preferred?.width?.roundToPx() ?: constraints.maxWidth).coerceAtLeast(0)
        val child = measurables.single().measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0))
        val target = partitions?.lastOrNull { it.height.toPx() >= child.height } ?: preferred
        val gap =
            if (target != null &&
                bottom != null
            ) {
                (bottom!! - target.bottom.toPx()).roundToInt().coerceAtLeast(0)
            } else {
                0
            }
        val height = constraints.constrainHeight(child.height + gap)
        val left =
            if (target != null &&
                origin != null
            ) {
                (target.left.toPx() - origin!!.x).roundToInt().coerceAtLeast(0)
            } else {
                0
            }
        layout(constraints.maxWidth, height) { child.place(left, 0) }
    }
}
