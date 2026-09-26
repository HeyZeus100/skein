// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §5.3, §10.11): shadow-free
// `AlertDialog` defaults.
package app.skein.core.designsystem.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinRadius

/**
 * Shadow-free `AlertDialog` defaults (spec §5.3: "tone plus hairline, never
 * shadow"; §10.11: dialog container `surfaceContainerHigh`, radius 20,
 * `tonalElevation = 0`). Material's own
 * `AlertDialogDefaults.TonalElevation` is already `0.dp` in 1.4.0 —
 * [tonalElevation] here just names that explicitly, the way every other
 * Skein overlay default does, rather than relying on an upstream default
 * that could silently change. The one real override is [shape]: Material's
 * own dialog shape is its "extra-extra-large" 28 dp corner (`Shapes`'
 * internal-only slot, `SkeinShapes`' own KDoc), wider than the spec's dialog
 * radius ([SkeinRadius.radiusXl], 20 dp).
 *
 * Used by [SkeinDestructiveDialog] and [SkeinRenameDialog]; any other
 * `AlertDialog` call site can adopt the same three values directly.
 */
object SkeinDialogDefaults {
    val shape: Shape
        @Composable get() = RoundedCornerShape(SkeinRadius.radiusXl)

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    val tonalElevation: Dp = 0.dp
}
