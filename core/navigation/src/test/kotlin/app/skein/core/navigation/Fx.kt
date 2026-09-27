package app.skein.core.navigation

/** Shared ids, one sample of every key type, and deterministic navigators for the tests. */
internal object Fx {
    val chat1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a51")
    val chat2 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a52")
    val msg1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a61")
    val note1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a71")
    val note2 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a72")
    val file1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a81")
    val model1 = SkeinId.of("4b1c9d3e-2f6a-4c8b-9d0e-1a2b3c4d5e6f")
    val space1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a91")
    val draft1 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4aa1")
    val draft2 = SkeinId.of("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4aa2")

    /** Text-like ids the vault and registry produce today, none of which may ever be saved (M1, M2). */
    const val FOREIGN_NOTE = "project-falcon-notes"
    const val TAG_NODE = "tag:divorce"
    const val TITLE_NODE = "title:letter to my lawyer"
    const val ENTITY_NODE = "entity:42"
    const val MODEL_SLUG = "qwen2.5-3b-instruct-q4_k_m-3fa2c1"

    /** One of every [SkeinKey] type except [TransientKey], each field set where it has one. */
    val savedSamples: List<SkeinKey> =
        listOf(
            ChatHomeKey,
            NewChatKey(draft1),
            ChatKey(chat1),
            ChatContextKey(chat1, msg1),
            ChatSourceKey(chat1, note1, 812),
            KnowledgeHomeKey,
            NoteKey(note1, 64),
            NewNoteKey(draft2),
            FileKey(file1, 0),
            ConnectionsKey(note1),
            GraphKey(note1),
            GraphNodeKey(note1, note2),
            ModelsHomeKey,
            ModelDetailsKey(model1),
            SettingsHomeKey,
            SettingsCategoryKey(SettingsCategory.PRIVACY_AND_SECURITY),
        )

    /** Handles minted in a fixed order, so transient states compare equal across runs. */
    fun navigator(): Navigator {
        var n = 0
        return Navigator { SkeinId.of("00000000-0000-4000-8000-%012d".format(++n)) }
    }

    /** A state with something in every stack, a pane on top of Chat and a Space. */
    fun richState(): SkeinNavigationState =
        SkeinNavigationState.of(
            Destination.KNOWLEDGE,
            mapOf(
                Destination.CHAT to
                    listOf(ChatHomeKey, ChatKey(chat1), ChatContextKey(chat1, msg1), ChatSourceKey(chat1, note1, 812)),
                Destination.KNOWLEDGE to listOf(KnowledgeHomeKey, NoteKey(note1, 64), ConnectionsKey(note1)),
                Destination.GRAPH to listOf(GraphKey(note1), GraphNodeKey(note1, note2), NoteKey(note2)),
                Destination.MODELS to listOf(ModelsHomeKey, ModelDetailsKey(model1)),
                Destination.SETTINGS to listOf(SettingsHomeKey, SettingsCategoryKey(SettingsCategory.ADVANCED)),
            ),
            space1,
        )

    fun stateOf(
        topLevel: Destination,
        vararg stacks: Pair<Destination, List<SkeinKey>>,
    ): SkeinNavigationState = SkeinNavigationState.of(topLevel, stacks.toMap())

    /** Every string anywhere in a saved form: map keys and leaves. */
    fun strings(tree: Any?): List<String> =
        when (tree) {
            is String -> listOf(tree)
            is Map<*, *> -> tree.flatMap { (k, v) -> strings(k) + strings(v) }
            is List<*> -> tree.flatMap(::strings)
            else -> emptyList()
        }
}
