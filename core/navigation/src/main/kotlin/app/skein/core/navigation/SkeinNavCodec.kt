package app.skein.core.navigation

/** Serial tags: what the saved form and [contentKey] call each key. Stable; renaming one drops old entries. */
internal enum class KeyTag(
    val tag: String,
) {
    CHAT_HOME("chat.home"),
    NEW_CHAT("chat.new"),
    CHAT("chat"),
    CHAT_CONTEXT("chat.context"),
    CHAT_SOURCE("chat.source"),
    KNOWLEDGE_HOME("knowledge.home"),
    NOTE("note"),
    NEW_NOTE("note.new"),
    FILE("file"),
    CONNECTIONS("connections"),
    GRAPH("graph"),
    GRAPH_NODE("graph.node"),
    MODELS_HOME("models.home"),
    MODEL("model"),
    SETTINGS_HOME("settings.home"),
    SETTINGS_CATEGORY("settings.category"),

    /** Content keys only; never in the saved form. */
    TRANSIENT("transient"),
}

internal val SkeinKey.keyTag: KeyTag
    get() =
        when (this) {
            ChatHomeKey -> KeyTag.CHAT_HOME
            is NewChatKey -> KeyTag.NEW_CHAT
            is ChatKey -> KeyTag.CHAT
            is ChatContextKey -> KeyTag.CHAT_CONTEXT
            is ChatSourceKey -> KeyTag.CHAT_SOURCE
            KnowledgeHomeKey -> KeyTag.KNOWLEDGE_HOME
            is NoteKey -> KeyTag.NOTE
            is NewNoteKey -> KeyTag.NEW_NOTE
            is FileKey -> KeyTag.FILE
            is ConnectionsKey -> KeyTag.CONNECTIONS
            is GraphKey -> KeyTag.GRAPH
            is GraphNodeKey -> KeyTag.GRAPH_NODE
            ModelsHomeKey -> KeyTag.MODELS_HOME
            is ModelDetailsKey -> KeyTag.MODEL
            SettingsHomeKey -> KeyTag.SETTINGS_HOME
            is SettingsCategoryKey -> KeyTag.SETTINGS_CATEGORY
            is TransientKey -> KeyTag.TRANSIENT
        }

/**
 * `tag/uuid…`: what Nav3 must key per-entry state by (`NavEntry(contentKey = …)`).
 * Nav3's default is the key's `toString()`, which would carry any field a key
 * ever gains into the Bundle (§8.9 item 2). Only identity goes in: the anchor and
 * the inspector's focus are left out, so moving them keeps the entry and its state.
 *
 * Unique within a stack (the state is normalised by it), **not** across stacks:
 * the same note can sit in Knowledge and, followed, in Graph. AL-08 must keep
 * one entry-state scope per destination, or qualify it by destination.
 */
val SkeinKey.contentKey: String
    get() {
        val tag = keyTag.tag
        return when (this) {
            ChatHomeKey, KnowledgeHomeKey, ModelsHomeKey, SettingsHomeKey -> tag
            is NewChatKey -> "$tag/${draftId.value}"
            is ChatKey -> "$tag/${chatId.value}"
            is ChatContextKey -> "$tag/${chatId.value}"
            is ChatSourceKey -> "$tag/${chatId.value}/${docId.value}"
            is NoteKey -> "$tag/${docId.value}"
            is NewNoteKey -> "$tag/${draftId.value}"
            is FileKey -> "$tag/${docId.value}"
            is ConnectionsKey -> "$tag/${docId.value}"
            is GraphKey -> if (focusDocId == null) tag else "$tag/${focusDocId.value}"
            is GraphNodeKey -> "$tag/${nodeDocId.value}"
            is ModelDetailsKey -> "$tag/${modelId.value}"
            is SettingsCategoryKey -> "$tag/${category.name}"
            is TransientKey -> "$tag/${handle.value}"
        }
    }

/**
 * The total codec between [SkeinNavigationState] and its saved form
 * (`SECURITY_REVIEW_D7.md` M1–M4c; spec §7.2, §8.9 item 1).
 *
 * **The saved form** is a tree of `Map<String, Any>`, `List` and leaves that are
 * `String` or `Int` only:
 * ```
 * { "top": "CHAT", "space": "<uuid>",                       // space omitted for the default Space
 *   "CHAT": [ {"t": "chat.home"}, {"t": "chat", "chat": "<uuid>"}, {"t": "chat.source", "chat": "<uuid>", "doc": "<uuid>", "anchor": 812} ],
 *   "KNOWLEDGE": [...], "GRAPH": [...], "MODELS": [...], "SETTINGS": [...] }
 * ```
 * Every string in it satisfies [isAllowedString]: a tag, a field or structural
 * name, a destination or enum constant name, or a canonical UUID. AL-08 maps it
 * 1:1 onto a `Bundle` (a map is a `Bundle`, a list an `ArrayList<Bundle>`, leaves
 * `putString`/`putInt`) and back. [TransientKey]s and the raw-id table are never
 * written (M2a).
 *
 * **Decoding is total** (M4c): any input, including garbage, truncated or
 * renamed entries, decodes without throwing. An entry with an unknown tag, a
 * missing or invalid field or a wrong type is dropped on its own; the result is
 * normalised, so an empty or all-invalid stack becomes its root and a missing
 * top level becomes Chat.
 */
