package app.skein.core.designsystem.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpRect

/** Activity-window coordinates, supplied by the shell and inherited by separate Dialog/Popup windows. */
data class SkeinWindowPartitions(
    val window: DpRect,
    val reading: DpRect,
    val confirmation: DpRect,
    val anchors: List<DpRect>,
)

/** Null keeps Material's ordinary placement, including previews and a flat, open Fold. */
val LocalSkeinWindowPartitions = staticCompositionLocalOf<SkeinWindowPartitions?> { null }

/** False suppresses separate windows for a retained, inactive workspace owner. */
val LocalSkeinWindowActive = staticCompositionLocalOf { true }

enum class SkeinDialogPartition { READING, CONFIRMATION }
