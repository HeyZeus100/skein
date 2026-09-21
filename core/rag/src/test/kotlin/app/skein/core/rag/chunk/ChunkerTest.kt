package app.skein.core.rag.chunk

import app.skein.core.rag.tokenizers.Tokenizer
import app.skein.core.rag.tokenizers.TokenizerFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * bd skein-92u (E5.I5). Exercises `Chunker` against the real nomic tokenizer
 * (skein-bpt), the same one ingest budgets against, rather than an
 * approximation — see [TokenizerFixtures].
 */
class ChunkerTest {
    private val nomic: Tokenizer by lazy { TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC) }
    private val chunker: Chunker by lazy { Chunker(nomic) }

    // ---------------------------------------------------------------- empty

    @Test
    fun emptyBodyProducesNoChunks() {
        val chunks = chunker.chunk("")

        assertThat(chunks).isEmpty()
    }

    @Test
    fun whitespaceOnlyBodyProducesNoChunks() {
        val chunks = chunker.chunk("   \n\n\t\n  ")

        assertThat(chunks).isEmpty()
    }

    @Test
    fun frontmatterOnlyBodyProducesNoChunks() {
        val body = "---\ntitle: Only frontmatter\ntags: [a, b]\n---\n"

        val chunks = chunker.chunk(body)

        assertThat(chunks).isEmpty()
    }

    @Test
    fun frontmatterIsStrippedBeforeChunkingTheRest() {
        val body = "---\ntitle: Has a body too\n---\nActual body content here."

        val chunks = chunker.chunk(body)

        assertThat(chunks.single().text).isEqualTo("Actual body content here.")
    }

    // ------------------------------------------------------------- offsets

    @Test
    fun everyChunkTextIsExactlyItsBodySubstring() {
        val chunks = chunker.chunk(sampleNoteWithHeadingsAndParagraphs())

        for (c in chunks) assertThat(sampleNoteWithHeadingsAndParagraphs().substring(c.start, c.end)).isEqualTo(c.text)
    }

    @Test
    fun ordinalsAreSequentialFromZero() {
        val chunks = chunker.chunk(sampleNoteWithHeadingsAndParagraphs())

        assertThat(chunks.map { it.ord }).isEqualTo(chunks.indices.toList())
    }

    // ------------------------------------------------------------ boundary

    @Test
    fun fencedCodeBlockIsNeverSplitEvenWhenOverTarget() {
        val code = (1..700).joinToString(" ") { "token$it" }
        val body = "# Title\n\nIntro paragraph.\n\n```kotlin\n$code\n```\n\nOutro paragraph."

        val chunks = chunker.chunk(body)

        val fenceChunk = chunks.first { it.text.contains("```kotlin") }
        assertThat(fenceChunk.text).contains(code)
        assertThat(fenceChunk.text.trim()).startsWith("```kotlin")
        assertThat(fenceChunk.text.trim()).endsWith("```")
    }

    @Test
    fun headingIsNeverSeparatedFromItsFirstParagraph() {
        val body = "# Title\n\nFirst paragraph under the title.\n\n" + "Filler content follows here. ".repeat(300)

        val chunks = chunker.chunk(body)

        val first = chunks.first()
        assertThat(first.text).contains("# Title")
        assertThat(first.text).contains("First paragraph under the title.")
    }

    @Test
    fun listItemsStayGroupedInOneChunkWhenTheyFitTheBudget() {
        val list = (1..10).joinToString("\n") { "- item number $it" }
        val body = "# List\n\n$list"

        val chunks = chunker.chunk(body)

        assertThat(chunks).hasSize(1)
        for (i in 1..10) assertThat(chunks.first().text).contains("item number $i")
    }

    // --------------------------------------------------------------- budget

    @Test
    fun everyChunkRespectsTheTargetTokenBudgetWithHeadingSlack() {
        val body = eightParagraphNote()
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)

        val chunks = budgetedChunker.chunk(body)

        for (c in chunks) assertThat(c.tokenCount).isAtMost(520)
    }

    @Test
    fun consecutiveChunksShareAtLeastTheConfiguredOverlap() {
        val body = eightParagraphNote()
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)

        val chunks = budgetedChunker.chunk(body)

        assertThat(chunks.size).isAtLeast(2)
        for (i in 1 until chunks.size) {
            val overlapStart = maxOf(chunks[i].start, chunks[i - 1].start)
            val overlapEnd = minOf(chunks[i].end, chunks[i - 1].end)
            assertThat(overlapEnd).isGreaterThan(overlapStart)
            assertThat(nomic.countTokens(body.substring(overlapStart, overlapEnd))).isAtLeast(40)
        }
    }

    // -------------------------------------------------------------- oversize

    @Test
    fun oversizedSingleParagraphIsSplitAtSentenceBoundaries() {
        val sentence = "This is one sentence in a very long paragraph that keeps going on and on. "
        val body = sentence.repeat(200)
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)

        val chunks = budgetedChunker.chunk(body)

        assertThat(chunks.size).isAtLeast(2)
        for (c in chunks) assertThat(c.tokenCount).isAtMost(520)
    }

    @Test
    fun oversizedParagraphSplitReconstructsExactly() {
        val sentence = "This is one sentence in a very long paragraph that keeps going on and on. "
        val body = sentence.repeat(200)
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)

        val chunks = budgetedChunker.chunk(body)

        assertThat(reconstructFromNonOverlap(body, chunks)).isEqualTo(body)
    }

    @Test
    fun aSingleWordWithNoWhitespaceStillTerminatesAndReconstructsExactly() {
        // A run this long with no whitespace/punctuation is degenerate input
        // for a WordPiece vocabulary (it typically collapses to a single
        // [UNK] token rather than many subword pieces) — this test is a
        // robustness/no-infinite-loop guard on the char-level splitting
        // fallback, not an assertion about how many chunks come out.
        val body = "a".repeat(5000)
        val budgetedChunker = Chunker(nomic, targetTokens = 64, overlapTokens = 8)

        val chunks = budgetedChunker.chunk(body)

        assertThat(chunks).isNotEmpty()
        assertThat(reconstructFromNonOverlap(body, chunks)).isEqualTo(body)
    }

    // ------------------------------------------------------------ heading path

    @Test
    fun headingBreadcrumbReflectsNestedHeadingPath() {
        val body =
            "# Title\n\nintro\n\n## Section\n\nsection body\n\n" + "Filler content follows here. ".repeat(300)

        val chunks = chunker.chunk(body)

        val sectionChunk = chunks.first { it.text.contains("section body") }
        assertThat(sectionChunk.headingBreadcrumb).isEqualTo("# Title › ## Section")
    }

    @Test
    fun chunkBeforeAnyHeadingHasNoBreadcrumb() {
        val body = "Body text with no heading at all above it, just plain content."

        val chunks = chunker.chunk(body)

        assertThat(chunks.single().headingBreadcrumb).isNull()
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun chunkingIsDeterministic() {
        val body = sampleNoteWithHeadingsAndParagraphs()

        val a = chunker.chunk(body)
        val b = chunker.chunk(body)

        assertThat(a).isEqualTo(b)
    }

    // ------------------------------------------------------------ reconstruction

    @Test
    fun concatenatingNonOverlapRegionsReconstructsTheBody() {
        val body = eightParagraphNote()
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)

        val chunks = budgetedChunker.chunk(body)

        assertThat(reconstructFromNonOverlap(body, chunks)).isEqualTo(body)
    }

    @Test
    fun concatenatingNonOverlapRegionsReconstructsAVarietyOfBodies() {
        val budgetedChunker = Chunker(nomic, targetTokens = 512, overlapTokens = 64)
        val bodies =
            listOf(
                sampleNoteWithHeadingsAndParagraphs(),
                eightParagraphNote(),
                "# Only a title\n\nJust one short paragraph.",
                (1..30).joinToString("\n") { "- item $it with a little more text to bulk it up" },
            )

        for (body in bodies) {
            val chunks = budgetedChunker.chunk(body)
            assertThat(reconstructFromNonOverlap(body, chunks)).isEqualTo(body)
        }
    }

    // ----------------------------------------------------------------- init

    @Test
    fun rejectsOverlapNotLessThanTarget() {
        try {
            Chunker(nomic, targetTokens = 64, overlapTokens = 64)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun reconstructFromNonOverlap(
        body: String,
        chunks: List<Chunk>,
    ): String {
        if (chunks.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(body, chunks.first().start, chunks.first().end)
        for (i in 1 until chunks.size) sb.append(body, chunks[i - 1].end, chunks[i].end)
        return sb.toString()
    }

    private fun sampleNoteWithHeadingsAndParagraphs(): String =
        """
        # Title

        Intro paragraph with some content to open the note.

        ## Section One

        Body text for section one, explaining the first idea in a bit of detail.

        - first item
        - second item
        - third item

        ## Section Two

        > A quoted remark that spans this whole block.

        ```kotlin
        fun example() = 1 + 1
        ```

        Closing paragraph after the code block.
        """.trimIndent()

    private fun eightParagraphNote(): String {
        val sentence = "The quick brown fox jumps over the lazy dog near the riverbank at dusk. "
        return (1..8).joinToString("\n\n") { n -> "## Section $n\n\n" + sentence.repeat(18) + "Paragraph $n marker." }
    }
}
