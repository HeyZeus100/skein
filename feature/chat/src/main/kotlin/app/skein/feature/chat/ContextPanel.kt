// skein-6as (E6.I8). The context inspector's Knowledge section (spec §8.4,
// CHAT_UX_SPEC.md §17): the `Retrieved` list for the last turn; each row opens
// its source.
package app.skein.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocId
import app.skein.core.model.Retrieved

public const val CONTEXT_PANEL_TEST_TAG: String = "app.skein.feature.chat.ContextPanel"

/** Test tag for the context-panel row rendering [retrieved]'s chunk. */
public fun contextRowTestTag(retrieved: Retrieved): String =
    "app.skein.feature.chat.ContextPanel.row.${retrieved.chunkId}"

/**
 * The `Retrieved` list for the last turn (spec §8.4); tapping a row opens its
 * source document through [onOpenSource].
 */
@Composable
public fun ContextPanel(
    items: List<Retrieved>,
    onOpenSource: (DocId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(CONTEXT_PANEL_TEST_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "Sources", style = MaterialTheme.typography.labelLarge)
            if (items.isEmpty()) {
                Text(
                    text = "No sources for this turn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEachIndexed { index, retrieved ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(contextRowTestTag(retrieved))
                            .clickable { onOpenSource(retrieved.docId) }
                            .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(text = retrieved.docTitle, style = MaterialTheme.typography.bodyMedium)
                        // IA §3.1 / DESIGN_SYSTEM.md §11.3: "score 0.83",
                        // "recalled by: vector" are never shown — a raw
                        // relevance score and retrieval-method breakdown are
                        // implementation detail, not product language, at
                        // any level. [Retrieved.recalledBy] still drives
                        // which passages are listed at all.
                        Text(
                            text = "Relevant passage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
