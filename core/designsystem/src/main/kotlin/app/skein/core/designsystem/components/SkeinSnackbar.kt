// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §5.3, §10.13).
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * Skein's snackbar (spec §5.3, §10.13): Material's own `Snackbar` composable
 * hard-codes a shadow with no parameter to remove it in 1.4.0
 * (`NoShadowOrGradientTest` bans calling it directly outside this file), so
 * this is a from-scratch anatomy on a `Surface(shadowElevation = 0.dp)`
 * rather than a wrapper — the same container/text/action roles Material's
 * own `Snackbar` uses (`inverseSurface` / `inverseOnSurface` / `inversePrimary`,
 * §6.5's measured pairs), radius `radiusSm` (8 dp), max width 560 dp.
 *
 * Host it with [SkeinSnackbarHost] in place of a `SnackbarHost` wired to
 * Material's own default content.
 */
@Composable
fun SkeinSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .testTag(SKEIN_SNACKBAR_TEST_TAG)
                .padding(SkeinSpacing.space16)
                .widthIn(max = SKEIN_SNACKBAR_MAX_WIDTH),
        shape = RoundedCornerShape(SkeinRadius.radiusSm),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = SkeinSize.touchTarget)
                    .padding(horizontal = SkeinSpacing.space16, vertical = SkeinSpacing.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.testTag(SKEIN_SNACKBAR_MESSAGE_TEST_TAG),
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    modifier = Modifier.testTag(SKEIN_SNACKBAR_ACTION_TEST_TAG),
                ) { Text(text = label, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** [SnackbarHost] wired to [SkeinSnackbar] instead of Material's own shadowed `Snackbar`. */
@Composable
fun SkeinSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> SkeinSnackbar(data) }
}

/** §10.13: max width 560 dp — narrower than [SkeinSize.sheetMaxWidth] (640), so its own literal. */
private val SKEIN_SNACKBAR_MAX_WIDTH = 560.dp

const val SKEIN_SNACKBAR_TEST_TAG: String = "app.skein.core.designsystem.components.SkeinSnackbar"
const val SKEIN_SNACKBAR_MESSAGE_TEST_TAG: String = "$SKEIN_SNACKBAR_TEST_TAG.Message"
const val SKEIN_SNACKBAR_ACTION_TEST_TAG: String = "$SKEIN_SNACKBAR_TEST_TAG.Action"
