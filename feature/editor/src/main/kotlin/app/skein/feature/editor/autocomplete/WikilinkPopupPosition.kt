package app.skein.feature.editor.autocomplete

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/** The caret's partition owns both the popup bounds and its scrolling viewport. */
internal class WikilinkPopupPosition(
    private val offset: IntOffset,
    private val partitions: List<IntRect>,
    private val onPartition: (IntRect) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val caret =
            IntOffset(
                if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.left + offset.x
                } else {
                    anchorBounds.right - offset.x
                },
                anchorBounds.top + offset.y,
            )
        val desired =
            IntOffset(
                if (layoutDirection == LayoutDirection.Ltr) caret.x else caret.x - popupContentSize.width,
                caret.y,
            )
        val partition =
            partitions.minByOrNull { bounds ->
                val x = caret.x.coerceIn(bounds.left, bounds.right)
                val y = caret.y.coerceIn(bounds.top, bounds.bottom)
                val dx = caret.x.toLong() - x
                val dy = caret.y.toLong() - y
                dx * dx + dy * dy
            } ?: return desired
        val safe =
            IntRect(
                partition.left.coerceIn(0, windowSize.width),
                partition.top.coerceIn(0, windowSize.height),
                partition.right.coerceIn(0, windowSize.width),
                partition.bottom.coerceIn(0, windowSize.height),
            )
        onPartition(safe)
        return IntOffset(
            desired.x.coerceIn(safe.left, maxOf(safe.left, safe.right - popupContentSize.width)),
            desired.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - popupContentSize.height)),
        )
    }
}
