package app.skein.core.rag.retrieval

import app.skein.core.model.DocumentKind
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LexicalEvidenceGateTest {
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
