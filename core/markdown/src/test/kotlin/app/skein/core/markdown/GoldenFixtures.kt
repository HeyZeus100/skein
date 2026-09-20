package app.skein.core.markdown

/**
 * The 30+ Markdown fixtures backing [GoldenFixtureTest] (parse -> committed
 * JSON snapshot) and [MarkdownAstRoundTripPropertyTest] (parse -> serialize
 * -> parse idempotency). Order is fixed and each name is unique, since both
 * tests key off it.
 *
 * Covers every construct `MarkdownRenderer`/the live-preview editor must
 * handle (spec E7.I2): headings, bold/italic/code, lists (bullet, ordered,
 * task, nested), blockquotes (incl. nested and lazy-continuation), links,
 * all four wikilink forms, images (attachment + external), fenced/indented
 * code blocks, thematic breaks, strikethrough, hard/soft breaks, and
 * autolinks — plus two fixtures that exercise the v1 guardrail (tables and
 * raw HTML blocks degrade to `UnsupportedBlock`, never silently dropped).
 */
object GoldenFixtures {
    val entries: List<Pair<String, String>> =
        listOf(
            "heading-h1" to "# Heading one\n",
            "heading-all-levels" to "# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6\n",
            "setext-headings" to "Title One\n=========\n\nTitle Two\n---------\n",
            "paragraph-plain" to "Just a plain paragraph of text.\n",
            "paragraph-emphasis" to "Some *italic* and **bold** and ***bold italic*** text.\n",
            "paragraph-code-span" to "Inline `code` and `` `nested backtick` `` spans.\n",
            "paragraph-strikethrough" to "This is ~~struck through~~ text.\n",
            "paragraph-soft-break" to "Line one\nLine two\nLine three\n",
            "paragraph-hard-break" to "Line one  \nLine two\n",
            "paragraph-multi" to "First paragraph.\n\nSecond paragraph.\n",
            "bullet-list-flat" to "- one\n- two\n- three\n",
            "bullet-list-nested" to "- item one\n- item two\n  - nested a\n  - nested b\n- item three\n",
            "ordered-list-flat" to "1. first\n2. second\n3. third\n",
            "ordered-list-custom-start" to "5. five\n6. six\n7. seven\n",
            "task-list" to "- [ ] todo item\n- [x] done item\n- [ ] another todo\n",
            "task-list-nested" to "- [ ] parent\n  - [x] child done\n  - [ ] child todo\n",
            "blockquote-single-line" to "> A single-line quote.\n",
            "blockquote-multi-line" to "> First line\n> second line\n",
            "blockquote-with-blank-line" to "> paragraph one\n>\n> paragraph two\n",
            "blockquote-nested" to "> outer\n> > inner\n",
            "link-inline" to "[Skein](https://example.com/skein) is a note app.\n",
            "link-with-title" to "[Docs](https://example.com/docs \"Skein Docs\")\n",
            "autolink-bracketed" to "See <https://example.com> for details.\n",
            "autolink-bare" to "Visit https://example.com today.\n",
            "wikilink-simple" to "See [[Project Overview]] for context.\n",
            "wikilink-alias" to "See [[Project Overview|the overview]] for context.\n",
            "wikilink-heading" to "See [[Project Overview#Goals]] for context.\n",
            "wikilink-heading-alias" to "See [[Project Overview#Goals|our goals]] for context.\n",
            "wikilink-multiple" to "Related: [[Alpha]], [[Beta|the beta doc]], and [[Gamma#Section]].\n",
            "wikilink-not-in-code" to "Use `[[literal]]` syntax, not [[Real Link]].\n",
            "image-attachment" to "![diagram](attachment:8b3f1c2a-0000-4000-8000-000000000000)\n",
            "image-external" to "![logo](https://example.com/logo.png \"Skein logo\")\n",
            "code-fence-language" to "```kotlin\nval x = 1\nfun f() = x\n```\n",
            "code-fence-no-language" to "```\nplain fenced text\n```\n",
            "code-indented" to "    val x = 1\n    val y = 2\n",
            "thematic-break" to "Above\n\n---\n\nBelow\n",
            "table-unsupported" to "| a | b |\n|---|---|\n| 1 | 2 |\n",
            "html-block-unsupported" to "<div>raw html block</div>\n",
            "kitchen-sink" to (
                "# Notes on [[Project Overview|the project]]\n\n" +
                    "Some **bold**, *italic*, and `code`. See [[Project Overview#Goals]].\n\n" +
                    "- [ ] draft the plan\n" +
                    "- [x] review with team\n\n" +
                    "> Remember: ship small.\n\n" +
                    "```kotlin\nfun main() {}\n```\n"
            ),
        )
}
