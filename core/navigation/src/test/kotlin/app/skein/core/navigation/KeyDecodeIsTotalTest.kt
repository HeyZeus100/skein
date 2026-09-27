package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * SECURITY_REVIEW_D7.md M4c (§7, AL-06): decoding never throws. An unknown,
 * renamed or truncated entry, an invalid id or a wrong type drops only that
 * entry; an all-invalid stack becomes its root. Then a fuzz over random,
 * garbage, truncated and mutated inputs checks that every result is a
 * well-formed state that re-encodes to allowlisted strings.
 */
class KeyDecodeIsTotalTest {
    private val chat = mapOf("t" to "chat", "chat" to Fx.chat1.value)
    private val note = mapOf("t" to "note", "doc" to Fx.note1.value)

    private fun decodeChatStack(vararg entries: Any?) =
        SkeinNavCodec
            .decode(
                mapOf("top" to "CHAT", "CHAT" to listOf(mapOf("t" to "chat.home"), *entries)),
            ).stack(Destination.CHAT)

    @Test
    fun `an unknown, renamed or truncated entry drops only itself`() {
        val bad =
            listOf(
                mapOf("t" to "chat.bogus", "chat" to Fx.chat1.value),
                mapOf("t" to "ChatKey", "chat" to Fx.chat1.value),
                mapOf("t" to "app.skein.core.navigation.ChatKey", "chat" to Fx.chat1.value),
                mapOf("type" to "chat", "chat" to Fx.chat1.value),
                mapOf("t" to "chat", "chatId" to Fx.chat1.value),
                mapOf("t" to "chat"),
                mapOf("t" to "chat.source", "chat" to Fx.chat1.value),
                mapOf("t" to "transient", "handle" to Fx.chat1.value),
                emptyMap<String, Any>(),
            )
        for (entry in bad) {
            assertWithMessage(
                entry.toString(),
            ).that(decodeChatStack(entry, chat)).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
        }
    }

    @Test
    fun `an invalid id or a wrong type drops only that entry`() {
        val bad =
            listOf(
                chat + ("chat" to Fx.FOREIGN_NOTE),
                chat + ("chat" to Fx.TAG_NODE),
                chat + ("chat" to Fx.chat1.value.uppercase()),
                chat + ("chat" to Fx.chat1.value.take(20)),
                chat + ("chat" to 7),
                chat + ("chat" to listOf(Fx.chat1.value)),
                mapOf("t" to "chat.context", "chat" to Fx.chat1.value, "message" to "title:x"),
                mapOf("t" to "chat.source", "chat" to Fx.chat1.value, "doc" to Fx.note1.value, "anchor" to -1),
                mapOf("t" to "chat.source", "chat" to Fx.chat1.value, "doc" to Fx.note1.value, "anchor" to 5L),
                mapOf("t" to "chat.source", "chat" to Fx.chat1.value, "doc" to Fx.note1.value, "anchor" to "5"),
                mapOf("t" to 3, "chat" to Fx.chat1.value),
                "chat",
                7,
                null,
                listOf("t", "chat"),
            )
        for (entry in bad) {
            assertWithMessage(
                entry.toString(),
            ).that(decodeChatStack(entry, chat)).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
        }
    }

    @Test
    fun `absent optional fields are fine, and unknown extra fields are ignored`() {
        val stack =
            decodeChatStack(
                mapOf("t" to "chat.context", "chat" to Fx.chat1.value),
                mapOf(
                    "t" to "chat.source",
                    "chat" to Fx.chat1.value,
                    "doc" to Fx.note1.value,
                    "title" to "Letters",
                    "v" to 2,
                ),
            )
        assertThat(
            stack,
        ).containsExactly(ChatHomeKey, ChatContextKey(Fx.chat1), ChatSourceKey(Fx.chat1, Fx.note1)).inOrder()
    }

