// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §5.2-5.3, §10.10).
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
 * Shadow-free `ModalBottomSheet` defaults (spec §10.10: container
 * `surfaceContainerLow`, top radius 20, `tonalElevation = 0`, scrim per
 * §5.3's theme-specific alpha). Material's own `ModalBottomSheet` already
 * defaults `tonalElevation` to `0.dp` in 1.4.0 — [tonalElevation] here just
 * names that. [scrimColor] is the one real override: Material's own
 * `BottomSheetDefaults.ScrimColor` is a fixed 32 % black regardless of
 * theme, where §6.2 wants 48 % in dark — [MaterialTheme.colorScheme.scrim]
 * already bakes the right alpha in per theme (`SkeinColorHex`), so passing
 * it through is enough; no separate token needed.
 *
 * `ModalBottomSheet` (still `@ExperimentalMaterial3Api` in 1.4.0) takes
 * these directly:
 *
 * ```
 * ModalBottomSheet(
 *     onDismissRequest = onDismiss,
 *     shape = SkeinSheetDefaults.shape,
 *     containerColor = SkeinSheetDefaults.containerColor,
 *     tonalElevation = SkeinSheetDefaults.tonalElevation,
 *     scrimColor = SkeinSheetDefaults.scrimColor,
 * ) { … }
 * ```
 */
object SkeinSheetDefaults {
    val shape: Shape
        @Composable get() = RoundedCornerShape(topStart = SkeinRadius.radiusXl, topEnd = SkeinRadius.radiusXl)

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow

    val tonalElevation: Dp = 0.dp

    val scrimColor: Color
        @Composable get() = MaterialTheme.colorScheme.scrim
}
