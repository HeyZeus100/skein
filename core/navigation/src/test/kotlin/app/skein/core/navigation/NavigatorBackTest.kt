package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Spec §3.6 and §8.3 rule 6 as pure transitions: mode-aware Back (the elided
 * visible stack), switching to Chat from another destination's root, never
 * consuming Back at the Chat root, and Esc, which never switches or leaves.
 */
class NavigatorBackTest {
    private val nav = Fx.navigator()
    private val allModes = NavMode.entries
    private val drawerOrMultiPane = listOf(NavMode.PHONE, NavMode.DUAL, NavMode.TRIPLE)

    private fun chat(vararg keys: SkeinKey) =
        Fx.stateOf(
            Destination.CHAT,
            Destination.CHAT to listOf(ChatHomeKey, *keys),
        )

    private val landing = chat(NewChatKey(Fx.draft1))

    @Test
    fun `the new-chat landing over the Chat root is elided wherever the root already shows the landing`() {
        for (mode in drawerOrMultiPane) {
            assertThat(
                nav.visibleStack(landing.currentStack, mode),
            ).containsExactly(ChatHomeKey)
        }
        assertThat(nav.visibleStack(landing.currentStack, NavMode.SINGLE)).isEqualTo(landing.currentStack)
        // Only a trailing draft directly over the root: anything else is shown as saved.
        val followed = chat(NewChatKey(Fx.draft1), ModelDetailsKey(Fx.model1)).currentStack
        for (mode in allModes) assertThat(nav.visibleStack(followed, mode)).isEqualTo(followed)
        val knowledgeDraft = listOf(KnowledgeHomeKey, NewNoteKey(Fx.draft2))
        for (mode in allModes) assertThat(nav.visibleStack(knowledgeDraft, mode)).isEqualTo(knowledgeDraft)
    }

    @Test
    fun `Back on the landing - not consumed on Phone, Dual and Triple, shows the Conversations list on Single`() {
        for (mode in drawerOrMultiPane) {
            assertWithMessage("$mode").that(nav.back(landing, mode)).isNull()
            assertWithMessage("$mode").that(nav.escape(landing, mode)).isNull()
        }
        assertThat(nav.back(landing, NavMode.SINGLE)!!.currentStack).containsExactly(ChatHomeKey)
    }

    @Test
    fun `Back pops sheets, extra panes and details in every mode`() {
        val cases =
            listOf(
                chat(ChatKey(Fx.chat1)) to listOf(ChatHomeKey),
                chat(ChatKey(Fx.chat1), ChatContextKey(Fx.chat1)) to listOf(ChatHomeKey, ChatKey(Fx.chat1)),
                chat(ChatKey(Fx.chat1), ChatSourceKey(Fx.chat1, Fx.note1)) to listOf(ChatHomeKey, ChatKey(Fx.chat1)),
                // A followed key over the landing: Back shows the landing (a visible change) and keeps the draft.
                chat(NewChatKey(Fx.draft1), ModelDetailsKey(Fx.model1)) to listOf(ChatHomeKey, NewChatKey(Fx.draft1)),
            )
        for ((state, expected) in cases) {
            for (mode in allModes) {
                val after = nav.back(state, mode)
                assertWithMessage("$mode ${state.currentStack.map { it.contentKey.substringBefore('/') }}")
                    .that(after?.currentStack)
                    .isEqualTo(expected)
                assertThat(after!!.topLevel).isEqualTo(Destination.CHAT)
                assertThat(nav.escape(state, mode)).isEqualTo(after)
            }
        }
    }

    @Test
    fun `Back deselects a graph node, then leaves Graph for Chat`() {
        val selected = nav.selectGraphNode(nav.openGraph(chat(ChatKey(Fx.chat1)), Fx.note1.value), Fx.note2.value)
        for (mode in allModes) {
            val deselected = nav.back(selected, mode)!!
            assertThat(deselected.currentStack).containsExactly(GraphKey(Fx.note1))
            val toChat = nav.back(deselected, mode)!!
            assertThat(toChat.topLevel).isEqualTo(Destination.CHAT)
            assertThat(toChat.stacks).isEqualTo(deselected.stacks)
        }
    }

