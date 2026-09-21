// `E2.I7` (bd `skein-ad5`): decides whether an `importText` payload is
// source code (and which fenced-code language tag it gets) or prose
// (Markdown / plain text, which `ImportServiceImpl` treats identically:
// frontmatter is parsed, the body is stored verbatim).
//
// Precedence — filename extension, then the caller-supplied MIME type,
// then a content sniff (plan §4.5 E2.I7: "Source code (`text/x-*`, or
// extensions in a small table) is wrapped in a fenced block … MIME
// sniffing falls back to extension"):
//   1. The extension is consulted first because it is what the user sees,
//      whereas the MIME type a `ContentResolver` reports for a shared or
//      picked file is frequently generic (`application/octet-stream`,
//      `text/plain`) or plain wrong. A prose extension (`.md`, `.txt`, …)
//      therefore short-circuits to prose even under a `text/x-*` MIME.
//   2. Otherwise the MIME type decides: `text/markdown`, `text/x-markdown`
//      and `text/plain` are prose; anything in `LanguageTable`'s MIME
//      tables, and every other `text/x-*` type, is source code.
//   3. Otherwise the content is sniffed: a leading `#!` shebang line names
//      the interpreter and hence the language. Anything else is prose —
//      the safe default, since prose is stored byte-for-byte.

package app.skein.core.vault.transfer

public object MimeSniffer {
    private val PROSE_EXTENSIONS: Set<String> = setOf("md", "markdown", "mdown", "mkd", "mkdn", "txt", "text")

    private val PROSE_MIME_TYPES: Set<String> = setOf("text/plain", "text/markdown", "text/x-markdown")

    /**
     * The fenced-code language tag to wrap this import in, or `null` when
     * the import is prose. [text] is only consulted when neither
     * [displayName]'s extension nor [mimeType] settles the question, and
     * then only its first line.
     */
    public fun sourceLanguage(
        displayName: String,
        mimeType: String,
        text: String,
    ): String? {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in PROSE_EXTENSIONS) return null
        LanguageTable.languageForExtension(extension)?.let { return it }

        val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
        if (normalizedMime in PROSE_MIME_TYPES) return null
        LanguageTable.languageForMimeType(normalizedMime)?.let { return it }

        return shebangLanguage(text)
    }

    /**
     * `#!/bin/sh`, `#!/usr/bin/env python3`, `#!/usr/bin/env -S node …`:
     * the interpreter is the first token's basename, or the first
     * non-option token after `env`.
     */
    private fun shebangLanguage(text: String): String? {
        if (!text.startsWith("#!")) return null
        val lineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val tokens =
            text
                .substring(2, lineEnd)
                .trim()
                .split(' ', '\t')
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val first = tokens[0].substringAfterLast('/')
        val interpreter =
            if (first == "env") {
                tokens.drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/') ?: return null
            } else {
                first
            }
        return LanguageTable.languageForInterpreter(interpreter)
    }
}
