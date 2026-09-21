package app.skein.core.rag.tokenizers

/** A minimal growable `int` vector — avoids boxing in the alignment hot paths. */
internal class IntList(
    initialCapacity: Int = 16,
) {
    var data: IntArray = IntArray(if (initialCapacity < 4) 4 else initialCapacity)
        private set
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun addRepeated(
        value: Int,
        times: Int,
    ) {
        for (i in 0 until times) add(value)
    }

    fun addRange(
        source: IntArray,
        from: Int,
        to: Int,
    ) {
        for (i in from until to) add(source[i])
    }

    /** Drops everything past [newSize]; never grows. */
    fun shrinkTo(newSize: Int) {
        if (newSize in 0 until size) size = newSize
    }

    fun toIntArray(): IntArray = data.copyOf(size)
}

/**
 * The list of `(char, change)` pairs a transformation feeds to
 * [NormalizedString.transform]. `change` follows Hugging Face's convention:
 *
 *  * `1`  — this is a newly inserted character
 *  * `0`  — this character replaces the existing one
 *  * `-N` — this character replaces the existing one and is immediately
 *           followed by `N` removed characters
 */
internal class Transformation(
    initialCapacity: Int = 16,
) {
    private val codePoints = IntList(initialCapacity)
    private val changes = IntList(initialCapacity)

    val size: Int get() = codePoints.size

    fun add(
        codePoint: Int,
        change: Int,
    ) {
        codePoints.add(codePoint)
        changes.add(change)
    }

    /** Replaces the `change` recorded for the most recently added character. */
    fun setLastChange(change: Int) {
        changes.data[changes.size - 1] = change
    }

    fun codePointAt(index: Int): Int = codePoints.data[index]

    fun changeAt(index: Int): Int = changes.data[index]
}

/**
 * A port of Hugging Face `tokenizers`' `NormalizedString`
 * (`tokenizers/src/tokenizer/normalizer.rs`, v0.22.1).
 *
 * It carries the original text, its normalized form, and a per-unit alignment
 * from the normalized form back to the original, so that token offsets survive
 * lowercasing, accent stripping, whitespace collapsing and CJK padding.
 *
 * The Rust original indexes everything in **UTF-8 bytes**. This port indexes
 * everything in **UTF-16 code units** instead, which is the natural unit for a
 * Kotlin `String`. The two are isomorphic for this algorithm: every index is
 * only ever used to address [alignStart]/[alignEnd], which replicate one entry
 * per encoding unit of each character, so "the unit just before index `i`"
 * still means "the last unit of the preceding character" in either encoding.
 * The benefit is that the offsets this produces are directly usable with
 * `String.substring`, with no byte/char conversion pass at the end (the Rust
 * pipeline needs `BytesToCharOffsetConverter` for that).
 *
 * Not thread-safe; instances are created per `encode` call.
 */
