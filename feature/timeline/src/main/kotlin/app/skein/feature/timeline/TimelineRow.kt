package app.skein.feature.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinDropdownMenu
import app.skein.core.designsystem.components.rememberSkeinMenuAnchor
import app.skein.core.designsystem.components.skeinMenuAnchor
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind

/**
 * One timeline row (plan `E6.I7`): kind glyph, title, stripped body
 * preview, persona chip, relative time. A tap opens the entry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineRow(
    document: Document,
    personaName: String?,
    relativeTime: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<SkeinAction> = emptyList(),
) {
    var expanded by remember { mutableStateOf(false) }
    val preview = remember(document.bodyMd) { previewOf(document.bodyMd) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (menuActions.isEmpty()) null else ({ expanded = true }),
                    onLongClickLabel = if (menuActions.isEmpty()) null else "Show options",
                ).semantics {
                    customActions =
                        menuActions.map { action ->
                            CustomAccessibilityAction(action.accessibilityLabel) {
                                action.onClick()
                                true
                            }
                        }
                }.padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(TimelineTestTags.entryRow(document.id)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KindGlyph(kind = document.kind)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = document.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (personaName != null) {
                    PersonaChip(name = personaName)
                }
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (menuActions.isNotEmpty()) {
            val anchor = rememberSkeinMenuAnchor()
            Box(Modifier.skeinMenuAnchor(anchor)) {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(48.dp).testTag(TimelineTestTags.entryMenu(document.id)),
                ) {
                    Icon(
                        painterResource(SkeinIcons.More),
                        contentDescription = "Actions for ${document.title.ifBlank { "Untitled" }}",
                    )
                }
                SkeinDropdownMenu(expanded, { expanded = false }, anchorBounds = anchor.boundsInWindow) {
                    val (destructive, regular) = menuActions.partition { it.destructive }
                    (regular + destructive).forEachIndexed { index, action ->
                        if (index == regular.size && index > 0 && destructive.isNotEmpty()) HorizontalDivider()
                        val colors = MaterialTheme.colorScheme
                        val color = if (action.destructive) colors.error else colors.onSurface
                        DropdownMenuItem(
                            text = { Text(action.label, color = color) },
                            leadingIcon =
                                action.icon?.let { icon ->
                                    { Icon(painterResource(icon), null, tint = color) }
                                },
                            onClick = {
                                expanded = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Kind glyph in a small tinted square. The glyph itself is decorative:
 * semantics are replaced by [kindLabel] so screen readers announce "Note"
 * rather than an emoji, and so the rail (glyphs only) exposes no text.
 */
@Composable
internal fun KindGlyph(
    kind: DocumentKind,
    modifier: Modifier = Modifier,
) {
    val label = kindLabel(kind)
    Box(
        modifier =
            modifier
                .size(GLYPH_SIZE)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = kindGlyph(kind), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PersonaChip(name: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "◈ $name",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private val GLYPH_SIZE = 28.dp
