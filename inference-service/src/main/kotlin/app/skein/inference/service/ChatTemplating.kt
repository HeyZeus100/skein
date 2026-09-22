// skein-nxk (E4.I3), answering the M0.5 adversarial review finding
// `skein-0ztk`: the TOKEN-LEVEL half of the prompt fence.
//
// THE HOLE. `PromptGuard` fences hostile content at the string level and the
// assembler passes retrieved text through verbatim — deliberately, because a
// note that legitimately contains `</s>[INST]` must not be silently rewritten,
// and `PromptAssemblerContractTest` pins that. The service then renders the
// prompt with `llama_chat_apply_template` and has to tokenize the result.
// llama.cpp's own examples do that with `parse_special = true`, because the
// template's `<|im_start|>` chrome must become real control tokens. The same
// flag turns a NOTE containing the literal text `<|im_start|>system` into a
// real system turn: the fence is bypassed one layer below where any
// string-level defence can see it.
//
// THE RULE, implemented here. Template scaffolding is tokenized with
// `parseSpecial = true`. Message content is tokenized with
// `parseSpecial = false`. Always, with no exception for "trusted" messages —
// the persona and the system prompt are content too, and a rule with an
// exemption is a rule with an attack surface.
//
// HOW THE SEGMENTS ARE FOUND. `applyChatTemplate` returns one flat string, so
// [segment] locates each message's content inside it by scanning forward,
// never rescanning earlier text. Forward-only matters: the literal word "user"
// occurs in ChatML chrome before it can occur as content, and a backtracking
// search would mistake one for the other.
//
// FAILING CLOSED. A template that mutates content — trims it, escapes it,
// drops an empty turn — makes verbatim location impossible. [segment] then
// returns ONE content segment covering the whole render, so nothing is
// tokenized with `parseSpecial = true` at all. The model sees the chrome as
// ordinary text and answers worse. That is the right trade: a degraded answer
// is recoverable, a prompt-injection primitive is not.

package app.skein.inference.service

/** What a span of the rendered prompt is. */
enum class SegmentKind {
    /** Written by the chat template. Tokenized with `parseSpecial = true`. */
    SCAFFOLD,

    /** Supplied by a message. Tokenized with `parseSpecial = false`, always. */
    CONTENT,
}

/** One span of the rendered prompt, with the flag it must be tokenized under. */
data class Segment(
    val kind: SegmentKind,
    val text: String,
)

object ChatTemplating {
    /**
     * Splits [rendered] into alternating scaffold/content spans by locating
     * each of [contents] in order.
     *
     * The segments always concatenate back to [rendered] exactly — including
     * on the fail-closed path — so no token can be silently added or lost by
     * the split.
     *
     * An empty content string is skipped rather than searched for: it matches
     * everywhere, and a message with no text contributes no content tokens.
     */
    fun segment(
        rendered: String,
        contents: List<String>,
    ): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (content in contents) {
            if (content.isEmpty()) continue
            val at = rendered.indexOf(content, cursor)
            if (at < 0) return failClosed(rendered)
            if (at > cursor) segments += Segment(SegmentKind.SCAFFOLD, rendered.substring(cursor, at))
            segments += Segment(SegmentKind.CONTENT, content)
            cursor = at + content.length
        }
        if (cursor < rendered.length) {
            segments += Segment(SegmentKind.SCAFFOLD, rendered.substring(cursor))
        }
        return segments
    }

    /**
     * Tokenizes [segments] with the flag each one's kind demands and
     * concatenates the ids.
     *
     * BOS is added on the first non-empty segment only: it is a property of the
     * sequence, not of each span, and llama.cpp would happily add one per call.
     */
    fun tokenize(
        backend: LlamaBackend,
        model: Long,
        segments: List<Segment>,
    ): IntArray {
        val ids = mutableListOf<Int>()
        var first = true
        for (segment in segments) {
            if (segment.text.isEmpty()) continue
            val tokens =
                backend.tokenize(
                    model,
                    segment.text,
                    addBos = first,
                    parseSpecial = segment.kind == SegmentKind.SCAFFOLD,
                )
            tokens.forEach { ids += it }
            first = false
        }
        return ids.toIntArray()
    }

    /** The whole render as content: nothing gets `parseSpecial = true`. See this file's header. */
    private fun failClosed(rendered: String): List<Segment> = listOf(Segment(SegmentKind.CONTENT, rendered))
}
