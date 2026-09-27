// skein-xtov.24.6 (AL-07, UX Wave 3): pure-data inputs for the navigation
// container, so AL-08/AL-09 can feed real vault/persona rows without this
// module knowing about `VaultRepository`, `Document` or `Persona` — the
// same "screens take plain rows, a ViewModel maps them" shape
// `TimelineScreen`/`TimelineFormatting.kt` already uses.
package app.skein.feature.shell.container

/**
 * One chat-history row (`CHAT_UX_SPEC.md` §12.3). [timeLabel] is the
 * caller's already-locale-formatted display text ("9:41" / "Tue" / "3 Sep")
 * — this module does no locale formatting of its own, only grouping
 * ([groupChatHistory] buckets by [lastMessageAtMillis]).
 *
 * [onOpen]/[onRename]/[onDelete] are callbacks only (IA §3.3 "Rename /
 * Delete"): this row never mutates chat state itself.
 * [SkeinNavigationContainer] wraps [onOpen] to also close a modal drawer
 * (spec §3.3 "the drawer closes when the user picks … a chat row"); it
 * deliberately leaves [onRename]/[onDelete] un-wrapped, since renaming or
 * deleting from a history row keeps the drawer open under the dialog
 * (spec §3.3's exception).
 */
data class ChatHistoryItem(
    val id: String,
    val title: String,
    val lastMessageAtMillis: Long,
    val timeLabel: String,
    val preview: String? = null,
    /** CHAT_UX_SPEC.md §12.3: a turn is running in this chat. */
    val isAnswering: Boolean = false,
    /** The open chat (IA §3.3): painted `secondaryContainer`, exposes `selected = true`. */
    val isSelected: Boolean = false,
    val onOpen: () -> Unit,
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * A Space (`INFORMATION_ARCHITECTURE.md` §8b): a persona-backed domain —
 * its own knowledge, instructions and default model. [SkeinSpaceSwitcher]
 * (and the drawer/rail slots that host it) render nothing while the
 * caller passes fewer than two Spaces (§8b Level 1: "nothing about Spaces
 * is shown while it is the only one").
 */
data class SkeinSpace(
    val id: String,
    val name: String,
    val isSelected: Boolean = false,
    val onSelect: () -> Unit,
)
