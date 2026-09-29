package app.skein.feature.shell.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The usable window rectangle for one secondary surface (spec §5.3–5.4), in window dp.
 * Callers still own their system/IME insets. A book surface stays on the end page;
 * tabletop reading/text entry stays above the crease and confirmations stay below.
 * This must not wrap a multi-pane scene: Material already splits that scene at its hinge.
 */
fun SkeinLayoutDecision.surfaceBounds(
    surface: SecondarySurface,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): DpRect {
    val window = DpRect(0.dp, 0.dp, size.width.coerceAtLeast(0.dp), size.height.coerceAtLeast(0.dp))
    val hinge =
        when (val current = posture) {
            is SkeinPosture.Book -> current.hinge
            is SkeinPosture.Tabletop -> current.hinge
            SkeinPosture.Flat -> return window
        }
    if (!hinge.hasFiniteCoordinates()) return window
    return when (posture) {
        is SkeinPosture.Book ->
            if (layoutDirection == LayoutDirection.Ltr) {
                window.copy(left = maxOf(hinge.left, hinge.right).coerceIn(window.left, window.right))
            } else {
                window.copy(right = minOf(hinge.left, hinge.right).coerceIn(window.left, window.right))
            }
        is SkeinPosture.Tabletop ->
            when (partitionOf(surface)) {
                SurfacePartition.TOP ->
                    window.copy(
                        bottom = minOf(hinge.top, hinge.bottom).coerceIn(window.top, window.bottom),
                    )
                SurfacePartition.BOTTOM ->
                    window.copy(
                        top = maxOf(hinge.top, hinge.bottom).coerceIn(window.top, window.bottom),
                    )
                SurfacePartition.ANY -> window
            }
        SkeinPosture.Flat -> window
    }
}

internal fun DpRect.hasFiniteCoordinates(): Boolean = listOf(left, top, right, bottom).all { it.value.isFinite() }

/**
 * Constrains the actual child layout and touch region to [boundsInWindow], or
 * leaves the parent constraints untouched when it is null (flat/already split).
 * Restricted content is initially clipped to zero until its window origin is known.
 * The outer slot remains full size and [content] keeps one call site through posture
 * changes, so resizing does not recreate its remembered state. Window coordinates
 * are translated at this container's real origin, including parent insets/rail.
 *
 * This handles content in the current window. A platform Dialog/Popup owns another
 * window and must be sized/positioned by that window's owner; wrapping its launcher
 * here cannot constrain it. [modifier] can carry a caller's geometry test tag.
 */
@Composable
fun SkeinHingeSafeArea(
    boundsInWindow: DpRect?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    Layout(
        modifier =
            modifier.fillMaxSize().onGloballyPositioned {
                origin = it.positionInWindow()
            },
        content = { Box(Modifier.fillMaxSize().clipToBounds()) { content() } },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val position = origin
        val restricted = boundsInWindow?.takeIf { it.hasFiniteCoordinates() }
        val pending = restricted != null && position == null
        val left =
            if (restricted != null && position != null) {
                (restricted.left.toPx() - position.x).roundToInt().coerceIn(0, width)
            } else {
                0
            }
        val top =
            if (restricted != null && position != null) {
                (restricted.top.toPx() - position.y).roundToInt().coerceIn(0, height)
            } else {
                0
            }
        val right =
            when {
                pending -> 0
                restricted != null && position != null ->
                    (restricted.right.toPx() - position.x).roundToInt().coerceIn(left, width)
                else -> width
            }
        val bottom =
            when {
                pending -> 0
                restricted != null && position != null ->
                    (restricted.bottom.toPx() - position.y).roundToInt().coerceIn(top, height)
                else -> height
            }
        val child = measurables.single().measure(Constraints.fixed(right - left, bottom - top))
        layout(width, height) { child.place(left, top) }
    }
}
