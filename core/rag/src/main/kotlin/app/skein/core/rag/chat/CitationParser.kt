// `CitationParser` (skein-n5q, E5.I16, plan
// `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines 3706-3722). The
// "cite" step of design spec §7.3/§8.4: consumes `Token.Text` pieces as they
// stream out of `InferenceEngine.stream` and splits them into
// `Segment.Text`/`Segment.Citation` so the chat surface (`E6.I8`) can render
// a citation chip the instant its closing `]` arrives, instead of waiting for
// the whole turn to finish.
//
// ## Validated against what was offered, never trusted from the model
//
// The allow-list this class checks every `[N]` against is `AssembledPrompt.
// citations` — exactly the markers `PromptAssemblerImpl` (`E5.I15`) actually
// put in the prompt. This mirrors `PromptGuard.citationsAllowed`'s output
// contract ("a `[N]` outside the allow-list stays inert literal text, never a
// chip") but incrementally, one streamed piece at a time, rather than over
// one finished string. A marker the model invents — because it hallucinated
// one, or because a retrieved chunk tried to plant "as shown in [7]" and the
// model echoed it — never becomes a [Segment.Citation]; retrieved content is
// data, and nothing it contains can make itself citable.
//
// ## The 8-character unclosed-bracket timeout (bead `skein-n5q`)
//
// A `[` starts a tentative citation. If its closing `]` has not arrived after
// 8 more characters, the `[` and those 8 characters are folded back into
// plain text and scanning resumes on the character that would have been the
// 9th — so `"before [123456789 after"` (nine digits between the brackets)
// round-trips byte-for-byte as one [Segment.Text], never a dropped or hung
// bracket. This bounds how much text a single unterminated `[` can hold
// hostage in the tail buffer, independent of how the model's own output is
// shaped.
//
// ## Grouped markers `[N, M]`
//
// Recognised the same way `CitationFilter` recognises them for the
// whole-string case: a bracket whose content is a comma-separated list of
// plain non-negative integers (surrounding spaces tolerated). Each entry
// still passes through the allow-list independently:
//   - every entry valid                → one [Segment.Citation] per entry, in
//     order, immediately after any pending text is flushed.
//   - at least one entry valid          → the valid entries become citations;
//     an invented entry riding along in the same group (e.g. `[1, 9]` when
//     only `1` was offered) is dropped silently, exactly as a lone invented
//     marker would be — it never becomes text of its own, since the group as
//     a whole was already recognised as a real citation.
//   - no entry valid, or the bracket's content is not a plain digit list
//     (`[i]`, `[a, b]`, an 8-character timeout) → the whole bracket, brackets
//     included, is folded into plain text unchanged. This is the group
//     generalisation of the single-marker rule the bead's third AC pins:
//     `"[9]"` with only `{1, 2}` offered renders as the literal text `[9]`.
//
// ## Streaming semantics
//
// [push] returns only the [Segment]s it can already be certain of: a
// completed [Segment.Citation] the moment its group resolves, and any text
// that precedes it. Trailing plain text — including a bracket run that
// turned out not to be a citation — is held in an internal buffer rather
// than emitted eagerly, so that (for example) `" and "` followed later by an
// invented `"[9]"` in a *separate* `push` call still coalesces into one
// `Segment.Text(" and [9]")`, matching the bead's first AC exactly. [flush]
// (call on `Token.Done`) emits whatever text remains, including an
// in-progress, still-unclosed bracket.
//
// Pure and allocation-light: no I/O, no logging of the streamed text (spec
// §9 — an assistant reply can itself contain retrieved content the model
// echoed), and the only heap growth per instance is the two `StringBuilder`s
// backing the pending-text and in-progress-bracket buffers.
package app.skein.core.rag.chat

import us.aherrera.skein.core.model.Retrieved

/**
 * Incremental citation parser. One instance per streamed assistant turn —
 * it is stateful across [push] calls and must not be reused for a second
 * turn.
 *
 * @param citations the exact `AssembledPrompt.citations` the prompt for this
 *   turn was built with: 1-based marker → the [Retrieved] source. A `[N]`
 *   whose `N` is not a key of this map is never emitted as a
 *   [Segment.Citation].
 */
