package app.skein.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale (spec §4.1, DS5): a 4 dp baseline with 8 dp steps.
 * Rhythm: inline gaps (icon ↔ label) and gaps between related items use
 * [space8]; between groups [space16]; between sections [space24]; screen-
 * level separation [space32]. Named tokens say the number so there is
 * nothing to look up.
 */
object SkeinSpacing {
    val space2: Dp = 2.dp
    val space4: Dp = 4.dp
    val space8: Dp = 8.dp
    val space12: Dp = 12.dp
    val space16: Dp = 16.dp
    val space20: Dp = 20.dp
    val space24: Dp = 24.dp
    val space32: Dp = 32.dp
    val space40: Dp = 40.dp
    val space48: Dp = 48.dp
    val space64: Dp = 64.dp
}
