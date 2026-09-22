// skein-nxk (E4.I3): stop strings, matched on the accumulated text tail.
//
// A stop string is a property of the TEXT, not of the token stream: "</s>" can
// arrive as three separate pieces, and a matcher that looked at one piece at a
// time would never see it. So the matcher carries the last few characters of
// the previous chunk — exactly `longest - 1` of them, since nothing older can
// still be part of a match that has not already been reported — and scans the
// carry plus the new text together.
//
// It also reports how many characters of the marker were ALREADY delivered to
// the client in earlier chunks, so the worker can retract them. The client must
// never be shown the marker generation stopped on.

package app.skein.inference.service

/**
 * @param stop the literal markers to stop on. Empty strings are ignored: `""`
 *   matches at every position and would end generation before the first token.
 */
class StopStringMatcher(
    stop: List<String>,
) {
    private val stops = stop.filter { it.isNotEmpty() }
    private val longest = stops.maxOfOrNull { it.length } ?: 0

    /** Characters carried over from earlier chunks; at most [longest] - 1. */
    private var carry = ""

    /** A completed stop-string match. */
    data class Match(
        /** The marker that matched. */
        val stop: String,
        /**
         * How many characters of the marker were already handed to the client
         * in EARLIER chunks and must now be retracted.
         *
         * Characters of the marker that arrived in the CURRENT chunk are not
         * counted: the caller has not emitted those yet and simply drops them.
         */
        val retractChars: Int,
    )

    /**
     * Feeds [text] — newly decoded characters, not yet emitted — and reports
     * the first completed match, if any.
     */
    fun append(text: String): Match? {
        if (stops.isEmpty()) return null
        if (text.isEmpty()) return null

        val carried = carry.length
        val window = carry + text

        // Only matches ENDING inside the new text are new; anything ending
        // earlier was already reported on the call that delivered it.
        for (end in carried + 1..window.length) {
            for (marker in stops) {
                val start = end - marker.length
                if (start < 0) continue
                if (!window.regionMatches(start, marker, 0, marker.length)) continue
                val charsInNewText = end - maxOf(start, carried)
                carry = ""
                return Match(marker, retractChars = marker.length - charsInNewText)
            }
        }

        carry = window.takeLast((longest - 1).coerceAtLeast(0))
        return null
    }

    /**
     * True when the carry ends with a proper prefix of some stop string, i.e.
     * the next piece might complete a marker.
     *
     * The worker holds emission back while this is true, so a marker is never
     * partially shown and then retracted.
     */
    fun mayBeMidMatch(): Boolean =
        stops.any { marker ->
            val maxPrefix = minOf(marker.length - 1, carry.length)
            (1..maxPrefix).any { n -> carry.endsWith(marker.substring(0, n)) }
        }

    /** Characters currently carried. Bounded by the longest stop string. */
    fun tailLength(): Int = carry.length
}
