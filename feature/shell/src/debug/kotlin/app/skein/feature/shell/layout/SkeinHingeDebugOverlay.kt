package app.skein.feature.shell.layout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import app.skein.core.model.SkeinLog

/** Opt-in debug observation only: geometry/flags, no navigation, vault, Space or content values. */
@Composable
fun SkeinHingeDebugOverlay(
    info: WindowAdaptiveInfo,
    enabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val posture = info.windowPosture
    LaunchedEffect(posture) {
        SkeinLog.d(
            "SkeinHinge",
            "tabletop=${posture.isTabletop} count=${posture.hingeList.size} " +
                posture.hingeList.joinToString(" ") { hinge ->
                    "vertical=${hinge.isVertical} separating=${hinge.isSeparating} occluding=${hinge.isOccluding} " +
                        "boundsPx=${hinge.bounds.left},${hinge.bounds.top},${hinge.bounds.right},${hinge.bounds.bottom}"
                },
        )
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val separatingColor = MaterialTheme.colorScheme.error
    val flatColor = MaterialTheme.colorScheme.primary
    // Canvas adds no input handler or accessibility action; it cannot intercept controls underneath.
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        for (hinge in posture.hingeList) {
            val bounds = hinge.bounds
            val color = if (hinge.isSeparating) separatingColor else flatColor
            val topLeft = bounds.topLeft - origin
            if (bounds.width == 0f || bounds.height == 0f) {
                drawLine(color, topLeft, bounds.bottomRight - origin, strokeWidth = 2.dp.toPx())
            } else {
                drawRect(color.copy(alpha = 0.3f), topLeft, Size(bounds.width, bounds.height))
            }
        }
    }
}
