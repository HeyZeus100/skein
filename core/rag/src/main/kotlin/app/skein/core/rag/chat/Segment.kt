// `Segment` (skein-n5q, E5.I16). The chat surface (`E6.I8`) consumes a flat
// stream of these: `Text` renders as Markdown, `Citation` renders a tappable
// chip that opens [Citation.retrieved]'s document as a preview tab (design
// spec §7.3/§8.4). `CitationParser` is the only producer.

package app.skein.core.rag.chat

import us.aherrera.skein.core.model.Retrieved

/**
 * One piece of a parsed assistant reply, in stream order.
 *
 * A `[N]` marker the model emitted that `PromptGuard.citationsAllowed`
 * (`:core:security`) rejects — because it names a marker outside what the
 * assembled prompt actually offered — never becomes a [Citation]; its literal
 * characters (`[`, the
 * digits, `]`) are folded into the surrounding [Text] instead, so a
 * hallucinated or injected citation renders as inert prose, never a chip.
 */
public sealed interface Segment {
    /** Literal Markdown text, exactly as it streamed (never rewritten, never truncated). */
    public data class Text(
        public val text: String,
    ) : Segment

    /**
     * A validated `[marker]` citation. [retrieved] is the exact
     * `AssembledPrompt.citations[marker]` value the prompt was built with —
     * never re-derived from the model's output — so the chip can open
     * [Retrieved.docId] as a preview tab without a second lookup.
     */
    public data class Citation(
        public val marker: Int,
        public val retrieved: Retrieved,
    ) : Segment
}
