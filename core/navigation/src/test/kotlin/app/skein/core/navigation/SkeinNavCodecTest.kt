package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The saved form (spec §7.2, §8.9 item 1): round trips, the pinned shape, the
 * content keys, and the privacy allowlist (SECURITY_REVIEW_D7.md M3a) that AL-15's
 * Bundle scan reuses.
 */
class SkeinNavCodecTest {
    private fun roundTrip(state: SkeinNavigationState) = SkeinNavCodec.decode(SkeinNavCodec.encode(state))

    @Test
    fun `every saved key type round-trips with every field`() {
        for (key in Fx.savedSamples) {
            val state =
                if (key.isRoot) {
                    Fx.stateOf(
                        key.destination,
                        key.destination to listOf(key),
                    )
                } else {
                    Fx.navigator().goTo(SkeinNavigationState.initial(), key)
                }
            assertWithMessage(key.javaClass.simpleName).that(state.stack(key.destination).last()).isEqualTo(key)
            assertWithMessage(key.javaClass.simpleName).that(roundTrip(state)).isEqualTo(state)
        }
    }

    @Test
    fun `representative states round-trip - top level, every stack, followed keys, absent optionals, the Space`() {
        val nav = Fx.navigator()
        val states =
            listOf(
                SkeinNavigationState.initial(),
                Fx.richState(),
                Fx.richState().copy(topLevel = Destination.SETTINGS, space = null),
                // Followed keys sit in a foreign destination's stack and must restore there.
                nav.follow(
                    nav.follow(Fx.richState().copy(topLevel = Destination.CHAT), NoteKey(Fx.note2)),
                    ModelDetailsKey(Fx.model1),
                ),
                nav.goTo(SkeinNavigationState.initial(), NewChatKey(Fx.draft1)),
                Fx.stateOf(
                    Destination.GRAPH,
                    Destination.CHAT to
                        listOf(
                            ChatHomeKey,
                            ChatKey(Fx.chat1),
                            ChatContextKey(Fx.chat1),
                            ChatSourceKey(Fx.chat1, Fx.file1),
                        ),
                    Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, NewNoteKey(Fx.draft2), FileKey(Fx.file1)),
                    Destination.GRAPH to listOf(GraphKey(), GraphNodeKey(null, Fx.note1)),
                ),
            )
        for (state in states) assertThat(roundTrip(state)).isEqualTo(state)
    }

    @Test
    fun `the saved form is pinned - tags, field names, enum names, uuids and ints only`() {
        val state =
            Fx
                .stateOf(
                    Destination.CHAT,
                    Destination.CHAT to listOf(ChatHomeKey, ChatKey(Fx.chat1), ChatSourceKey(Fx.chat1, Fx.note1, 812)),
                    Destination.SETTINGS to listOf(SettingsHomeKey, SettingsCategoryKey(SettingsCategory.ABOUT)),
                ).copy(space = Fx.space1)
        assertThat(SkeinNavCodec.encode(state))
            .isEqualTo(
                mapOf(
                    "top" to "CHAT",
                    "space" to Fx.space1.value,
                    "CHAT" to
                        listOf(
                            mapOf("t" to "chat.home"),
                            mapOf("t" to "chat", "chat" to Fx.chat1.value),
                            mapOf(
                                "t" to "chat.source",
                                "chat" to Fx.chat1.value,
                                "doc" to Fx.note1.value,
                                "anchor" to 812,
                            ),
                        ),
                    "KNOWLEDGE" to listOf(mapOf("t" to "knowledge.home")),
                    "GRAPH" to listOf(mapOf("t" to "graph")),
                    "MODELS" to listOf(mapOf("t" to "models.home")),
                    "SETTINGS" to
                        listOf(mapOf("t" to "settings.home"), mapOf("t" to "settings.category", "category" to "ABOUT")),
                ),
            )
    }

    @Test
    fun `content keys are tag slash uuid, carry identity only, and are all allowlisted`() {
        val expected =
            listOf(
                "chat.home",
                "chat.new/${Fx.draft1.value}",
                "chat/${Fx.chat1.value}",
                "chat.context/${Fx.chat1.value}",
                "chat.source/${Fx.chat1.value}/${Fx.note1.value}",
                "knowledge.home",
                "note/${Fx.note1.value}",
                "note.new/${Fx.draft2.value}",
                "file/${Fx.file1.value}",
                "connections/${Fx.note1.value}",
                "graph/${Fx.note1.value}",
                "graph.node/${Fx.note2.value}",
                "models.home",
                "model/${Fx.model1.value}",
                "settings.home",
                "settings.category/PRIVACY_AND_SECURITY",
            )
        assertThat(Fx.savedSamples.map { it.contentKey }).containsExactlyElementsIn(expected).inOrder()
        assertThat(GraphKey().contentKey).isEqualTo("graph")
        val transient = TransientKey(TransientKind.GRAPH_NODE, Fx.draft1)
        assertThat(transient.contentKey).isEqualTo("transient/${Fx.draft1.value}")
        for (key in Fx.savedSamples + transient) assertThat(SkeinNavCodec.isAllowedString(key.contentKey)).isTrue()
        // Moving an anchor or the inspector's focus keeps the entry (and its saved state).
        assertThat(NoteKey(Fx.note1, 9).contentKey).isEqualTo(NoteKey(Fx.note1).contentKey)
        assertThat(ChatContextKey(Fx.chat1, Fx.msg1).contentKey).isEqualTo(ChatContextKey(Fx.chat1).contentKey)
    }

    @Test
    fun `privacy - every string in a rich saved form is on the allowlist`() {
        val nav = Fx.navigator()
        var state = nav.follow(Fx.richState(), NoteKey(Fx.note2, 3))
        state = nav.openDocument(state, Fx.FOREIGN_NOTE, ObjectKind.NOTE, OpenMode.FOLLOW)
        state = nav.selectGraphNode(state, Fx.TITLE_NODE)
        state = nav.openModel(state, Fx.MODEL_SLUG)
        val saved = SkeinNavCodec.encode(state)
        assertThat(SkeinNavCodec.violations(saved)).isEmpty()
        assertThat(Fx.strings(saved).all(SkeinNavCodec::isAllowedString)).isTrue()
    }

    @Test
    fun `privacy - the check is not vacuous`() {
        val leaky =
            mapOf(
                "top" to "CHAT",
                "CHAT" to listOf(mapOf("t" to "chat", "chat" to "my divorce lawyer")),
                "title" to "Letters",
                "GRAPH" to listOf(mapOf("t" to "graph.node", "node" to Fx.TAG_NODE)),
                "MODELS" to listOf(mapOf("t" to "model", "model" to Fx.MODEL_SLUG)),
                "saved" to 1_727_000_000_000L,
                "flag" to true,
                "nothing" to null,
                "ChatKey(chatId=x, title=Letters)" to "",
                "opaque" to Any(),
            )
        val values = SkeinNavCodec.violations(leaky).map { it.value }
        assertThat(values)
            .containsAtLeast(
                "my divorce lawyer",
                "title",
                "Letters",
                Fx.TAG_NODE,
                Fx.MODEL_SLUG,
                "type java.lang.Long",
                "type java.lang.Boolean",
                "type null",
                "ChatKey(chatId=x, title=Letters)",
                "",
                "type java.lang.Object",
            )
    }

    @Test
    fun `the allowlist admits navigation's own vocabulary and nothing text-like`() {
        val allowed =
            listOf(
                "top",
                "space",
                "t",
                "chat",
                "doc",
                "anchor",
                "focus",
                "node",
                "model",
                "category",
                "draft",
                "message",
            ) +
                Destination.entries.map { it.name } +
                SettingsCategory.entries.map { it.name } +
                listOf(
                    "chat.home",
                    "graph.node",
                    "settings.category/ABOUT",
                    "graph",
                    "transient/${Fx.draft1.value}",
                    Fx.chat1.value,
                )
        for (s in allowed) assertWithMessage(s).that(SkeinNavCodec.isAllowedString(s)).isTrue()

        val denied =
            listOf(
                "my divorce lawyer",
                "Letters",
                Fx.TAG_NODE,
                Fx.TITLE_NODE,
                Fx.ENTITY_NODE,
                Fx.FOREIGN_NOTE,
                Fx.MODEL_SLUG,
                Fx.chat1.value.uppercase(),
                "app.skein.core.navigation.ChatKey",
                "ChatKey",
                "transient",
                "note/${Fx.FOREIGN_NOTE}",
                "note/${Fx.note1.value}/${Fx.note2.value}/${Fx.chat1.value}",
                "graph/-",
                "chat/",
                "/${Fx.chat1.value}",
                "bogus/${Fx.chat1.value}",
                "settings.category/Advanced",
                "",
                "1727000000000",
            )
        for (s in denied) assertWithMessage(s).that(SkeinNavCodec.isAllowedString(s)).isFalse()
    }
}
