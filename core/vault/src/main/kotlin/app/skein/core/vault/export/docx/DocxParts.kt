// E2.I12 (bd skein-jq8): the OOXML package's individual XML parts, plus the
// `PrintBlock` -> `word/document.xml` body mapping. Every function here is a
// pure string builder — no `javax.xml.stream`/DOM writer, per the task
// brief's "hand-rolled minimal OOXML writer ... string-built XML, no
// third-party lib". `DocxWriter` assembles these parts into the zip;
// `DocxTemplate` supplies an alternate `styles.xml`/`sectPr` when a user
// template is given.
//
// Reuses `MarkdownFlattener`/`PrintBlock` (`:core:markdown`, moved there
// from `:core:export` by this same bead — see `PrintBlock.kt`'s header)
// instead of re-walking the `:core:markdown` AST: headings/paragraphs/
// bold/italic/code runs/lists/block quotes/wikilinks all already have a
// flattened, per-block shape from the PDF path (`E2.I11`) that maps
// naturally onto `w:p`/`w:r`.
//
// Known scope limits (v1, "minimal" per the plan):
//   - Tables (GFM) and raw HTML blocks are not part of the `:core:markdown`
//     AST (they parse to `UnsupportedBlock`, preserving raw source text) —
//     `MarkdownFlattener` already turns those into a plain `Paragraph`, so
//     they land as one literal-text paragraph here too, same as PDF export.
//     `w:tbl` synthesis from scratch is out of scope until `:core:markdown`
//     gains a real `Table` AST node.
//   - Images render as the flattener's `"[image] <alt>"` italic placeholder
//     text (matching the PDF path) rather than an embedded `word/media/*`
//     part — embedding needs attachment bytes from `VaultRepository`, which
//     this pure XML-building layer deliberately does not depend on.
//   - Task-list checkboxes (`- [ ]`/`- [x]`) render as a literal
//     `☐`/`☑` prefix (not native OOXML numbering) — see [listParagraphXml].
package app.skein.core.vault.export.docx

import app.skein.core.markdown.layout.PrintBlock
import app.skein.core.markdown.layout.StyledText
import app.skein.core.markdown.layout.TextSpan

/** One `word/_rels/document.xml.rels` `Relationship` for a hyperlink run — `id` is also the `r:id` the corresponding `w:hyperlink` element in `document.xml` references. */
internal data class HyperlinkRel(
    val id: String,
    val target: String,
)

internal object DocxParts {
    const val CONTENT_TYPES_PATH: String = "[Content_Types].xml"
    const val ROOT_RELS_PATH: String = "_rels/.rels"
    const val DOCUMENT_PATH: String = "word/document.xml"
    const val STYLES_PATH: String = "word/styles.xml"
    const val NUMBERING_PATH: String = "word/numbering.xml"
    const val SETTINGS_PATH: String = "word/settings.xml"
    const val DOCUMENT_RELS_PATH: String = "word/_rels/document.xml.rels"
    const val CORE_PATH: String = "docProps/core.xml"

    /** Fixed `docProps/core.xml` created/modified timestamp — determinism (bd `skein-jq8` acceptance criterion), same rationale as `ExportServiceImpl`'s fixed zip-entry time. */
    const val FIXED_CORE_DATE: String = "2026-01-01T00:00:00Z"

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CODE_FONT = "Consolas"

    // ------------------------------------------------------------------
    // Package-level parts
    // ------------------------------------------------------------------

    fun contentTypesXml(includeSettings: Boolean): String =
        buildString {
            append(XML_DECL)
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append(
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
            )
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append(
                "<Override PartName=\"/word/document.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>",
            )
            append(
                "<Override PartName=\"/word/styles.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>",
            )
            append(
                "<Override PartName=\"/word/numbering.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>",
            )
            if (includeSettings) {
                append(
                    "<Override PartName=\"/word/settings.xml\" " +
                        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>",
                )
            }
            append(
                "<Override PartName=\"/docProps/core.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>",
            )
            append("</Types>")
        }

    fun rootRelsXml(): String =
        XML_DECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" " +
            "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
            "Target=\"docProps/core.xml\"/>" +
            "</Relationships>"

