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
// THE RULE, implemented here. One native tokenization preserves ordinary
// text merges across scaffold/content boundaries. CONTROL/UNKNOWN spellings
// are recognized only when wholly inside proven scaffold byte ranges. There
// is no exception for "trusted" messages —
// the persona and the system prompt are content too, and a rule with an
// exemption is a rule with an attack surface.
//
// BOUNDARIES (skein-gg11.28). Looking for the content string in the render is
// ambiguous: "user" matches the role header, and "<|im_start|>" matches the
// opening control token. The actual message then ends up in SCAFFOLD. Instead
// render a second time with unique placeholders, locate those placeholders,
// and substitute the original content. The reconstructed text MUST equal the
// original model render exactly. Empty contents stay empty during the probe
// so a template that omits an empty system message keeps its normal shape.
//
// FAIL CLOSED means refuse before tokenization/decode, not silently turn the
// entire conversation into raw continuation text. Unsupported templates,
// mutated/dropped/duplicated content and unprovable boundaries are explicit
// TEMPLATE_UNSUPPORTED failures. A generic ChatML fallback is not evidence
// that the loaded model was trained for that format.

package app.skein.inference.service

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** What a span of the rendered prompt is. */
enum class SegmentKind {
    /** Written by the chat template; may authorize control-token spellings. */
    SCAFFOLD,

    /** Supplied by a message; cannot authorize control-token spellings. */
    CONTENT,
}

/** One proven provenance span of the rendered prompt. */
data class Segment(
    val kind: SegmentKind,
    val text: String,
)

/**
 * A model render whose scaffold/content boundaries have been checked before
 * tokenization. The segments concatenate to [text] exactly.
 */
data class RenderedPrompt(
    val text: String,
    val segments: List<Segment>,
) {
    val scaffoldSpans: Int get() = segments.count { it.kind == SegmentKind.SCAFFOLD }
    val contentSpans: Int get() = segments.count { it.kind == SegmentKind.CONTENT }
}

object ChatTemplating {
    /**
     * Uses the model's own template twice: the actual render and a boundary
     * probe. Placeholders are never sent to the tokenizer, returned to the
     * client or logged. A template that changes content or structure when
     * probed is refused; native failures propagate without a format fallback.
     */
    fun render(
        backend: LlamaBackend,
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistantPrefix: Boolean,
    ): RenderedPrompt {
        val rendered = backend.applyChatTemplate(model, roles, contents, addAssistantPrefix)
        // The prefix is absent from BOTH actual content and the model render.
        // Untrusted input therefore cannot forge one of the probe boundaries.
        var prefix: String
        do {
            prefix = "SKEIN_BOUNDARY_${UUID.randomUUID()}_"
        } while (prefix in rendered || contents.any { prefix in it })
        val placeholders =
            contents.mapIndexed {
                index,
                content,
                ->
                if (content.isEmpty()) "" else "$prefix${index}_END"
            }
        val probe = backend.applyChatTemplate(model, roles, placeholders.toTypedArray(), addAssistantPrefix)
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (index in contents.indices) {
            val placeholder = placeholders[index]
            if (placeholder.isEmpty()) continue
            val at = probe.indexOf(placeholder, cursor)
            if (at < 0 ||
                probe.indexOf(placeholder) != at ||
                probe.indexOf(placeholder, at + placeholder.length) >= 0
            ) {
                unsupportedBoundaries()
            }
            if (at > cursor) segments += Segment(SegmentKind.SCAFFOLD, probe.substring(cursor, at))
            segments += Segment(SegmentKind.CONTENT, contents[index])
            cursor = at + placeholder.length
        }
        if (cursor < probe.length) segments += Segment(SegmentKind.SCAFFOLD, probe.substring(cursor))
        if (segments.any { prefix in it.text } || segments.joinToString("") { it.text } != rendered) {
            unsupportedBoundaries()
        }
        return RenderedPrompt(rendered, segments)
    }

    private fun unsupportedBoundaries(): Nothing =
        throw LlamaException(LlamaErrorCode.TEMPLATE_UNSUPPORTED, "Chat template content boundaries cannot be verified")

    /**
     * Tokenizes the complete sequence once. Splitting tokenizer calls at message
     * boundaries changes BPE merges, whitespace preprocessing and BOS/EOS.
     * Native partitioning instead restricts only CONTROL/UNKNOWN recognition.
     */
    fun tokenize(
        backend: LlamaBackend,
        model: Long,
        segments: List<Segment>,
    ): IntArray = tokenizeDetailed(backend, model, segments).ids

    /** Exact sequence ids. Per-kind token counts do not exist across merged boundaries. */
    class TokenizedPrompt(
        val ids: IntArray,
    )

    fun tokenizeDetailed(
        backend: LlamaBackend,
        model: Long,
        segments: List<Segment>,
    ): TokenizedPrompt {
        val encoder =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = StringBuilder()
        val ranges = mutableListOf<Int>()
        var byteOffset = 0
        for (segment in segments) {
            val bytes =
                try {
                    encoder.encode(CharBuffer.wrap(segment.text)).remaining()
                } catch (_: CharacterCodingException) {
                    throw LlamaException(LlamaErrorCode.INVALID_ARGUMENT, "Prompt contains invalid Unicode")
                }
            val end = Math.addExact(byteOffset, bytes)
            if (bytes > 0 && segment.kind == SegmentKind.SCAFFOLD) {
                if (ranges.isNotEmpty() && ranges.last() == byteOffset) {
                    ranges[ranges.lastIndex] = end
                } else {
                    ranges += byteOffset
                    ranges += end
                }
            }
            text.append(segment.text)
            byteOffset = end
        }
        return TokenizedPrompt(backend.tokenizeScaffold(model, text.toString(), true, ranges.toIntArray()))
    }
}
