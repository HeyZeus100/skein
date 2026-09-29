package app.skein.core.designsystem.components

import android.view.Gravity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/** Material dialog semantics, dismissal and styling, placed wholly in one separating-hinge partition. */
@Composable
fun SkeinAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    partition: SkeinDialogPartition = SkeinDialogPartition.CONFIRMATION,
    properties: DialogProperties = DialogProperties(),
) {
    val partitions = LocalSkeinWindowPartitions.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Read at the launcher: a separate window has a different inset origin. Raw insets are
    // deliberately used, even when the entry has already consumed its own system bars.
    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
    val bounds =
        partitions?.let {
            val requested = if (partition == SkeinDialogPartition.READING) it.reading else it.confirmation
            with(density) {
                intersectWindowPartition(
                    requested,
                    DpRect(
                        insets.getLeft(this, direction).toDp(),
                        insets.getTop(this).toDp(),
                        it.window.right - insets.getRight(this, direction).toDp(),
                        it.window.bottom - insets.getBottom(this).toDp(),
                    ),
                )
            }
        }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.then(if (bounds == null) Modifier else Modifier.inDialogPartition(bounds)),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text =
            if (bounds == null ||
                text == null
            ) {
                text
            } else {
                ({ Box(Modifier.verticalScroll(rememberScrollState())) { text() } })
            },
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        properties =
            if (bounds == null) {
                properties
            } else {
                DialogProperties(
                    dismissOnBackPress = properties.dismissOnBackPress,
                    dismissOnClickOutside = properties.dismissOnClickOutside,
                    securePolicy = properties.securePolicy,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    windowTitle = properties.windowTitle,
                    windowType = properties.windowType,
                    windowToken = properties.windowToken,
                )
            },
    )
}

internal fun intersectWindowPartition(
    partition: DpRect,
    available: DpRect,
): DpRect {
    val left = maxOf(partition.left, available.left)
    val top = maxOf(partition.top, available.top)
    return DpRect(
        left,
        top,
        minOf(partition.right, available.right).coerceAtLeast(left),
        minOf(partition.bottom, available.bottom).coerceAtLeast(top),
    )
}

/** Composed inside Material's Dialog, where LocalView refers to the dialog's own window. */
private fun Modifier.inDialogPartition(bounds: DpRect): Modifier =
    composed {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val density = LocalDensity.current
        var size by remember { mutableStateOf(IntSize.Zero) }
        DisposableEffect(window, bounds, size, density) {
            val previous =
                window?.attributes?.let {
                    android.view.WindowManager
                        .LayoutParams()
                        .apply { copyFrom(it) }
                }
            if (window != null && size != IntSize.Zero) {
                val attributes = window.attributes
                attributes.gravity = Gravity.TOP or Gravity.LEFT
                with(density) {
                    attributes.x = (bounds.left.toPx() + (bounds.width.toPx() - size.width) / 2f).roundToInt()
                    attributes.y = (bounds.top.toPx() + (bounds.height.toPx() - size.height) / 2f).roundToInt()
                }
                window.attributes = attributes
            }
            onDispose {
                if (window != null && previous != null) {
                    val attributes = window.attributes
                    attributes.gravity = previous.gravity
                    attributes.x = previous.x
                    attributes.y = previous.y
                    window.attributes = attributes
                }
            }
        }
        val positioned = size != IntSize.Zero
        this.sizeIn(maxWidth = bounds.width, maxHeight = bounds.height).onSizeChanged { size = it }.graphicsLayer {
            alpha =
                if (positioned) 1f else 0f
        }
    }
