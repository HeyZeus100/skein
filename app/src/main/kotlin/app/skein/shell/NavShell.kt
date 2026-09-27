// skein-xtov.24.8 (AL-09a): `:app` assembles the NavDisplay shell's entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.1: "features depend on keys, never on one
// another, and `:app` assembles the entry provider"). Each destination's
// entries live in its feature module; this file only wires the open vault
// into them and dispatches a key to its destination.
package app.skein.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.skein.BuildConfig
import app.skein.core.inference.models.DeleteOutcome
import app.skein.core.model.PersonaService
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.destination
import app.skein.feature.chat.entries.ChatDetailPlaceholder
import app.skein.feature.chat.entries.ChatEntry
import app.skein.feature.chat.entries.ChatEntryDeps
import app.skein.feature.chat.entries.ChatHandoff
import app.skein.feature.chat.entries.rememberChatHistory
import app.skein.feature.editor.entries.KnowledgeDetailPlaceholder
import app.skein.feature.editor.entries.KnowledgeEntry
import app.skein.feature.editor.entries.KnowledgeEntryDeps
import app.skein.feature.graph.entries.GraphEntry
import app.skein.feature.graph.entries.GraphEntryDeps
import app.skein.feature.models.ModelListItem
import app.skein.feature.models.entries.ModelsDetailPlaceholder
import app.skein.feature.models.entries.ModelsEntry
import app.skein.feature.models.entries.ModelsEntryDeps
import app.skein.feature.settings.SettingsViewModel
import app.skein.feature.settings.entries.SettingsDetailPlaceholder
import app.skein.feature.settings.entries.SettingsEntry
import app.skein.feature.settings.entries.SettingsEntryDeps
import app.skein.feature.shell.container.SkeinSpace
import app.skein.feature.shell.host.PlaceholderEntry
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.models.ModelServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The NavDisplay shell over [session]; [shell] is hoisted above the vault
 * gate (§8.8). [settingsViewModel] is `:app`'s own (Activity-scoped
 * `SecurityPrefs`/`AppearancePrefs`/biometric reauthentication for the
 * recovery-key export row) — built once by `MainActivity`, same as
 * `UnlockedShell`'s copy, and handed in rather than rebuilt here so this file
 * never needs a `FragmentActivity`.
 */
@Composable
internal fun NavShell(
    session: VaultSession,
    shell: SkeinShellState,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val personas = session.personaService
    val knowledge =
        remember(session) { KnowledgeEntryDeps(session.repository, session.indexStore, personas.observeAll()) }
    val handoff = remember(session) { ChatHandoff() }
    val history = rememberChatHistory(session.repository, shell)
    val models = session.models
    // ponytail: re-read on every destination switch (the Models destination is where a model is added);
    // a registry change flow would make it live.
    val hasModel by produceState(false, models, shell.nav.topLevel) { value = models?.registry?.default() != null }
    val chat =
        ChatEntryDeps(
            repository = session.repository,
            knowledge = knowledge,
            sendPipeline = models?.sendPipeline,
            hasModel = hasModel,
            handoff = handoff,
            importService = session.importService,
            history = history,
        )
    val graph = remember(session) { GraphEntryDeps(session.repository, session.indexStore) }
    val modelsDeps = rememberModelsEntryDeps(models)
    val settings =
        remember(settingsViewModel) {
            SettingsEntryDeps(settingsViewModel, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
    val kinds =
        remember(session) { navKindsOf(session.repository) { personas.observeAll().first().map { it.id } } }
    SkeinShellHost(
        shell = shell,
        resolveKinds = kinds,
        modifier = modifier,
        history = history,
        spaces = rememberSpaces(personas, shell),
        detailPlaceholder = { destination ->
            when (destination) {
                Destination.CHAT -> ChatDetailPlaceholder(shell, chat)
                Destination.KNOWLEDGE -> KnowledgeDetailPlaceholder(shell)
                Destination.MODELS -> ModelsDetailPlaceholder()
                Destination.SETTINGS -> SettingsDetailPlaceholder(settings)
                else -> PlaceholderEntry(null)
            }
        },
    ) { key ->
        when (key.destination) {
            Destination.CHAT -> ChatEntry(key, shell, chat)
            Destination.KNOWLEDGE -> KnowledgeEntry(key, shell, knowledge)
            Destination.GRAPH -> GraphEntry(key, shell, graph)
            Destination.MODELS -> ModelsEntry(key, shell, modelsDeps)
            Destination.SETTINGS -> SettingsEntry(key, shell, settings)
            else -> PlaceholderEntry(key)
        }
    }
}

/**
 * skein-xtov.24.9 (AL-09b): the live registry snapshot plus set-default/
 * delete — the exact [app.skein.core.inference.models.ModelManager] calls
 * `UnlockedShell`'s `/models` overlay used, unchanged (LC-27: a delete
 * refusal is shown, never swallowed).
 */
@Composable
private fun rememberModelsEntryDeps(models: ModelServices?): ModelsEntryDeps {
    var version by remember { mutableIntStateOf(0) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(models) {
        models?.rescued?.collect { ids -> if (ids.isNotEmpty()) version++ }
    }
    val items by
        produceState(initialValue = emptyList<ModelListItem>(), models, version) {
            value =
                models?.let { services ->
                    val defaultId = services.registry.default()
                    services.registry.list().map { record ->
                        ModelListItem(
                            id = record.model.id,
                            displayName = record.model.name,
                            sizeBytes = record.model.sizeBytes,
                            licenseSpdx = record.licenseSpdx ?: "UNKNOWN",
                            isDefault = record.model.id == defaultId,
                            isLoaded = services.isLoaded(record.model.id),
                        )
                    }
                } ?: emptyList()
        }
    val scope = rememberCoroutineScope()
    return ModelsEntryDeps(
        models = items,
        onSetDefault = { id ->
            scope.launch {
                models?.manager?.setDefault(id)
                version++
            }
        },
        onDelete = { id ->
            scope.launch {
                val name = items.firstOrNull { it.id == id }?.displayName ?: id
                if (models?.manager?.delete(id) is DeleteOutcome.Refused) {
                    actionMessage = "Couldn't delete “$name”. It's in use right now. Try again in a moment."
                }
                models?.manifestCache?.refresh()
                version++
            }
        },
        actionMessage = actionMessage,
        onDismissActionMessage = { actionMessage = null },
    )
}

/**
 * The Space switcher's rows (IA §8b): every persona, the current Space selected
 * (no id in the state is the default Space). The switcher shows only from two.
 */
@Composable
private fun rememberSpaces(
    personas: PersonaService,
    shell: SkeinShellState,
): List<SkeinSpace> {
    val all by remember(personas) { personas.observeAll() }.collectAsState(emptyList())
    val defaultId by produceState<String?>(null, personas, all) { value = personas.default().id }
    val current = shell.nav.space?.value ?: defaultId
    return all.map { persona ->
        SkeinSpace(
            id = persona.id,
            name = persona.name,
            isSelected = persona.id == current,
            onSelect = {
                val space = if (persona.id == defaultId) null else SkeinId.parse(persona.id)
                shell.navigate { switchSpace(it, space) }
            },
        )
    }
}
