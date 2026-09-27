package app.skein.core.navigation

/**
 * The shell's navigation state (spec §8.1): the top-level destination, one back
 * stack per destination, and the current Space (IA §8b; null is the default
 * Space). Immutable; the [Navigator] computes every transition, and AL-08 holds
 * the current value in Compose state above `VaultGate`.
 *
 * Every instance is normalised: each stack starts with its destination's root,
 * holds no other root, and holds each entry identity once (so `contentKey`s are
 * unique within a stack; see [contentKey] for why that matters to Nav3). A
 * [TransientKey] is present only while its raw id is in memory.
 *
 * [SkeinNavCodec] saves [topLevel], [space] and the stacks; never the transient
 * table.
 */
@ConsistentCopyVisibility
data class SkeinNavigationState internal constructor(
    val topLevel: Destination,
    val stacks: Map<Destination, List<SkeinKey>>,
    val space: SkeinId?,
    /** Transient handle → raw (non-canonical) id. Memory only (M2a); it may hold text such as `title:…`. */
    internal val transientIds: Map<SkeinId, String>,
) {
    val currentStack: List<SkeinKey> get() = stack(topLevel)

    fun stack(destination: Destination): List<SkeinKey> = stacks.getValue(destination)

    /** The raw id a [TransientKey] stands for, for the entry provider to load; null once it is gone. */
    fun rawIdOf(key: TransientKey): String? = transientIds[key.handle]

    /** Sizes and flags only (M13): no id or raw id reaches a log line or exception message this way. */
    override fun toString(): String =
        "SkeinNavigationState(topLevel=$topLevel, stackSizes=${Destination.entries.map { stack(it).size }}, " +
            "space=${if (space == null) "default" else "set"}, transient=${transientIds.size})"

    companion object {
        /** Every stack at its root, on Chat, in the default Space. */
        fun initial(): SkeinNavigationState = of(Destination.CHAT, emptyMap())

        /** Builds a normalised state (missing stacks become their roots). A [TransientKey] cannot be passed in. */
        fun of(
            topLevel: Destination,
            stacks: Map<Destination, List<SkeinKey>>,
            space: SkeinId? = null,
        ): SkeinNavigationState = normalised(topLevel, stacks, space, emptyMap())
    }
}

/** The one place states are built: every stack normalised, the transient table cut to live handles. */
internal fun normalised(
    topLevel: Destination,
    stacks: Map<Destination, List<SkeinKey>>,
    space: SkeinId?,
    transientIds: Map<SkeinId, String>,
): SkeinNavigationState {
    val clean = Destination.entries.associateWith { normaliseStack(it, stacks[it].orEmpty(), transientIds) }
    val live =
        clean.values
            .flatten()
            .filterIsInstance<TransientKey>()
            .mapTo(HashSet()) { it.handle }
    return SkeinNavigationState(topLevel, clean, space, transientIds.filterKeys { it in live })
}

private fun normaliseStack(
    destination: Destination,
    keys: List<SkeinKey>,
    transientIds: Map<SkeinId, String>,
): List<SkeinKey> {
    val first = keys.firstOrNull()
    val root = if (first != null && first.isRoot && first.destination == destination) first else rootOf(destination)
    val out = arrayListOf(root)
    val seen = hashSetOf(identity(root))
    for (key in keys) {
        if (key.isRoot) continue
        if (key is TransientKey && key.handle !in transientIds) continue
        if (seen.add(identity(key))) out += key
    }
    return out
}

/** What de-duplication compares (§8.3 rule 4): the content key, except that a stack holds one new-chat draft. */
internal fun identity(key: SkeinKey): String = if (key is NewChatKey) KeyTag.NEW_CHAT.tag else key.contentKey

internal fun SkeinNavigationState.withStack(
    destination: Destination,
    stack: List<SkeinKey>,
    topLevel: Destination = this.topLevel,
): SkeinNavigationState = normalised(topLevel, stacks + (destination to stack), space, transientIds)

internal fun SkeinNavigationState.allKeys(): Sequence<SkeinKey> = stacks.values.asSequence().flatten()
