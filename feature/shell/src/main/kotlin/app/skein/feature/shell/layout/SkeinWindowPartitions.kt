package app.skein.feature.shell.layout

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinWindowPartitions

/** Bridges the shell posture model to separate-window components without a feature dependency. */
fun SkeinLayoutDecision.windowPartitions(direction: LayoutDirection = LayoutDirection.Ltr): SkeinWindowPartitions? {
    if (posture == SkeinPosture.Flat) return null
    val window = DpRect(0.dp, 0.dp, size.width, size.height)
    val anchors =
        when (posture) {
            is SkeinPosture.Book ->
                listOf(
                    surfaceBounds(SecondarySurface.OPENED_SOURCE, LayoutDirection.Rtl),
                    surfaceBounds(SecondarySurface.OPENED_SOURCE, LayoutDirection.Ltr),
                )
            is SkeinPosture.Tabletop ->
                listOf(surfaceBounds(SecondarySurface.RENAME_DIALOG), surfaceBounds(SecondarySurface.CONFIRM_DIALOG))
            SkeinPosture.Flat -> return null
        }
    return SkeinWindowPartitions(
        window = window,
        reading = surfaceBounds(SecondarySurface.RENAME_DIALOG, direction),
        confirmation = surfaceBounds(SecondarySurface.CONFIRM_DIALOG, direction),
        anchors = anchors,
    )
}
