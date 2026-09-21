package app.skein.core.rag.tokenizers

import java.util.regex.Pattern as JavaPattern

/**
 * One contiguous slice of a string produced by a pattern scan. The slices
 * returned by [findMatches] / [findRegexMatches] always cover the whole input
 * in order, exactly as Hugging Face's `Pattern::find_matches` contract requires.
 */
internal class PatternMatch(
    val start: Int,
    val end: Int,
    val isMatch: Boolean,
)

/** Port of `impl<F: Fn(char) -> bool> Pattern for F`. */
internal fun findMatches(
    inside: String,
    predicate: (Int) -> Boolean,
): List<PatternMatch> {
    if (inside.isEmpty()) return listOf(PatternMatch(0, 0, false))
    val out = ArrayList<PatternMatch>()
    var lastOffset = 0
    var lastSeen = 0
    var i = 0
    while (i < inside.length) {
        val cp = inside.codePointAt(i)
        val width = Character.charCount(cp)
        lastSeen = i + width
        if (predicate(cp)) {
            if (lastOffset < i) out.add(PatternMatch(lastOffset, i, false))
            out.add(PatternMatch(i, i + width, true))
            lastOffset = i + width
        }
        i += width
    }
    if (lastSeen > lastOffset) out.add(PatternMatch(lastOffset, lastSeen, false))
    return out
}

/** Port of `impl Pattern for &SysRegex`. */
internal fun findRegexMatches(
    inside: String,
    regex: JavaPattern,
): List<PatternMatch> {
    if (inside.isEmpty()) return listOf(PatternMatch(0, 0, false))
    val out = ArrayList<PatternMatch>()
    val matcher = regex.matcher(inside)
    var prev = 0
    while (matcher.find()) {
        if (prev != matcher.start()) out.add(PatternMatch(prev, matcher.start(), false))
        out.add(PatternMatch(matcher.start(), matcher.end(), true))
        prev = matcher.end()
    }
    if (prev != inside.length) out.add(PatternMatch(prev, inside.length, false))
    return out
}

/** A `[start, end)` range that survives splitting. */
internal class SplitRange(
    val start: Int,
    val end: Int,
)

/** Port of `SplitDelimiterBehavior` — only the variants our two pipelines use. */
internal enum class SplitDelimiterBehavior {
    /** The delimiter is dropped. */
    REMOVED,

    /** The delimiter becomes a split of its own. */
    ISOLATED,

    /** The delimiter is glued to the following split (Metaspace's `▁`). */
    MERGED_WITH_NEXT,
    ;

    fun apply(matches: List<PatternMatch>): List<SplitRange> =
        when (this) {
            REMOVED -> matches.filter { !it.isMatch }.map { SplitRange(it.start, it.end) }
            ISOLATED -> matches.map { SplitRange(it.start, it.end) }
            MERGED_WITH_NEXT -> {
                // Folded right-to-left, exactly as the Rust does: a delimiter is
                // merged into the split that follows it unless that split is
                // itself a delimiter.
                val acc = ArrayList<IntArray>()
                var previousMatch = false
                for (i in matches.indices.reversed()) {
                    val m = matches[i]
                    if (m.isMatch && !previousMatch && acc.isNotEmpty()) {
                        acc[acc.size - 1][0] = m.start
                    } else {
                        acc.add(intArrayOf(m.start, m.end))
                    }
                    previousMatch = m.isMatch
                }
                acc.reverse()
                acc.map { SplitRange(it[0], it[1]) }
            }
        }
}
