package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SECURITY_REVIEW_D7.md M4(d) / spec §8.3 rule 7 (§7, AL-06): after unlock and
 * after a restore, entries whose ids no longer resolve or changed kind are dropped
 * silently; draft keys are kept; a deleted graph focus re-centres; only the
 * content-free kind lookup is used.
 */
class NavigatorSanitiseTest {
    private val nav = Fx.navigator()

    /** The vault as it stands after the restore: every fixture id resolves as its own kind. */
    private val live =
        mapOf(
            Fx.chat1 to ObjectKind.CHAT,
            Fx.chat2 to ObjectKind.CHAT,
            Fx.msg1 to ObjectKind.MESSAGE,
            Fx.note1 to ObjectKind.NOTE,
            Fx.note2 to ObjectKind.NOTE,
            Fx.file1 to ObjectKind.FILE,
            Fx.model1 to ObjectKind.MODEL,
            Fx.space1 to ObjectKind.SPACE,
        )

    private fun sanitise(
        state: SkeinNavigationState,
        kinds: Map<SkeinId, ObjectKind>,
    ) = nav.sanitise(state) { kinds[it] }

    @Test
    fun `when everything resolves nothing changes`() {
        val state = Fx.richState()
        assertThat(sanitise(state, live)).isEqualTo(state)
    }

    @Test
    fun `a deleted id is dropped silently, wherever it is, and the rest is kept`() {
        val after = sanitise(Fx.richState(), live - Fx.note1)
        assertThat(
            after.stack(Destination.CHAT),
        ).containsExactly(ChatHomeKey, ChatKey(Fx.chat1), ChatContextKey(Fx.chat1, Fx.msg1)).inOrder()
        assertThat(after.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey)
        // A deleted graph focus re-centres on the default, and the node detail under it closes.
        assertThat(after.stack(Destination.GRAPH)).containsExactly(GraphKey(), NoteKey(Fx.note2)).inOrder()
        assertThat(after.topLevel).isEqualTo(Destination.KNOWLEDGE)
        assertThat(after.space).isEqualTo(Fx.space1)
    }

    @Test
    fun `a deleted selected node closes its detail`() {
        val state = nav.selectGraphNode(nav.openGraph(SkeinNavigationState.initial(), Fx.note1.value), Fx.note2.value)
        assertThat(sanitise(state, live - Fx.note2).currentStack).containsExactly(GraphKey(Fx.note1))
    }

    @Test
    fun `a kind change drops the entry`() {
        val changed =
            live + (Fx.note1 to ObjectKind.FILE) + (Fx.chat1 to ObjectKind.NOTE) + (Fx.model1 to ObjectKind.CHAT)
        val after = sanitise(Fx.richState(), changed)
        assertThat(after.stack(Destination.CHAT)).containsExactly(ChatHomeKey)
        // note1 is now a file: its NoteKey goes, but a source, Connections or graph focus accept either.
        assertThat(
            after.stack(Destination.KNOWLEDGE),
        ).containsExactly(KnowledgeHomeKey, ConnectionsKey(Fx.note1)).inOrder()
        assertThat(after.stack(Destination.GRAPH)).isEqualTo(Fx.richState().stack(Destination.GRAPH))
        assertThat(after.stack(Destination.MODELS)).containsExactly(ModelsHomeKey)
    }

    @Test
    fun `draft keys are never stale, with or without a row`() {
        var state = nav.goTo(Fx.richState(), NewChatKey(Fx.draft1))
        state = nav.goTo(state, NewNoteKey(Fx.draft2))
        val after = sanitise(state, emptyMap())
        assertThat(after.stack(Destination.CHAT)).containsExactly(ChatHomeKey, NewChatKey(Fx.draft1)).inOrder()
        assertThat(
            after.stack(Destination.KNOWLEDGE),
        ).containsExactly(KnowledgeHomeKey, NewNoteKey(Fx.draft2)).inOrder()
        assertThat(
            sanitise(state, mapOf(Fx.draft2 to ObjectKind.CHAT)).currentStack.last(),
        ).isEqualTo(NewNoteKey(Fx.draft2))
    }

    @Test
    fun `optional ids degrade instead - inspector focus, Space`() {
        val after = sanitise(Fx.richState(), live - Fx.msg1 - Fx.space1)
        assertThat(after.stack(Destination.CHAT)[2]).isEqualTo(ChatContextKey(Fx.chat1))
        assertThat(after.space).isNull()
        assertThat(sanitise(Fx.richState(), live + (Fx.space1 to ObjectKind.NOTE)).space).isNull()
    }

    @Test
    fun `everything gone leaves the roots and id-free keys, on the same destination`() {
        val after = sanitise(Fx.richState(), emptyMap())
        val expected =
            Fx.stateOf(
                Destination.KNOWLEDGE,
                Destination.SETTINGS to listOf(SettingsHomeKey, SettingsCategoryKey(SettingsCategory.ADVANCED)),
            )
        assertThat(after).isEqualTo(expected)
    }

    @Test
    fun `transient entries are dropped - their ids cannot be checked`() {
        var state = nav.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE, OpenMode.FOLLOW)
        state = nav.selectGraphNode(state, Fx.TAG_NODE)
        val after = sanitise(state, live)
        // Selecting the tag node replaced the typed node detail; the tag node itself cannot survive.
        val graphAtRoot = nav.switchTo(nav.switchTo(Fx.richState(), Destination.GRAPH), Destination.GRAPH)
        assertThat(after).isEqualTo(graphAtRoot)
        assertThat(after.transientIds).isEmpty()
    }

    @Test
    fun `only the content-free kind lookup is used, and only for referenced ids`() {
        val asked = mutableListOf<SkeinId>()
        val state = nav.goTo(Fx.richState(), NewNoteKey(Fx.draft2))
        nav.sanitise(state) {
            asked += it
            live[it]
        }
        val referenced = nav.referencedIds(state)
        assertThat(asked.toSet()).isEqualTo(referenced)
        assertThat(referenced).doesNotContain(Fx.draft2)
        assertThat(referenced).containsExactly(Fx.chat1, Fx.msg1, Fx.note1, Fx.note2, Fx.model1, Fx.space1)
    }

    @Test
    fun `sanitise is idempotent and its result round-trips`() {
        val once = sanitise(Fx.richState(), live - Fx.note2 - Fx.chat1)
        assertThat(sanitise(once, live - Fx.note2 - Fx.chat1)).isEqualTo(once)
        assertThat(SkeinNavCodec.decode(SkeinNavCodec.encode(once))).isEqualTo(once)
    }
}
