// `CitationParser` (skein-n5q, E5.I16, plan
// `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines 3706-3722; bead
// `skein-n5q`). Streams `Token.Text` pieces incrementally and splits them
// into `Segment.Text`/`Segment.Citation` against the `AssembledPrompt.citations`
// allow-list, mirroring `PromptGuard.citationsAllowed`'s "a marker outside the
// allow-list stays inert literal text" rule but incrementally, one piece at a
// time, with an 8-character unclosed-bracket timeout.
//
// AAA throughout: one behavior asserted per test. Every test collects the
// segments emitted by every `push` call plus the final `flush()` into one
// flat list, since that is the only order a caller streaming tokens ever
// observes.

package app.skein.core.rag.chat

import app.skein.core.model.DocumentKind
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CitationParserTest {
    private fun retrieved(
        chunkId: Long = 1L,
        docId: String = "doc-1",
    ): Retrieved =
        Retrieved(
            chunkId = chunkId,
            docId = docId,
            docTitle = "Title",
            text = "the retrieved text",
            score = 0.9,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.VECTOR),
        )

    private fun parse(
        allowed: Map<Int, Retrieved>,
        pieces: List<String>,
    ): List<Segment> {
        val parser = CitationParser(allowed)
        val segments = mutableListOf<Segment>()
        for (piece in pieces) segments += parser.push(piece)
        segments += parser.flush()
        return segments
    }

    @Test
    fun `bead AC - streaming pieces split into text, citation, text`() {
        val allowed = mapOf(1 to retrieved(1), 2 to retrieved(2))

        val segments = parse(allowed, listOf("see ", "[", "2", "]", " and [9]"))

        assertThat(segments)
            .containsExactly(
                Segment.Text("see "),
                Segment.Citation(2, allowed.getValue(2)),
                Segment.Text(" and [9]"),
            ).inOrder()
    }

    @Test
    fun `bead AC - a bracket that never closes within 8 characters is flushed as text`() {
        val allowed = mapOf(1 to retrieved(1))

        val segments = parse(allowed, listOf("before [123456789 after"))

        assertThat(segments).containsExactly(Segment.Text("before [123456789 after")).inOrder()
    }

    @Test
    fun `bead AC - persisted grouped marker N,M both allowed emits two citations`() {
        val allowed = mapOf(1 to retrieved(1), 2 to retrieved(2))

        val segments = parse(allowed, listOf("see [1, 2] please"))

        assertThat(segments)
            .containsExactly(
                Segment.Text("see "),
                Segment.Citation(1, allowed.getValue(1)),
                Segment.Citation(2, allowed.getValue(2)),
                Segment.Text(" please"),
            ).inOrder()
    }

    @Test
    fun `a marker at the very end of the stream is flushed as text on flush`() {
        val allowed = mapOf(1 to retrieved(1))

        val segments = parse(allowed, listOf("trailing [2"))

        assertThat(segments).containsExactly(Segment.Text("trailing [2")).inOrder()
    }

    @Test
    fun `a marker split into one-character pieces is still recognized`() {
        val allowed = mapOf(2 to retrieved(2))

        val segments = parse(allowed, listOf("s", "e", "e", " ", "[", "2", "]"))

        assertThat(segments).containsExactly(Segment.Text("see "), Segment.Citation(2, allowed.getValue(2))).inOrder()
    }

    @Test
    fun `a piece boundary inside a surrogate pair does not corrupt surrounding text`() {
        val allowed = mapOf(1 to retrieved(1))
        // U+1F600 GRINNING FACE as a UTF-16 surrogate pair, split across two pieces.
        val emoji = "😀"
        val highSurrogate = emoji.substring(0, 1)
        val lowSurrogate = emoji.substring(1, 2)

        val segments = parse(allowed, listOf("hi $highSurrogate", "$lowSurrogate [1]"))

        assertThat(segments)
            .containsExactly(Segment.Text("hi $emoji "), Segment.Citation(1, allowed.getValue(1)))
            .inOrder()
    }

    @Test
    fun `an invented marker outside the allowed set stays literal text`() {
        val allowed = mapOf(1 to retrieved(1))

        val segments = parse(allowed, listOf("as shown in [9]"))

        assertThat(segments).containsExactly(Segment.Text("as shown in [9]")).inOrder()
    }

    @Test
    fun `a mixed group keeps only the valid marker and drops the invented one silently`() {
        val allowed = mapOf(1 to retrieved(1))

        val segments = parse(allowed, listOf("see [1, 9] here"))

        assertThat(segments)
            .containsExactly(Segment.Text("see "), Segment.Citation(1, allowed.getValue(1)), Segment.Text(" here"))
            .inOrder()
    }

    @Test
    fun `a non-numeric bracket group is not a citation and stays literal text`() {
        val allowed = mapOf(1 to retrieved(1))

        val segments = parse(allowed, listOf("array[i] indexing"))

        assertThat(segments).containsExactly(Segment.Text("array[i] indexing")).inOrder()
    }

    @Test
    fun `an empty allow-list never emits a citation`() {
        val segments = parse(emptyMap(), listOf("see [1]"))

        assertThat(segments).containsExactly(Segment.Text("see [1]")).inOrder()
    }

    @Test
    fun `plain text with no brackets is emitted once flush is called`() {
        val segments = parse(mapOf(1 to retrieved(1)), listOf("no markers here"))

        assertThat(segments).containsExactly(Segment.Text("no markers here")).inOrder()
    }

    @Test
    fun `ready text drains before stream completion without being emitted twice`() {
        val parser = CitationParser(emptyMap())

        assertThat(parser.push("plain answer")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("plain answer"))
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.push(" continues")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text(" continues"))
        assertThat(parser.flush()).isEmpty()
    }

    @Test
    fun `ready text before an unresolved group drains while the citation allow-list stays enforced`() {
        val source = retrieved(1)
        val parser = CitationParser(mapOf(1 to source))

        assertThat(parser.push("see [1,")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("see "))
        assertThat(parser.push(" 9")).isEmpty()
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.push("] after")).containsExactly(Segment.Citation(1, source))
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text(" after"))
        assertThat(parser.flush()).isEmpty()
    }

    @Test
    fun `invalid bracket groups drain as literal text`() {
        for (literal in listOf("[9]", "[i]", "[1,]", "[]")) {
            val parser = CitationParser(mapOf(1 to retrieved(1)))

            assertThat(parser.push(literal.dropLast(1))).isEmpty()
            assertThat(parser.drainReadyText()).isEmpty()
            assertThat(parser.push("]")).isEmpty()
            assertThat(parser.drainReadyText()).containsExactly(Segment.Text(literal))
            assertThat(parser.flush()).isEmpty()
        }
    }

    @Test
    fun `draining keeps eight unresolved bracket characters and releases them on the ninth`() {
        val parser = CitationParser(emptyMap())

        assertThat(parser.push("before [12345678")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("before "))
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.push("9 after")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("[123456789 after"))
        assertThat(parser.flush()).isEmpty()
    }

    @Test
    fun `a citation closing after eight characters still resolves after draining`() {
        val source = retrieved(1)
        val parser = CitationParser(mapOf(12345678 to source))

        assertThat(parser.push("[12345678")).isEmpty()
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.push("]")).containsExactly(Segment.Citation(12345678, source))
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.flush()).isEmpty()
    }

    @Test
    fun `final flush preserves an unresolved bracket after ready text has drained`() {
        val parser = CitationParser(mapOf(1 to retrieved(1)))

        assertThat(parser.push("trailing [1")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("trailing "))
        assertThat(parser.flush()).containsExactly(Segment.Text("[1"))
        assertThat(parser.drainReadyText()).isEmpty()
        assertThat(parser.flush()).isEmpty()
    }

    @Test
    fun `draining does not split a surrogate pair across text segments`() {
        val parser = CitationParser(emptyMap())
        val emoji = "😀"

        assertThat(parser.push("hi ${emoji.substring(0, 1)}")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("hi "))
        assertThat(parser.push("${emoji.substring(1)} there")).isEmpty()
        assertThat(parser.drainReadyText()).containsExactly(Segment.Text("$emoji there"))
        assertThat(parser.flush()).isEmpty()
    }
}
