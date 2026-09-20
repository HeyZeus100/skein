package app.skein.core.markdown.parser

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.impl.AutolinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.impl.ImageParser
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

/**
 * The GFM flavour (per the plan: `org.jetbrains:markdown` + GFM), with two
 * deliberate deviations from stock [GFMFlavourDescriptor]:
 *
 *  - [WikiLinkParser] is spliced into the inline parser sequence right after
 *    [BacktickParser] so `[[wikilinks]]` are first-class (see its kdoc).
 *  - `MathParser` (GFM inline math, `$...$`) is omitted: MathJax support is
 *    explicitly deferred to v2 (spec non-negotiable). Block-level GFM tables
 *    and raw HTML are still recognized by the inherited block-marker
 *    processor (tables can't be disabled independently of the rest of GFM's
 *    block grammar) but `MarkdownAst` intentionally does not map them to a
 *    first-class `SkeinNode` — they fall back to [app.skein.core.markdown.ast.UnsupportedBlock]
 *    with the original source preserved. See `bd note skein-ujn` for the
 *    rationale.
 */
class SkeinFlavourDescriptor : GFMFlavourDescriptor() {
    override val sequentialParserManager: SequentialParserManager =
        object : SequentialParserManager() {
            override fun getParserSequence(): List<SequentialParser> =
                listOf(
                    AutolinkParser(listOf(MarkdownTokenTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK)),
                    BacktickParser(),
                    WikiLinkParser(),
                    ImageParser(),
                    InlineLinkParser(),
                    ReferenceLinkParser(),
                    EmphasisLikeParser(EmphStrongDelimiterParser(), StrikeThroughDelimiterParser()),
                )
        }
}
