package app.skein.core.markdown.parser

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.TokensCache

/** The AST node type produced for a recognized `[[...]]` span. */
object WikiLinkElementType : IElementType("SKEIN_WIKI_LINK")

/**
 * Recognizes `[[title]]`, `[[title|alias]]`, `[[title#heading]]` and
 * `[[title#heading|alias]]` at the sequential-parser level — the same stage
 * CommonMark's own link/image/emphasis parsers run at — so wikilinks are a
 * first-class parsed AST construct, not a regex swept over already-rendered
 * text (spec non-negotiable).
 *
 * Runs immediately after [org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser]
 * in [SkeinFlavourDescriptor] so:
 *  - `[[` inside inline code (`` `[[not a link]]` ``) is never claimed here,
 *    because backtick spans are already removed from the token pool.
 *  - `[[title]]` is claimed here before the standard link/image/reference
 *    parsers see it, so it is never mis-parsed as `[` + `[title]` (a
 *    short-reference-link-shaped span) + `]`.
 *
 * The full `[[...]]` span (including the alias/heading separators) becomes
 * a single leaf node; [app.skein.core.markdown.MarkdownAst] splits the raw
 * text of that node into target/heading/alias when building the Skein AST.
 * Alias text is intentionally treated as plain text — Obsidian does not
 * support formatted wikilink display text, and neither do we.
 */
class WikiLinkParser : SequentialParser {
    override fun parse(
        tokens: TokensCache,
        rangesToGlue: List<IntRange>,
    ): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val delegateIndices = RangesListBuilder()
        var iterator: TokensCache.Iterator = tokens.RangesListIterator(rangesToGlue)

        while (iterator.type != null) {
            if (iterator.type == MarkdownTokenTypes.LBRACKET &&
                iterator.advance().type == MarkdownTokenTypes.LBRACKET
            ) {
                val closing = findClosing(iterator.advance().advance())
                if (closing != null) {
                    result.withNode(SequentialParser.Node(iterator.index..closing.index + 1, WikiLinkElementType))
                    iterator = closing.advance()
                    continue
                }
            }
            delegateIndices.put(iterator.index)
            iterator = iterator.advance()
        }

        return result.withFurtherProcessing(delegateIndices.get())
    }

    /** Finds the second `]` of the first `]]` pair reachable from [start], if any. */
    private fun findClosing(start: TokensCache.Iterator): TokensCache.Iterator? {
        var iterator = start
        while (iterator.type != null) {
            if (iterator.type == MarkdownTokenTypes.RBRACKET) {
                val second = iterator.advance()
                if (second.type == MarkdownTokenTypes.RBRACKET) {
                    return second
                }
            }
            iterator = iterator.advance()
        }
        return null
    }
}
