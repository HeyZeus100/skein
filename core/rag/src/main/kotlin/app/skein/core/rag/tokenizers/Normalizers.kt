package app.skein.core.rag.tokenizers

import java.text.Normalizer as JavaNormalizer
import java.util.regex.Pattern as JavaPattern

/** One stage of a `tokenizer.json` `normalizer` pipeline. */
internal fun interface Normalizer {
    fun normalize(input: NormalizedString)
}

/** `{"type": "Sequence", "normalizers": [...]}`. */
internal class SequenceNormalizer(
    private val stages: List<Normalizer>,
) : Normalizer {
    override fun normalize(input: NormalizedString) {
        for (stage in stages) stage.normalize(input)
    }
}

/** `{"type": "NFC" | "NFD" | "NFKC" | "NFKD"}`. */
internal class UnicodeNormalizer(
    private val form: JavaNormalizer.Form,
) : Normalizer {
    override fun normalize(input: NormalizedString) {
        val text = input.get()
        if (text.isEmpty()) return
        if (JavaNormalizer.isNormalized(text, form)) return
        input.transform(buildTransformation(text, form), 0)
    }

    companion object {
        /**
         * Unicode normalization with alignment tracking, mirroring the
         * `unicode_normalization_alignments` crate Hugging Face uses.
         *
         * The string is cut into *composition segments* — a starter plus every
         * following combining mark — and each is normalized on its own, so a
         * segment's output characters can be attributed back to its input
         * characters.
         *
         * The composing forms need one extra rule: Hangul jamo compose
         * (L + V + T → a syllable) even though every one of them is a starter,
         * so a run of Hangul is kept in a single segment for NFC/NFKC. The
         * decomposing forms must *not* do that — each syllable decomposes
         * independently, and merging them would smear every jamo's offset onto
         * the first syllable.
         */
        fun buildTransformation(
            text: String,
            form: JavaNormalizer.Form,
        ): Transformation {
            val composes = form == JavaNormalizer.Form.NFC || form == JavaNormalizer.Form.NFKC
            val transformation = Transformation(text.length)
            var segmentStart = 0
            var i = 0
            var previousCp = -1
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val width = Character.charCount(cp)
                val continues = i == segmentStart || isContinuation(cp, previousCp, composes)
                if (!continues) {
                    appendSegment(transformation, text, segmentStart, i, form)
                    segmentStart = i
                }
                previousCp = cp
                i += width
            }
            if (segmentStart < text.length) {
                appendSegment(transformation, text, segmentStart, text.length, form)
            }
            return transformation
        }

        private fun isContinuation(
            cp: Int,
            previousCp: Int,
            composes: Boolean,
        ): Boolean {
            if (isCombiningMark(cp)) return true
            return composes && isHangul(cp) && previousCp >= 0 && isHangul(previousCp)
        }

        private fun isHangul(cp: Int): Boolean =
            cp in 0x1100..0x11FF || cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF || cp in 0xAC00..0xD7A3

        private fun appendSegment(
            transformation: Transformation,
            text: String,
            from: Int,
            to: Int,
            form: JavaNormalizer.Form,
        ) {
            val segment = text.substring(from, to)
            val normalized = JavaNormalizer.normalize(segment, form)
            val inputCount = segment.codePointCount(0, segment.length)
            val outputCount = normalized.codePointCount(0, normalized.length)
            var k = 0
            var index = 0
            while (k < normalized.length) {
                val cp = normalized.codePointAt(k)
                k += Character.charCount(cp)
                // Composition always folds the following marks into the segment's
                // first character, so that is the one that "removes" the inputs
                // the segment shed; decomposition appends new characters after
                // the ones that replace the originals.
                val change =
                    when {
                        outputCount > inputCount -> if (index >= inputCount) 1 else 0
                        index == 0 -> -(inputCount - outputCount)
                        else -> 0
                    }
                transformation.add(cp, change)
                index++
            }
        }
    }
}

/** `unicode_normalization_alignments::char::is_combining_mark` — general category group `M`. */
internal fun isCombiningMark(cp: Int): Boolean =
    when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        -> true
        else -> false
    }

/** `{"type": "Lowercase"}`. */
internal object LowercaseNormalizer : Normalizer {
    override fun normalize(input: NormalizedString) = input.lowercase()
}

/** `{"type": "StripAccents"}`. */
internal object StripAccentsNormalizer : Normalizer {
    override fun normalize(input: NormalizedString) = input.filter { !isCombiningMark(it) }
}

/** `{"type": "Strip", "strip_left": …, "strip_right": …}`. */
internal class StripNormalizer(
    private val left: Boolean,
    private val right: Boolean,
) : Normalizer {
    override fun normalize(input: NormalizedString) = input.strip(left, right)
}

/** `{"type": "Replace", "pattern": {...}, "content": "..."}`. */
internal class ReplaceNormalizer(
    private val regex: JavaPattern,
    private val content: String,
) : Normalizer {
    override fun normalize(input: NormalizedString) {
        input.replace(findRegexMatches(input.get(), regex), content)
    }
}

/** `{"type": "Prepend", "prepend": "..."}`. */
internal class PrependNormalizer(
    private val text: String,
) : Normalizer {
    override fun normalize(input: NormalizedString) = input.prepend(text)
}

/**
 * `{"type": "BertNormalizer", ...}` — clean text, pad CJK, strip accents,
 * lowercase, in that order.
 */
internal class BertNormalizer(
    private val cleanText: Boolean,
    private val handleChineseChars: Boolean,
    private val stripAccents: Boolean?,
    private val lowercase: Boolean,
) : Normalizer {
    override fun normalize(input: NormalizedString) {
        if (cleanText) {
            input.filter { cp -> !(cp == 0 || cp == 0xFFFD || Unicode.isBertControl(cp)) }
            input.map { cp -> if (Unicode.isBertWhitespace(cp)) ' '.code else cp }
        }
        if (handleChineseChars) {
            val transformation = Transformation(input.length())
            val text = input.get()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (Unicode.isChineseChar(cp)) {
                    transformation.add(' '.code, 0)
                    transformation.add(cp, 1)
                    transformation.add(' '.code, 1)
                } else {
                    transformation.add(cp, 0)
                }
            }
            input.transform(transformation, 0)
        }
        if (stripAccents ?: lowercase) {
            UnicodeNormalizer(JavaNormalizer.Form.NFD).normalize(input)
            input.filter { !Unicode.isNonSpacingMark(it) }
        }
        if (lowercase) {
            input.lowercase()
        }
    }
}
