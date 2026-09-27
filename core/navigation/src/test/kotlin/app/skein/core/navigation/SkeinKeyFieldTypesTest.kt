package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * SECURITY_REVIEW_D7.md M1 (§7, AL-06): every field of every key is a typed id,
 * an enum or a number, by reflection over the sealed hierarchy, so a key that
 * gains a `String` (a title, a slug, a query) fails here before it can reach the
 * saved-state Bundle.
 */
class SkeinKeyFieldTypesTest {
    private val keyTypes: List<Class<*>> = SkeinKey::class.java.permittedSubclasses.toList()

    private fun instanceFields(type: Class<*>) =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    @Test
    fun `every key field is a SkeinId, an enum or an Int - never a String or collection`() {
        assertThat(keyTypes).hasSize(17)
        val allowed = setOf<Class<*>>(SkeinId::class.java, Int::class.javaPrimitiveType!!, Int::class.javaObjectType)
        for (type in keyTypes) {
            for (field in instanceFields(type)) {
                assertWithField(type, field.name, field.type in allowed || field.type.isEnum)
                assertWithField(type, field.name, !CharSequence::class.java.isAssignableFrom(field.type))
                assertWithField(type, field.name, !Iterable::class.java.isAssignableFrom(field.type))
                assertWithField(type, field.name, !Map::class.java.isAssignableFrom(field.type))
            }
        }
    }

    @Test
    fun `the one String behind every id is SkeinId's own validated value`() {
        val fields = instanceFields(SkeinId::class.java)
        assertThat(fields.map { it.name to it.type }).containsExactly("value" to String::class.java)
        // Kotlin adds a synthetic constructor for the companion's access; it is not callable from source.
        val constructors = SkeinId::class.java.declaredConstructors.filterNot { it.isSynthetic }
        assertThat(constructors.map { Modifier.isPrivate(it.modifiers) }).containsExactly(true)
    }

    @Test
    fun `the samples cover every saved key type, and TransientKey is the only one left out`() {
        val sampled = Fx.savedSamples.map { it.javaClass }.toSet()
        assertThat(keyTypes.toSet() - sampled).containsExactly(TransientKey::class.java)
    }

    @Test
    fun `keys implement nothing but SkeinKey - not Nav3's NavKey, not Serializable`() {
        assertThat(SkeinKey::class.java.interfaces).isEmpty()
        for (type in keyTypes) assertThat(type.interfaces.toList()).containsExactly(SkeinKey::class.java)
    }

    @Test
    fun `every key reports its spec 8_2 destination and pane role`() {
        val table =
            Fx.savedSamples.associate { it.javaClass.simpleName to (it.destination to it.role) } +
                TransientKind.entries.associate { "Transient.$it" to (it.destination to it.role) }
        assertThat(table)
            .containsExactlyEntriesIn(
                mapOf(
                    "ChatHomeKey" to (Destination.CHAT to PaneRole.LIST),
                    "NewChatKey" to (Destination.CHAT to PaneRole.DETAIL),
                    "ChatKey" to (Destination.CHAT to PaneRole.DETAIL),
                    "ChatContextKey" to (Destination.CHAT to PaneRole.EXTRA),
                    "ChatSourceKey" to (Destination.CHAT to PaneRole.EXTRA),
                    "KnowledgeHomeKey" to (Destination.KNOWLEDGE to PaneRole.LIST),
                    "NoteKey" to (Destination.KNOWLEDGE to PaneRole.DETAIL),
                    "NewNoteKey" to (Destination.KNOWLEDGE to PaneRole.DETAIL),
                    "FileKey" to (Destination.KNOWLEDGE to PaneRole.DETAIL),
                    "ConnectionsKey" to (Destination.KNOWLEDGE to PaneRole.EXTRA),
                    "GraphKey" to (Destination.GRAPH to PaneRole.MAIN),
                    "GraphNodeKey" to (Destination.GRAPH to PaneRole.SUPPORTING),
                    "ModelsHomeKey" to (Destination.MODELS to PaneRole.LIST),
                    "ModelDetailsKey" to (Destination.MODELS to PaneRole.DETAIL),
                    "SettingsHomeKey" to (Destination.SETTINGS to PaneRole.LIST),
                    "SettingsCategoryKey" to (Destination.SETTINGS to PaneRole.DETAIL),
                    "Transient.CHAT" to (Destination.CHAT to PaneRole.DETAIL),
                    "Transient.NOTE" to (Destination.KNOWLEDGE to PaneRole.DETAIL),
                    "Transient.FILE" to (Destination.KNOWLEDGE to PaneRole.DETAIL),
                    "Transient.SOURCE" to (Destination.CHAT to PaneRole.EXTRA),
                    "Transient.CONNECTIONS" to (Destination.KNOWLEDGE to PaneRole.EXTRA),
                    "Transient.GRAPH_NODE" to (Destination.GRAPH to PaneRole.SUPPORTING),
                    "Transient.MODEL" to (Destination.MODELS to PaneRole.DETAIL),
                ),
            )
        // Exactly one root per destination, and a root is never transient.
        assertThat(
            Fx.savedSamples.filter { it.isRoot }.map { it.destination },
        ).containsExactlyElementsIn(Destination.entries)
        assertThat(TransientKind.entries.none { it.role == PaneRole.LIST || it.role == PaneRole.MAIN }).isTrue()
    }

    @Test
    fun `negative anchors cannot be constructed - an anchor is a bounded byte offset (M1c)`() {
        for (build in listOf<() -> Any>(
            { ChatSourceKey(Fx.chat1, Fx.note1, -1) },
            { NoteKey(Fx.note1, -1) },
            { FileKey(Fx.file1, Int.MIN_VALUE) },
            { TransientKey(TransientKind.NOTE, Fx.note1, -5) },
        )) {
            assertThat(runCatching(build).exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `M13 - keys and states print no id and no raw transient id`() {
        val nav = Fx.navigator()
        var state = nav.openDocument(Fx.richState(), Fx.FOREIGN_NOTE, ObjectKind.NOTE)
        state = nav.selectGraphNode(state, Fx.TITLE_NODE)
        val printed = Fx.savedSamples.joinToString { it.toString() } + state.toString() + state.stacks.toString()
        for (id in listOf(
            Fx.chat1,
            Fx.msg1,
            Fx.note1,
            Fx.note2,
            Fx.file1,
            Fx.model1,
            Fx.space1,
            Fx.draft1,
            Fx.draft2,
        )) {
            assertThat(printed).doesNotContain(id.value)
        }
        assertThat(printed).doesNotContain(Fx.FOREIGN_NOTE)
        assertThat(printed).doesNotContain("lawyer")
    }

    private fun assertWithField(
        type: Class<*>,
        field: String,
        ok: Boolean,
    ) = assertWithMessage("${type.simpleName}.$field").that(ok).isTrue()
}
