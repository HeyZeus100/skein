// AL-11: insets belong to entries, not to the navigation container.
package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Rebase insets at the actual pane bounds, including a rail, sheet or hinge offset.
 * Only the pane touching a window edge consumes that edge. Top bars see the consumed
 * top inset and cannot add it twice. Bottom content owns navigation bars and the IME;
 * neither can resize adjacent panes through this boundary (adaptive spec §6.2).
 */
@Composable
fun EntryInsets(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val mode = LocalSheetMode.current
    val isPeek = mode == SheetMode.PEEK
    val window = LocalView.current.rootView
    var edgeDistances by remember { mutableStateOf(PaneEdgeDistances()) }
    val consumed =
        if (isPeek) {
            WindowInsets(0, 0, 0, 0)
        } else {
            WindowInsets(edgeDistances.left, edgeDistances.top, edgeDistances.right, edgeDistances.bottom)
        }
    // Record numeric, physically positioned edge distances after placement;
    // equal geometry never invalidates composition again.
    val paneModifier =
        if (isPeek) {
            Modifier
        } else {
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    edgeDistances =
                        PaneEdgeDistances(
                            position.x.roundToInt().coerceAtLeast(0),
                            position.y.roundToInt().coerceAtLeast(0),
                            (window.width - position.x - coordinates.size.width).roundToInt().coerceAtLeast(0),
                            (window.height - position.y - coordinates.size.height).roundToInt().coerceAtLeast(0),
                        )
                }.consumeWindowInsets(consumed)
        }
    val paddingInsets =
        when (mode) {
            SheetMode.PEEK ->
                WindowInsets.ime
                    .union(WindowInsets.navigationBars)
                    .union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            SheetMode.EXPANDED -> WindowInsets.safeDrawing
            SheetMode.PANE -> WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        }
    // Overlay controls must clear a retained keyboard even when the field belongs
    // to the underlying entry. Rebased distances consume only the covered remainder.
    val entryConsumed = if (mode == SheetMode.PANE) consumed else consumed.union(paddingInsets)
    // Keep the same content call site across presentation changes, while peek stays intrinsic.
    Box(modifier.then(paneModifier)) {
        Box(
            (if (isPeek) Modifier else Modifier.fillMaxSize()).windowInsetsPadding(paddingInsets),
        ) {
            CompositionLocalProvider(
                LocalEntryBottomInsets provides WindowInsets.navigationBars.exclude(entryConsumed),
                LocalEntryConsumedInsets provides entryConsumed,
            ) {
                content()
            }
        }
    }
}

private val LocalEntryBottomInsets = compositionLocalOf<WindowInsets?> { null }
private val LocalEntryConsumedInsets = compositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }

/** Remaining bottom obstruction for sizing a composer; padding still consumes the platform insets normally. */
@Composable
fun entryBottomObstruction(): Dp {
    val density = LocalDensity.current
    return with(density) {
        WindowInsets.ime
            .union(WindowInsets.navigationBars)
            .exclude(LocalEntryConsumedInsets.current)
            .getBottom(density)
            .toDp()
    }
}

/** Bottom padding belongs inside scrolling content, so the last row can clear gesture navigation. */
@Composable
fun entryBottomPadding(): PaddingValues =
    (LocalEntryBottomInsets.current ?: WindowInsets.navigationBars).only(WindowInsetsSides.Bottom).asPaddingValues()

private data class PaneEdgeDistances(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)
