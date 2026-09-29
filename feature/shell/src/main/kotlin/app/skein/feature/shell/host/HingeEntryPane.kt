package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.core.navigation.ChatSourceKey
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.feature.shell.layout.SecondarySurface
import app.skein.feature.shell.layout.SkeinHingeSafeArea
import app.skein.feature.shell.layout.SkeinPosture
import app.skein.feature.shell.layout.surfaceBounds

/**
 * Material owns a multi-pane split. Only an entry whose actual bounds still cross a
 * book hinge is confined to the end page (for example a followed, single-pane source).
 * A list already on the start page is left alone. In tabletop, key-based secondary
 * surfaces also stay in their designated partition, whether shown as a pane or sheet.
 * The content call site stays stable through each posture change.
 */
@Composable
internal fun HingeEntryPane(
    key: SkeinKey,
    content: @Composable () -> Unit,
) {
    val peek = LocalSheetMode.current == SheetMode.PEEK
    val layout = LocalSkeinWindowLayout.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var measuredBounds by remember { mutableStateOf<Rect?>(null) }
    val pane = measuredBounds
    val hinge = (layout.posture as? SkeinPosture.Book)?.hinge
    val surface =
        if (key is ChatSourceKey || (key is TransientKey && key.kind == TransientKind.SOURCE)) {
            SecondarySurface.OPENED_SOURCE
        } else {
            sheetSurfaceOf(key)
        }
    val crosses =
        pane != null &&
            hinge != null &&
            with(density) {
                pane.left < hinge.right.toPx() &&
                    pane.right > hinge.left.toPx() &&
                    pane.top < hinge.bottom.toPx() &&
                    pane.bottom > hinge.top.toPx()
            }
    val bounds =
        when {
            peek -> null
            layout.posture is SkeinPosture.Tabletop && surface != null -> layout.surfaceBounds(surface, direction)
            hinge == null -> null
            pane == null -> DpRect(0.dp, 0.dp, 0.dp, 0.dp)
            crosses -> layout.surfaceBounds(SecondarySurface.OPENED_SOURCE, direction)
            else -> null
        }
    val sizing = if (peek) Modifier else Modifier.fillMaxSize()
    Box(sizing.onGloballyPositioned { measuredBounds = it.boundsInWindow() }) {
        SkeinHingeSafeArea(bounds, expand = !peek, content = content)
    }
}
