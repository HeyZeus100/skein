// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.8, §10.21): the inline
// notice — the only card Skein draws ("Choose a model to start",
// "Couldn't import “notes.pdf”").
package app.skein.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/** A [SkeinNotice]'s tone (§10.8). Always paired with its own icon — never colour alone. */
enum class SkeinNoticeTone { Info, Success, Warning, Error }

/**
 * An inline notice (§10.8): radius 12, 1 dp `outlineVariant`, padding 16; a
 * 24 dp tone icon, a `titleSmall` [title], an optional `bodyMedium` [body]
 * and an optional [action] as a text button under them. [SkeinNoticeTone.Info]
 * is a neutral `surfaceContainerLow` card with the `info` icon in `primary`;
 * the others use their container colours (`successContainer`,
 * `warningContainer`, `errorContainer`).
 *
 * Copy (§11.4): errors say what happened, why in product words, and what to
 * do — "Couldn't import “notes.pdf”." / "The file is password-protected." /
 * **Choose another file**.
 */
@Composable
fun SkeinNotice(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: SkeinNoticeTone = SkeinNoticeTone.Info,
    action: SkeinAction? = null,
) {
    val colors = MaterialTheme.colorScheme
    val extended = LocalSkeinColors.current
    val (container, content, iconTint, icon) =
        when (tone) {
            SkeinNoticeTone.Info ->
                NoticeStyle(
                    colors.surfaceContainerLow,
                    colors.onSurface,
                    colors.primary,
                    SkeinIcons.Info,
                )
            SkeinNoticeTone.Success ->
                NoticeStyle(
                    extended.successContainer,
                    extended.onSuccessContainer,
                    extended.onSuccessContainer,
                    SkeinIcons.Success,
                )
            SkeinNoticeTone.Warning ->
                NoticeStyle(
                    extended.warningContainer,
                    extended.onWarningContainer,
                    extended.onWarningContainer,
                    SkeinIcons.Warning,
                )
            SkeinNoticeTone.Error ->
                NoticeStyle(
                    colors.errorContainer,
                    colors.onErrorContainer,
                    colors.onErrorContainer,
                    SkeinIcons.ActivityFailed,
                )
        }
    val bodyColor = if (tone == SkeinNoticeTone.Info) colors.onSurfaceVariant else content
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = BorderStroke(SkeinSize.hairline, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(SkeinSpacing.space16),
            horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space16),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(SkeinSize.iconStandard),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SkeinSpacing.space4)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = bodyColor)
                if (action != null) {
                    // TODO(skein-xtov.23.7 DS7): DS7's button defaults; §10.5's shape meanwhile.
                    TextButton(
                        onClick = action.onClick,
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = if (tone == SkeinNoticeTone.Info) colors.primary else content,
                            ),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private data class NoticeStyle(
    val container: Color,
    val content: Color,
    val iconTint: Color,
    val icon: Int,
)
