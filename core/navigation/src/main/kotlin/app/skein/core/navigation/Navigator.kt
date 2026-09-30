package app.skein.core.navigation

/**
 * The shell's mode (spec §2.2), as far as navigation is concerned. AL-08 derives
 * it from AL-04's `SkeinLayoutDecision`: a drawer (Phone and Short windows) is
 * [PHONE]; with a rail, `maxPanes` 1, 2 and 3 are [SINGLE], [DUAL] and [TRIPLE].
 */
enum class NavMode { PHONE, SINGLE, DUAL, TRIPLE }

/** §8.3 rule 1 (go to the object's home destination) or rule 2 (follow: push onto the current stack). */
enum class OpenMode { GO_TO, FOLLOW }

/**
 * What an id names, as the vault (B8 `kindsOf`), the message table, the model
 * registry or the Space list reports it. [Navigator.openDocument] takes the first three.
 */
enum class ObjectKind { CHAT, NOTE, FILE, MESSAGE, MODEL, SPACE }

/**
 * The spec §8.3 rules as pure transitions: each function takes a state and
 * returns the next one (or null where Back is not consumed). No I/O, no logging,
 * nothing thrown on user or restored input (M4c, M13).
 *
 * Raw ids from the vault or the registry enter only through the `open…`/
 * [selectGraphNode] functions, which validate them: a canonical id becomes a
 * typed key; anything else (a foreign frontmatter id, a `tag:`/`title:`/
 * `entity:` graph node, a model filename slug) opens as a [TransientKey] that is
 * never saved (M1a, M2a). [newHandle] mints transient handles; tests inject it.
 */
