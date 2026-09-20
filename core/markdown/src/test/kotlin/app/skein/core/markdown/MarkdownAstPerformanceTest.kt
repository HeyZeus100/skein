package app.skein.core.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * bd skein-ujn acceptance criterion #4: parsing a 200 KB document completes
 * in under 100 ms on the JVM. One warmup pass runs first so this measures
 * steady-state performance rather than JIT/classloading cost, matching how
 * the editor actually uses `MarkdownAst.parse` (repeatedly, on a live
 * document) rather than a cold one-shot invocation.
 */
class MarkdownAstPerformanceTest {
    @Test
    fun parses200KbDocumentUnder100Ms() {
        val document = build200KbDocument()
        assertTrue("fixture should be at least 200 KB, was ${document.length} chars", document.length >= 200_000)

        // Warmup: let the JIT compile the hot parsing/conversion paths.
        repeat(3) { MarkdownAst.parse(document) }

        val elapsedNanos = measureNanoTime { MarkdownAst.parse(document) }
        val elapsedMillis = elapsedNanos / 1_000_000.0

        assertTrue(
            "expected parse of a ${document.length}-char document to take < 100ms, took ${elapsedMillis}ms",
            elapsedMillis < 100,
        )
    }

    private fun build200KbDocument(): String =
        buildString {
            var section = 0
            while (length < 200_000) {
                section++
                append("## Section $section\n\n")
                append(
                    "This is paragraph $section with *italic*, **bold**, `code`, a [[Wiki Link $section]], " +
                        "and a [link](https://example.com/$section \"title $section\").\n\n",
                )
                append("- item one\n- item two\n  - nested item\n- [ ] todo $section\n- [x] done $section\n\n")
                append("> A quoted line for section $section.\n\n")
                append("```kotlin\nval section$section = $section\n```\n\n")
            }
        }
}
