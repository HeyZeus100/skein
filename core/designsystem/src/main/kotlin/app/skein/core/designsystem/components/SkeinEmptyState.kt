// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.20;
// INFORMATION_ARCHITECTURE.md §3.7): the one empty-state template — every
// empty state answers "what should I do next?".
package app.skein.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * The empty-state template (§10.20): [headline] + one line of [body] + one
 * [primaryAction] (a filled button) + up to four [secondaryActions] (48 dp
 * rows with a leading icon, no chevrons). No illustrations. Left-aligned in a
 * column ≤ 480 dp. [body] is copy written to fit two lines; it wraps rather
 * than truncating at large font scales.
 *
 * Give it the free space (`Modifier.fillMaxSize()` or a weight): on Compact
 * the column sits in the upper third of that space, on Medium+ (≥ 600 dp
 * wide) it is centred and the headline steps up to `headlineMedium`. When the
 * primary action is the composer (the chat landing), pass no
 * [primaryAction] and put the composer below.
 */
@Composable
fun SkeinEmptyState(
    headline: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    primaryAction: SkeinAction? = null,
    secondaryActions: List<SkeinAction> = emptyList(),
) {
    val compact = LocalConfiguration.current.screenWidthDp < MEDIUM_MIN_WIDTH_DP
    Column(
        modifier = modifier.padding(horizontal = SkeinSpacing.space16),
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        // Space above : below — 1 : 2 puts the block in the upper third; 1 : 1 centres it.
        Spacer(Modifier.weight(1f))
        val typography = MaterialTheme.typography
        Column(
            modifier = Modifier.widthIn(max = EMPTY_STATE_MAX_WIDTH).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SkeinSpacing.space8),
        ) {
            Text(
                text = headline,
                style = if (compact) typography.headlineSmall else typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (primaryAction != null) {
                Box(Modifier.padding(top = SkeinSpacing.space16)) {
                    // TODO(skein-xtov.23.7 DS7): DS7's button defaults; §10.5's shape and no hover shadow meanwhile.
                    Button(onClick = primaryAction.onClick, shape = MaterialTheme.shapes.medium, elevation = null) {
                        Text(primaryAction.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (secondaryActions.isNotEmpty()) {
                Column(Modifier.padding(top = SkeinSpacing.space8)) {
                    secondaryActions.forEach { SecondaryActionRow(it) }
                }
            }
        }
        Spacer(Modifier.weight(if (compact) 2f else 1f))
    }
}

@Composable
private fun SecondaryActionRow(action: SkeinAction) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SkeinSize.touchTarget)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = action.onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space16),
    ) {
        if (action.icon != null) {
            Icon(
                painter = painterResource(action.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SkeinSize.iconStandard),
            )
        }
        Text(action.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** §10.20: the empty-state column is capped narrower than the 576 dp prose width. */
private val EMPTY_STATE_MAX_WIDTH = 480.dp

/** §4.2: Medium starts at 600 dp. */
private const val MEDIUM_MIN_WIDTH_DP = 600
