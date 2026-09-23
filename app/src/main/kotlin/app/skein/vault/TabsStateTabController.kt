// skein-whg8 — adapts `feature/shell`'s `SkeinApp.chatTabContent` slot
// (a plain `(docId, title, kind) -> TabId` callback closing over the real
// `TabsState`) to `feature/chat`'s own `TabController` seam. That module's
// own header explains why it invents this interface rather than depending
// on `:feature:shell`'s concrete `TabsState`; `:app` is the one place that
// can see both, so this is a one-line delegation, not a reimplementation.
package app.skein.vault

import app.skein.feature.chat.ChatTabKind
import app.skein.feature.chat.TabController
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind

/** [openPreview] mirrors `SkeinApp`'s `chatTabContent` slot's own callback signature exactly. */
class TabsStateTabController(
    private val openPreview: (docId: String, title: String, kind: TabKind) -> TabId,
) : TabController {
    override fun openPreview(
        docId: String,
        title: String,
        kind: ChatTabKind,
    ): String = openPreview(docId, title, kind.toShellKind()).value

    private fun ChatTabKind.toShellKind(): TabKind =
        when (this) {
            ChatTabKind.CHAT -> TabKind.CHAT
            ChatTabKind.NOTE -> TabKind.NOTE
            ChatTabKind.ATTACHMENT -> TabKind.ATTACHMENT
        }
}
