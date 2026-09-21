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
 * Two independent rules, per `E1.I11`'s acceptance criteria:
 *  - [forward] drops (returns `null` for) anything at [LlamaLogLevel.DEBUG]
 *    or [LlamaLogLevel.INFO] — llama.cpp's own verbose/info logging is not
 *    useful in a release build and is never forwarded, sensitive or not.
 *  - [redact] strips content after a `prompt:`/`text:` marker from anything
 *    that *is* forwarded (`WARN`/`ERROR`), replacing it with
 *    `<redacted N chars>` where `N` is the length of the text that would
 *    have followed the marker. A message with no marker passes through
 *    unchanged; a message is free to contain more than one marker.
 */
object LlamaLogRedactor {
    private val markers = listOf("prompt:", "text:")

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
     * are preserved; only the content after them is replaced.
     */
    fun redact(message: String): String {
        val matches = findMarkers(message)
        if (matches.isEmpty()) return message

        val result = StringBuilder()
        var cursor = 0
        for ((index, match) in matches.withIndex()) {
            // Everything since the previous match's content, including this
            // marker itself, is copied through untouched.
            result.append(message, cursor, match.markerEnd)

            var contentStart = match.markerEnd
            val hadSeparatingSpace = contentStart < message.length && message[contentStart] == ' '
            if (hadSeparatingSpace) contentStart += 1

            val contentEnd = if (index + 1 < matches.size) matches[index + 1].start else message.length
            val content = message.substring(contentStart, contentEnd)

            if (hadSeparatingSpace) result.append(' ')
            result.append("<redacted ").append(content.length).append(" chars>")
            cursor = contentEnd
        }
        result.append(message, cursor, message.length)
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
