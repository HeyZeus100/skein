package app.skein.inference.service

/**
 * Mirrors `ggml_log_level`'s four levels (`GGML_LOG_LEVEL_NONE`/`CONT` are
 * not surfaced here — llama.cpp never emits them for the callback this feeds,
 * per `ggml.h`). The future `llama_log_set` JNI glue (`E1.I4`/`E4.I1`,
 * skein-ca2/skein-3aw) maps the native `ggml_log_level` int to this enum
 * before calling [LlamaLogRedactor.forward].
 */
enum class LlamaLogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Redacts prompt/generation text out of llama.cpp's log callback before it
 * ever reaches [app.skein.core.model.SkeinLog] (spec §9: llama.cpp must
 * never log prompt or chunk content). Pure Kotlin — no JNI dependency; the
 * native `llama_log_set` callback that calls this lands with the llama.cpp
 * build itself (skein-ca2/skein-3aw), out of scope for `E1.I11`/skein-4je.
 *
 * Three rules, the third added by `skein-gg11.2` (OL-19,
 * `JNI_ANALYSIS.md` §5) for load-error lines folded into a failed load's
 * diagnostic, per `E1.I11`'s acceptance criteria plus that bead's own:
 *  - [forward] drops (returns `null` for) anything at [LlamaLogLevel.DEBUG]
 *    or [LlamaLogLevel.INFO] — llama.cpp's own verbose/info logging is not
 *    useful in a release build and is never forwarded, sensitive or not.
 *  - [redact] strips content after a `prompt:`/`text:` marker from anything
 *    that *is* forwarded (`WARN`/`ERROR`), replacing it with
 *    `<redacted N chars>` where `N` is the length of the text that would
 *    have followed the marker. A message with no marker passes through
 *    unchanged; a message is free to contain more than one marker.
 *  - [redact] also replaces any filesystem-path-shaped substring (two or
 *    more `/segment` runs) with `<redacted path>` — a load-error line is
 *    UNTRUSTED input to this function (it can originate in a hostile GGUF's
 *    own load-failure text) and design spec §9 forbids a path crossing this
 *    boundary exactly as it forbids prompt content.
 */
object LlamaLogRedactor {
    private val markers = listOf("prompt:", "text:")

    /** Two or more `/segment` runs — a bare `a/b` (e.g. a throughput unit like `GB/s`) does not match. */
    private val pathPattern = Regex("""(?:/[^\s/]+){2,}/?""")

    /** Level-gate + redact in one call: `null` means "drop, do not log at all". */
    fun forward(
        level: LlamaLogLevel,
        message: String,
    ): String? {
        if (level <= LlamaLogLevel.INFO) return null
        return redact(message)
    }

    /**
     * `redact("prompt: hello world") == "prompt: <redacted 11 chars>"` —
     * `E1.I11` AC(c). The marker and a single separating space (if present)
     * are preserved; only the content after them is replaced. Any
     * filesystem path is replaced first (see this object's KDoc), so a path
     * appearing outside a marker's span is still caught.
     */
    fun redact(message: String): String {
        val pathSafe = pathPattern.replace(message, "<redacted path>")
        val matches = findMarkers(pathSafe)
        if (matches.isEmpty()) return pathSafe

        val result = StringBuilder()
        var cursor = 0
        for ((index, match) in matches.withIndex()) {
            // Everything since the previous match's content, including this
            // marker itself, is copied through untouched.
            result.append(pathSafe, cursor, match.markerEnd)

            var contentStart = match.markerEnd
            val hadSeparatingSpace = contentStart < pathSafe.length && pathSafe[contentStart] == ' '
            if (hadSeparatingSpace) contentStart += 1

            val contentEnd = if (index + 1 < matches.size) matches[index + 1].start else pathSafe.length
            val content = pathSafe.substring(contentStart, contentEnd)

            if (hadSeparatingSpace) result.append(' ')
            result.append("<redacted ").append(content.length).append(" chars>")
            cursor = contentEnd
        }
        result.append(pathSafe, cursor, pathSafe.length)
        return result.toString()
    }

    private data class MarkerMatch(
        val start: Int,
        val markerEnd: Int,
    )

    private fun findMarkers(message: String): List<MarkerMatch> {
        val matches = mutableListOf<MarkerMatch>()
        var searchFrom = 0
        while (searchFrom <= message.length) {
            val next =
                markers
                    .map { marker -> marker to message.indexOf(marker, searchFrom) }
                    .filter { (_, pos) -> pos >= 0 }
                    .minByOrNull { (_, pos) -> pos }
                    ?: break
            val (marker, pos) = next
            matches += MarkerMatch(start = pos, markerEnd = pos + marker.length)
            searchFrom = pos + marker.length
        }
        return matches
    }
}
