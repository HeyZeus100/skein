package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Spec §8.3 rules 1–5 and 8: open by kind, go to vs follow, de-duplication, new
 * chat, destination switching, draft promotion, and the LC-20 prune on delete.
 */
class NavigatorTest {
    private val nav = Fx.navigator()
    private val start = SkeinNavigationState.initial()

    // --- Open by kind (IA §2 principle 2; LC-20) ---

    @Test
    fun `openDocument dispatches on kind to the one screen for it, in its home destination`() {
        val chat = nav.openDocument(start, Fx.chat1.value, ObjectKind.CHAT)
        assertThat(chat.topLevel).isEqualTo(Destination.CHAT)
        assertThat(chat.currentStack).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()

        val note = nav.openDocument(chat, Fx.note1.value, ObjectKind.NOTE, anchor = 40)
        assertThat(note.topLevel).isEqualTo(Destination.KNOWLEDGE)
        assertThat(note.currentStack).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note1, 40)).inOrder()

        val file = nav.openDocument(note, Fx.file1.value, ObjectKind.FILE)
        assertThat(file.currentStack).containsExactly(KnowledgeHomeKey, FileKey(Fx.file1)).inOrder()
        // Go to never disturbs another destination's stack.
        assertThat(file.stack(Destination.CHAT)).isEqualTo(chat.stack(Destination.CHAT))
    }

    @Test
    fun `a negative anchor is ignored, not trusted`() {
        val state = nav.openDocument(start, Fx.note1.value, ObjectKind.NOTE, anchor = -3)
        assertThat(state.currentStack.last()).isEqualTo(NoteKey(Fx.note1))
        assertThat(
            nav.openSource(start, Fx.chat1, Fx.note1.value, -1).currentStack.last(),
        ).isEqualTo(ChatSourceKey(Fx.chat1, Fx.note1))
    }

    @Test
    fun `openDocument refuses a kind that is not a document, without echoing the id`() {
        for (kind in listOf(ObjectKind.MESSAGE, ObjectKind.MODEL, ObjectKind.SPACE)) {
            val error = runCatching { nav.openDocument(start, Fx.model1.value, kind) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error!!.message).doesNotContain(Fx.model1.value)
        }
    }

    @Test
    fun `models go to Models or follow from the Model sheet`() {
        val goTo = nav.openModel(start, Fx.model1.value)
        assertThat(goTo.topLevel).isEqualTo(Destination.MODELS)
        assertThat(goTo.currentStack).containsExactly(ModelsHomeKey, ModelDetailsKey(Fx.model1)).inOrder()

        val inChat = nav.goTo(start, ChatKey(Fx.chat1))
        val followed = nav.openModel(inChat, Fx.model1.value, OpenMode.FOLLOW)
        assertThat(followed.topLevel).isEqualTo(Destination.CHAT)
        assertThat(
            followed.currentStack,
        ).containsExactly(ChatHomeKey, ChatKey(Fx.chat1), ModelDetailsKey(Fx.model1)).inOrder()
    }

    // --- Go to vs follow (rules 1–3) ---

    @Test
    fun `go to makes the object the detail of its home stack, replacing what was open there`() {
        val state = nav.goTo(Fx.richState().copy(topLevel = Destination.CHAT), NoteKey(Fx.note2))
        assertThat(state.topLevel).isEqualTo(Destination.KNOWLEDGE)
        assertThat(state.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note2)).inOrder()
        assertThat(state.stack(Destination.CHAT)).isEqualTo(Fx.richState().stack(Destination.CHAT))
    }

    @Test
    fun `follow pushes onto the current stack, whatever the key's home, and Back returns to the same place`() {
        val inChat = nav.goTo(start, ChatKey(Fx.chat1))
        val followed = nav.follow(inChat, NoteKey(Fx.note1))
        assertThat(followed.topLevel).isEqualTo(Destination.CHAT)
        assertThat(followed.currentStack).containsExactly(ChatHomeKey, ChatKey(Fx.chat1), NoteKey(Fx.note1)).inOrder()
        assertThat(followed.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey)
        assertThat(nav.back(followed, NavMode.DUAL)).isEqualTo(inChat)
    }

    @Test
    fun `citations, the inspector and Connections are followed panes`() {
        var state = nav.goTo(start, ChatKey(Fx.chat1))
        state = nav.follow(state, ChatContextKey(Fx.chat1))
        state = nav.openSource(state, Fx.chat1, Fx.note1.value, 812)
        assertThat(state.currentStack)
            .containsExactly(
                ChatHomeKey,
                ChatKey(Fx.chat1),
                ChatContextKey(Fx.chat1),
                ChatSourceKey(Fx.chat1, Fx.note1, 812),
            ).inOrder()

        var knowledge = nav.goTo(state, NoteKey(Fx.note1))
        knowledge = nav.openConnections(knowledge, Fx.note1.value)
        assertThat(
            knowledge.currentStack,
        ).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note1), ConnectionsKey(Fx.note1)).inOrder()
    }

    @Test
    fun `following a root is a go to`() {
        val state = nav.follow(nav.goTo(start, ChatKey(Fx.chat1)), GraphKey(Fx.note1))
        assertThat(state.topLevel).isEqualTo(Destination.GRAPH)
        assertThat(state.stack(Destination.CHAT)).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
        assertThat(state.currentStack).containsExactly(GraphKey(Fx.note1))
    }

    @Test
    fun `graph - open in graph and centre here replace the root and close the node detail`() {
        var state = nav.openGraph(start, Fx.note1.value)
        state = nav.selectGraphNode(state, Fx.note2.value)
        state = nav.openGraph(state, Fx.note2.value)
        assertThat(state.currentStack).containsExactly(GraphKey(Fx.note2))
        assertThat(nav.openGraph(state).currentStack).containsExactly(GraphKey())
        // ponytail limit: a foreign focus centres on the default rather than failing.
        assertThat(nav.openGraph(state, Fx.FOREIGN_NOTE).currentStack).containsExactly(GraphKey())
    }

    @Test
    fun `graph - Open on a selected node follows the note onto the Graph stack`() {
        var state = nav.selectGraphNode(nav.openGraph(start, Fx.note1.value), Fx.note2.value)
        state = nav.openDocument(state, Fx.note2.value, ObjectKind.NOTE, OpenMode.FOLLOW)
        assertThat(
            state.currentStack,
        ).containsExactly(GraphKey(Fx.note1), GraphNodeKey(Fx.note1, Fx.note2), NoteKey(Fx.note2)).inOrder()
    }

    // --- De-duplication (rule 4) ---

    @Test
    fun `following a key already in the stack pops back to it instead of pushing a copy`() {
        var state = nav.goTo(start, NoteKey(Fx.note1))
        state = nav.follow(state, NoteKey(Fx.note2))
        state = nav.follow(state, ConnectionsKey(Fx.note2))
        state = nav.follow(state, NoteKey(Fx.note1, 99))
        assertThat(state.currentStack).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note1, 99)).inOrder()
    }

    @Test
    fun `changing the inspector's focus replaces it in place`() {
        var state = nav.follow(nav.goTo(start, ChatKey(Fx.chat1)), ChatContextKey(Fx.chat1))
        state = nav.follow(state, ChatContextKey(Fx.chat1, Fx.msg1))
        assertThat(
            state.currentStack,
        ).containsExactly(ChatHomeKey, ChatKey(Fx.chat1), ChatContextKey(Fx.chat1, Fx.msg1)).inOrder()
    }

    @Test
    fun `Test B - going to the open chat again never duplicates it`() {
        var state = nav.goTo(start, ChatKey(Fx.chat1))
        state = nav.follow(state, ChatContextKey(Fx.chat1))
        state = nav.goTo(state, ChatKey(Fx.chat1))
        state = nav.openDocument(state, Fx.chat1.value, ObjectKind.CHAT)
        assertThat(state.currentStack).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
    }

    // --- New chat and destinations (rule 5) ---

    @Test
    fun `new chat pops Chat to its root and pushes the one draft, from anywhere`() {
        var state = nav.follow(nav.goTo(Fx.richState(), ChatKey(Fx.chat1)), ChatContextKey(Fx.chat1))
        state = nav.switchTo(state, Destination.GRAPH)
        state = nav.goTo(state, NewChatKey(Fx.draft1))
        assertThat(state.topLevel).isEqualTo(Destination.CHAT)
        assertThat(state.currentStack).containsExactly(ChatHomeKey, NewChatKey(Fx.draft1)).inOrder()
        state = nav.goTo(state, NewChatKey(Fx.draft2))
        assertThat(state.currentStack).containsExactly(ChatHomeKey, NewChatKey(Fx.draft2)).inOrder()
        // Following into the landing keeps a single draft too.
        state = nav.follow(nav.follow(state, ModelDetailsKey(Fx.model1)), NewChatKey(Fx.draft1))
        assertThat(state.currentStack).containsExactly(ChatHomeKey, NewChatKey(Fx.draft1)).inOrder()
    }

    @Test
    fun `switching destinations keeps every stack, and re-selecting the current one pops it to its root`() {
        val rich = Fx.richState()
        val switched = nav.switchTo(rich, Destination.GRAPH)
        assertThat(switched.topLevel).isEqualTo(Destination.GRAPH)
        assertThat(switched.stacks).isEqualTo(rich.stacks)

        val reselected = nav.switchTo(switched, Destination.GRAPH)
        assertThat(reselected.currentStack).containsExactly(GraphKey(Fx.note1))
        assertThat(reselected.stack(Destination.KNOWLEDGE)).isEqualTo(rich.stack(Destination.KNOWLEDGE))
    }

    @Test
    fun `the first send or commit promotes the draft key in place`() {
        var state = nav.goTo(start, NewChatKey(Fx.draft1))
        state = nav.promoteDraft(state, Fx.draft1, ChatKey(Fx.chat2))
        assertThat(state.currentStack).containsExactly(ChatHomeKey, ChatKey(Fx.chat2)).inOrder()

        state = nav.goTo(state, NewNoteKey(Fx.draft2))
        state = nav.follow(state, ConnectionsKey(Fx.note1))
        state = nav.promoteDraft(state, Fx.draft2, NoteKey(Fx.draft2))
        assertThat(
            state.currentStack,
        ).containsExactly(KnowledgeHomeKey, NoteKey(Fx.draft2), ConnectionsKey(Fx.note1)).inOrder()
        assertThat(nav.promoteDraft(state, Fx.draft1, ChatKey(Fx.chat1))).isEqualTo(state)
    }

    @Test
    fun `the current Space is navigation state`() {
        val state = nav.switchSpace(Fx.richState(), Fx.draft1)
        assertThat(state.space).isEqualTo(Fx.draft1)
        assertThat(state.stacks).isEqualTo(Fx.richState().stacks)
        assertThat(nav.switchSpace(state, null).space).isNull()
    }

    // --- Prune on delete (rule 8; OBJECT_LIFECYCLE_SPEC.md §3.5, §4.7, §6.7; LC-20) ---

    @Test
    fun `every entry naming a deleted id is pruned from every stack, pane entries included`() {
        var state = Fx.richState()
        state = nav.follow(nav.switchTo(state, Destination.MODELS), NoteKey(Fx.note1))
        state = nav.goTo(state, NewNoteKey(Fx.draft2))

        val pruned = nav.prune(state, Fx.note1.value)

        // Chat: the source citing note1 goes; the chat and its inspector stay.
        assertThat(pruned.stack(Destination.CHAT))
            .containsExactly(ChatHomeKey, ChatKey(Fx.chat1), ChatContextKey(Fx.chat1, Fx.msg1))
            .inOrder()
        // Graph: centred on note1, so it re-centres on the default and the node detail under that
        // focus closes; the other note, followed from it, stays.
        assertThat(pruned.stack(Destination.GRAPH)).containsExactly(GraphKey(), NoteKey(Fx.note2)).inOrder()
        // Models: the note followed from Models goes.
        assertThat(
            pruned.stack(Destination.MODELS),
        ).containsExactly(ModelsHomeKey, ModelDetailsKey(Fx.model1)).inOrder()
        assertThat(nav.referencedIds(pruned)).doesNotContain(Fx.note1)
        // The top level is kept.
        assertThat(pruned.topLevel).isEqualTo(state.topLevel)
    }

    @Test
    fun `deleting the open chat leaves the Chat root and never opens another chat`() {
        var state = nav.goTo(start, ChatKey(Fx.chat2))
        state = nav.goTo(state, ChatKey(Fx.chat1))
        state = nav.follow(state, ChatContextKey(Fx.chat1))
        state = nav.openSource(state, Fx.chat1, Fx.note1.value)
        val pruned = nav.prune(state, Fx.chat1.value)
        assertThat(pruned.topLevel).isEqualTo(Destination.CHAT)
        assertThat(pruned.currentStack).containsExactly(ChatHomeKey)
    }

    @Test
    fun `deleting a note opened from a citation returns to the chat`() {
        var state = nav.goTo(start, ChatKey(Fx.chat1))
        state = nav.follow(state, NoteKey(Fx.note1))
        state = nav.follow(state, ConnectionsKey(Fx.note1))
        assertThat(
            nav.prune(state, Fx.note1.value).currentStack,
        ).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
    }

    @Test
    fun `a deleted inspector focus falls back to the latest answer, a deleted Space to the default`() {
        val state = nav.follow(nav.goTo(Fx.richState(), ChatKey(Fx.chat1)), ChatContextKey(Fx.chat1, Fx.msg1))
        assertThat(nav.prune(state, Fx.msg1.value).currentStack.last()).isEqualTo(ChatContextKey(Fx.chat1))
        assertThat(nav.prune(state, Fx.space1.value).space).isNull()
    }

    @Test
    fun `deleting an object with a foreign id prunes its transient entries by raw id`() {
        var state = nav.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE)
        state = nav.openConnections(state, Fx.FOREIGN_NOTE)
        state = nav.selectGraphNode(state, Fx.TAG_NODE)
        val pruned = nav.prune(state, Fx.FOREIGN_NOTE)
        assertThat(pruned.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey)
        assertThat(pruned.stack(Destination.GRAPH).last()).isInstanceOf(TransientKey::class.java)
        assertThat(pruned.transientIds.values).containsExactly(Fx.TAG_NODE)
    }

    @Test
    fun `pruning an id nothing names changes nothing`() {
        val state = Fx.richState()
        assertThat(nav.prune(state, Fx.chat2.value)).isEqualTo(state)
        assertThat(nav.prune(state, "not-an-id")).isEqualTo(state)
        assertThat(nav.prune(state, "")).isEqualTo(state)
    }
}
