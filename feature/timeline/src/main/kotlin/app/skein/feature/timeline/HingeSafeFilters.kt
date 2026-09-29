package app.skein.feature.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions

/** Fixed filters move below a near-top tabletop crease; the scrolling list keeps its own slot. */
@Composable
internal fun HingeSafeFilters(content: @Composable () -> Unit) {
    val partitions =
        LocalSkeinWindowPartitions.current
            ?.anchors
            .orEmpty()
            .sortedBy { it.top }
    val top = partitions.firstOrNull()
    val bottom = partitions.lastOrNull()
    val tabletop = top != null && bottom != null && top.bottom < bottom.top
    val density = LocalDensity.current
    var origin by remember { mutableStateOf<Dp?>(null) }
    var naturalHeight by remember { mutableIntStateOf(0) }
    val y = origin
    val intersects =
        tabletop && y != null && y < bottom!!.top && y + with(density) { naturalHeight.toDp() } > top!!.bottom
    val clearance = if (intersects) (bottom!!.top - y!!).coerceAtLeast(0.dp) else 0.dp
    val available =
        when {
            tabletop && y == null -> 0.dp
            intersects -> (bottom!!.bottom - bottom.top).coerceAtLeast(0.dp)
            else -> Dp.Infinity
        }
    Column(Modifier.onGloballyPositioned { origin = with(density) { it.positionInWindow().y.toDp() } }) {
        Column(Modifier.padding(top = clearance).heightIn(max = available).verticalScroll(rememberScrollState())) {
            Column(Modifier.onSizeChanged { naturalHeight = it.height }) { content() }
        }
    }
}
