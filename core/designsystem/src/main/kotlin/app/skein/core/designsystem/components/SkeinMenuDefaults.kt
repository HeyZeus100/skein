// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §5.3, §10.12).
package app.skein.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize

/**
 * Shadow-free `DropdownMenu` defaults (spec §5.3: "`DropdownMenu`… take[s]
 * `shadowElevation` (set 0)"; §10.12: container `surfaceContainer`, shape
 * `shapes.small`, `tonalElevation = 0`, `shadowElevation = 0`, a 1 dp
 * `outlineVariant` border standing in for the 3 dp shadow Material draws by
 * default — `MenuDefaults.ShadowElevation` (`MenuTokens.ContainerElevation`).
 *
 * `DropdownMenu`'s own parameter names already match these one for one, so
 * the lightest adoption is passing them straight through — no wrapper
 * composable needed:
 *
 * ```
 * DropdownMenu(
 *     expanded = expanded,
 *     onDismissRequest = onDismiss,
 *     shape = SkeinMenuDefaults.shape,
 *     containerColor = SkeinMenuDefaults.containerColor,
 *     tonalElevation = SkeinMenuDefaults.tonalElevation,
 *     shadowElevation = SkeinMenuDefaults.shadowElevation,
 *     border = SkeinMenuDefaults.border,
 * ) { … }
 * ```
 */
object SkeinMenuDefaults {
    val shape: Shape
        @Composable get() = RoundedCornerShape(SkeinRadius.radiusSm)

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainer

    val tonalElevation: Dp = 0.dp
    val shadowElevation: Dp = 0.dp

    val border: BorderStroke
        @Composable get() = BorderStroke(SkeinSize.hairline, MaterialTheme.colorScheme.outlineVariant)
}
