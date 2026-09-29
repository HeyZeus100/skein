package app.skein.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.host.entryBottomObstruction

/** AL-19's fixed-control guard; the flat layout and the session content call site stay unchanged. */
@Composable
internal fun HingeSafeChatControls(
    contentTopInWindow: Dp,
    bottomInWindow: Dp,
    hinge: DpRect? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val controlsBottom = bottomInWindow - entryBottomObstruction()
    var controlsHeight by remember { mutableIntStateOf(0) }
    val controlsTop = controlsBottom - with(density) { controlsHeight.toDp() }
    val intersects = hinge != null && controlsTop < hinge.bottom && controlsBottom > hinge.top
    val above = if (hinge == null) 0.dp else (minOf(controlsBottom, hinge.top) - contentTopInWindow).coerceAtLeast(0.dp)
    val below =
        if (hinge == null) 0.dp else (controlsBottom - maxOf(contentTopInWindow, hinge.bottom)).coerceAtLeast(0.dp)
    val useAbove = intersects && above >= below
    val clearance = if (useAbove) (controlsBottom - hinge!!.top).coerceAtLeast(0.dp) else 0.dp
    val available =
        when {
            !intersects -> Dp.Infinity
            useAbove -> above
            else -> below
        }
    // Consume at the fixed cluster so a retained IME cannot separate the chip from
    // its composer. ChatBottomBar still consumes its own insets when hosted alone.
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(bottom = clearance),
    ) {
        Column(Modifier.heightIn(max = available).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().onSizeChanged { controlsHeight = it.height }) { content() }
        }
    }
}
