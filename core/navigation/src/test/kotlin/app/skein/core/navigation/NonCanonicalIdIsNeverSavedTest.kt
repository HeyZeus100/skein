package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SECURITY_REVIEW_D7.md M2(a) (§7, AL-06): an object whose id is not canonical
 * still opens, but its entry is transient. The saved form has no trace of it,
 * and a restore lands on what was beneath it (usually the destination root).
 */
class NonCanonicalIdIsNeverSavedTest {
    private val nav = Fx.navigator()

    private fun assertNoTrace(
        state: SkeinNavigationState,
        vararg raw: String,
    ) {
        val strings = Fx.strings(SkeinNavCodec.encode(state))
        for (r in raw) assertThat(strings.filter { it.contains(r) }).isEmpty()
        assertThat(SkeinNavCodec.violations(SkeinNavCodec.encode(state))).isEmpty()
    }

    @Test
    fun `a note whose id is project-falcon-notes opens, is not saved, and restores to the root`() {
        val state = nav.openDocument(SkeinNavigationState.initial(), Fx.FOREIGN_NOTE, ObjectKind.NOTE, anchor = 12)

        assertThat(state.topLevel).isEqualTo(Destination.KNOWLEDGE)
        val top = state.currentStack.last() as TransientKey
        assertThat(top.kind).isEqualTo(TransientKind.NOTE)
        assertThat(top.anchor).isEqualTo(12)
        assertThat(state.rawIdOf(top)).isEqualTo(Fx.FOREIGN_NOTE)

        assertNoTrace(state, Fx.FOREIGN_NOTE, "falcon")
        val restored = SkeinNavCodec.decode(SkeinNavCodec.encode(state))
        assertThat(restored.topLevel).isEqualTo(Destination.KNOWLEDGE)
        assertThat(restored.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey)
    }

    @Test
    fun `every open-by-raw-id path goes transient for a foreign id`() {
        var state = Fx.richState().copy(topLevel = Destination.CHAT)
        state = nav.openDocument(state, "legacy-chat", ObjectKind.CHAT, OpenMode.FOLLOW)
        state = nav.openSource(state, Fx.chat1, "obsidian-note-7", anchor = 3)
        state = nav.openDocument(state, "scan-2024.pdf", ObjectKind.FILE)
        state = nav.openConnections(state, "obsidian-note-8")
        state = nav.openModel(state, Fx.MODEL_SLUG)
        state = nav.openDocument(state, Fx.FOREIGN_NOTE, ObjectKind.NOTE, OpenMode.FOLLOW)

        val transients =
            state.stacks.values
                .flatten()
                .filterIsInstance<TransientKey>()
        assertThat(transients.map { it.kind })
            .containsExactly(
                TransientKind.CHAT,
                TransientKind.SOURCE,
                TransientKind.FILE,
                TransientKind.CONNECTIONS,
                TransientKind.MODEL,
                TransientKind.NOTE,
            )
        assertThat(transients.map { state.rawIdOf(it) })
            .containsExactly(
                "legacy-chat",
                "obsidian-note-7",
                "scan-2024.pdf",
                "obsidian-note-8",
                Fx.MODEL_SLUG,
                Fx.FOREIGN_NOTE,
            )

        assertNoTrace(state, "legacy-chat", "obsidian", "scan-2024", "qwen", "falcon")
        // Everything typed survives the restore; every transient entry is gone.
        val restored = SkeinNavCodec.decode(SkeinNavCodec.encode(state))
        assertThat(restored).isEqualTo(nav.dropTransient(state))
        assertThat(restored.stack(Destination.MODELS)).containsExactly(ModelsHomeKey)
        assertThat(restored.stack(Destination.CHAT)).isEqualTo(Fx.richState().stack(Destination.CHAT))
    }

    @Test
    fun `a canonical id is never transient`() {
        val state = nav.openDocument(SkeinNavigationState.initial(), Fx.note1.value, ObjectKind.NOTE)
        assertThat(state.currentStack).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note1)).inOrder()
        assertThat(state.transientIds).isEmpty()
    }

    @Test
    fun `reopening the same foreign object reuses its handle, so de-duplication applies`() {
        var state = nav.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE)
        state = nav.follow(state, NoteKey(Fx.note2))
        state = nav.openDocument(state, Fx.FOREIGN_NOTE, ObjectKind.NOTE, OpenMode.FOLLOW)
        val stack = state.stack(Destination.KNOWLEDGE)
        assertThat(stack).hasSize(2)
        assertThat(state.rawIdOf(stack[1] as TransientKey)).isEqualTo(Fx.FOREIGN_NOTE)
        assertThat(state.transientIds).hasSize(1)
    }

    @Test
    fun `raw ids leave memory with their entries, and at a lock`() {
        var state = nav.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE)
        assertThat(state.transientIds).hasSize(1)
        state = nav.back(state, NavMode.PHONE)!!
        assertThat(state.transientIds).isEmpty()

        state = nav.openModel(state, Fx.MODEL_SLUG)
        val locked = nav.dropTransient(state)
        assertThat(locked.transientIds).isEmpty()
        assertThat(locked.stack(Destination.MODELS)).containsExactly(ModelsHomeKey)
        assertThat(locked.stack(Destination.KNOWLEDGE)).isEqualTo(state.stack(Destination.KNOWLEDGE))
    }

    @Test
    fun `a transient key cannot be smuggled in without its raw id`() {
        val orphan = TransientKey(TransientKind.NOTE, Fx.draft1)
        val state = Fx.stateOf(Destination.KNOWLEDGE, Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, orphan))
        assertThat(state.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey)
        assertThat(
            nav.goTo(SkeinNavigationState.initial(), orphan).stack(Destination.KNOWLEDGE),
        ).containsExactly(KnowledgeHomeKey)
    }
}
