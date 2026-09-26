// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.1, §3.5; CHAT_UX_SPEC.md
// §4): the top app bar with a title over an optional model-label subtitle.
//
// Not Material's `TopAppBar(title, subtitle, …, expandedHeight)`: that lays
// its row out at a fixed `expandedHeight`, so the caller has to re-derive the
// scaled title + subtitle line heights (§3.5 rule 4) or the block clips at
// 150–200 % font. A `Row` with `heightIn(min = 64.dp)` grows with its text by
// construction — the same "no fixed heights on anything that holds text"
// rule (§3.5 rule 2) — and keeps §10.1's colours, hairline and slots.
package app.skein.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * Skein's top app bar (§10.1): optional [navigationIcon] (an `IconButton`:
 * `menu` on Compact, `arrow_back` on a pushed detail, none beside a rail),
 * a one-line [title] (a `heading()`), an optional [subtitle] and up to two
 * [actions] plus ⋮. At least [SkeinSize.topBar] tall, and taller whenever the
 * title block needs it at large font scales — it never clips.
 *
 * The subtitle is the chat's model label (CHAT_UX_SPEC.md §4.3), laid out
 * `[subtitle, ellipsised first][ · subtitleDetail][▾]` so the location
 * ("Local", "Starting… 42%", "Couldn't start") never truncates before the
 * model name does. [subtitleIsError] draws the detail (only) in `error` with
 * a 16 dp error icon.
 *
 * With [onSubtitleClick], the whole title block becomes one ≥ 48 dp button
 * (§10.1: "one target, one action") with a trailing ▾ chevron;
 * [subtitleClickLabel] is its spoken label, e.g. "Model: Qwen 2.5 3B, local.
 * Change model.". The title stays its own heading node for TalkBack.
 *
 * The title is `titleMedium` when there is a subtitle (chat) and
 * `titleLarge` otherwise (destinations). [scrolled] switches the container to
 * `surfaceContainer` with a 1 dp `outlineVariant` bottom line (§5.3).
 */
@Composable
fun SkeinTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleDetail: String? = null,
    subtitleIsError: Boolean = false,
    onSubtitleClick: (() -> Unit)? = null,
    subtitleClickLabel: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrolled: Boolean = false,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (scrolled) colors.surfaceContainer else colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.windowInsetsPadding(windowInsets)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = SkeinSize.topBar,
                        ).padding(horizontal = SkeinSpacing.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    navigationIcon?.invoke()
                }
                // §4.2 alignment line: text at 16 dp from the edge (4 + 8 + the title block's own 4),
                // or at 56 dp after a 48 dp navigation button (4 + 48 + 4).
                if (navigationIcon == null) Spacer(Modifier.width(SkeinSpacing.space8))
                TitleBlock(
                    title = title,
                    subtitle = subtitle,
                    subtitleDetail = subtitleDetail,
                    subtitleIsError = subtitleIsError,
                    onSubtitleClick = onSubtitleClick,
                    subtitleClickLabel = subtitleClickLabel,
                    modifier = Modifier.weight(1f),
                )
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    actions()
                }
            }
            if (scrolled) HorizontalDivider(thickness = SkeinSize.hairline, color = colors.outlineVariant)
        }
    }
}

@Composable
private fun TitleBlock(
    title: String,
    subtitle: String?,
    subtitleDetail: String?,
    subtitleIsError: Boolean,
    onSubtitleClick: (() -> Unit)?,
    subtitleClickLabel: String?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val clickable =
        if (onSubtitleClick == null) {
            Modifier
        } else {
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onSubtitleClick)
                .semantics { subtitleClickLabel?.let { contentDescription = it } }
        }
    Column(
        modifier =
            modifier
                .heightIn(min = SkeinSize.touchTarget)
                .then(clickable)
                .padding(horizontal = SkeinSpacing.space4, vertical = SkeinSpacing.space8),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = if (subtitle == null) typography.titleLarge else typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Its own merge boundary, so the subtitle button's label doesn't swallow the heading.
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        )
        if (subtitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subtitle,
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (subtitleDetail != null) {
                    Text(text = " · ", style = typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    if (subtitleIsError) {
                        Icon(
                            painter = painterResource(SkeinIcons.ActivityFailed),
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.padding(end = SkeinSpacing.space4).size(SkeinSize.iconInline),
                        )
                    }
                    Text(
                        text = subtitleDetail,
                        style = typography.bodySmall,
                        color = if (subtitleIsError) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (onSubtitleClick != null) {
                    // §9.3: `chevron_right` rotated to point down stands for ▾.
                    Icon(
                        painter = painterResource(SkeinIcons.Expand),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = SkeinSpacing.space2).size(SkeinSize.iconChip).rotate(90f),
                    )
                }
            }
        }
    }
}
