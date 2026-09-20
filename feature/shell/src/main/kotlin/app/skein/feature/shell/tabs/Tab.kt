package app.skein.feature.shell.tabs

/**
 * Identity for a single tab instance. Distinct from [docId] — reopening the
 * same document produces the same [docId] but (usually) a fresh [TabId], and
 * a preview tab that gets replaced in place keeps the *new* incoming tab's id
 * rather than the old one (spec §8.3, plan `E6.I5`).
 */
@JvmInline
value class TabId(
    val value: String,
)

/**
 * The three tab content kinds spec §8.3 defines, each with its restrained
 * glyph. Real content per kind lands in later issues (`E6.I8` chat, `E6.I9`
 * note, attachment viewer) — this issue only mocks it via [TabHost].
 */
enum class TabKind(
    val glyph: String,
) {
    CHAT("💬"), // 💬
    NOTE("📄"), // 📄
    ATTACHMENT("📎"), // 📎
}

/**
 * Cursor-style preview vs. pinned lifecycle (spec §8.3): a [PREVIEW] tab is
 * rendered with an italic title and is replaced wholesale the next time a
 * *different* document is opened as a preview. [PINNED] tabs survive that —
 * promotion happens on double-click or on first edit of the content.
 */
enum class TabState { PREVIEW, PINNED }

/**
 * One entry in the tab strip / Recent dropdown.
 *
 * @param id this tab instance's identity (see [TabId]).
 * @param docId the underlying document/content key — what [kind]-specific
 *   real screens (`E6.I6`+) will use to load actual content. Two tabs may
 *   share a [docId] only transiently; [TabsState] avoids duplicate pinned
 *   tabs for the same [docId].
 * @param title display name; rendered italic while [state] is [TabState.PREVIEW].
 * @param kind which glyph/content family this tab is.
 * @param state preview vs. pinned (spec §8.3).
 */
data class Tab(
    val id: TabId,
    val docId: String,
    val title: String,
    val kind: TabKind,
    val state: TabState = TabState.PREVIEW,
) {
    val pinned: Boolean get() = state == TabState.PINNED
}
