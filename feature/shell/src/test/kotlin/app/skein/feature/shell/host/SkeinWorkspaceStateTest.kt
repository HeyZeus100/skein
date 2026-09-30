package app.skein.feature.shell.host

import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.GraphKey
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NewNoteKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.ObjectKind
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

    private fun shell(
        owner: String,
        state: SkeinNavigationState = SkeinNavigationState.initial(),
    ) = SkeinShellState(state, emptyMap(), SessionEntryStores(manager), ownerKey = owner)

    @Test
    fun `duplicate selection activates existing owner without a second writer route`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.activate(WorkspacePane.SECONDARY)
        workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.stack(Destination.KNOWLEDGE))
        workspace.secondary.navigate { goTo(it, ChatKey(CHAT_A)) }
        assertEquals(
            ChatKey(CHAT_A),
            workspace.secondary.nav.currentStack
                .last(),
        )
        assertEquals(
            NoteKey(NOTE_B),
            workspace.primary.nav.currentStack
                .last(),
        )
    }

    @Test
    fun `restored duplicates are removed before any pane content can render`() {
        val duplicate =
            SkeinNavigationState.of(
                Destination.KNOWLEDGE,
                mapOf(
                    Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, NoteKey(NOTE_B)),
                ),
            )
        val workspace = SkeinWorkspaceState(shell("primary", duplicate), shell("secondary", duplicate))
        assertEquals(
            NoteKey(NOTE_B),
            workspace.primary.nav.currentStack
                .last(),
        )
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.currentStack)
    }

    @Test
    fun `inactive destination retains editor ownership and duplicate selection reveals it`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.primary.navigate { follow(it, ConnectionsKey(NOTE_B)) }
        workspace.primary.navigate { goTo(it, GraphKey()) }
        workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        assertEquals(Destination.KNOWLEDGE, workspace.primary.nav.topLevel)
        assertEquals(
            ConnectionsKey(NOTE_B),
            workspace.primary.nav.currentStack
                .last(),
        )
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.stack(Destination.KNOWLEDGE))
    }

    @Test
    fun `restoration removes editable collisions from inactive destination stacks`() {
        val primary = shell("primary")
        primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        val secondary = shell("secondary", primary.nav)
        primary.navigate { goTo(it, GraphKey()) }
        secondary.navigate { goTo(it, ChatKey(CHAT_A)) }
        val workspace = SkeinWorkspaceState(primary, secondary)
        assertEquals(Destination.GRAPH, workspace.primary.nav.topLevel)
        assertEquals(Destination.CHAT, workspace.secondary.nav.topLevel)
        assertEquals(listOf(KnowledgeHomeKey), workspace.secondary.nav.stack(Destination.KNOWLEDGE))
        assertEquals(
            NoteKey(NOTE_B),
            workspace.primary.nav
                .stack(Destination.KNOWLEDGE)
                .last(),
        )
    }

    @Test
    fun `inactive destination reserves explicit chat draft identity at runtime`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        workspace.primary.navigate { goTo(it, GraphKey()) }
        workspace.activate(WorkspacePane.SECONDARY)
        workspace.secondary.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        assertEquals(
            NewChatKey(DRAFT_D),
            workspace.primary.nav.currentStack
                .last(),
        )
        assertFalse(
            workspace.secondary.nav.stacks.values
                .flatten()
                .contains(NewChatKey(DRAFT_D)),
        )
    }

    @Test
    fun `same restored new chat identity becomes an independent secondary draft`() {
        val source = shell("primary")
        source.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        val workspace = SkeinWorkspaceState(source, shell("secondary", source.nav))
        assertNotEquals(
            workspace.primary.nav.currentStack
                .last(),
            workspace.secondary.nav.currentStack
                .last(),
        )
    }

    @Test
    fun `inactive restored chat drafts cannot alias another pane after a destination switch`() {
        val primary = shell("primary")
        primary.navigate { goTo(it, NewChatKey(DRAFT_D)) }
        val secondary = shell("secondary", primary.nav)
        primary.navigate { goTo(it, GraphKey()) }
        val workspace = SkeinWorkspaceState(primary, secondary)
        workspace.primary.navigate { switchTo(it, Destination.CHAT) }
        assertNotEquals(
            workspace.primary.nav.currentStack
                .last(),
            workspace.secondary.nav.currentStack
                .last(),
        )
        val secondaryDraft =
            workspace.secondary.nav.currentStack
                .last() as NewChatKey
        workspace.primary.navigate { goTo(it, secondaryDraft) }
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        assertEquals(
            NewChatKey(DRAFT_D),
            workspace.primary.nav.currentStack
                .last(),
        )
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

    @Test
    fun `committed deletion prunes both workspaces without selecting another object`() {
        val workspace = SkeinWorkspaceState(shell("primary"), shell("secondary"))
        workspace.primary.navigate { goTo(it, NoteKey(NOTE_B)) }
        workspace.primary.navigate { follow(it, ConnectionsKey(NOTE_B)) }
        workspace.secondary.navigate { goTo(it, GraphKey(NOTE_B)) }
        workspace.secondary.navigate { goTo(it, ChatKey(CHAT_A)) }
        workspace.activate(WorkspacePane.SECONDARY)
        val otherChat = workspace.secondary.nav.currentStack
        listOf(workspace.primary, workspace.secondary).forEach { it.pruneDocument(NOTE_B.value) }
        assertEquals(listOf(KnowledgeHomeKey), workspace.primary.nav.stack(Destination.KNOWLEDGE))
        assertEquals(listOf(GraphKey()), workspace.secondary.nav.stack(Destination.GRAPH))
        assertEquals(otherChat, workspace.secondary.nav.currentStack)
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
    }

    @Test
    fun `deleted saved new note is pruned while matching new chat draft stays`() {
        val owner = shell("primary")
        owner.navigate { goTo(it, NewChatKey(NOTE_B)) }
        owner.navigate { goTo(it, NewNoteKey(NOTE_B)) }
        owner.pruneDocument(NOTE_B.value)
        assertEquals(listOf(KnowledgeHomeKey), owner.nav.stack(Destination.KNOWLEDGE))
        assertTrue(owner.nav.stack(Destination.CHAT).contains(NewChatKey(NOTE_B)))
    }

    @Test
    fun `deletion removes imported raw ids without coercing them into saved uuid routes`() {
        val owner = shell("primary")
        owner.navigate { openDocument(it, "fixture-imported-note", ObjectKind.NOTE) }
        owner.pruneDocument("fixture-imported-note")
        assertEquals(listOf(KnowledgeHomeKey), owner.nav.stack(Destination.KNOWLEDGE))
    }
}
