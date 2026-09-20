package app.skein.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Property-based round-trip coverage (bd skein-ujn / plan E7.I2): for a
 * corpus of Markdown fragments, `parse -> serialize -> parse` must be
 * lossless *at the AST level* — `parse(serialize(parse(x))) == parse(x)`.
 *
 * Byte-for-byte `serialize(parse(x)) == x` is not a meaningful goal for
 * Markdown (many inputs are semantically identical, e.g. `*x*` vs `_x_`,
 * or `- a` vs `-  a`), so structural idempotency after one normalization
 * pass is what "lossless" means here.
 *
 * The base corpus is [GoldenFixtures] (39 fixtures); on top of that this
 * test generates randomized combinations of the same building blocks so the
 * property is checked well beyond the fixed fixture set, per the plan's
 * "property-based tests ... for a corpus of Markdown fragments" requirement.
 */
class MarkdownAstRoundTripPropertyTest {
    @Test
    fun goldenFixturesRoundTripLosslessly() {
        for ((name, markdown) in GoldenFixtures.entries) {
            assertRoundTripStable(markdown, name)
        }
    }

    @Test
    fun generatedFragmentsRoundTripLosslessly() {
        val random = Random(42)
        val fragments =
            buildList {
                repeat(200) { add(randomFragment(random)) }
            }
        for ((index, fragment) in fragments.withIndex()) {
            assertRoundTripStable(fragment, "generated-$index: ${fragment.replace("\n", "\\n")}")
        }
    }

    private fun assertRoundTripStable(
        markdown: String,
        label: String,
    ) {
        val first = MarkdownAst.parse(markdown)
        val serialized = MarkdownAst.serialize(first)
        val second = MarkdownAst.parse(serialized)
        assertEquals(
            "parse -> serialize -> parse changed the AST for [$label]\n" +
                "serialized form was:\n$serialized",
            first,
            second,
        )
    }

    /** Builds a random paragraph/list/quote/heading out of the same primitives the renderer must support. */
    private fun randomFragment(random: Random): String {
        val blockKind = random.nextInt(6)
        return when (blockKind) {
            0 -> "#".repeat(random.nextInt(1, 7)) + " " + randomInlineText(random)
            1 -> randomInlineText(random)
            2 -> (1..random.nextInt(1, 4)).joinToString("\n") { "- ${randomWord(random)}" }
            3 -> (1..random.nextInt(1, 4)).joinToString("\n") { i -> "$i. ${randomWord(random)}" }
            4 -> "> " + randomInlineText(random)
            else -> "- [${if (random.nextBoolean()) "x" else " "}] ${randomWord(random)}"
        } + "\n"
    }

    private fun randomInlineText(random: Random): String {
        val pieces = mutableListOf<String>()
        repeat(random.nextInt(1, 5)) {
            pieces +=
                when (random.nextInt(6)) {
                    0 -> randomWord(random)
                    1 -> "*${randomWord(random)}*"
                    2 -> "**${randomWord(random)}**"
                    3 -> "`${randomWord(random)}`"
                    4 -> "[[${randomWord(random)}]]"
                    else -> "[[${randomWord(random)}|${randomWord(random)}]]"
                }
        }
        return pieces.joinToString(" ")
    }

    private fun randomWord(random: Random): String {
        val words = listOf("alpha", "beta", "gamma", "note", "vault", "graph", "skein", "wiki", "link", "draft")
        return words[random.nextInt(words.size)]
    }
}
