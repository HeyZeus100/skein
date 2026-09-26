// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §10.7;
// docs/ux/CHAT_UX_SPEC.md §8): the context chip above the composer.
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize
import androidx.compose.ui.semantics.onClick as onClickSemantics

/** §10.7's ~200 dp input-chip label cap, reused here so a long grammar string still fits one line. */
private val ContextLabelMaxWidth = 220.dp

/**
 * The chip directly above the composer that summarises what Skein will read
 * with the next message (`CHAT_UX_SPEC.md` §8: "2 notes · Knowledge on").
 * Tap opens the context inspector. The caller assembles [label] from §8.1's
 * grammar (and applies §8.2's drop/merge rules at Compact + large font
 * scale before calling this) and passes the always-full state through
 * [contentDescription] (§8.2: "Context: 2 notes, 1 file, knowledge on.
 * Double-tap to inspect.") — that replaces the visible label as this node's
 * accessible name so TalkBack never reads the abbreviated form.
 *
 * One line; unlike most chips (§10.7: "labels never ellipsise"), a context
 * label that still overflows [ContextLabelMaxWidth] middle-ellipsises
 * (`TextOverflow.MiddleEllipsis`, §3.4) rather than end-ellipsise, so the
 * trailing `Knowledge on`/`off` state stays legible instead of the leading
 * attachment counts.
 */
@Composable
fun SkeinContextChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    contentDescription: String? = null,
) {
    val extended = LocalSkeinColors.current
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.widthIn(max = ContextLabelMaxWidth),
            )
        },
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .skeinFocusRing(cornerRadius = SkeinRadius.radiusSm)
                .let { base ->
                    if (contentDescription == null) {
                        base
                    } else {
                        base.clearAndSetSemantics {
                            this.contentDescription = contentDescription
                            role = Role.Button
                            // clearAndSetSemantics replaces AssistChip's own merged
                            // semantics wholesale, which would otherwise silently
                            // drop its click action (and TalkBack's "double-tap to
                            // activate" cue) along with the label text it's here to
                            // replace — restore it explicitly.
                            onClickSemantics(label = null) {
                                onClick()
                                true
                            }
                        }
                    }
                },
        leadingIcon = {
            Icon(
                painter = painterResource(if (warning) SkeinIcons.Warning else SkeinIcons.Context),
                contentDescription = null,
                modifier = Modifier.size(SkeinSize.iconChip),
                tint = if (warning) extended.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        shape = RoundedCornerShape(SkeinRadius.radiusSm),
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor =
                    if (warning) {
                        extended.warningContainer
                    } else {
                        AssistChipDefaults
                            .assistChipColors()
                            .containerColor
                    },
                labelColor = if (warning) extended.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = if (warning) extended.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        border =
            if (warning) {
                null
            } else {
                AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    borderWidth = SkeinSize.hairline,
                )
            },
    )
}
