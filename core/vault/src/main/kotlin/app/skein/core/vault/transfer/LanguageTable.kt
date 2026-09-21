// `E2.I7` (bd `skein-ad5`): the small, curated extension / MIME /
// interpreter → fenced-code language-tag tables `MimeSniffer` consults to
// recognize source code. Intentionally not a general-purpose language
// detection library — no third-party dependency, per the issue's
// non-negotiables — just the handful of mappings a share-target or file
// picker realistically hands us.
//
// Detection precedence lives in `MimeSniffer`; this object is pure lookup.

package app.skein.core.vault.transfer

public object LanguageTable {
    /** Filename extension (lowercase, no leading dot) → fenced-code-block language tag. */
    private val EXTENSION_TO_LANGUAGE: Map<String, String> =
        mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "js" to "javascript",
            "mjs" to "javascript",
            "cjs" to "javascript",
            "jsx" to "jsx",
            "ts" to "typescript",
            "tsx" to "tsx",
            "py" to "python",
            "rs" to "rust",
            "go" to "go",
            "rb" to "ruby",
            "c" to "c",
            "h" to "c",
            "cpp" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "hpp" to "cpp",
            "hh" to "cpp",
            "cs" to "csharp",
            "swift" to "swift",
            "php" to "php",
            "sh" to "bash",
            "bash" to "bash",
            "zsh" to "bash",
            "sql" to "sql",
            "yaml" to "yaml",
            "yml" to "yaml",
            "toml" to "toml",
            "json" to "json",
            "xml" to "xml",
            "html" to "html",
            "htm" to "html",
            "css" to "css",
            "scss" to "scss",
            "m" to "objectivec",
            "scala" to "scala",
            "pl" to "perl",
            "lua" to "lua",
            "groovy" to "groovy",
            "gradle" to "groovy",
            "dart" to "dart",
            "r" to "r",
            "ps1" to "powershell",
        )

    /**
     * Full MIME type (lowercase, parameters stripped) → language tag, for
     * source-code MIME types outside the `text/x-*` family.
     */
    private val MIME_TYPE_TO_LANGUAGE: Map<String, String> =
        mapOf(
            "text/javascript" to "javascript",
            "application/javascript" to "javascript",
            "application/x-javascript" to "javascript",
            "application/typescript" to "typescript",
            "application/json" to "json",
            "application/xml" to "xml",
            "text/xml" to "xml",
            "text/html" to "html",
            "text/css" to "css",
            "application/x-sh" to "bash",
            "application/x-shellscript" to "bash",
            "application/sql" to "sql",
            "application/x-yaml" to "yaml",
            "application/yaml" to "yaml",
            "text/yaml" to "yaml",
            "application/toml" to "toml",
        )

    /**
     * `text/x-<subtype>` MIME subtype (lowercase) → language tag, for the
     * subtypes that don't already spell the tag themselves (any other
     * `text/x-*` subtype is used verbatim as the tag).
     */
    private val MIME_SUBTYPE_TO_LANGUAGE: Map<String, String> =
        mapOf(
            "java-source" to "java",
            "csrc" to "c",
            "chdr" to "c",
            "c++src" to "cpp",
            "c++hdr" to "cpp",
            "c++" to "cpp",
            "objcsrc" to "objectivec",
            "rustsrc" to "rust",
            "shellscript" to "bash",
            "sh" to "bash",
            "script.python" to "python",
            "python-script" to "python",
        )

    /** Shebang interpreter basename (lowercase, version suffix already stripped) → language tag. */
    private val INTERPRETER_TO_LANGUAGE: Map<String, String> =
        mapOf(
            "sh" to "bash",
            "bash" to "bash",
            "zsh" to "bash",
            "dash" to "bash",
            "ksh" to "bash",
            "python" to "python",
            "node" to "javascript",
            "nodejs" to "javascript",
            "deno" to "typescript",
            "ruby" to "ruby",
            "perl" to "perl",
            "php" to "php",
            "lua" to "lua",
            "pwsh" to "powershell",
        )

    /** Language tag for a source file named with [extension] (case-insensitive, no leading dot), or `null` if unrecognized. */
    public fun languageForExtension(extension: String): String? = EXTENSION_TO_LANGUAGE[extension.lowercase()]

    /**
     * Language tag for a source-code [mimeType] (case-insensitive; any
     * `; charset=…` parameters are ignored), or `null` if the type isn't a
     * known source-code type. Every `text/x-*` type counts as source code.
     */
    public fun languageForMimeType(mimeType: String): String? {
        val normalized = mimeType.substringBefore(';').trim().lowercase()
        MIME_TYPE_TO_LANGUAGE[normalized]?.let { return it }
        val subtype = normalized.substringAfter("text/x-", missingDelimiterValue = "")
        if (subtype.isEmpty()) return null
        return MIME_SUBTYPE_TO_LANGUAGE[subtype] ?: subtype
    }

    /**
     * Language tag for a shebang [interpreter] name (e.g. `python3`,
     * `lua5.4`, `node`): the trailing version digits/dots are stripped
     * before lookup, and an interpreter this table doesn't know is used
     * verbatim as the tag — a shebang is unambiguous evidence of source
     * code even when the dialect is unfamiliar.
     */
    public fun languageForInterpreter(interpreter: String): String? {
        val base = interpreter.lowercase().trimEnd { it.isDigit() || it == '.' }
        if (base.isEmpty()) return null
        return INTERPRETER_TO_LANGUAGE[base] ?: base
    }
}
