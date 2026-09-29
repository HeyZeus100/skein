package app.skein.feature.shell.host

import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.GraphKey
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinNavigationState
import app.skein.core.vault.session.UnlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinWorkspaceStateTest {
    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())

    private fun shell(owner: String, state: SkeinNavigationState = SkeinNavigationState.initial()) =
        SkeinShellState(state, emptyMap(), SessionEntryStores(manager), ownerKey = owner)

    @Test
    fun `duplicate selection activates existing owner without a second writer route`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.activate(WorkspacePane.SECONDARY)
        workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.stack(Destination.KNOWLEDGE))
        workspace.secondary.navigate { goTo(it, ChatKey(CHAT_A)) }
        assertEquals(ChatKey(CHAT_A), workspace.secondary.nav.currentStack.last())
        assertEquals(NoteKey(NOTE_B), workspace.primary.nav.currentStack.last())
    }

    @Test
    fun `restored duplicates are removed before any pane content can render`() {
        val duplicate = SkeinNavigationState.of(Destination.KNOWLEDGE, mapOf(Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, NoteKey(NOTE_B))))
        val workspace = SkeinWorkspaceState(shell("primary", duplicate), shell("secondary", duplicate))
        assertEquals(NoteKey(NOTE_B), workspace.primary.nav.currentStack.last())
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.currentStack)
    }

    @Test
    fun `returning to an inspector cannot reactivate an editor already open in the other owner`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.primary.navigate { follow(it, ConnectionsKey(NOTE_B)) }
        workspace.primary.navigate { goTo(it, GraphKey()) }
        workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.activate(WorkspacePane.PRIMARY)
        workspace.primary.navigate { switchTo(it, Destination.KNOWLEDGE) }
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        assertEquals(Destination.GRAPH, workspace.primary.nav.topLevel)
        assertEquals(NoteKey(NOTE_B), workspace.secondary.nav.currentStack.last())
    }

    @Test
    fun `same restored new chat identity becomes an independent secondary draft`() {
        val source = shell("primary")
        source.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        val workspace = SkeinWorkspaceState(source, shell("secondary", source.nav))
        assertNotEquals(workspace.primary.nav.currentStack.last(), workspace.secondary.nav.currentStack.last())
    }

    @Test
    fun `inactive restored chat drafts cannot alias another pane after a destination switch`() {
        val primary = shell("primary")
        primary.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        val secondary = shell("secondary", primary.nav)
        primary.navigate { goTo(it, GraphKey()) }
        val workspace = SkeinWorkspaceState(primary, secondary)
        workspace.primary.navigate { switchTo(it, Destination.CHAT) }
        assertNotEquals(workspace.primary.nav.currentStack.last(), workspace.secondary.nav.currentStack.last())
        val secondaryDraft = workspace.secondary.nav.currentStack.last() as NewChatKey
        workspace.primary.navigate { goTo(it, secondaryDraft) }
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        assertEquals(NewChatKey(DRAFT_D), workspace.primary.nav.currentStack.last())
    }

    @Test
    fun `swapping moves whole owners without changing routes or editable identities`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.secondary.navigate { goTo(it, ChatKey(CHAT_A)) }
        val primaryState = workspace.primary.nav
        val secondaryState = workspace.secondary.nav
        workspace.swapPanes()
        assertEquals(WorkspacePane.SECONDARY, workspace.paneAtPosition(0))
        assertEquals(WorkspacePane.PRIMARY, workspace.paneAtPosition(1))
        assertEquals(primaryState, workspace.primary.nav)
        assertEquals(secondaryState, workspace.secondary.nav)
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
    }

    @Test
    fun `space is shared but split sidebar and navigation choices are independent`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { switchSpace(it, CHAT_A) }
        assertEquals(CHAT_A, workspace.secondary.nav.space)
        workspace.primary.toggleList(Destination.CHAT)
        workspace.toggleSplit()
        assertTrue(workspace.splitRequested)
        assertFalse(workspace.primary.isListExpanded(Destination.CHAT))
        assertTrue(workspace.secondary.isListExpanded(Destination.CHAT))
        workspace.activate(WorkspacePane.SECONDARY)
        workspace.resetForNewVault()
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        assertFalse(workspace.splitRequested)
        assertEquals(SkeinNavigationState.initial(), workspace.primary.nav)
        assertEquals(SkeinNavigationState.initial(), workspace.secondary.nav)
    }
}