    fun documentRelsXml(
        hyperlinks: List<HyperlinkRel>,
        includeSettings: Boolean,
    ): String =
        buildString {
            append(XML_DECL)
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append("<Relationship Id=\"rId1\" Type=\"$R_NS/styles\" Target=\"styles.xml\"/>")
            append("<Relationship Id=\"rId2\" Type=\"$R_NS/numbering\" Target=\"numbering.xml\"/>")
            if (includeSettings) {
                append("<Relationship Id=\"rId3\" Type=\"$R_NS/settings\" Target=\"settings.xml\"/>")
            }
            for (link in hyperlinks) {
                append(
                    "<Relationship Id=\"${link.id}\" Type=\"$R_NS/hyperlink\" " +
                        "Target=\"${XmlEscaper.escapeAttribute(link.target)}\" TargetMode=\"External\"/>",
                )
            }
            append("</Relationships>")
        }

    fun coreXml(title: String): String =
        XML_DECL +
            "<cp:coreProperties " +
            "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${XmlEscaper.escapeText(title)}</dc:title>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$FIXED_CORE_DATE</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$FIXED_CORE_DATE</dcterms:modified>" +
            "</cp:coreProperties>"

    fun documentXml(
        bodyXml: String,
        sectPrXml: String,
    ): String {
        val body = bodyXml.ifEmpty { "<w:p/>" }
        return XML_DECL +
            "<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\">" +
            "<w:body>$body$sectPrXml</w:body>" +
            "</w:document>"
    }

    /** Letter page size (12240 x 15840 twentieths-of-a-point = 8.5in x 11in), 1in margins all around — used only when no template supplies its own `w:sectPr`. */
    fun defaultSectPrXml(): String =
        "<w:sectPr>" +
            "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" " +
            "w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>" +
            "</w:sectPr>"

    // ------------------------------------------------------------------
    // Default styles.xml / numbering.xml — always present unless a
    // template overrides styles.xml (numbering.xml is always this writer's
    // own: see the file header's "Known scope limits").
    // ------------------------------------------------------------------

    /** Style ids this writer's own [documentXml] can reference. A user-supplied template's `styles.xml` is *not* guaranteed to define these (Word/LibreOffice fall back to Normal-ish formatting for an undefined `w:pStyle` rather than failing to open). */
    val STYLE_IDS: Set<String> =
        setOf(
            "Normal",
            "Heading1",
            "Heading2",
            "Heading3",
            "Heading4",
            "Heading5",
            "Heading6",
            "Quote",
            "Code",
            "ListParagraph",
        )

