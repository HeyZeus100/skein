// skein-6as (E6.I8). `feature/shell`'s `TabsState` (skein-4gf, spec §8.3) has
// no interface a Composable can be written against — it's a concrete
// `@Stable` class taking a `Tab` value object, not `(docId, title, kind)` —
// and this bead's hard boundary forbids touching `:feature:shell`. Per the
// bead's own instructions ("If a shell/editor API you need is missing,
// define a small interface in feature/chat and note it for the wiring
// bead"), this file is that interface: the citation-chip / context-panel
// row "open as a preview tab" gesture, reduced to what `ChatViewModel`
// actually needs.
//
// The wiring bead (`skein-whg8`) adapts the real `TabsState` to this
// interface (`TabsState.openPreview` takes a `Tab`; the adapter builds one
// from `docId`/`title`/`kind` and calls through). `app.skein.testing`'s
// `RecordingTabController` already exists with the same shape (mirroring
// `TabsState`'s public surface) for exactly this reason — see its own file
// header — so a test-time adapter is a few lines of delegation, not a
// reimplementation (see `ChatScreenTest`'s `RecordingTabControllerAdapter`).
package app.skein.feature.chat

import app.skein.core.model.DocId

/** The tab content kinds spec §8.3 names — mirrors `feature/shell`'s `TabKind`/`app.skein.testing.RecordedTabKind`. */
public enum class ChatTabKind { CHAT, NOTE, ATTACHMENT }

/**
 * The one tab gesture `ChatViewModel` needs: open a document as a preview
 * tab (citation chip tap, context-panel row tap — spec §8.4).
 */
public fun interface TabController {
    /**
     * Opens [docId] (titled [title]) as a preview tab, replacing any
     * existing preview — spec §8.3. A SAM (`fun`) interface's single
     * abstract method cannot carry a default parameter value, so every
     * caller in this module passes [kind] explicitly — see
     * [ChatTabKind.NOTE] for the common "just open the doc" case.
     */
    public fun openPreview(
        docId: DocId,
        title: String,
        kind: ChatTabKind,
    ): String
}