    @Test
    fun `at another destination's root Back switches to Chat, stacks unchanged, and Esc does nothing`() {
        val rich = Fx.richState()
        for (destination in Destination.entries - Destination.CHAT) {
            val atRoot = nav.switchTo(nav.switchTo(rich, destination), destination)
            for (mode in allModes) {
                val after = nav.back(atRoot, mode)!!
                assertThat(after.topLevel).isEqualTo(Destination.CHAT)
                assertThat(after.stacks).isEqualTo(atRoot.stacks)
                assertThat(nav.escape(atRoot, mode)).isNull()
            }
        }
    }

    @Test
    fun `at the Chat root Back is not consumed, so the system can finish the task`() {
        val atChatRoot = Fx.richState().let { nav.switchTo(nav.switchTo(it, Destination.CHAT), Destination.CHAT) }
        for (mode in allModes) {
            assertThat(nav.back(atChatRoot, mode)).isNull()
            assertThat(nav.escape(atChatRoot, mode)).isNull()
        }
    }

    @Test
    fun `scripted - Phone opens a chat and its inspector, Back walks out and then leaves the app`() {
        var state = nav.goTo(SkeinNavigationState.initial(), NewChatKey(Fx.draft1))
        state = nav.promoteDraft(state, Fx.draft1, ChatKey(Fx.chat1))
        state = nav.follow(state, ChatContextKey(Fx.chat1))
        state = nav.back(state, NavMode.PHONE)!!
        assertThat(state.currentStack).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
        state = nav.back(state, NavMode.PHONE)!!
        assertThat(state.currentStack).containsExactly(ChatHomeKey)
        assertThat(nav.back(state, NavMode.PHONE)).isNull()
    }

    @Test
    fun `scripted - a fold between Backs changes the rendering, never the stack`() {
        var state = nav.goTo(SkeinNavigationState.initial(), NewChatKey(Fx.draft1))
        state = nav.follow(state, ModelDetailsKey(Fx.model1))
        state = nav.back(state, NavMode.DUAL)!!
        assertThat(state.currentStack).containsExactly(ChatHomeKey, NewChatKey(Fx.draft1)).inOrder()
        // Unfolded to a Single window, the same saved stack shows the landing over the list again.
        assertThat(nav.visibleStack(state.currentStack, NavMode.SINGLE)).isEqualTo(state.currentStack)
        assertThat(nav.back(state, NavMode.SINGLE)!!.currentStack).containsExactly(ChatHomeKey)
        assertThat(nav.back(state, NavMode.PHONE)).isNull()
    }

    @Test
    fun `every consumed Back changes what is on screen, and touches only the current stack`() {
        val n = Fx.navigator()
        val states =
            listOf(
                SkeinNavigationState.initial(),
                landing,
                Fx.richState(),
                n.switchTo(Fx.richState(), Destination.CHAT),
                n.switchTo(Fx.richState(), Destination.GRAPH),
                n.switchTo(n.switchTo(Fx.richState(), Destination.SETTINGS), Destination.SETTINGS),
                n.follow(n.goTo(Fx.richState(), NewChatKey(Fx.draft1)), NoteKey(Fx.note2)),
                n.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE, OpenMode.FOLLOW),
            )
        for (state in states) {
            for (mode in allModes) {
                val after = nav.back(state, mode) ?: continue
                val visibleBefore = state.topLevel to nav.visibleStack(state.currentStack, mode)
                val visibleAfter = after.topLevel to nav.visibleStack(after.currentStack, mode)
                assertThat(visibleAfter).isNotEqualTo(visibleBefore)
                for (d in Destination.entries - state.topLevel) assertThat(after.stack(d)).isEqualTo(state.stack(d))
            }
        }
    }
}
