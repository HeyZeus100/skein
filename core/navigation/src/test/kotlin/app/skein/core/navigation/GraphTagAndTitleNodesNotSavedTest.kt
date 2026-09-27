package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SECURITY_REVIEW_D7.md M1(a) (§7, AL-06): selecting a `tag:`, `title:` or
 * `entity:` graph node keeps it in memory only. It leaves no trace in the saved
 * form, and after a restore or a lock its detail is closed.
 */
class GraphTagAndTitleNodesNotSavedTest {
    private val nav = Fx.navigator()
    private val centred = nav.openGraph(SkeinNavigationState.initial(), Fx.note1.value)

    @Test
    fun `tag, title and entity nodes open as transient node details and leave no trace`() {
        for (raw in listOf(Fx.TAG_NODE, Fx.TITLE_NODE, Fx.ENTITY_NODE)) {
            val state = nav.selectGraphNode(centred, raw)
            val node = state.stack(Destination.GRAPH).last() as TransientKey
            assertThat(node.kind).isEqualTo(TransientKind.GRAPH_NODE)
            assertThat(state.rawIdOf(node)).isEqualTo(raw)

            val strings = Fx.strings(SkeinNavCodec.encode(state))
            for (fragment in listOf("tag:", "title:", "entity:", "divorce", "lawyer")) {
                assertThat(strings.filter { it.contains(fragment) }).isEmpty()
            }
            val restored = SkeinNavCodec.decode(SkeinNavCodec.encode(state))
            assertThat(restored.stack(Destination.GRAPH)).containsExactly(GraphKey(Fx.note1))
        }
    }

    @Test
    fun `a document node is saved, with the canvas focus`() {
        val state = nav.selectGraphNode(centred, Fx.note2.value)
        assertThat(
            state.stack(Destination.GRAPH),
        ).containsExactly(GraphKey(Fx.note1), GraphNodeKey(Fx.note1, Fx.note2)).inOrder()
        assertThat(SkeinNavCodec.decode(SkeinNavCodec.encode(state))).isEqualTo(state)
    }

    @Test
    fun `selecting another node replaces the selection, typed or transient`() {
        var state = nav.selectGraphNode(centred, Fx.note2.value)
        state = nav.selectGraphNode(state, Fx.TAG_NODE)
        assertThat(
            state.stack(Destination.GRAPH).map {
                it::class
            },
        ).containsExactly(GraphKey::class, TransientKey::class).inOrder()
        state = nav.selectGraphNode(state, Fx.note2.value)
        assertThat(
            state.stack(Destination.GRAPH),
        ).containsExactly(GraphKey(Fx.note1), GraphNodeKey(Fx.note1, Fx.note2)).inOrder()
        assertThat(state.transientIds).isEmpty()
    }

    @Test
    fun `after a lock the tag node's detail is closed and its text is gone from memory`() {
        val selected = nav.selectGraphNode(centred, Fx.TITLE_NODE)
        val locked = nav.dropTransient(selected)
        assertThat(locked.stack(Destination.GRAPH)).containsExactly(GraphKey(Fx.note1))
        assertThat(locked.transientIds.values).isEmpty()
        assertThat(locked.toString()).doesNotContain("lawyer")
    }
}
