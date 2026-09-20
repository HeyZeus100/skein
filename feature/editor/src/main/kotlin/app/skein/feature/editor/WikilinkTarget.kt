package app.skein.feature.editor

/**
 * The parsed target of a `[[Note Title]]` (or `[[Note Title#heading|alias]]`)
 * wikilink click. Resolution to an actual `Document` is intentionally the
 * caller's responsibility (via [EditorState.onLinkOpen]) — this module does
 * not depend on `VaultRepository` so `:feature:editor` stays pure enough to
 * unit-test the transformer on the JVM without a vault runtime, and so no
 * external content fetch happens as a side effect of typing text.
 *
 * See `bd skein-03f` guardrails ("Wikilink resolution: `EditorState` takes
 * an `onLinkOpen` callback ... Do NOT bake vault access into
 * `:feature:editor` — keep it pure").
 */
public data class WikilinkTarget(
    /** The note title portion — `[[Foo|bar]]` → `"Foo"`, `[[Foo#h]]` → `"Foo"`. */
    val title: String,
    /** The optional `#heading` fragment, `null` when the link has none. */
    val heading: String? = null,
    /** The optional `|alias` display override, `null` when the link has none. */
    val alias: String? = null,
)
