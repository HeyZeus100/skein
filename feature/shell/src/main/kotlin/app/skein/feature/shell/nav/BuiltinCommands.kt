package app.skein.feature.shell.nav

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.tabs.Tab
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabsState
import java.util.UUID

/**
 * `/chat` (skein-whg8): opens a fresh chat document (`DocumentKind.CHAT`)
 * and shows it **pinned**, the same `TabsState.openPinned` path
 * [newNoteCommand] uses. No `:feature:chat` dependency is needed here — the
 * tab only needs a [TabKind.CHAT] entry and a `docId` the real screen (wired
 * by `:app` into `SkeinApp`'s `chatTabContent` slot) can render; opening the
 * tab and rendering its content are deliberately two different seams (spec
 * §8.3 vs §8.4).
 */
fun chatCommand(
    vaultRepository: VaultRepository,
    personaId: PersonaId?,
    tabsState: TabsState,
): Command =
    Command(
        keyword = "chat",
        hint = "— start a new chat and open it pinned",
    ) {
        val document =
            vaultRepository.createDocument(
                NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = "", personaId = personaId),
            )
        tabsState.openPinned(Tab(TabId(UUID.randomUUID().toString()), document.id, document.title, TabKind.CHAT))
    }

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
