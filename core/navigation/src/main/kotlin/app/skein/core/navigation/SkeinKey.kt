package app.skein.core.navigation

/** The five primary destinations (IA §3.2), in rail order. Each has one back stack (spec §8.1). */
enum class Destination { CHAT, KNOWLEDGE, GRAPH, MODELS, SETTINGS }

/**
 * The pane a key asks the scene strategies for: spec §8.2's "Role metadata"
 * column. AL-08 maps it to `ListDetailSceneStrategy.listPane/detailPane/extraPane`
 * and `SupportingPaneSceneStrategy.mainPane/supportingPane`.
 */
enum class PaneRole { LIST, DETAIL, EXTRA, MAIN, SUPPORTING }

/** Settings categories (IA §3.2; "Personas" is "Spaces" per IA §8b). */
enum class SettingsCategory { APPEARANCE, PRIVACY_AND_SECURITY, SPACES, KNOWLEDGE_AND_SEARCH, ABOUT, ADVANCED }

/**
 * A back-stack key (spec §8.2). Every field is a [SkeinId], an enum or a
 * non-negative `Int`: never a `String` (`SECURITY_REVIEW_D7.md` M1, pinned by
 * `SkeinKeyFieldTypesTest`). Titles, queries, heading slugs, graph `tag:` and
 * `title:` node ids and model filename slugs cannot be expressed.
 *
 * Deliberately not a Nav3 `NavKey`: that marker exists only for
 * `rememberNavBackStack`/`NavKeySerializer`, which Skein must not use (§8.9),
 * so leaving it off makes that misuse a compile error. `NavDisplay` and
 * `rememberDecoratedNavEntries` accept any type.
 *
 * [destination] is the key's home destination and scene key; [role] its pane;
 * [contentKey] the `tag/uuid` Nav3 must key entry state by.
 */
sealed interface SkeinKey

/** The Chat root. How it renders depends on the mode (spec §8.2): the landing, or the Conversations list. */
data object ChatHomeKey : SkeinKey

/** A draft key: the new-chat landing (`OBJECT_LIFECYCLE_SPEC.md` C1). Never stale; at most one per stack. */
data class NewChatKey(
    val draftId: SkeinId,
) : SkeinKey

data class ChatKey(
    val chatId: SkeinId,
) : SkeinKey

/** The context inspector, on the latest answer or on [focusMessageId] (`CHAT_UX_SPEC.md` §17.2). */
data class ChatContextKey(
    val chatId: SkeinId,
    val focusMessageId: SkeinId? = null,
) : SkeinKey

/** A cited note or file opened at the passage; [anchor] is a byte offset, never text (M1c). */
data class ChatSourceKey(
    val chatId: SkeinId,
    val docId: SkeinId,
    val anchor: Int? = null,
) : SkeinKey {
    init {
        requireAnchor(anchor)
    }
}

/** The Knowledge root. Its filter chips are entry state (T2 enums, spec §7.3 row 19), not key fields. */
data object KnowledgeHomeKey : SkeinKey

data class NoteKey(
    val docId: SkeinId,
    val anchor: Int? = null,
) : SkeinKey {
    init {
        requireAnchor(anchor)
    }
}

/** A draft key: [draftId] is pre-minted; the row is created at the first non-blank commit. Never stale. */
data class NewNoteKey(
    val draftId: SkeinId,
) : SkeinKey

data class FileKey(
    val docId: SkeinId,
    val anchor: Int? = null,
) : SkeinKey {
    init {
        requireAnchor(anchor)
    }
}

data class ConnectionsKey(
    val docId: SkeinId,
) : SkeinKey

/** The Graph root; a null focus means the default (the most recently opened document, else the empty state). */
data class GraphKey(
    val focusDocId: SkeinId? = null,
) : SkeinKey

/** A selected graph node: a document only (M1a). Tag and title nodes are [TransientKey]s. */
data class GraphNodeKey(
    val focusDocId: SkeinId?,
    val nodeDocId: SkeinId,
) : SkeinKey

data object ModelsHomeKey : SkeinKey

/** [modelId] is an opaque registry UUID (B9), never a filename slug; today's slug ids open as [TransientKey]s. */
data class ModelDetailsKey(
    val modelId: SkeinId,
) : SkeinKey

data object SettingsHomeKey : SkeinKey

data class SettingsCategoryKey(
    val category: SettingsCategory,
) : SkeinKey

/** What a [TransientKey] stands in for; it fixes the transient entry's destination and pane. */
enum class TransientKind(
    val destination: Destination,
    val role: PaneRole,
) {
    CHAT(Destination.CHAT, PaneRole.DETAIL),
    NOTE(Destination.KNOWLEDGE, PaneRole.DETAIL),
    FILE(Destination.KNOWLEDGE, PaneRole.DETAIL),
    SOURCE(Destination.CHAT, PaneRole.EXTRA),
    CONNECTIONS(Destination.KNOWLEDGE, PaneRole.EXTRA),
    GRAPH_NODE(Destination.GRAPH, PaneRole.SUPPORTING),
    MODEL(Destination.MODELS, PaneRole.DETAIL),
}

/**
 * An object whose id failed [SkeinId] validation (M2a): it opens, but it is never
 * saved. [handle] is a random id; the raw id sits in memory only, in the state's
 * transient table ([SkeinNavigationState.rawIdOf]). The codec drops these
 * entries, so a restore lands on what was beneath them; [Navigator.dropTransient]
 * closes them at a lock (M1a).
 */
data class TransientKey(
    val kind: TransientKind,
    val handle: SkeinId,
    val anchor: Int? = null,
) : SkeinKey {
    init {
        requireAnchor(anchor)
    }
}

private fun requireAnchor(anchor: Int?) = require(anchor == null || anchor >= 0) { "anchor must be non-negative" }

val SkeinKey.destination: Destination
    get() =
        when (this) {
            ChatHomeKey, is NewChatKey, is ChatKey, is ChatContextKey, is ChatSourceKey -> Destination.CHAT
            KnowledgeHomeKey, is NoteKey, is NewNoteKey, is FileKey, is ConnectionsKey -> Destination.KNOWLEDGE
            is GraphKey, is GraphNodeKey -> Destination.GRAPH
            ModelsHomeKey, is ModelDetailsKey -> Destination.MODELS
            SettingsHomeKey, is SettingsCategoryKey -> Destination.SETTINGS
            is TransientKey -> kind.destination
        }

val SkeinKey.role: PaneRole
    get() =
        when (this) {
            ChatHomeKey, KnowledgeHomeKey, ModelsHomeKey, SettingsHomeKey -> PaneRole.LIST
            is GraphKey -> PaneRole.MAIN
            is NewChatKey, is ChatKey -> PaneRole.DETAIL
            is NoteKey, is NewNoteKey, is FileKey, is ModelDetailsKey, is SettingsCategoryKey -> PaneRole.DETAIL
            is ChatContextKey, is ChatSourceKey, is ConnectionsKey -> PaneRole.EXTRA
            is GraphNodeKey -> PaneRole.SUPPORTING
            is TransientKey -> kind.role
        }

/** A destination root: only ever at the bottom of its own destination's stack. */
val SkeinKey.isRoot: Boolean get() = role == PaneRole.LIST || role == PaneRole.MAIN

internal fun rootOf(destination: Destination): SkeinKey =
    when (destination) {
        Destination.CHAT -> ChatHomeKey
        Destination.KNOWLEDGE -> KnowledgeHomeKey
        Destination.GRAPH -> GraphKey()
        Destination.MODELS -> ModelsHomeKey
        Destination.SETTINGS -> SettingsHomeKey
    }
