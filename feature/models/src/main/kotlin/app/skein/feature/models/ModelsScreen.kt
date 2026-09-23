// skein-whg8: the minimal `/models` list — skein-ym3 owns the full screen
// later (management, import flow, per-model detail). This module takes only
// plain data and function types (no `:core:inference`/`:core:vault` project
// dependency), the same "smallest adapter" shape `feature/chat`'s
// `TabController` follows for its own out-of-scope seam: `:app` is the one
// place that can see both this module and the real `ModelManager`/
// `ModelRegistry`, so it supplies the list and the callbacks.
package app.skein.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

public const val MODELS_SCREEN_TEST_TAG: String = "app.skein.feature.models.ModelsScreen"
public const val MODELS_EMPTY_TEST_TAG: String = "app.skein.feature.models.ModelsEmpty"
public const val MODELS_DEFAULT_MARKER: String = "default"

/** One row `ModelsScreen` renders — everything `:app` already knows from `ModelRecord`/`Model`. */
public data class ModelListItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val licenseSpdx: String,
    val isDefault: Boolean,
    val isLoaded: Boolean,
)

/**
 * A minimal list: display name, size, licence SPDX, the default marker, a
 * delete action (disabled while [ModelListItem.isLoaded]) and a set-default
 * action. skein-ym3 replaces this with the full management screen.
 */
@Composable
public fun ModelsScreen(
    models: List<ModelListItem>,
    onSetDefault: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(MODELS_SCREEN_TEST_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Models", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (models.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().testTag(MODELS_EMPTY_TEST_TAG)) {
                    Text(
                        text = "No models imported yet — use /import model",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(models, key = { it.id }) { model ->
                        ModelRow(model = model, onSetDefault = onSetDefault, onDelete = onDelete)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelListItem,
    onSetDefault: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(text = model.displayName, style = MaterialTheme.typography.titleSmall)
                if (model.isDefault) {
                    Text(
                        text = " · $MODELS_DEFAULT_MARKER",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "${humanSize(model.sizeBytes)} · ${model.licenseSpdx}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!model.isDefault) {
            TextButton(onClick = { onSetDefault(model.id) }) { Text("Set default") }
        }
        Button(onClick = { onDelete(model.id) }, enabled = !model.isLoaded) { Text("Delete") }
    }
}

private fun humanSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}