    fun defaultStylesXml(): String =
        buildString {
            append(XML_DECL)
            append("<w:styles xmlns:w=\"$W_NS\">")
            append("<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>")
            append(
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">" +
                    "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>",
            )
            for (level in 1..6) {
                val size = HEADING_SIZES[level - 1]
                append(
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
                        "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
                        "<w:qFormat/>" +
                        "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>" +
                        "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                        "<w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>",
                )
            }
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Quote\">" +
                    "<w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:ind w:left=\"720\"/>" +
                    "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"AAAAAA\"/></w:pBdr></w:pPr>" +
                    "<w:rPr><w:i/></w:rPr></w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Code\">" +
                    "<w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"$CODE_FONT\" w:hAnsi=\"$CODE_FONT\" w:cs=\"$CODE_FONT\"/></w:rPr></w:style>",
            )
            append(
                "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\">" +
                    "<w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/></w:style>",
            )
            append("</w:styles>")
        }

    private val HEADING_SIZES = intArrayOf(36, 32, 28, 24, 22, 20)

    /** `numId=1` (bullet) / `numId=2` (ordered decimal), 9 levels each (`w:ilvl` 0-8, Word's own default depth) — see [listParagraphXml]. */
    fun defaultNumberingXml(): String =
        XML_DECL +
            "<w:numbering xmlns:w=\"$W_NS\">" +
            abstractNumXml(abstractNumId = 0, bullet = true) +
            abstractNumXml(abstractNumId = 1, bullet = false) +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>" +
            "</w:numbering>"

    private fun abstractNumXml(
        abstractNumId: Int,
        bullet: Boolean,
    ): String =
        buildString {
            append("<w:abstractNum w:abstractNumId=\"$abstractNumId\">")
            for (ilvl in 0..8) {
                val indentLeft = 720 * (ilvl + 1)
                append("<w:lvl w:ilvl=\"$ilvl\"><w:start w:val=\"1\"/>")
                if (bullet) {
                    append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/>")
                } else {
                    append("<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%${ilvl + 1}.\"/>")
                }
                append("<w:lvlJc w:val=\"left\"/>")
                append("<w:pPr><w:ind w:left=\"$indentLeft\" w:hanging=\"360\"/></w:pPr>")
                append("</w:lvl>")
            }
            append("</w:abstractNum>")
        }

    // ------------------------------------------------------------------
    // PrintBlock -> word/document.xml body
    // ------------------------------------------------------------------

    /** Hyperlink `r:id`s start well past the fixed styles(1)/numbering(2)/settings(3) relationship ids so the two ranges can never collide, without needing to know [includeSettings] up front. */
    private const val HYPERLINK_ID_BASE = 1000

    fun bodyXml(
        blocks: List<PrintBlock>,
        hyperlinks: MutableList<HyperlinkRel>,
    ): String = blocks.joinToString("") { blockXml(it, hyperlinks) }

    private fun blockXml(
        block: PrintBlock,
        hyperlinks: MutableList<HyperlinkRel>,
    ): String =
        when (block) {
            is PrintBlock.Heading ->
                paragraphXml("Heading${block.level.coerceIn(1, 6)}", block.text.spans, hyperlinks)
            is PrintBlock.Paragraph -> paragraphXml("Normal", block.text.spans, hyperlinks)
            is PrintBlock.Quote ->
                splitOnBlankLine(block.text).joinToString("") { paragraphXml("Quote", it, hyperlinks) }
            is PrintBlock.Code -> codeParagraphXml(block)
            is PrintBlock.ListEntry -> listParagraphXml(block, hyperlinks)
            PrintBlock.ThematicBreak -> thematicBreakParagraphXml()
        }

    private fun paragraphXml(
        pStyle: String,
        spans: List<TextSpan>,
        hyperlinks: MutableList<HyperlinkRel>,
    ): String = "<w:p><w:pPr><w:pStyle w:val=\"$pStyle\"/></w:pPr>${spansToRunsXml(spans, hyperlinks)}</w:p>"

    private fun codeParagraphXml(block: PrintBlock.Code): String =
        buildString {
            append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr>")
            val rPr = "<w:rPr><w:rFonts w:ascii=\"$CODE_FONT\" w:hAnsi=\"$CODE_FONT\" w:cs=\"$CODE_FONT\"/></w:rPr>"
            block.lines.forEachIndexed { index, line ->
                if (index > 0) append("<w:r>$rPr<w:br/></w:r>")
                append("<w:r>$rPr<w:t xml:space=\"preserve\">${XmlEscaper.escapeText(line)}</w:t></w:r>")
            }
            append("</w:p>")
        }

    /**
     * `PrintBlock.ListEntry.marker` is the flattener's already-rendered
     * prefix — `"• "` for a plain bullet, `"<n>. "` for an ordered
     * item, or `"☑ "`/`"☐ "` for a GFM task-list item (see
     * `MarkdownFlattener`). Recognizing the first two shapes lets this
     * writer use real OOXML numbering (`w:numPr`, `numId` 1/2) instead of
     * baking the marker into the run text, which is what a real Word list
     * looks like; task-list checkboxes have no native OOXML numbering
     * format in a "minimal" writer, so they keep their literal glyph
     * prefix — still fully readable, just not a real "checkbox" content
     * control.
     */
    private fun listParagraphXml(
        entry: PrintBlock.ListEntry,
        hyperlinks: MutableList<HyperlinkRel>,
    ): String {
        val numId =
            when {
                entry.marker == BULLET_MARKER -> 1
                ORDERED_MARKER.matches(entry.marker) -> 2
                else -> null
            }
        val groups = splitOnBlankLine(entry.text)
        val firstSpans = if (numId != null) groups.first() else listOf(TextSpan(entry.marker)) + groups.first()
        val numPr =
            if (numId !=
                null
            ) {
                "<w:numPr><w:ilvl w:val=\"${entry.depth}\"/><w:numId w:val=\"$numId\"/></w:numPr>"
            } else {
                ""
            }
        return buildString {
            append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/>$numPr</w:pPr>")
            append(spansToRunsXml(firstSpans, hyperlinks))
            append("</w:p>")
            for (spans in groups.drop(1)) {
                append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/></w:pPr>")
                append(spansToRunsXml(spans, hyperlinks))
                append("</w:p>")
            }
        }
    }

    private fun thematicBreakParagraphXml(): String =
        "<w:p><w:pPr>" +
            "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr>" +
            "<w:spacing w:after=\"120\"/></w:pPr></w:p>"

    /**
     * `MarkdownFlattener.joinBlocks` (nested block-quote/list-item content)
     * separates sibling blocks with a literal `TextSpan("\n\n")`. Splitting
     * on that exact span (dropping the separator) reconstructs "one DOCX
     * paragraph per original Markdown block" instead of collapsing a
     * multi-paragraph quote/list item into a single run of text with a
     * blank line baked into it — the blank line would otherwise survive
     * only as literal whitespace inside one `w:t`, invisible to a reader
     * scanning the styles pane. A block with no such separator (headings,
     * plain paragraphs) is unaffected — it always yields exactly one group.
     */
    private fun splitOnBlankLine(text: StyledText): List<List<TextSpan>> {
        val groups = mutableListOf(mutableListOf<TextSpan>())
        for (span in text.spans) {
            if (span.text == "\n\n") {
                groups.add(mutableListOf())
            } else {
                groups.last().add(span)
            }
        }
        return groups
    }

    /** Wraps consecutive spans sharing the same non-null [TextSpan.href] in one `w:hyperlink` (a real `Link` inline never actually splits like this — `MarkdownFlattener` copies `href` onto every span it produces from one `Link` — but grouping is harmless and keeps this function correct even if that ever changes). */
    private fun spansToRunsXml(
        spans: List<TextSpan>,
        hyperlinks: MutableList<HyperlinkRel>,
    ): String {
        val sb = StringBuilder()
        var i = 0
        while (i < spans.size) {
            val href = spans[i].href
            if (href != null) {
                var j = i
                while (j < spans.size && spans[j].href == href) j++
                val relId = "rId${HYPERLINK_ID_BASE + hyperlinks.size}"
                hyperlinks += HyperlinkRel(relId, href)
                sb.append("<w:hyperlink r:id=\"$relId\">")
                for (k in i until j) sb.append(runXml(spans[k]))
                sb.append("</w:hyperlink>")
                i = j
            } else {
                sb.append(runXml(spans[i]))
                i++
            }
        }
        return sb.toString()
    }

    /** One [TextSpan] -> one or more `w:r` — a span whose text is exactly `"\n"` (a flattened `HardBreak`) becomes a run containing `w:br` instead of `w:t`; any other embedded `\n` (not expected from `MarkdownFlattener` today, but handled defensively) does the same, per-piece. */
    private fun runXml(span: TextSpan): String {
        val rPr = runPropsXml(span)
        if (span.text.isEmpty()) return ""
        val pieces = span.text.split("\n")
        if (pieces.size == 1) {
            return "<w:r>$rPr<w:t xml:space=\"preserve\">${XmlEscaper.escapeText(span.text)}</w:t></w:r>"
        }
        return buildString {
            pieces.forEachIndexed { index, piece ->
                if (index > 0) append("<w:r>$rPr<w:br/></w:r>")
                if (piece.isNotEmpty()) {
                    append("<w:r>$rPr<w:t xml:space=\"preserve\">${XmlEscaper.escapeText(piece)}</w:t></w:r>")
                }
            }
        }
    }

    private fun runPropsXml(span: TextSpan): String {
        if (!span.bold && !span.italic && !span.strikethrough && !span.underline && !span.monospace) return ""
        return buildString {
            append("<w:rPr>")
            if (span.bold) append("<w:b/>")
            if (span.italic) append("<w:i/>")
            if (span.strikethrough) append("<w:strike/>")
            if (span.underline) append("<w:u w:val=\"single\"/>")
            if (span.monospace) append("<w:rFonts w:ascii=\"$CODE_FONT\" w:hAnsi=\"$CODE_FONT\" w:cs=\"$CODE_FONT\"/>")
            append("</w:rPr>")
        }
    }

    private const val BULLET_MARKER = "• "
    private val ORDERED_MARKER = Regex("""\d+\. """)
}
