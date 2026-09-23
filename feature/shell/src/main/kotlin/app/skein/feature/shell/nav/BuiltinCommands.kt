package app.skein.feature.shell.nav

import app.skein.feature.shell.tabs.Tab
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabsState
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.VaultRepository
import java.util.UUID

/**
 * `/new note [title]` (spec §8.2, plan `E6.I4` slice A / bd `skein-ps0`,
 * coordinator note 2026-09-22): creates a NOTE document via
 * [VaultRepository.createDocument] and opens it **pinned** — the same
 * `TabsState.openPinned` path `SkeinApp.onTimelineEntryPin` already uses
 * for a graph-node long-press. A blank/whitespace-only title falls back to
 * `"Untitled"`. The created document then shows up in the timeline "via
 * the existing `observeTimeline` flow without extra work" (the coordinator
 * note's own phrasing) because [vaultRepository] here is the *same*
 * instance the timeline observes — creating through it is enough to make
 * the change bus (`VaultRepositoryImpl.changeBus`) re-emit.
 *
 * Registered by [SkeinApp] under [CommandScope.GLOBAL] whenever a
 * [VaultRepository] is wired in; see that file's own `LaunchedEffect`.
 */
fun newNoteCommand(
    vaultRepository: VaultRepository,
    personaId: PersonaId?,
    tabsState: TabsState,
): Command =
    Command(
        keyword = "new note",
        hint = "[title] — create a note and open it pinned",
    ) { arg ->
        val title = arg.trim().ifBlank { "Untitled" }
        val document =
            vaultRepository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = "", personaId = personaId),
            )
        tabsState.openPinned(Tab(TabId(UUID.randomUUID().toString()), document.id, document.title, TabKind.NOTE))
    }
