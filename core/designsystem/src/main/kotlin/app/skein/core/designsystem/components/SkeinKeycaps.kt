// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §7.3, §10.23;
// docs/ux/research/CONTINUE.md's proposed Ctrl-based shortcut set): keyboard-
// shortcut hint keycaps ("Ctrl" "K"), shown only while a hardware keyboard is
// attached — "the outer screen never shows them" (§7.3).
package app.skein.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.skein.core.designsystem.theme.LocalSkeinMonoStyles
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * True when a hardware keyboard is attached and not folded away (§7.3:
 * `Configuration.keyboard != KEYBOARD_NOKEYS && hardKeyboardHidden !=
 * HARDKEYBOARDHIDDEN_YES`). A plain function over a [Configuration] so
 * [SkeinKeycaps]' gating is unit-testable without Robolectric.
 */
fun isSkeinHardwareKeyboardPresent(configuration: Configuration): Boolean =
    configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES

/**
 * Keyboard-shortcut hint keycaps (§10.23's palette row, e.g. `[Ctrl][K]`),
 * one box per [keys] element, rendered in
 * [monoLabel][app.skein.core.designsystem.theme.SkeinMonoStyles.monoLabel]
 * (Skein Mono, §3.3): a 1 dp `outlineVariant` box, radius 4, 4×2 dp padding,
 * 4 dp apart (§10.23). Renders nothing at all — not even for TalkBack — when
 * there is no hardware keyboard to press them on (§7.3).
 */
@Composable
fun SkeinKeycaps(
    vararg keys: String,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    if (keys.isEmpty() || !isSkeinHardwareKeyboardPresent(configuration)) return

    val monoLabel = LocalSkeinMonoStyles.current.monoLabel
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = keys.joinToString(separator = "+")
            },
        horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space4),
    ) {
        keys.forEach { key ->
            Text(
                text = key,
                style = monoLabel,
                color = onSurfaceVariant,
                modifier =
                    Modifier
                        .border(SkeinSize.hairline, outlineVariant, RoundedCornerShape(SkeinRadius.radiusXs))
                        .padding(horizontal = SkeinSpacing.space4, vertical = SkeinSpacing.space2),
            )
        }
    }
}
