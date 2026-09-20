package app.skein.feature.shell.tabs

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Saver.save] needs a [SaverScope] receiver; this fake accepts everything, for pure-JVM tests. */
private val fakeSaverScope = SaverScope { true }

private fun <Original, Saveable : Any> Saver<Original, Saveable>.saveWith(
    scope: SaverScope,
    value: Original,
): Saveable? = with(scope) { save(value) }

private fun tab(
    id: String,
    docId: String = id,
    title: String = id,
    kind: TabKind = TabKind.NOTE,
    state: TabState = TabState.PREVIEW,
) = Tab(TabId(id), docId, title, kind, state)

/** `E6.I5` state-transition tests for [TabsState] in isolation from Compose UI. */
class TabsStateTest {
    @Test
    fun `opening a preview on an empty state adds one tab`() {
        val state = TabsState()

        state.openPreview(tab("a"))

        assertEquals(1, state.tabs.size)
    }

    @Test
    fun `opening a preview on an empty state activates it`() {
        val state = TabsState()

        state.openPreview(tab("a"))

        assertEquals(TabId("a"), state.activeId)
    }

    @Test
    fun `opening a second preview for a different doc replaces the first`() {
        val state = TabsState()
        state.openPreview(tab("a", docId = "doc-a"))

        state.openPreview(tab("b", docId = "doc-b"))

        assertEquals(1, state.tabs.size)
    }

    @Test
    fun `opening a second preview for a different doc activates the replacement`() {
        val state = TabsState()
        state.openPreview(tab("a", docId = "doc-a"))

        state.openPreview(tab("b", docId = "doc-b"))

        assertEquals(TabId("b"), state.activeId)
    }

    @Test
    fun `opening a preview for the same doc already previewed reuses the tab`() {
        val state = TabsState()
        state.openPreview(tab("a", docId = "doc-a"))

        state.openPreview(tab("a-again", docId = "doc-a"))

        assertEquals(TabId("a"), state.tabs.single().id)
    }

    @Test
    fun `pinning a preview tab leaves the tab count unchanged`() {
        val state = TabsState()
        state.openPreview(tab("a"))

        state.pin(TabId("a"))

        assertEquals(1, state.tabs.size)
    }

    @Test
    fun `pinning a preview tab marks it pinned`() {
        val state = TabsState()
        state.openPreview(tab("a"))

        state.pin(TabId("a"))

        assertTrue(state.tabs.single().pinned)
    }

    @Test
    fun `pinning the current preview then opening a new preview yields two tabs`() {
        val state = TabsState()
        state.openPreview(tab("a", docId = "doc-a"))
        state.pin(TabId("a"))

        state.openPreview(tab("b", docId = "doc-b"))

        assertEquals(2, state.tabs.size)
    }

    @Test
    fun `pinning the current preview then opening a new preview keeps the pinned tab`() {
        val state = TabsState()
        state.openPreview(tab("a", docId = "doc-a"))
        state.pin(TabId("a"))

        state.openPreview(tab("b", docId = "doc-b"))

        assertTrue(state.tabs.any { it.id == TabId("a") && it.pinned })
    }

    @Test
    fun `opening pinned for a doc that is already pinned reuses that tab instead of duplicating`() {
        val state = TabsState()
        state.openPinned(tab("a", docId = "doc-a"))

        state.openPinned(tab("a-again", docId = "doc-a"))

        assertEquals(1, state.tabs.size)
    }

    @Test
    fun `closing the active tab when it is the only tab clears the active id`() {
        val state = TabsState()
        state.openPreview(tab("a"))

        state.close(TabId("a"))

        assertNull(state.activeId)
    }

    @Test
    fun `closing the active tab when it is the only tab empties the tab list`() {
        val state = TabsState()
        state.openPreview(tab("a"))

        state.close(TabId("a"))

        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `closing the active tab activates a neighbor when other tabs remain`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))
        state.openPinned(tab("c"))
        state.activate(TabId("b"))

        state.close(TabId("b"))

        assertTrue(state.activeId == TabId("a") || state.activeId == TabId("c"))
    }

    @Test
    fun `closing an inactive tab does not change the active id`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))
        state.activate(TabId("a"))

        state.close(TabId("b"))

        assertEquals(TabId("a"), state.activeId)
    }

    @Test
    fun `closing others removes every tab except the one specified`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))
        state.openPinned(tab("c"))

        state.closeOthers(TabId("b"))

        assertEquals(listOf(TabId("b")), state.tabs.map { it.id })
    }

    @Test
    fun `activating a tab makes it the active tab`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))

        state.activate(TabId("a"))

        assertEquals(TabId("a"), state.activeId)
    }

    @Test
    fun `activating an unknown tab id is a no-op`() {
        val state = TabsState()
        state.openPinned(tab("a"))

        state.activate(TabId("does-not-exist"))

        assertEquals(TabId("a"), state.activeId)
    }

    @Test
    fun `recent dropdown lists the most recently activated tab first`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))
        state.openPinned(tab("c"))

        state.activate(TabId("a"))

        assertEquals(TabId("a"), state.recentDropdown().first().id)
    }

    @Test
    fun `recent dropdown includes every open tab`() {
        val state = TabsState()
        state.openPinned(tab("a"))
        state.openPinned(tab("b"))
        state.openPinned(tab("c"))

        val recent = state.recentDropdown()

        assertEquals(3, recent.size)
    }

    @Test
    fun `state survives a Saver round trip`() {
        val original = TabsState()
        original.openPinned(tab("a", docId = "doc-a", title = "Alpha", kind = TabKind.CHAT))
        original.openPreview(tab("b", docId = "doc-b", title = "Beta", kind = TabKind.ATTACHMENT))
        original.activate(TabId("a"))

        val saved = TabsState.Saver.saveWith(fakeSaverScope, original)
        val restored = TabsState.Saver.restore(saved!!)!!

        assertEquals(original.tabs, restored.tabs)
    }

    @Test
    fun `state survives a Saver round trip preserving the active id`() {
        val original = TabsState()
        original.openPinned(tab("a", docId = "doc-a", title = "Alpha", kind = TabKind.CHAT))
        original.openPreview(tab("b", docId = "doc-b", title = "Beta", kind = TabKind.ATTACHMENT))
        original.activate(TabId("a"))

        val saved = TabsState.Saver.saveWith(fakeSaverScope, original)
        val restored = TabsState.Saver.restore(saved!!)!!

        assertEquals(original.activeId, restored.activeId)
    }

    @Test
    fun `state survives a Saver round trip preserving recency order`() {
        val original = TabsState()
        original.openPinned(tab("a"))
        original.openPinned(tab("b"))
        original.openPinned(tab("c"))
        original.activate(TabId("a"))

        val saved = TabsState.Saver.saveWith(fakeSaverScope, original)
        val restored = TabsState.Saver.restore(saved!!)!!

        assertEquals(original.recentDropdown().map { it.id }, restored.recentDropdown().map { it.id })
    }
}
