// bd `skein-fay` (plan `E6.I16`, spec §3.1 share source), reconciled
// 2026-09-21 to `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2/§4.5 — see
// `bd show skein-fay`'s description for the authoritative v1 scope (the
// original FileProvider text on this bead, and the plan JSON's stale
// `E6.I16` entry, are both superseded).
//
// v1 "share source" splits into two flows:
//   1. "Share as text" (this file) — `ACTION_SEND` with `Intent.EXTRA_TEXT`,
//      in-memory only. No `Uri`, no `ClipData` `Uri`, no `FileProvider`, no
//      `DocumentsProvider` grant of any kind. The receiving app gets a
//      `String` copy; nothing outlives this call.
//   2. "Save as..." (`SaveAsIntents.kt`, same package) — `ACTION_CREATE_DOCUMENT`
//      for file-backed formats, streaming into the SAF-returned `Uri`.
//
// An `ACTION_SEND` flow that hands another app a `content://` `Uri` granted
// off `VaultDocumentsProvider` (§4.2 candidate (a)) is explicitly deferred
// to v1.1 and is intentionally NOT built here or anywhere else in this
// module — `NoFileProviderOnClasspathTest` (bd `skein-fubu`) guards the
// FileProvider half of that; `ShareIntentsTest` guards this file's half by
// asserting the built intent carries no `Uri`/`ClipData`/grant flag.
//
// Pure Kotlin object: builds `Intent`s only, never touches `Context` or
// starts an activity. `NoteTab`'s header menu (`:feature:editor`) is the
// only caller that ever hands what this returns to
// `Context.startActivity`.

package app.skein.feature.editor.share

import android.content.Intent

/** "Share as text" (bd `skein-fay` v1 scope, item 1). */
public object ShareIntents {
    /** MIME type used for the "share as text" `ACTION_SEND` intent. */
    public const val MIME_TYPE_TEXT_PLAIN: String = "text/plain"

    /**
     * `ACTION_SEND` / [MIME_TYPE_TEXT_PLAIN] carrying [title] as
     * [Intent.EXTRA_SUBJECT] and [bodyMd] (the note's raw Markdown body) as
     * [Intent.EXTRA_TEXT]. Deliberately never calls [Intent.setData] or
     * [Intent.setClipData] and never sets a `FLAG_GRANT_*` flag — there is
     * no `Uri` anywhere in this flow for a grant to apply to.
     */
    public fun shareAsText(
        title: String,
        bodyMd: String,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_TEXT_PLAIN
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, bodyMd)
        }

    /**
     * Wraps [intent] in the system chooser (`Intent.createChooser`) so no
     * single app is silently pre-selected as the share target.
     */
    public fun chooser(
        intent: Intent,
        title: String? = null,
    ): Intent = Intent.createChooser(intent, title)
}