    @Test
    fun `an all-invalid or missing stack becomes its root, and garbage at the top becomes the initial state`() {
        val allInvalid =
            mapOf(
                "top" to "NOT_A_DESTINATION",
                "space" to Fx.TAG_NODE,
                "CHAT" to listOf(mapOf("t" to "chat.bogus"), "x", null),
                "KNOWLEDGE" to "not a list",
                "GRAPH" to mapOf("t" to "graph"),
                "MODELS" to listOf(mapOf("t" to "model", "model" to Fx.MODEL_SLUG)),
            )
        assertThat(SkeinNavCodec.decode(allInvalid)).isEqualTo(SkeinNavigationState.initial())
        for (garbage in listOf(
            null,
            "",
            "CHAT",
            42,
            emptyMap<String, Any>(),
            emptyList<Any>(),
            listOf(chat),
            Any(),
            ByteArray(8),
        )) {
            assertThat(SkeinNavCodec.decode(garbage)).isEqualTo(SkeinNavigationState.initial())
        }
        // The top level survives on its own; only the broken parts fall back.
        assertThat(SkeinNavCodec.decode(mapOf("top" to "GRAPH")).topLevel).isEqualTo(Destination.GRAPH)
        assertThat(SkeinNavCodec.decode(mapOf("top" to "graph")).topLevel).isEqualTo(Destination.CHAT)
    }

    @Test
    fun `restored stacks are normalised - a missing root is added, misplaced roots and duplicates are dropped`() {
        val saved =
            mapOf(
                "top" to "KNOWLEDGE",
                "KNOWLEDGE" to
                    listOf(
                        note,
                        mapOf("t" to "graph"),
                        note,
                        mapOf("t" to "chat.home"),
                        mapOf("t" to "knowledge.home"),
                    ),
                "CHAT" to
                    listOf(
                        mapOf("t" to "chat.home"),
                        mapOf("t" to "chat.new", "draft" to Fx.draft1.value),
                        mapOf(
                            "t" to "chat.new",
                            "draft" to Fx.draft2.value,
                        ),
                    ),
                "GRAPH" to
                    listOf(
                        mapOf("t" to "graph", "focus" to "title:x"),
                        mapOf(
                            "t" to "graph.node",
                            "node" to Fx.note2.value,
                        ),
                    ),
            )
        val state = SkeinNavCodec.decode(saved)
        assertThat(state.stack(Destination.KNOWLEDGE)).containsExactly(KnowledgeHomeKey, NoteKey(Fx.note1)).inOrder()
        assertThat(state.stack(Destination.CHAT)).containsExactly(ChatHomeKey, NewChatKey(Fx.draft1)).inOrder()
        // An invalid root is dropped like any entry, and its destination's default root takes its place.
        assertThat(state.stack(Destination.GRAPH)).containsExactly(GraphKey(), GraphNodeKey(null, Fx.note2)).inOrder()
    }

    @Test
    fun `hostile collections cannot make decode throw`() {
        val throwingList =
            object : AbstractList<Any?>() {
                override val size = 3

                override fun get(index: Int): Any? = throw IllegalStateException("boom")
            }
        val throwingMap =
            object : AbstractMap<Any?, Any?>() {
                override val entries: Set<Map.Entry<Any?, Any?>> get() = throw UnsupportedOperationException()

                override fun get(key: Any?): Any? = throw ClassCastException()
            }
        val throwingRecord =
            object : HashMap<Any?, Any?>(chat) {
                override fun get(key: Any?): Any? =
                    if (key ==
                        "chat"
                    ) {
                        throw IllegalArgumentException()
                    } else {
                        super.get(key)
                    }
            }
        assertThat(SkeinNavCodec.decode(throwingMap)).isEqualTo(SkeinNavigationState.initial())
        assertThat(SkeinNavCodec.decode(mapOf("CHAT" to throwingList))).isEqualTo(SkeinNavigationState.initial())
        assertThat(decodeChatStack(throwingRecord, chat)).containsExactly(ChatHomeKey, ChatKey(Fx.chat1)).inOrder()
    }

    @Test
    fun `fuzz - random garbage trees always decode to a well-formed state`() {
        val gen = Garbage(Random(0x5EED_0001))
        var decodedEntries = 0
        repeat(RANDOM_ITERATIONS) { i ->
            val input = if (i % 2 == 0) gen.stateTree() else gen.value(0)
            val state = decodeOrFail(input, "random #$i")
            assertWellFormed(state, "random #$i")
            decodedEntries += state.stacks.values.sumOf { it.size - 1 }
        }
        // Not vacuous: the generator reaches past the shape checks into real entries.
        assertThat(decodedEntries).isGreaterThan(RANDOM_ITERATIONS / 10)
    }