class Navigator(
    private val newHandle: () -> SkeinId = SkeinId::random,
) {
    /**
     * Rail or drawer selection: switch destinations, every stack intact (§8.1).
     * Re-selecting the current destination pops its stack to the root (§8.3 rule 5).
     */
    fun switchTo(
        state: SkeinNavigationState,
        destination: Destination,
    ): SkeinNavigationState =
        if (destination == state.topLevel) {
            state.withStack(destination, state.stack(destination).take(1))
        } else {
            state.copy(topLevel = destination)
        }

    /**
     * Go to (§8.3 rule 1): switch to the key's home destination and make it that
     * stack's detail, over the existing root. A root key replaces the root and
     * clears the stack ("Centre here", "Open in Graph").
     *
     * The same transition serves rule 3 (selecting from a visible list replaces
     * the detail; on one pane it pushes the detail onto the list, which is the
     * same stack) and rule 5 (new chat: `goTo(NewChatKey(draftId))` pops Chat to
     * its root and pushes the one draft; `goTo(NewNoteKey(draftId))` likewise).
     */
    fun goTo(
        state: SkeinNavigationState,
        key: SkeinKey,
    ): SkeinNavigationState {
        val destination = key.destination
        val stack = if (key.isRoot) listOf(key) else listOf(state.stack(destination).first(), key)
        return state.withStack(destination, stack, topLevel = destination)
    }

    /**
     * Follow (§8.3 rule 2): push onto the current stack, whatever the key's home
     * destination, so Back returns to the same place. De-duplicates (rule 4): if
     * the key's identity is already in the stack, pop back to it and take the new
     * key's fields (an anchor, the inspector's focus). A root key is a go to.
     */
    fun follow(
        state: SkeinNavigationState,
        key: SkeinKey,
    ): SkeinNavigationState {
        if (key.isRoot) return goTo(state, key)
        val stack = state.currentStack
        val at = stack.indexOfFirst { identity(it) == identity(key) }
        return state.withStack(state.topLevel, if (at >= 0) stack.take(at) + key else stack + key)
    }

    /** Open by kind (IA §2 principle 2; LC-20): a chat, note or file id, validated here. */
    fun openDocument(
        state: SkeinNavigationState,
        rawId: String,
        kind: ObjectKind,
        mode: OpenMode = OpenMode.GO_TO,
        anchor: Int? = null,
    ): SkeinNavigationState {
        val id = SkeinId.parse(rawId)
        val at = anchor?.takeIf { it >= 0 }
        return when (kind) {
            ObjectKind.CHAT -> open(state, id?.let(::ChatKey), TransientKind.CHAT, rawId, null, mode)
            ObjectKind.NOTE -> open(state, id?.let { NoteKey(it, at) }, TransientKind.NOTE, rawId, at, mode)
            ObjectKind.FILE -> open(state, id?.let { FileKey(it, at) }, TransientKind.FILE, rawId, at, mode)
            ObjectKind.MESSAGE, ObjectKind.MODEL, ObjectKind.SPACE -> throw IllegalArgumentException(
                "not a document kind: $kind",
            )
        }
    }

    /** Model details: go to ("Manage models ›") or follow ("Model details ›" in the Model sheet). */
    fun openModel(
        state: SkeinNavigationState,
        rawId: String,
        mode: OpenMode = OpenMode.GO_TO,
    ): SkeinNavigationState =
        open(state, SkeinId.parse(rawId)?.let(::ModelDetailsKey), TransientKind.MODEL, rawId, null, mode)

    /** A citation or inspector passage (§8.3 rule 2): follows [ChatSourceKey]. */
    fun openSource(
        state: SkeinNavigationState,
        chatId: SkeinId,
        rawDocId: String,
        anchor: Int? = null,
    ): SkeinNavigationState {
        val at = anchor?.takeIf { it >= 0 }
        val key = SkeinId.parse(rawDocId)?.let { ChatSourceKey(chatId, it, at) }
        return open(state, key, TransientKind.SOURCE, rawDocId, at, OpenMode.FOLLOW)
    }

    /** A note's or file's Connections: follows [ConnectionsKey]. */
    fun openConnections(
        state: SkeinNavigationState,
        rawDocId: String,
    ): SkeinNavigationState =
        open(
            state,
            SkeinId.parse(rawDocId)?.let(::ConnectionsKey),
            TransientKind.CONNECTIONS,
            rawDocId,
            null,
            OpenMode.FOLLOW,
        )

    /**
     * Go to Graph centred on a document ("Open in Graph", "Centre here"): the root
     * is replaced and the old canvas's node detail closes. Null centres on the
     * default. ponytail: a non-canonical focus also falls back to the default
     * (a root cannot be transient); M2(b)'s import re-minting removes the case.
     */
    fun openGraph(
        state: SkeinNavigationState,
        rawFocusId: String? = null,
    ): SkeinNavigationState = goTo(state, GraphKey(SkeinId.parse(rawFocusId)))

    /** Selects a graph node: it replaces any selected node, and Back deselects. Only document ids are saved (M1a). */
    fun selectGraphNode(
        state: SkeinNavigationState,
        rawNodeId: String,
    ): SkeinNavigationState {
        val focus = (state.stack(Destination.GRAPH).first() as? GraphKey)?.focusDocId
        val key = SkeinId.parse(rawNodeId)?.let { GraphNodeKey(focus, it) }
        return open(state, key, TransientKind.GRAPH_NODE, rawNodeId, null, OpenMode.GO_TO)
    }

    /** The first send or commit replaces the draft key, wherever it is, with the created object's key. */
    fun promoteDraft(
        state: SkeinNavigationState,
        draftId: SkeinId,
        created: SkeinKey,
    ): SkeinNavigationState {
        fun isDraft(key: SkeinKey) =
            (key is NewChatKey && key.draftId == draftId) || (key is NewNoteKey && key.draftId == draftId)
        val stacks = state.stacks.mapValues { (_, stack) -> stack.map { if (isDraft(it)) created else it } }
        return normalised(state.topLevel, stacks, state.space, state.transientIds)
    }

    /**
     * Sets the current Space (IA §8b); null is the default Space. The stacks are
     * kept: ponytail, what a switch does to open objects belongs to the Spaces bead.
     */
    fun switchSpace(
        state: SkeinNavigationState,
        space: SkeinId?,
    ): SkeinNavigationState = state.copy(space = space)

    /**
     * What `NavDisplay` is given for [stack] in [mode]. An explicit new chat
     * keeps its own draft identity, even where the root also shows a landing.
     * Phone elides the root below that draft; wider windows keep Conversations
     * beside the actual draft entry. The saved stack is untouched.
     */
    fun visibleStack(
        stack: List<SkeinKey>,
        mode: NavMode,
    ): List<SkeinKey> =
        if (mode == NavMode.PHONE && isChatLandingRoot(stack, mode)) {
            stack.takeLast(1)
        } else {
            stack
        }

    /** A bare unsent landing has no visible Back target except Single's Conversations list. */
    fun isChatLandingRoot(
        stack: List<SkeinKey>,
        mode: NavMode,
    ): Boolean = mode != NavMode.SINGLE && stack.size == 2 && stack[0] == ChatHomeKey && stack[1] is NewChatKey

    /**
     * System Back after the IME and transient overlays (§3.6 steps 3–6): pop the
     * top visible entry (a sheet, a pane or a detail); at another destination's
     * root, switch to Chat with its stack unchanged; at the Chat root, null: not
     * consumed, so the system plays its back-to-home. Every consumed Back changes
     * what is on screen.
     */
    fun back(
        state: SkeinNavigationState,
        mode: NavMode,
    ): SkeinNavigationState? =
        escape(state, mode) ?: if (state.topLevel != Destination.CHAT) state.copy(topLevel = Destination.CHAT) else null

    /** Esc on a hardware keyboard (§3.6): pops like [back] but never switches destination or leaves. */
    fun escape(
        state: SkeinNavigationState,
        mode: NavMode,
    ): SkeinNavigationState? {
        if (isChatLandingRoot(state.currentStack, mode)) return null
        val visible = visibleStack(state.currentStack, mode)
        return if (visible.size > 1) state.withStack(state.topLevel, visible.dropLast(1)) else null
    }

    /**
     * Delete (§8.3 rule 8; `OBJECT_LIFECYCLE_SPEC.md` §3.5, LC-20): removes every
     * entry whose key names [id], from every stack, pane entries included (the
     * inspector, a source, Connections, a node detail), and transient entries for
     * that raw id. A deleted graph focus re-centres on the default; a deleted
     * inspector focus falls back to the latest answer; a deleted Space falls back
     * to the default. The top level is kept, so the new top of the same stack
     * shows: deleting the open chat leaves the Chat root and never opens another.
     */
    fun prune(
        state: SkeinNavigationState,
        id: String,
        includeSavedNoteDraft: Boolean = false,
    ): SkeinNavigationState {
        val target = SkeinId.parse(id)
        val deleted = { x: SkeinId, role: IdRole -> role != IdRole.DRAFT && x == target }
        val stacks =
            state.stacks.mapValues { (_, stack) ->
                stack.mapNotNull { key ->
                    when {
                        key is TransientKey -> key.takeUnless { state.rawIdOf(it) == id }
                        includeSavedNoteDraft && key is NewNoteKey && key.draftId == target -> null
                        else -> key.surviving(deleted)
                    }
                }
            }
        return normalised(state.topLevel, stacks, state.space.takeUnless { it == target }, state.transientIds)
    }

    /**
     * After unlock and after a process-death restore, before the first entry
     * renders (§8.3 rule 7, M4d): silently drops every entry with an id that no
     * longer resolves or whose kind changed. Draft keys are kept (never stale).
     * Optional ids degrade instead: a graph focus re-centres on the default, an
     * inspector focus falls back to the latest answer, a Space to the default.
     * Transient entries are dropped: their ids cannot be checked.
     *
     * [kindOf] is content-free and must not throw; AL-08 builds it from one batch
     * lookup over [referencedIds].
     */
    fun sanitise(
        state: SkeinNavigationState,
        kindOf: (SkeinId) -> ObjectKind?,
    ): SkeinNavigationState {
        val gone = { id: SkeinId, role: IdRole -> role != IdRole.DRAFT && kindOf(id) !in role.accepted }
        val stacks =
            state.stacks.mapValues { (_, stack) ->
                stack.filterNot { it is TransientKey }.mapNotNull { it.surviving(gone) }
            }
        val space = state.space?.takeIf { kindOf(it) == ObjectKind.SPACE }
        return normalised(state.topLevel, stacks, space, emptyMap())
    }

    /** Every id [sanitise] asks `kindOf` about: all but draft ids and transient handles. */
    fun referencedIds(state: SkeinNavigationState): Set<SkeinId> =
        buildSet {
            // Visits each key's ids through the same table sanitise uses; "never gone", so nothing changes.
            for (key in state.allKeys()) {
                key.surviving { id, role ->
                    if (role != IdRole.DRAFT) add(id)
                    false
                }
            }
            state.space?.let(::add)
        }

    /** At a vault lock (M1a, M12): transient entries close and their raw ids leave memory. */
    fun dropTransient(state: SkeinNavigationState): SkeinNavigationState =
        normalised(
            state.topLevel,
            state.stacks.mapValues { (_, s) ->
                s.filterNot { it is TransientKey }
            },
            state.space,
            emptyMap(),
        )

    private fun open(
        state: SkeinNavigationState,
        typed: SkeinKey?,
        kind: TransientKind,
        rawId: String,
        anchor: Int?,
        mode: OpenMode,
    ): SkeinNavigationState {
        var next = state
        val key =
            typed ?: run {
                // Re-opening the same foreign object reuses its handle, so de-duplication still applies.
                val handle =
                    state
                        .allKeys()
                        .filterIsInstance<TransientKey>()
                        .firstOrNull {
                            it.kind == kind &&
                                state.rawIdOf(it) == rawId
                        }?.handle
                        ?: newHandle()
                next = state.copy(transientIds = state.transientIds + (handle to rawId))
                TransientKey(kind, handle, anchor)
            }
        return when (mode) {
            OpenMode.GO_TO -> goTo(next, key)
            OpenMode.FOLLOW -> follow(next, key)
        }
    }
}

