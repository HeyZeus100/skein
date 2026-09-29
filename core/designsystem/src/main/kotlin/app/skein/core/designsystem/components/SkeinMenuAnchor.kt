package app.skein.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpRect

/** Measured launcher bounds shared with a menu's separate Android window. */
@Stable
class SkeinMenuAnchor internal constructor() {
    var boundsInWindow: DpRect? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberSkeinMenuAnchor(): SkeinMenuAnchor = remember { SkeinMenuAnchor() }

fun Modifier.skeinMenuAnchor(anchor: SkeinMenuAnchor): Modifier =
    composed {
        val density = LocalDensity.current
        this.onGloballyPositioned { coordinates ->
            anchor.boundsInWindow =
                with(density) {
                    val origin = coordinates.positionInWindow()
                    DpRect(
                        origin.x.toDp(),
                        origin.y.toDp(),
                        (origin.x + coordinates.size.width).toDp(),
                        (
                            origin.y +
                                coordinates.size.height
                        ).toDp(),
                    )
                }
        }
    }
