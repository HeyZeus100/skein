// skein-xtov.24.8 (AL-09a): `:app` assembles the NavDisplay shell's entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.1: "features depend on keys, never on one
// another, and `:app` assembles the entry provider"). Each destination's
// entries live in its feature module; this file only wires the open vault
// into them and dispatches a key to its destination.
package app.skein.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.skein.feature.editor.entries.KnowledgePreparation
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
import app.skein.feature.shell.host.vaultSearch
import app.skein.models.ModelImportOutcome
import app.skein.models.ModelImportState
import app.skein.models.ModelServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The NavDisplay shell over [session]; [shell] is hoisted above the vault
 * gate (§8.8). [settingsViewModel] is `:app`'s own (Activity-scoped
 * `SecurityPrefs`/`AppearancePrefs`/biometric reauthentication for the
 * recovery-key export row) — built once by `MainActivity` and handed in
 * rather than rebuilt here so this file never needs a `FragmentActivity`.
 */
@Composable
internal fun NavShell(
    session: VaultSession,
    shell: SkeinShellState,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onNavigationReady: () -> Unit = {},
    knowledgePreparation: Flow<KnowledgePreparation> = flowOf(KnowledgePreparation()),
) {
    val personas = session.personaService
    val knowledge =
        remember(session, knowledgePreparation) {
            KnowledgeEntryDeps(
                session.repository,
                session.indexStore,
                personas.observeAll(),
                preparation = knowledgePreparation,
            )
        }
    val handoff = remember(session) { ChatHandoff() }
    val defaultSpaceId by produceState<String?>(null, personas) { value = personas.default().id }
    val history = rememberChatHistory(session.repository, shell)
    val models = session.models
    var modelVersion by remember(models) { mutableIntStateOf(0) }
    // App-owned imports and unlock adoption can finish while Chat remains visible.
    val hasModel by produceState(false, models, modelVersion, shell.nav.topLevel) {
        value = models?.registry?.default() != null
    }
    val chat =
        ChatEntryDeps(
            repository = session.repository,
            knowledge = knowledge,
            sendPipeline = models?.sendPipeline,
            hasModel = hasModel,
            handoff = handoff,
            importService = session.importService,
            history = history,
            turns = models?.turns,
            drafts = models?.drafts,
            defaultSpaceId = defaultSpaceId,
        )
    val graph = remember(session) { GraphEntryDeps(session.repository, session.indexStore) }
    val modelsDeps = rememberModelsEntryDeps(models, shell, modelVersion) { modelVersion++ }
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
        onNavigationReady = onNavigationReady,
        history = history,
        spaces = rememberSpaces(personas, shell),
        search = remember(session) { vaultSearch(session.repository) },
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
 * the old `/models` overlay used, unchanged (LC-27: a delete refusal is
 * shown, never swallowed). skein-xtov.24.23 (AL-09c): plus the retired
 * `/import model` — the same picker, one import at a time, import then set
 * default then refresh the manifest cache — with its progress and result in
 * the Models list instead of a row over the composer (IA §4). The import runs
 * in the app-owned foreground import coordinator; this composition only observes it.
 */
@Composable
private fun rememberModelsEntryDeps(
    models: ModelServices?,
    shell: SkeinShellState,
    version: Int,
    onModelsChanged: () -> Unit,
): ModelsEntryDeps {
    var localActionMessage by remember(models) { mutableStateOf<String?>(null) }
    val importStates =
        remember(models) {
            models?.imports?.state
                ?: MutableStateFlow<ModelImportState>(ModelImportState.Idle)
        }
    val importState by importStates.collectAsState()
    val importProgress = (importState as? ModelImportState.Running)?.fraction
    val scope = rememberCoroutineScope()
    // skein-gg11.18: a sealed copy whose registration was lost (the vault locked mid-import) is
    // registered again at unlock by ModelServices; refresh the list and say so, since the user never saw it land.
    LaunchedEffect(models) {
        val services = models ?: return@LaunchedEffect
        services.rescued.collect { ids ->
            if (ids.isNotEmpty()) {
                onModelsChanged()
                // A friendly name, never the raw model id (DESIGN_SYSTEM.md §11.5).
                val names =
                    ids.mapNotNull { id ->
                        services.registry
                            .get(id)
                            ?.model
                            ?.name
                    }
                if (names.isNotEmpty()) localActionMessage = "Registered ${names.joinToString()} from an earlier import"
            }
        }
    }
    // A success clears itself; a failure or a running import stays until dismissed.
    LaunchedEffect(localActionMessage) {
        val text = localActionMessage ?: return@LaunchedEffect
        if (TRANSIENT_PREFIXES.any(text::startsWith)) {
            delay(STATUS_AUTO_DISMISS_MILLIS)
            if (localActionMessage == text) localActionMessage = null
        }
    }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val services = models
            if (uri == null || services == null) return@rememberLauncherForActivityResult
            val imports = services.imports ?: return@rememberLauncherForActivityResult
            if (!imports.startImport(uri)) {
                localActionMessage =
                    if (imports.state.value is ModelImportState.Running) {
                        IMPORT_RUNNING
                    } else {
                        "Couldn't start the import. Try again while the app is open."
                    }
            }
        }
    LaunchedEffect(models, importState) {
        val result = importState as? ModelImportState.Done ?: return@LaunchedEffect
        onModelsChanged()
        if (result.outcome == ModelImportOutcome.IMPORTED) {
            delay(STATUS_AUTO_DISMISS_MILLIS)
            models?.imports?.dismissResult(result)
        }
    }
    // Current import state takes precedence over a replayed rescue lookup finishing later.
    val actionMessage =
        when (val state = importState) {
            ModelImportState.Idle -> localActionMessage
            is ModelImportState.Running ->
                state.fraction?.let { "Importing model… ${(it * 100).toInt()}%" }
                    ?: "Importing model…"
            is ModelImportState.Done ->
                when (state.outcome) {
                    ModelImportOutcome.IMPORTED -> "Imported model and set as default"
                    ModelImportOutcome.SAVED_FOR_UNLOCK -> "Model copied. Unlock to finish registration."
                    ModelImportOutcome.REFUSED -> "Couldn't import the model. Choose a different file and try again."
                    ModelImportOutcome.FAILED -> "The import stopped. Choose the file again to retry."
                }
        }
    // Re-read on every destination switch as well: an import can finish while Models is not showing.
    val items by
        produceState(initialValue = emptyList<ModelListItem>(), models, version, shell.nav.topLevel) {
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
    return ModelsEntryDeps(
        models = items,
        onSetDefault = { id ->
            scope.launch {
                models?.manager?.setDefault(id)
                onModelsChanged()
            }
        },
        onDelete = { id ->
            scope.launch {
                val name = items.firstOrNull { it.id == id }?.displayName ?: id
                if (models?.manager?.delete(id) is DeleteOutcome.Refused) {
                    localActionMessage = "Couldn't delete “$name”. It's in use right now. Try again in a moment."
                }
                models?.manifestCache?.refresh()
                onModelsChanged()
            }
        },
        actionMessage = actionMessage,
        onDismissActionMessage = {
            localActionMessage = null
            (importState as? ModelImportState.Done)?.let { models?.imports?.dismissResult(it) }
        },
        onImport =
            models?.let {
                {
                    // One import at a time: a second pick while one runs would race the same staging directory.
                    if (models.imports?.state?.value is ModelImportState.Running) {
                        localActionMessage = IMPORT_RUNNING
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                }
            },
        importProgress = importProgress,
    )
}

private const val IMPORT_RUNNING = "An import is already running — wait for it to finish"

/** The messages that clear themselves after [STATUS_AUTO_DISMISS_MILLIS]. */
private val TRANSIENT_PREFIXES = listOf("Imported ", "Registered ", IMPORT_RUNNING)

private const val STATUS_AUTO_DISMISS_MILLIS = 6_000L

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