/** What each id field of a key must resolve to (§8.3 rule 7). */
internal enum class IdRole(
    vararg kinds: ObjectKind,
) {
    CHAT(ObjectKind.CHAT),
    NOTE(ObjectKind.NOTE),
    FILE(ObjectKind.FILE),
    NOTE_OR_FILE(ObjectKind.NOTE, ObjectKind.FILE),
    DOCUMENT(ObjectKind.CHAT, ObjectKind.NOTE, ObjectKind.FILE),
    MESSAGE(ObjectKind.MESSAGE),
    MODEL(ObjectKind.MODEL),

    /** A draft id: a row may not exist yet, and the key is never stale. */
    DRAFT,
    ;

    val accepted: Set<ObjectKind> = kinds.toSet()
}

/**
 * The key with every id [gone] removed: null if a required id is gone, a degraded
 * key if only an optional focus is. Shared by prune, sanitise and referencedIds.
 * Transient keys pass through; their callers handle them.
 */
internal fun SkeinKey.surviving(gone: (SkeinId, IdRole) -> Boolean): SkeinKey? =
    when (this) {
        ChatHomeKey, KnowledgeHomeKey, ModelsHomeKey, SettingsHomeKey, is SettingsCategoryKey, is TransientKey -> this
        is NewChatKey -> takeUnless { gone(draftId, IdRole.DRAFT) }
        is NewNoteKey -> takeUnless { gone(draftId, IdRole.DRAFT) }
        is ChatKey -> takeUnless { gone(chatId, IdRole.CHAT) }
        is ChatContextKey ->
            when {
                gone(chatId, IdRole.CHAT) -> null
                focusMessageId != null && gone(focusMessageId, IdRole.MESSAGE) -> copy(focusMessageId = null)
                else -> this
            }
        is ChatSourceKey -> takeUnless { gone(chatId, IdRole.CHAT) || gone(docId, IdRole.NOTE_OR_FILE) }
        is NoteKey -> takeUnless { gone(docId, IdRole.NOTE) }
        is FileKey -> takeUnless { gone(docId, IdRole.FILE) }
        is ConnectionsKey -> takeUnless { gone(docId, IdRole.NOTE_OR_FILE) }
        is GraphKey -> if (focusDocId != null && gone(focusDocId, IdRole.DOCUMENT)) GraphKey() else this
        is GraphNodeKey ->
            takeUnless { gone(nodeDocId, IdRole.DOCUMENT) || (focusDocId != null && gone(focusDocId, IdRole.DOCUMENT)) }
        is ModelDetailsKey -> takeUnless { gone(modelId, IdRole.MODEL) }
    }
