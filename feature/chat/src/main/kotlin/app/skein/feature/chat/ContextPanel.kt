// The Knowledge inspector lists only sources included in the assembled prompt.
package app.skein.feature.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.DocId
import app.skein.core.model.Retrieved
import app.skein.feature.shell.host.entryBottomPadding

public const val CONTEXT_PANEL_TEST_TAG: String = "app.skein.feature.chat.ContextPanel"
public const val KNOWLEDGE_SWITCH_TEST_TAG: String = "app.skein.feature.chat.SearchKnowledge"

/** A source row is keyed by its first included passage. */
public fun contextRowTestTag(retrieved: Retrieved): String =
    "app.skein.feature.chat.ContextPanel.row.${retrieved.chunkId}"

/**
 * [items] must come from the focused turn's AssembledPrompt.citations, never
 * from retrieval candidates. Counts and rows are grouped by document.
 * Changing [knowledgeEnabled] applies to the next send; it does not change
 * the record of sources already supplied for the displayed answer.
 */
@Composable
public fun ContextPanel(
    items: List<Retrieved>,
    onOpenSource: (DocId) -> Unit,
    modifier: Modifier = Modifier,
    knowledgeEnabled: Boolean? = null,
    onKnowledgeChange: (Boolean) -> Unit = {},
    knowledgeChangePending: Boolean = false,
    knowledgeChangeFailed: Boolean = false,
    hasTurn: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
) {
    val documents = items.groupBy { it.docId }.values.toList()
    Surface(
        modifier = modifier.testTag(CONTEXT_PANEL_TEST_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(scrollState)
                    .padding(entryBottomPadding())
                    .padding(SkeinSpacing.space12),
        ) {
            Text(text = "Knowledge", style = MaterialTheme.typography.titleSmall)
            if (knowledgeEnabled != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(KNOWLEDGE_SWITCH_TEST_TAG)
                            .toggleable(
                                value = knowledgeEnabled,
                                enabled = !knowledgeChangePending,
                                role = Role.Switch,
                                onValueChange = onKnowledgeChange,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space8),
                ) {
                    Text("Search Knowledge", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = knowledgeEnabled, onCheckedChange = null, enabled = !knowledgeChangePending)
                }
                Text(
                    "Searches your notes and files. Changes apply to your next message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (knowledgeChangeFailed) {
                    Text(
                        "Couldn't change Knowledge. Try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = SkeinSpacing.space8),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = SkeinSpacing.space12))
            }
            Text(text = "Used in this answer", style = MaterialTheme.typography.labelLarge)
            when {
                !hasTurn ->
                    Text(
                        "Sources appear here after a reply in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                documents.isEmpty() ->
                    Text(
                        "No notes were used for this answer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    Text(
                        if (documents.size == 1) "1 source" else "${documents.size} sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            documents.forEachIndexed { index, passages ->
                val first = passages.first()
                if (index > 0) HorizontalDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(contextRowTestTag(first))
                            .clickable(role = Role.Button) { onOpenSource(first.docId) }
                            .padding(vertical = SkeinSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = first.docTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (passages.size == 1) "1 passage" else "${passages.size} passages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
