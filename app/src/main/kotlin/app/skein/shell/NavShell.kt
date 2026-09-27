// skein-xtov.24.8 (AL-09a): `:app` assembles the NavDisplay shell's entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.1: "features depend on keys, never on one
// another, and `:app` assembles the entry provider"). Each destination's
// entries live in its feature module; this file only wires the open vault
// into them and dispatches a key to its destination.
package app.skein.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import app.skein.feature.shell.container.SkeinSpace
import app.skein.feature.shell.host.PlaceholderEntry
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.vault.VaultSession
import kotlinx.coroutines.flow.first

/** The NavDisplay shell over [session]; [shell] is hoisted above the vault gate (§8.8). */
@Composable
internal fun NavShell(
    session: VaultSession,
    shell: SkeinShellState,
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
                else -> PlaceholderEntry(null)
            }
        },
    ) { key ->
        when (key.destination) {
            Destination.CHAT -> ChatEntry(key, shell, chat)
            Destination.KNOWLEDGE -> KnowledgeEntry(key, shell, knowledge)
            else -> PlaceholderEntry(key)
        }
    }
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
