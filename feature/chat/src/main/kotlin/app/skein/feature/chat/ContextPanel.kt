// skein-6as (E6.I8). Header toggle `⚹ context` (spec §8.4): the `Retrieved`
// list for the last turn, with scores and `recalledBy`; each row opens the
// source as a preview tab through `TabController`.
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
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved

public const val CONTEXT_PANEL_TEST_TAG: String = "app.skein.feature.chat.ContextPanel"

/** Test tag for the context-panel row rendering [retrieved]'s chunk. */
public fun contextRowTestTag(retrieved: Retrieved): String =
    "app.skein.feature.chat.ContextPanel.row.${retrieved.chunkId}"

/**
 * The `Retrieved` list for the last turn (spec §8.4), each row showing
 * [Retrieved.score] and [Retrieved.recalledBy], tapping it opens the source
 * document as a preview tab via [tabController].
 */
@Composable
public fun ContextPanel(
    items: List<Retrieved>,
    tabController: TabController,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(CONTEXT_PANEL_TEST_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "context", style = MaterialTheme.typography.labelLarge)
            if (items.isEmpty()) {
                Text(
                    text = "no retrieved context for this turn",
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
                            .clickable {
                                tabController.openPreview(
                                    retrieved.docId,
                                    retrieved.docTitle,
                                    ChatTabKind.NOTE,
                                )
                            }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(text = retrieved.docTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = recalledByLabel(retrieved.recalledBy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "score %.2f".format(retrieved.score),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun recalledByLabel(recalledBy: Set<RecallSource>): String =
    if (recalledBy.isEmpty()) "recalled by: —" else "recalled by: " + recalledBy.joinToString { it.name.lowercase() }