object SkeinNavCodec {
    private const val TOP = "top"
    private const val SPACE = "space"
    private const val TAG = "t"
    private const val CHAT = "chat"
    private const val DOC = "doc"
    private const val DRAFT = "draft"
    private const val MESSAGE = "message"
    private const val ANCHOR = "anchor"
    private const val FOCUS = "focus"
    private const val NODE = "node"
    private const val MODEL = "model"
    private const val CATEGORY = "category"

    private val SAVED_TAGS = KeyTag.entries.filter { it != KeyTag.TRANSIENT }.associateBy { it.tag }
    private val CONTENT_KEY_TAGS = KeyTag.entries.mapTo(HashSet()) { it.tag }
    private val SETTINGS_CATEGORIES = SettingsCategory.entries.associateBy { it.name }

    /** Every name the saved form can contain that is not an id. */
    private val VOCABULARY: Set<String> =
        buildSet {
            addAll(SAVED_TAGS.keys)
            addAll(listOf(TOP, SPACE, TAG, CHAT, DOC, DRAFT, MESSAGE, ANCHOR, FOCUS, NODE, MODEL, CATEGORY))
            addAll(Destination.entries.map { it.name })
            addAll(SETTINGS_CATEGORIES.keys)
        }

    fun encode(state: SkeinNavigationState): Map<String, Any> =
        buildMap {
            put(TOP, state.topLevel.name)
            state.space?.let { put(SPACE, it.value) }
            for (d in Destination.entries) put(d.name, state.stack(d).mapNotNull(::encodeKey))
        }

    /** Never throws; see the class KDoc. [saved] is whatever came back out of the Bundle. */
    fun decode(saved: Any?): SkeinNavigationState {
        val map = saved as? Map<*, *> ?: return SkeinNavigationState.initial()
        return try {
            val top = Destination.entries.firstOrNull { it.name == map[TOP] } ?: Destination.CHAT
            val stacks = Destination.entries.associateWith { decodeStack(map[it.name]) }
            normalised(top, stacks, (map[SPACE] as? String)?.let(SkeinId::parse), transientIds = emptyMap())
        } catch (_: RuntimeException) {
            SkeinNavigationState.initial()
        }
    }

    private fun decodeStack(saved: Any?): List<SkeinKey> = (saved as? List<*>).orEmpty().mapNotNull(::decodeKey)

    internal fun encodeKey(key: SkeinKey): Map<String, Any>? =
        when (key) {
            ChatHomeKey, KnowledgeHomeKey, ModelsHomeKey, SettingsHomeKey -> record(key)
            is NewChatKey -> record(key, DRAFT to key.draftId)
            is ChatKey -> record(key, CHAT to key.chatId)
            is ChatContextKey -> record(key, CHAT to key.chatId, MESSAGE to key.focusMessageId)
            is ChatSourceKey -> record(key, CHAT to key.chatId, DOC to key.docId, ANCHOR to key.anchor)
            is NoteKey -> record(key, DOC to key.docId, ANCHOR to key.anchor)
            is NewNoteKey -> record(key, DRAFT to key.draftId)
            is FileKey -> record(key, DOC to key.docId, ANCHOR to key.anchor)
            is ConnectionsKey -> record(key, DOC to key.docId)
            is GraphKey -> record(key, FOCUS to key.focusDocId)
            is GraphNodeKey -> record(key, FOCUS to key.focusDocId, NODE to key.nodeDocId)
            is ModelDetailsKey -> record(key, MODEL to key.modelId)
            is SettingsCategoryKey -> record(key, CATEGORY to key.category.name)
            is TransientKey -> null
        }

    private fun record(
        key: SkeinKey,
        vararg fields: Pair<String, Any?>,
    ): Map<String, Any> =
        buildMap {
            put(TAG, key.keyTag.tag)
            for ((name, value) in fields) {
                when (value) {
                    null -> Unit
                    is SkeinId -> put(name, value.value)
                    else -> put(name, value)
                }
            }
        }

