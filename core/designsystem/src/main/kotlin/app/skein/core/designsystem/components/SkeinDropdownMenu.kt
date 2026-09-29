package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

/** A Material menu anchored inside its launcher's hinge partition; flat placement is unchanged. */
@Composable
fun SkeinDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: DpRect?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val partitions = LocalSkeinWindowPartitions.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val bounds =
        if (partitions != null && anchorBounds != null) {
            val center =
                DpOffset(
                    (anchorBounds.left + anchorBounds.right) / 2,
                    (anchorBounds.top + anchorBounds.bottom) / 2,
                )
            val partition =
                partitions.anchors.firstOrNull {
                    center.x >= it.left && center.x <= it.right && center.y >= it.top && center.y <= it.bottom
                } ?: partitions.anchors.maxBy { it.width.value * it.height.value }
            with(density) {
                // Material's position provider keeps 48 dp from the display's vertical edges.
                intersectWindowPartition(
                    partition,
                    DpRect(
                        insets.getLeft(this, direction).toDp(),
                        maxOf(48.dp, insets.getTop(this).toDp()),
                        partitions.window.right - insets.getRight(this, direction).toDp(),
                        partitions.window.bottom - maxOf(48.dp, insets.getBottom(this).toDp()),
                    ),
                )
            }
        } else {
            null
        }
    var measuredSize by remember(bounds) { mutableStateOf(IntSize.Zero) }
    val offset =
        if (bounds != null && anchorBounds != null) {
            with(density) {
                menuPartitionOffset(
                    anchorBounds,
                    bounds,
                    DpSize(measuredSize.width.toDp(), measuredSize.height.toDp()),
                    direction,
                )
            }
        } else {
            DpOffset.Zero
        }
    val positioned = measuredSize != IntSize.Zero
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier =
            modifier.then(
                if (bounds == null) {
                    Modifier
                } else {
                    Modifier
                        .sizeIn(maxWidth = bounds.width, maxHeight = bounds.height)
                        .onSizeChanged { measuredSize = it }
                        .graphicsLayer { alpha = if (positioned) 1f else 0f }
                },
            ),
        content = content,
    )
}

internal fun menuPartitionOffset(
    anchor: DpRect,
    bounds: DpRect,
    size: DpSize,
    direction: LayoutDirection,
): DpOffset {
    val preferredX = if (direction == LayoutDirection.Ltr) anchor.left else anchor.right - size.width
    val x = preferredX.coerceIn(bounds.left, (bounds.right - size.width).coerceAtLeast(bounds.left))
    val y = anchor.bottom.coerceIn(bounds.top, (bounds.bottom - size.height).coerceAtLeast(bounds.top))
    return DpOffset(
        if (direction ==
            LayoutDirection.Ltr
        ) {
            x - anchor.left
        } else {
            anchor.right - size.width - x
        },
        y - anchor.bottom,
    )
}