    @Test
    fun `fuzz - truncated, renamed and mutated valid encodings always decode to a well-formed state`() {
        val gen = Garbage(Random(0x5EED_0002))
        val nav = Fx.navigator()
        val bases =
            listOf(
                Fx.richState(),
                nav.goTo(nav.follow(Fx.richState(), NoteKey(Fx.note2, 3)), NewChatKey(Fx.draft1)),
                Fx.stateOf(
                    Destination.KNOWLEDGE,
                    Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, NewNoteKey(Fx.draft2), FileKey(Fx.file1, 4)),
                ),
            ).map(SkeinNavCodec::encode)
        var survivors = 0
        repeat(MUTATION_ITERATIONS) { i ->
            var input: Any? = bases[i % bases.size]
            repeat(1 + gen.r.nextInt(4)) { input = gen.mutate(input, 0) }
            val state = decodeOrFail(input, "mutation #$i")
            assertWellFormed(state, "mutation #$i")
            survivors += state.stacks.values.sumOf { it.size - 1 }
        }
        // Not vacuous: most mutations leave valid entries behind to decode.
        assertThat(survivors).isGreaterThan(MUTATION_ITERATIONS)
    }

    private fun decodeOrFail(
        input: Any?,
        label: String,
    ): SkeinNavigationState =
        try {
            SkeinNavCodec.decode(input)
        } catch (t: Throwable) {
            throw AssertionError("$label: decode threw ${t.javaClass.name}", t)
        }

    private fun assertWellFormed(
        state: SkeinNavigationState,
        label: String,
    ) {
        for (d in Destination.entries) {
            val stack = state.stack(d)
            val ok =
                stack.isNotEmpty() &&
                    stack[0].isRoot &&
                    stack[0].destination == d &&
                    stack.drop(1).none { it.isRoot || it is TransientKey } &&
                    stack.map(::identity).toSet().size == stack.size
            if (!ok) throw AssertionError("$label: malformed $d stack")
        }
        if (state.transientIds.isNotEmpty()) throw AssertionError("$label: transient ids restored")
        val saved = SkeinNavCodec.encode(state)
        val violations = SkeinNavCodec.violations(saved)
        if (violations.isNotEmpty()) throw AssertionError("$label: $violations")
        if (SkeinNavCodec.decode(saved) != state) throw AssertionError("$label: not idempotent")
    }

    /** Random, garbage-heavy saved-state trees, plausible enough to reach deep into the decoder. */
    private class Garbage(
        val r: Random,
    ) {
        private val ids =
            listOf(
                Fx.chat1,
                Fx.chat2,
                Fx.msg1,
                Fx.note1,
                Fx.note2,
                Fx.file1,
                Fx.model1,
                Fx.space1,
                Fx.draft1,
            ).map {
                it.value
            }
        private val vocabulary =
            listOf(
                "top",
                "space",
                "t",
                "chat",
                "doc",
                "draft",
                "message",
                "anchor",
                "focus",
                "node",
                "model",
                "category",
            ) +
                KeyTag.entries.map { it.tag } +
                Destination.entries.map { it.name } +
                SettingsCategory.entries.map { it.name }
        private val junk =
            listOf(
                Fx.FOREIGN_NOTE,
                Fx.TAG_NODE,
                Fx.TITLE_NODE,
                Fx.ENTITY_NODE,
                Fx.MODEL_SLUG,
                "",
                " ",
                "Chat",
                "chat.v2",
                "ChatKey",
                "app.skein.core.navigation.ChatKey",
                "type",
                "value",
                "null",
                "-1",
                "\u0000",
                "💥",
                "a".repeat(10_000),
                ids[0].uppercase(),
                "{${ids[0]}}",
                ids[0].dropLast(1),
                ids[0] + "/" + ids[1],
            )

        fun string(): String =
            when (r.nextInt(10)) {
                in 0..3 -> vocabulary.random(r)
                in 4..5 -> ids.random(r)
                in 6..8 -> junk.random(r)
                else -> String(CharArray(r.nextInt(0, 40)) { r.nextInt(0, 0x3000).toChar() })
            }

        fun value(depth: Int): Any? =
            when (r.nextInt(if (depth >= 4) 9 else 13)) {
                0 -> null
                1 -> r.nextInt()
                2 -> r.nextInt(-3, 2000)
                3 -> r.nextLong()
                4 -> r.nextDouble()
                5 -> r.nextBoolean()
                6, 7 -> string()
                8 -> listOf(Any(), ByteArray(3), IntArray(2), 'c', 1.5f).random(r)
                9, 10 -> List(r.nextInt(0, 6)) { value(depth + 1) }
                11 -> record()
                else -> map(depth + 1)
            }

        private fun key(): Any? = if (r.nextInt(12) == 0) listOf(null, 3, 2L, Any()).random(r) else string()

        private fun map(depth: Int): Map<Any?, Any?> = (0 until r.nextInt(0, 7)).associate { key() to value(depth) }

        /** Mostly a real tag with real field names, with values that may or may not be valid. */
        private fun record(): Map<Any?, Any?> =
            buildMap {
                put(
                    if (r.nextInt(10) ==
                        0
                    ) {
                        key()
                    } else {
                        "t"
                    },
                    if (r.nextInt(5) == 0) string() else KeyTag.entries.random(r).tag,
                )
                repeat(r.nextInt(0, 4)) {
                    val field =
                        listOf(
                            "chat",
                            "doc",
                            "draft",
                            "message",
                            "anchor",
                            "focus",
                            "node",
                            "model",
                            "category",
                        ).random(r)
                    put(
                        field,
                        if (r.nextInt(3) ==
                            0
                        ) {
                            value(4)
                        } else if (field == "anchor") {
                            r.nextInt(-2, 5000)
                        } else {
                            ids.random(r)
                        },
                    )
                }
            }

        fun stateTree(): Map<Any?, Any?> =
            buildMap {
                if (r.nextInt(4) != 0) put("top", if (r.nextBoolean()) Destination.entries.random(r).name else string())
                if (r.nextInt(3) == 0) put("space", if (r.nextBoolean()) ids.random(r) else value(3))
                for (d in Destination.entries) {
                    if (r.nextInt(5) == 0) continue
                    put(
                        d.name,
                        if (r.nextInt(8) ==
                            0
                        ) {
                            value(2)
                        } else {
                            List(r.nextInt(0, 8)) { if (r.nextInt(6) == 0) value(3) else record() }
                        },
                    )
                }
            }

        /** One random edit: drop, rename, retype, truncate, shuffle, duplicate or replace, somewhere in the tree. */
        fun mutate(
            tree: Any?,
            depth: Int,
        ): Any? =
            when (tree) {
                is Map<*, *> -> {
                    val entries = tree.entries.map { it.key to it.value }.toMutableList()
                    if (entries.isEmpty()) {
                        value(depth)
                    } else {
                        val i = r.nextInt(entries.size)
                        when (r.nextInt(6)) {
                            0 -> entries.removeAt(i)
                            1 -> entries[i] = key() to entries[i].second
                            2 -> entries[i] = entries[i].first to value(depth + 1)
                            3 -> entries += key() to value(depth + 1)
                            else -> entries[i] = entries[i].first to mutate(entries[i].second, depth + 1)
                        }
                        entries.toMap()
                    }
                }
                is List<*> -> {
                    val items = tree.toMutableList()
                    if (items.isEmpty()) {
                        List(r.nextInt(0, 3)) { value(depth + 1) }
                    } else {
                        val i = r.nextInt(items.size)
                        when (r.nextInt(7)) {
                            0 -> items.subList(r.nextInt(items.size), items.size).clear()
                            1 -> items.removeAt(i)
                            2 -> items.add(i, value(depth + 1))
                            3 -> items.shuffle(r)
                            4 -> items.add(i, items[i])
                            else -> items[i] = mutate(items[i], depth + 1)
                        }
                        items
                    }
                }
                is String ->
                    when (r.nextInt(5)) {
                        0 -> tree.take(r.nextInt(0, tree.length + 1))
                        1 -> tree.uppercase()
                        2 -> tree + string()
                        else -> string()
                    }
                is Int -> listOf(-tree - 1, tree.toLong(), tree.toString(), Int.MAX_VALUE, value(depth)).random(r)
                else -> value(depth)
            }
    }

    private companion object {
        const val RANDOM_ITERATIONS = 10_000
        const val MUTATION_ITERATIONS = 10_000
    }
}
