// bd `skein-6rr` (plan `E7.I3`): "the `id:` line is read-only (edits to it
// are rejected)". `id` is the document's stable identity (spec §5 /
// `FrontmatterKeys.ID`, `core/vault/.../codec/Frontmatter.kt`) — sync and
// backlink/edge resolution depend on it never drifting out from under an
// open tab.

package app.skein.feature.editor.frontmatter

/** Result of [ProtectedIdGuard.guard]: the text to actually apply, and whether it differs from what was requested. */
internal data class GuardResult(
    val text: String,
    val rejected: Boolean,
)

/**
 * Enforces the protected-`id` rule at the [app.skein.feature.editor.EditorState]
 * input boundary.
 *
 * Deliberately narrow: it only intervenes when *both* [GuardResult.text]'s
 * inputs — the previous and the proposed next buffer — still have a
 * recognizable frontmatter block with an `id:` line. That is exactly what
 * typing inside the expanded frontmatter region produces (the rest of the
 * document, including the block's shape, survives an IME edit to one
 * line). An edit that removes the whole frontmatter block (or the id line
 * specifically, e.g. a test or caller that replaces the entire buffer) is
 * intentionally NOT reverted here — reverting a structural change the
 * caller clearly intended would be surprising. The real backstop against a
 * changed/missing id ever reaching disk is `NoteTabState`'s save path,
 * which independently re-pins `frontmatter.id` to the document's actual id
 * before calling `VaultRepository.updateFrontmatter` regardless of what
 * this guard did or didn't catch.
 */
internal object ProtectedIdGuard {
    private const val DELIMITER = "---"

    /**
     * Returns [next] unchanged (not rejected) unless [next] both (a) still
     * has a frontmatter block with an `id:` line and (b) that line's text
     * differs from [previous]'s — in which case the id line specifically
     * is reverted to [previous]'s and [GuardResult.rejected] is `true`.
     */
    fun guard(
        previous: String,
        next: String,
        idKey: String = "id",
    ): GuardResult {
        val prevIdLine = findIdLine(previous, idKey) ?: return GuardResult(next, rejected = false)
        val nextIdLine = findIdLine(next, idKey) ?: return GuardResult(next, rejected = false)
        if (nextIdLine == prevIdLine) return GuardResult(next, rejected = false)
        val reverted = replaceIdLine(next, idKey, prevIdLine) ?: return GuardResult(next, rejected = false)
        return GuardResult(reverted, rejected = true)
    }

    /** The frontmatter block's line list plus the index range of its interior (excluding both `---` delimiters), or null if none. */
    private fun blockInterior(text: String): Pair<List<String>, IntRange>? {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0] != DELIMITER) return null
        val closing = (1 until lines.size).firstOrNull { lines[it] == DELIMITER } ?: return null
        return lines to (1 until closing)
    }

    private fun findIdLine(
        text: String,
        idKey: String,
    ): String? {
        val (lines, interior) = blockInterior(text) ?: return null
        return interior.map { lines[it] }.firstOrNull { it.trim().startsWith("$idKey:") }
    }

    private fun replaceIdLine(
        text: String,
        idKey: String,
        correctLine: String,
    ): String? {
        val (lines, interior) = blockInterior(text) ?: return null
        val idx = interior.firstOrNull { lines[it].trim().startsWith("$idKey:") } ?: return null
        val mutable = lines.toMutableList()
        mutable[idx] = correctLine
        return mutable.joinToString("\n")
    }
}
