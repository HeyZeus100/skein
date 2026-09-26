// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.14, §8.4): the model /
// import status indicator — an 8 dp dot plus words, never the dot alone.
package app.skein.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/** What a [SkeinStatus] reports (§10.14). */
enum class SkeinStatusKind {
    /** `success` dot, words in `success` ("Ready"). */
    Ready,

    /** `primary` dot, muted words ("Starting… 42%", "Getting ready · 42%"). */
    Loading,

    /** 16 dp `error` icon, words in `error` ("Couldn't start"). */
    Error,

    /** No dot, muted words ("Loads when you send"). */
    Idle,
}

/**
 * A status indicator (§10.14): a static 8 dp dot (dots never animate, §8.4)
 * or, for [SkeinStatusKind.Error], the `error` icon — so the state never
 * rests on colour alone — beside a `labelMedium` [label].
 *
 * It is one polite live region whose spoken text is [announcement], not the
 * visible [label]: TalkBack re-announces only when [announcement] changes.
 * Keep it coarse — once per state change, at most every 25 % (§10.14): pass
 * "Starting… 25%" while [label] ticks through "Starting… 42%".
 */
@Composable
fun SkeinStatus(
    kind: SkeinStatusKind,
    label: String,
    modifier: Modifier = Modifier,
    announcement: String = label,
) {
    val colors = MaterialTheme.colorScheme
    val extended = LocalSkeinColors.current
    val textColor =
        when (kind) {
            SkeinStatusKind.Ready -> extended.success
            SkeinStatusKind.Error -> colors.error
            SkeinStatusKind.Loading, SkeinStatusKind.Idle -> colors.onSurfaceVariant
        }
    Row(
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            SkeinStatusKind.Ready -> Dot(extended.success)
            SkeinStatusKind.Loading -> Dot(colors.primary)
            SkeinStatusKind.Error ->
                Icon(
                    painter = painterResource(SkeinIcons.ActivityFailed),
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(SkeinSize.iconInline),
                )
            SkeinStatusKind.Idle -> Unit
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** Centred in the error icon's 16 dp slot, so every marked label starts at the same x. */
@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(SkeinSize.iconInline), contentAlignment = Alignment.Center) {
        Box(Modifier.size(SkeinSize.statusDot).background(color, SkeinRadius.radiusFull))
    }
}
