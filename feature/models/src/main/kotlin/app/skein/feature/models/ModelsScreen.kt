package app.skein.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinDestructiveDialog
import app.skein.core.designsystem.components.SkeinEmptyState
import app.skein.core.designsystem.icons.SkeinIcons

public const val MODELS_EMPTY_TEST_TAG: String = "app.skein.feature.models.ModelsEmpty"
public const val MODELS_DEFAULT_MARKER: String = "default"

/** One model list row — everything `:app` already knows from `ModelRecord`/`Model`. */
public data class ModelListItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val licenseSpdx: String,
    val isDefault: Boolean,
    val isLoaded: Boolean,
)

@Composable
private fun ModelRow(
    model: ModelListItem,
    onSetDefault: (String) -> Unit,
    onDelete: () -> Unit,
    // The selected row backs the open detail pane; its tap opens those details.
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Decorative: the adjacent name already identifies this as a model.
            Icon(
                painter = painterResource(SkeinIcons.Model),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = model.displayName, style = MaterialTheme.typography.titleSmall)
                if (model.isDefault) {
                    Text(
                        text = MODELS_DEFAULT_MARKER,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "${humanSize(model.sizeBytes)} · ${model.licenseSpdx}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The adaptive list pane can be only 280 dp wide. Actions get their own
        // wrapping row so long filenames and larger fonts retain readable width.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!model.isDefault) {
                TextButton(onClick = { onSetDefault(model.id) }) { Text("Set default") }
            }
            Button(onClick = onDelete, enabled = !model.isLoaded) { Text("Delete") }
        }
    }
}

/**
 * LC-27 / `OBJECT_LIFECYCLE_SPEC.md` §9: name the model, say what is lost,
 * repeat the verb. skein-xtov.23.7 (DS7): now [SkeinDestructiveDialog] —
 * Cancel focused by default, TalkBack pane-title semantics, shadow-free.
 */
@Composable
private fun DeleteModelDialog(
    model: ModelListItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val consequence =
        "This frees ${humanSize(model.sizeBytes)}. To use it again, you'll need to import it again." +
            if (model.isDefault) " Chats will need another model." else ""
    SkeinDestructiveDialog(
        title = "Delete \u201C${model.displayName}\u201D?",
        consequence = consequence,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
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

// -----------------------------------------------------------------------------
// skein-xtov.24.9 (AL-09b): the Models destination's list│details panes, for
// `:feature:shell`'s `ModelsHomeKey`/`ModelDetailsKey` entries. Same
// [onSetDefault]/[onDelete] contract as the retired overlay — set
// default/delete/the delete confirmation dialog behave exactly as today,
// only the surrounding chrome (a bare pane instead of a dismissible overlay)
// differs.
// -----------------------------------------------------------------------------

public const val MODELS_LIST_PANE_TEST_TAG: String = "app.skein.feature.models.ModelsListPane"

/**
 * The list pane: no title row or Close button (the host's own top bar and
 * Back supply that now). [selectedId] highlights the row whose details are
 * open beside it (Expanded); tapping a row invokes [onSelect]. With no models,
 * the empty state offers [onImport] when there is one.
 */
@Composable
public fun ModelsListPane(
    models: List<ModelListItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<ModelListItem?>(null) }
    pendingDelete?.let { model ->
        DeleteModelDialog(
            model = model,
            onConfirm = {
                pendingDelete = null
                onDelete(model.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
    if (models.isEmpty()) {
        SkeinEmptyState(
            headline = "No models yet",
            body = "Import a model file to start chatting. It runs on this device.",
            primaryAction = onImport?.let { SkeinAction("Import model", SkeinIcons.ImportFile, onClick = it) },
            modifier = modifier.fillMaxSize().testTag(MODELS_EMPTY_TEST_TAG),
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize().testTag(MODELS_LIST_PANE_TEST_TAG)) {
            items(models, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    onSetDefault = onSetDefault,
                    onDelete = { pendingDelete = model },
                    selected = model.id == selectedId,
                    onClick = { onSelect(model.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** The detail pane for one selected model — the same fields [ModelRow] already shows, laid out full-width. */
@Composable
public fun ModelDetailsPane(
    model: ModelListItem?,
    onSetDefault: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        Box(modifier = modifier.fillMaxSize()) {
            Text(
                text = "This model was removed.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    var pendingDelete by remember { mutableStateOf(false) }
    if (pendingDelete) {
        DeleteModelDialog(
            model = model,
            onConfirm = {
                pendingDelete = false
                onDelete(model.id)
            },
            onDismiss = { pendingDelete = false },
        )
    }
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Icon(
            painter = painterResource(SkeinIcons.Model),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(text = model.displayName, style = MaterialTheme.typography.titleLarge)
        if (model.isDefault) {
            Text(
                text = MODELS_DEFAULT_MARKER.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${humanSize(model.sizeBytes)} · ${model.licenseSpdx}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.isLoaded) {
            Text(
                text = "Currently loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row {
            if (!model.isDefault) {
                TextButton(onClick = { onSetDefault(model.id) }) { Text("Set default") }
            }
            Button(onClick = { pendingDelete = true }, enabled = !model.isLoaded) { Text("Delete") }
        }
    }
}

public const val MODELS_EMPTY_DETAIL_TEST_TAG: String = "app.skein.feature.models.ModelsEmptyDetail"

/** `ModelsHomeKey`'s list-pane placeholder (spec §8.2) while no model is selected on Expanded. */
@Composable
public fun ModelsEmptyDetail(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().testTag(MODELS_EMPTY_DETAIL_TEST_TAG), contentAlignment = Alignment.Center) {
        Text(
            text = "Select a model to see its details",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