    /** One entry: null (dropped) unless every field is present where required and valid where present. */
    internal fun decodeKey(saved: Any?): SkeinKey? =
        try {
            val record = saved as? Map<*, *>
            val tag = (record?.get(TAG) as? String)?.let(SAVED_TAGS::get)
            if (record == null || tag == null) null else Fields(record).key(tag)
        } catch (_: RuntimeException) {
            null
        }

    private class Invalid : RuntimeException(null, null, false, false)

    private class Fields(
        private val record: Map<*, *>,
    ) {
        fun key(tag: KeyTag): SkeinKey? =
            when (tag) {
                KeyTag.CHAT_HOME -> ChatHomeKey
                KeyTag.NEW_CHAT -> NewChatKey(id(DRAFT))
                KeyTag.CHAT -> ChatKey(id(CHAT))
                KeyTag.CHAT_CONTEXT -> ChatContextKey(id(CHAT), optId(MESSAGE))
                KeyTag.CHAT_SOURCE -> ChatSourceKey(id(CHAT), id(DOC), optAnchor())
                KeyTag.KNOWLEDGE_HOME -> KnowledgeHomeKey
                KeyTag.NOTE -> NoteKey(id(DOC), optAnchor())
                KeyTag.NEW_NOTE -> NewNoteKey(id(DRAFT))
                KeyTag.FILE -> FileKey(id(DOC), optAnchor())
                KeyTag.CONNECTIONS -> ConnectionsKey(id(DOC))
                KeyTag.GRAPH -> GraphKey(optId(FOCUS))
                KeyTag.GRAPH_NODE -> GraphNodeKey(optId(FOCUS), id(NODE))
                KeyTag.MODELS_HOME -> ModelsHomeKey
                KeyTag.MODEL -> ModelDetailsKey(id(MODEL))
                KeyTag.SETTINGS_HOME -> SettingsHomeKey
                KeyTag.SETTINGS_CATEGORY ->
                    SettingsCategoryKey(
                        (record[CATEGORY] as? String)?.let(SETTINGS_CATEGORIES::get) ?: throw Invalid(),
                    )
                KeyTag.TRANSIENT -> null
            }

        private fun id(name: String): SkeinId = optId(name) ?: throw Invalid()

        private fun optId(name: String): SkeinId? =
            when (val value = record[name]) {
                null -> null
                is String -> SkeinId.parse(value) ?: throw Invalid()
                else -> throw Invalid()
            }

        private fun optAnchor(): Int? =
            when (val value = record[ANCHOR]) {
                null -> null
                is Int -> value.takeIf { it >= 0 } ?: throw Invalid()
                else -> throw Invalid()
            }
    }

    /**
     * The allowlist (M3a, deny by default) for every string navigation puts in
     * the saved state: the saved form's names and values, and the [contentKey]s
     * that Nav3's entry decorators save as map keys. AL-15's Bundle scan admits
     * a navigation string only if this says so. User text, titles, `tag:`/`title:`
     * ids, slugs, class names and non-canonical UUIDs all fail.
     */
    fun isAllowedString(s: String): Boolean = s in VOCABULARY || SkeinId.isCanonical(s) || isContentKey(s)

    private fun isContentKey(s: String): Boolean {
        val parts = s.split('/')
        return parts.size in 2..3 &&
            parts[0] in CONTENT_KEY_TAGS &&
            parts.drop(1).all { SkeinId.isCanonical(it) || it in SETTINGS_CATEGORIES }
    }

    /** One finding of [violations]; [value] is the offending string or type, for the test's failure message. */
    data class Violation(
        val path: String,
        val value: String,
    )

    /**
     * The privacy check for a saved form (M3a): every map key and string leaf must
     * pass [isAllowedString], and the only other things allowed are `Int` leaves,
     * maps and lists. Empty means clean. A test helper that AL-15 can reuse on a
     * Bundle converted to the same tree.
     */
    fun violations(saved: Any?): List<Violation> = mutableListOf<Violation>().also { walk(saved, "$", it) }

    private fun walk(
        value: Any?,
        path: String,
        out: MutableList<Violation>,
    ) {
        when (value) {
            is Int -> Unit
            is String -> if (!isAllowedString(value)) out += Violation(path, value)
            is Map<*, *> ->
                for ((k, v) in value) {
                    if (k !is String || !isAllowedString(k)) out += Violation("$path{key}", k.toString())
                    walk(v, "$path.$k", out)
                }
            is List<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", out) }
            else -> out += Violation(path, "type ${value?.javaClass?.name ?: "null"}")
        }
    }
}
