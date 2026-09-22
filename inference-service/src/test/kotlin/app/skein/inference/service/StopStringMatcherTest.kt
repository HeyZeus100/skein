// skein-nxk (E4.I3) step 1: `StopStringMatcher`.
//
// A stop string is a property of the TEXT, not of the token stream, so it can
// straddle any number of token boundaries — "</s>" may arrive as "<", "/s", ">".
// The matcher therefore runs over an accumulated tail rather than per piece,
// and it must report how much of the emitted text to retract, because the
// client must never be shown the stop marker it stopped on.

package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopStringMatcherTest {
    @Test
    fun `no stop strings never matches`() {
        val matcher = StopStringMatcher(emptyList())

        assertNull(matcher.append("anything at all"))
    }

    @Test
    fun `an empty stop string is ignored rather than matching everything`() {
        val matcher = StopStringMatcher(listOf(""))

        assertNull(matcher.append("anything at all"))
    }

    @Test
    fun `a stop string arriving whole matches`() {
        val matcher = StopStringMatcher(listOf("</s>"))

        assertEquals("</s>", matcher.append("all done</s>")?.stop)
    }

    @Test
    fun `a stop string split across three chunks matches`() {
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("all done<")
        matcher.append("/s")

        assertEquals("</s>", matcher.append(">")?.stop)
    }

    @Test
    fun `the match reports every marker character already emitted`() {
        // "<", "/" and "s" went out in earlier chunks; only ">" is new.
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("all done<")
        matcher.append("/s")

        assertEquals(3, matcher.append(">")?.retractChars)
    }

    @Test
    fun `retractChars counts only the part of the marker already emitted`() {
        // "all done<" and "/s" were emitted (3 marker chars); ">" completes it.
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("all done<")

        assertEquals(1, matcher.append("/s>")?.retractChars)
    }

    @Test
    fun `a marker arriving whole in one chunk retracts none of the earlier output`() {
        // "all done" was emitted in its own chunk and contains no marker
        // characters, so nothing already sent to the client must be taken back.
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("all done")

        assertEquals(0, matcher.append("</s>")?.retractChars)
    }

    @Test
    fun `the first of several stop strings to complete wins`() {
        val matcher = StopStringMatcher(listOf("STOP", "<|im_end|>"))

        assertEquals("STOP", matcher.append("blah STOP")?.stop)
    }

    @Test
    fun `a longer stop string still matches when it completes first`() {
        val matcher = StopStringMatcher(listOf("STOP", "<|im_end|>"))

        assertEquals("<|im_end|>", matcher.append("blah <|im_end|>")?.stop)
    }

    @Test
    fun `a partial match is not a match`() {
        val matcher = StopStringMatcher(listOf("</s>"))

        assertNull(matcher.append("almost </s"))
    }

    @Test
    fun `a partial match does not wedge a later real match`() {
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("almost </s")

        assertNotNull(matcher.append("x and then </s>"))
    }

    @Test
    fun `mayBeMidMatch is true while a prefix is pending`() {
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("text <")

        assertTrue(matcher.mayBeMidMatch())
    }

    @Test
    fun `mayBeMidMatch is false when nothing is pending`() {
        val matcher = StopStringMatcher(listOf("</s>"))
        matcher.append("plain text")

        assertFalse(matcher.mayBeMidMatch())
    }

    @Test
    fun `the retained tail never grows past the longest stop string`() {
        val matcher = StopStringMatcher(listOf("</s>"))
        repeat(200) { matcher.append("0123456789") }

        assertTrue(matcher.tailLength() <= 4)
    }

    @Test
    fun `matching is case sensitive`() {
        val matcher = StopStringMatcher(listOf("STOP"))

        assertNull(matcher.append("please stop"))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