public class CitationParser(
    private val citations: Map<Int, Retrieved>,
) {
    /** Text accumulated since the last emitted [Segment] (a [Segment.Text] awaiting more, possibly). */
    private val pending: StringBuilder = StringBuilder()

    /** Non-null while scanning the content of an unclosed `[...`; holds everything seen since the `[`. */
    private var bracket: StringBuilder? = null

    /**
     * Feeds the next `Token.Text.text` piece and returns every [Segment] that
     * can already be emitted with certainty. May return an empty list (most
     * calls that only extend a still-open bracket or still-pending text do).
     */
    public fun push(piece: String): List<Segment> {
        if (piece.isEmpty()) return emptyList()
        val out = mutableListOf<Segment>()
        var index = 0
        while (index < piece.length) {
            val char = piece[index]
            val open = bracket
            if (open == null) {
                if (char == '[') {
                    bracket = StringBuilder()
                } else {
                    pending.append(char)
                }
            } else if (char == ']') {
                closeBracket(open, out)
                bracket = null
            } else if (open.length >= MAX_BRACKET_CHARS) {
                // Timeout: the `[` never closed in time. Fold it back into
                // plain text and reprocess this same character as ordinary
                // text (it was never consumed into `open`).
                pending.append(OPEN_BRACKET).append(open)
                bracket = null
                continue
            } else {
                open.append(char)
            }
            index += 1
        }
        return out
    }

    /**
     * Call once, after the stream's `Token.Done`. Emits whatever text is
     * still buffered — including an unclosed bracket, folded back to its
     * literal `[` plus whatever it had accumulated — as a final
     * [Segment.Text]. Returns an empty list if nothing was pending.
     */
    public fun flush(): List<Segment> {
        bracket?.let { pending.append(OPEN_BRACKET).append(it) }
        bracket = null
        if (pending.isEmpty()) return emptyList()
        val text = pending.toString()
        pending.setLength(0)
        return listOf(Segment.Text(text))
    }

    /**
     * Resolves a just-closed `[...]` group. Appends [out] with a flushed
     * [Segment.Text] (if any text was pending) followed by one
     * [Segment.Citation] per valid entry, or — when no entry validates, or
     * the content is not a plain digit list — folds the whole bracket back
     * into [pending] as literal text.
     */
    private fun closeBracket(
        buffer: StringBuilder,
        out: MutableList<Segment>,
    ) {
        val inner = buffer.toString()
        val entries = digitEntries(inner)
        val validMarkers = entries?.mapNotNull { it.toIntOrNull() }?.filter { it in citations }
        if (entries == null || validMarkers.isNullOrEmpty()) {
            pending.append(OPEN_BRACKET).append(inner).append(CLOSE_BRACKET)
            return
        }
        if (pending.isNotEmpty()) {
            out += Segment.Text(pending.toString())
            pending.setLength(0)
        }
        for (marker in validMarkers) out += Segment.Citation(marker, citations.getValue(marker))
    }

    /**
     * Splits [inner] on commas and trims each piece, returning the trimmed
     * entries only when every one of them is non-empty and every character
     * in it is an ASCII digit. `null` for anything else (an empty bracket,
     * `[i]`, `[a, b]`, `[1,]`) — those are not a citation shape at all, so the
     * whole bracket stays literal text.
     */
    private fun digitEntries(inner: String): List<String>? {
        if (inner.isEmpty()) return null
        val parts = inner.split(',').map { it.trim() }
        if (parts.any { part -> part.isEmpty() || part.any { c -> c !in '0'..'9' } }) return null
        return parts
    }

    private companion object {
        /** Bead `skein-n5q`: "a `[` that never closes within 8 characters is flushed as text". */
        const val MAX_BRACKET_CHARS: Int = 8
        const val OPEN_BRACKET: Char = '['
        const val CLOSE_BRACKET: Char = ']'
    }
}