internal class NormalizedString private constructor(
    private val original: String,
    private var normalized: String,
    private var alignStart: IntArray,
    private var alignEnd: IntArray,
    private val originalShift: Int,
) {
    companion object {
        fun of(
            text: String,
            originalShift: Int = 0,
        ): NormalizedString {
            val starts = IntArray(text.length)
            val ends = IntArray(text.length)
            var i = 0
            while (i < text.length) {
                val width = Character.charCount(text.codePointAt(i))
                for (k in i until i + width) {
                    starts[k] = i
                    ends[k] = i + width
                }
                i += width
            }
            return NormalizedString(text, text, starts, ends, originalShift)
        }
    }

    /** The normalized text. */
    fun get(): String = normalized

    fun isEmpty(): Boolean = normalized.isEmpty()

    fun length(): Int = normalized.length

    /** `(start, end)` of this (possibly sliced) string inside the outermost original text. */
    fun originalStart(): Int = originalShift

    // ---------------------------------------------------------------- offsets

    /**
     * Converts a range expressed in normalized units to the equivalent range in
     * this string's own original text. Mirrors `convert_offsets(Normalized(..))`.
     */
    fun convertOffsetsToOriginal(
        start: Int,
        end: Int,
    ): IntArray {
        if (start == end) return intArrayOf(start, end)
        if (start > end || start < 0 || end > normalized.length) return intArrayOf(start, end)
        return intArrayOf(alignStart[start], alignEnd[end - 1])
    }

    /**
     * Converts the full original range to normalized units. Mirrors
     * `convert_offsets(Original(..))`, including its "skip leading zero-width
     * alignments" behaviour.
     */
    private fun fullOriginalRangeAsNormalized(): IntArray {
        val lenOriginal = original.length
        val lenNormalized = normalized.length
        if (lenOriginal == 0) return intArrayOf(0, lenNormalized)
        var start = -1
        var end = -1
        for (i in 0 until lenNormalized) {
            if (lenOriginal < alignEnd[i]) break
            if (start < 0 && alignStart[i] != alignEnd[i]) start = i
            end = i + 1
        }
        if (start < 0 && end < 0) return intArrayOf(0, 0)
        if (start < 0) return intArrayOf(end, end)
        if (end < 0) return intArrayOf(start, start)
        return intArrayOf(start, end)
    }

    // ------------------------------------------------------------- transforms

    fun transform(
        dest: Transformation,
        initialOffset: Int,
    ) {
        val range = fullOriginalRangeAsNormalized()
        transformRange(range[0], range[1], dest, initialOffset)
    }

    /**
     * Applies [dest] over `normalized[nStart, nEnd)`, updating the alignments.
     * Port of `transform_range`.
     */
    fun transformRange(
        nStart: Int,
        nEnd: Int,
        dest: Transformation,
        initialOffset: Int,
    ) {
        // The characters being replaced, so we can track the change in width.
        val replaced = IntList(maxOf(4, nEnd - nStart))
        var i = nStart
        while (i < nEnd) {
            val cp = normalized.codePointAt(i)
            replaced.add(cp)
            i += Character.charCount(cp)
        }

        var replacedIndex = 0
        var initialRemoved = 0
        while (replacedIndex < initialOffset && replacedIndex < replaced.size) {
            initialRemoved += Character.charCount(replaced.data[replacedIndex])
            replacedIndex++
        }

        var offset = initialRemoved + nStart
        val newStarts = IntList(maxOf(4, nEnd - nStart))
        val newEnds = IntList(maxOf(4, nEnd - nStart))
        val builder = StringBuilder(maxOf(16, nEnd - nStart))

        for (j in 0 until dest.size) {
            val cp = dest.codePointAt(j)
            val change = dest.changeAt(j)
            val idx = offset
            val alignedStart: Int
            val alignedEnd: Int
            if (change > 0) {
                if (idx < 1) {
                    alignedStart = 0
                    alignedEnd = 0
                } else {
                    val at = clampIndex(idx - 1)
                    alignedStart = alignStart[at]
                    alignedEnd = alignEnd[at]
                }
            } else {
                val at = clampIndex(idx)
                alignedStart = alignStart[at]
                alignedEnd = alignEnd[at]
            }

            var replacedWidth = 0
            if (change <= 0 && replacedIndex < replaced.size) {
                replacedWidth = Character.charCount(replaced.data[replacedIndex])
                replacedIndex++
            }
            var removedWidth = 0
            if (change < 0) {
                var remaining = -change
                while (remaining > 0 && replacedIndex < replaced.size) {
                    removedWidth += Character.charCount(replaced.data[replacedIndex])
                    replacedIndex++
                    remaining--
                }
            }
            offset += replacedWidth + removedWidth

            val width = Character.charCount(cp)
            newStarts.addRepeated(alignedStart, width)
            newEnds.addRepeated(alignedEnd, width)
            builder.appendCodePoint(cp)
        }

        spliceAlignments(nStart, nEnd, newStarts, newEnds)
        normalized = normalized.substring(0, nStart) + builder + normalized.substring(nEnd)
    }

    private fun clampIndex(index: Int): Int =
        when {
            alignStart.isEmpty() -> 0
            index < 0 -> 0
            index >= alignStart.size -> alignStart.size - 1
            else -> index
        }

    private fun spliceAlignments(
        nStart: Int,
        nEnd: Int,
        newStarts: IntList,
        newEnds: IntList,
    ) {
        val starts = IntList(alignStart.size - (nEnd - nStart) + newStarts.size + 4)
        val ends = IntList(starts.size + 4)
        starts.addRange(alignStart, 0, nStart)
        ends.addRange(alignEnd, 0, nStart)
        starts.addRange(newStarts.data, 0, newStarts.size)
        ends.addRange(newEnds.data, 0, newEnds.size)
        starts.addRange(alignStart, nEnd, alignStart.size)
        ends.addRange(alignEnd, nEnd, alignEnd.size)
        alignStart = starts.toIntArray()
        alignEnd = ends.toIntArray()
    }

    /** Port of `filter`: drops every code point for which [keep] is false. */
    fun filter(keep: (Int) -> Boolean) {
        var removed = 0
        var removedStart = 0
        val transformation = Transformation(normalized.length)
        var lastCp = -1
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            if (keep(cp)) {
                if (lastCp >= 0) {
                    transformation.add(lastCp, -removed)
                } else {
                    removedStart = removed
                }
                lastCp = cp
                removed = 0
            } else {
                removed++
            }
        }
        if (lastCp >= 0) transformation.add(lastCp, -removed)
        transform(transformation, removedStart)
    }

    /** Port of `map`: replaces every code point one-for-one. */
    fun map(mapper: (Int) -> Int) {
        val transformation = Transformation(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            transformation.add(mapper(cp), 0)
        }
        transform(transformation, 0)
    }

    /** Port of `lowercase`, using the full (locale-independent) Unicode mapping. */
    fun lowercase() {
        val transformation = Transformation(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            // Lowercasing one code point at a time is deliberate: it keeps the
            // Greek final-sigma context rule from firing, which is exactly what
            // Rust's `char::to_lowercase` does.
            val lowered = String(Character.toChars(cp)).lowercase(java.util.Locale.ROOT)
            var k = 0
            var index = 0
            while (k < lowered.length) {
                val out = lowered.codePointAt(k)
                k += Character.charCount(out)
                transformation.add(out, if (index > 0) 1 else 0)
                index++
            }
        }
        transform(transformation, 0)
    }

    /** Port of `replace`, with [findMatches] supplying the pattern's coverage of the string. */
    fun replace(
        matches: List<PatternMatch>,
        content: String,
    ) {
        val builder = StringBuilder(normalized.length)
        val starts = IntList(alignStart.size)
        val ends = IntList(alignEnd.size)
        var lastEnd = 0
        for (match in matches) {
            if (!match.isMatch) continue
            builder.append(normalized, lastEnd, match.start)
            starts.addRange(alignStart, lastEnd, match.start)
            ends.addRange(alignEnd, lastEnd, match.start)

            // Every replacement character is "new" (change == 1) and therefore
            // inherits the alignment of the unit just before the offset, which
            // for a replacement starts at the *end* of the matched region.
            var offset = match.end
            var k = 0
            while (k < content.length) {
                val cp = content.codePointAt(k)
                k += Character.charCount(cp)
                val alignedStart: Int
                val alignedEnd: Int
                if (offset < 1) {
                    alignedStart = 0
                    alignedEnd = 0
                } else {
                    val at = clampIndex(offset - 1)
                    alignedStart = alignStart[at]
                    alignedEnd = alignEnd[at]
                }
                val width = Character.charCount(cp)
                starts.addRepeated(alignedStart, width)
                ends.addRepeated(alignedEnd, width)
                builder.appendCodePoint(cp)
            }
            lastEnd = match.end
        }
        builder.append(normalized, lastEnd, normalized.length)
        starts.addRange(alignStart, lastEnd, alignStart.size)
        ends.addRange(alignEnd, lastEnd, alignEnd.size)

        normalized = builder.toString()
        alignStart = starts.toIntArray()
        alignEnd = ends.toIntArray()
    }

    /** Port of `prepend`. */
    fun prepend(text: String) {
        if (normalized.isEmpty()) return
        val nextWidth = Character.charCount(normalized.codePointAt(0))
        val transformation = Transformation(text.length + 1)
        var i = 0
        var index = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            transformation.add(cp, if (index != 0) 1 else 0)
            index++
        }
        transformation.add(normalized.codePointAt(0), 1)
        transformRange(0, nextWidth, transformation, 0)
    }

    /** Port of `lrstrip` / `strip`, using the Unicode `White_Space` property. */
    fun strip(
        left: Boolean,
        right: Boolean,
    ) {
        val codePoints = IntList(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            codePoints.add(cp)
        }
        val count = codePoints.size
        var leading = 0
        if (left) {
            while (leading < count && Unicode.isWhitespace(codePoints.data[leading])) leading++
        }
        var trailing = 0
        if (right) {
            while (trailing < count - leading && Unicode.isWhitespace(codePoints.data[count - 1 - trailing])) trailing++
        }
        if (leading == 0 && trailing == 0) return

        val transformation = Transformation(count)
        for (index in 0 until count) {
            if (index < leading || index >= count - trailing) continue
            transformation.add(codePoints.data[index], 0)
        }
        if (transformation.size > 0 && trailing > 0) transformation.setLastChange(-trailing)
        transform(transformation, leading)
    }

    /** Port of `split`: returns the kept sub-strings, each a slice of this one. */
    fun split(
        matches: List<PatternMatch>,
        behavior: SplitDelimiterBehavior,
    ): List<NormalizedString> {
        val ranges = behavior.apply(matches)
        val out = ArrayList<NormalizedString>(ranges.size)
        for (range in ranges) {
            out.add(slice(range.start, range.end))
        }
        return out
    }

    /** Port of `slice(Range::Normalized(..))`. */
    fun slice(
        nStart: Int,
        nEnd: Int,
    ): NormalizedString {
        val originalRange = convertOffsetsToOriginal(nStart, nEnd)
        val oStart = originalRange[0].coerceIn(0, original.length)
        val oEnd = originalRange[1].coerceIn(oStart, original.length)
        val size = nEnd - nStart
        val starts = IntArray(size)
        val ends = IntArray(size)
        for (i in 0 until size) {
            starts[i] = alignStart[nStart + i] - originalRange[0]
            ends[i] = alignEnd[nStart + i] - originalRange[0]
        }
        return NormalizedString(
            original = original.substring(oStart, oEnd),
            normalized = normalized.substring(nStart, nEnd),
            alignStart = starts,
            alignEnd = ends,
            originalShift = originalShift + originalRange[0],
        )
    }
}
