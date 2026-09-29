package app.skein.core.designsystem.components

import android.view.Gravity
import android.view.WindowManager.LayoutParams
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

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
    if (!LocalSkeinWindowActive.current) return
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
        modifier =
            modifier.then(
                if (bounds ==
                    null
                ) {
                    Modifier
                } else {
                    Modifier.inDialogPartition(bounds, properties.usePlatformDefaultWidth)
                },
            ),
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
private fun Modifier.inDialogPartition(
    bounds: DpRect,
    platformDefaultWidth: Boolean,
): Modifier =
    composed {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val density = LocalDensity.current
        var positioned by remember(bounds) { mutableStateOf(false) }
        DisposableEffect(window, bounds, density, platformDefaultWidth) {
            val previous =
                window?.attributes?.let {
                    android.view.WindowManager
                        .LayoutParams()
                        .apply { copyFrom(it) }
                }
            if (window != null) {
                val attributes = window.attributes
                // The platform window occupies the partition. DialogLayout already centres the
                // measured Material card inside it; positioning the card again double-offsets it.
                attributes.gravity = Gravity.TOP or Gravity.LEFT
                with(density) {
                    attributes.x = bounds.left.roundToPx()
                    attributes.y = bounds.top.roundToPx()
                    attributes.width = bounds.width.roundToPx()
                    attributes.height = bounds.height.roundToPx()
                }
                window.attributes = attributes
                positioned = true
            }
            onDispose {
                if (window != null && previous != null) {
                    val attributes = window.attributes
                    attributes.gravity = previous.gravity
                    attributes.x = previous.x
                    attributes.y = previous.y
                    // Restore Material's sizing policy when the separating hinge disappears.
                    attributes.width =
                        if (platformDefaultWidth) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                    attributes.height = LayoutParams.WRAP_CONTENT
                    window.attributes = attributes
                }
            }
        }
        this.sizeIn(maxWidth = bounds.width, maxHeight = bounds.height).graphicsLayer {
            alpha = if (positioned) 1f else 0f
        }
    }
