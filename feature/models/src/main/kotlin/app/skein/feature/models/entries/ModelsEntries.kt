// skein-xtov.24.9 (AL-09b): the Models destination's NavDisplay entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.2, §12: list│details). [ModelsListPane]/
// [ModelDetailsPane]/[ModelsEmptyDetail] (this module's own `ModelsScreen.kt`)
// keep the exact set-default/delete/delete-confirmation behaviour the
// overlay had — only the chrome (the shell's own top bar and Back) and the
// split layout are new.
package app.skein.feature.models.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ModelDetailsKey
import app.skein.core.navigation.ModelsHomeKey
import app.skein.core.navigation.SkeinKey
import app.skein.feature.models.ModelDetailsPane
import app.skein.feature.models.ModelListItem
import app.skein.feature.models.ModelsEmptyDetail
import app.skein.feature.models.ModelsListPane
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.SkeinShellState

/**
 * What the Models entries need from the open vault's [app.skein.core.inference.models.ModelManager]
 * (`:app` owns the real registry — same "smallest adapter" contract [ModelListItem] already
 * documents). [actionMessage], when non-null, is LC-27's delete refusal — shown, never swallowed.
 */
class ModelsEntryDeps(
    val models: List<ModelListItem>,
    val onSetDefault: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val actionMessage: String? = null,
    val onDismissActionMessage: () -> Unit = {},
)

/** One Models key's content (§8.2). */
@Composable
fun ModelsEntry(
    key: SkeinKey,
    shell: SkeinShellState,
    deps: ModelsEntryDeps,
) {
    when (key) {
        ModelsHomeKey -> ModelsListEntry(shell, deps)
        is ModelDetailsKey -> ModelDetailsEntry(key, shell, deps)
        else -> Unit
    }
}

/** spec §8.2: `ModelsHomeKey`'s `listPane(detailPlaceholder = { ModelsEmptyDetail() })`. */
@Composable
fun ModelsDetailPlaceholder() {
    ModelsEmptyDetail()
}

@Composable
private fun ModelsListEntry(
    shell: SkeinShellState,
    deps: ModelsEntryDeps,
) {
    val selectedId = (shell.nav.stack(Destination.MODELS).lastOrNull() as? ModelDetailsKey)?.modelId?.value
    Column(Modifier.fillMaxSize()) {
        shell.EntryTopBar(ModelsHomeKey, "Models")
        deps.actionMessage?.let { message -> ActionMessageRow(message, deps.onDismissActionMessage) }
        ModelsListPane(
            models = deps.models,
            selectedId = selectedId,
            onSelect = { id -> shell.navigate { openModel(it, id) } },
            onSetDefault = deps.onSetDefault,
            onDelete = deps.onDelete,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

@Composable
private fun ModelDetailsEntry(
    key: ModelDetailsKey,
    shell: SkeinShellState,
    deps: ModelsEntryDeps,
) {
    val model = deps.models.firstOrNull { it.id == key.modelId.value }
    Column(Modifier.fillMaxSize()) {
        shell.EntryTopBar(key, model?.displayName ?: "Model")
        ModelDetailsPane(
            model = model,
            onSetDefault = deps.onSetDefault,
            onDelete = deps.onDelete,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

@Composable
private fun ActionMessageRow(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
