package app.skein.core.rag.retrieval

import app.skein.core.model.DocumentKind
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class LexicalEvidenceGateTest {
    @Test
    fun `requested value development controls preserve paraphrases and reject missing values`() {
        val fixture = checkNotNull(javaClass.getResource("/eval/requested-value-development.json")).readText()
        val cases =
            Json
                .parseToJsonElement(fixture)
                .jsonObject
                .getValue("cases")
                .jsonArray
        for (case in cases) {
            val row = case.jsonObject
            val selected =
                LexicalEvidenceGate(requestedValueChecks = true).select(
                    row.getValue("query").jsonPrimitive.content,
                    listOf(hit(row.getValue("text").jsonPrimitive.content)),
                )
            com.google.common.truth.Truth
                .assertWithMessage(row.getValue("id").jsonPrimitive.content)
                .that(selected.isNotEmpty())
                .isEqualTo(row.getValue("supported").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `final evidence review cannot borrow a requested value from a removed source`() {
        val topic = hit("Sundial gallery admission requires advance booking.")
        val answer = hit("Sundial gallery admission is 12 euros.").copy(chunkId = 2)
        val gate = LexicalEvidenceGate(requestedValueChecks = true)
        assertThat(gate.select("What is the Sundial gallery admission fee?", listOf(topic, answer)))
            .containsExactly(topic, answer)
            .inOrder()
        assertThat(gate.select("What is the Sundial gallery admission fee?", listOf(topic))).isEmpty()
    }

    @Test
    fun `experimental value heuristics cannot silently replace production`() {
        val query = "How long is the copper rod?"
        val supported = hit("The copper rod is 80 centimeters long.")
        assertThat(LexicalEvidenceGate().select(query, listOf(supported))).containsExactly(supported)
        assertThat(LexicalEvidenceGate(requestedValueChecks = true).select(query, listOf(supported))).isEmpty()

        val feeQuery = "What is the ticket price to visit the Sundial gallery?"
        val related = hit("Sundial gallery postcards cost 3 euros.")
        assertThat(LexicalEvidenceGate().select(feeQuery, listOf(related))).isEmpty()
        // Retain the falsification, not an assertion that this is supported evidence.
        assertThat(LexicalEvidenceGate(requestedValueChecks = true).select(feeQuery, listOf(related)))
            .containsExactly(related)
    }

    @Test
    fun `a normalized winner with only grammatical overlap is rejected`() {
        val winner = hit("The room is on the left.")
        assertThat(winner.score).isEqualTo(1.0)
        assertThat(LexicalEvidenceGate().select("What is the satellite frequency?", listOf(winner))).isEmpty()
    }

    @Test
    fun `related topic without enough requested terms is rejected`() {
        val topic = hit("The observatory has a telescope and visitor parking.")
        val selected = LexicalEvidenceGate().select("What is the observatory annual membership fee?", listOf(topic))
        assertThat(selected).isEmpty()
    }

    @Test
    fun `coverage uses distinct whole terms including numbers`() {
        val source = hit("Cabinet 27 holds replacement lenses.")
        val gate = LexicalEvidenceGate(0.75)
        assertThat(gate.select("Cabinet cabinet cabinet 28 lenses", listOf(source))).isEmpty()
        assertThat(gate.select("Cabinet 27 lenses", listOf(source))).containsExactly(source)
        assertThat(gate.select("Cab", listOf(source))).isEmpty()
    }

    @Test
    fun `one supported chunk preserves graph evidence units ordering and raw signals`() {
        val seed = hit("The telescope maintenance schedule links to the service notice.")
        val linked = hit("Replacement is due Friday.").copy(chunkId = 2, recalledBy = setOf(RecallSource.GRAPH))
        val candidates = listOf(seed, linked)
        assertThat(LexicalEvidenceGate().select("What is the telescope maintenance schedule?", candidates))
            .isSameInstanceAs(candidates)
    }

    @Test
    fun `empty content terms do not create evidence`() {
        assertThat(LexicalEvidenceGate().select("What is it?", listOf(hit("It is here.")))).isEmpty()
        assertThat(LexicalEvidenceGate().select("telescope", emptyList())).isEmpty()
    }

    @Test
    fun `unicode canonical equivalents and case match consistently`() {
        val source = hit("CAFÉ открыто")
        assertThat(LexicalEvidenceGate().select("cafe\u0301 ОТКРЫТО", listOf(source))).containsExactly(source)
    }

    @Test
    fun `uncalibrated vector recall does not inherit a lexical threshold`() {
        val semantic = hit("A rechargeable cell supplies current.").copy(recalledBy = setOf(RecallSource.VECTOR))
        assertThat(LexicalEvidenceGate().select("battery power", listOf(semantic))).containsExactly(semantic)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid threshold cannot silently disable rejection`() {
        LexicalEvidenceGate(Double.NaN)
    }

    private fun hit(text: String): Retrieved =
        Retrieved(
            chunkId = 1,
            docId = "source",
            docTitle = "Source",
            text = text,
            score = 1.0,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.LEXICAL),
            recallScores = mapOf(RecallSource.LEXICAL to 0.000001),
        )
}
