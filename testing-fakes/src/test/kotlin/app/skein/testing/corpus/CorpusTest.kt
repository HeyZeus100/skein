package app.skein.testing.corpus

import app.skein.core.model.InferenceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pins the §6.2 properties each fixture exists for, so an edit cannot quietly lose one. */
class CorpusTest {
    @Test
    fun `fixture now is 2026-09-26T10_00Z`() {
        assertEquals(Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(), Corpus.FIXTURE_NOW)
    }

    @Test
    fun `long titles and names have their stated lengths`() {
        assertEquals(6, Corpus.titlesLong.size)
        assertEquals(100, Corpus.titlesLong[0].length)
        assertEquals(60, Corpus.titlesLong[1].length)
        assertTrue(Corpus.titlesLong[1].none { it.isWhitespace() || it == '-' })
        assertEquals(40, Corpus.modelLongNames[3].length)
        assertEquals(90, Corpus.modelLongNames[4].length)
        assertEquals(80, Corpus.KNOWLEDGE_LONG_TITLE.length)
        assertEquals(
            1,
            Corpus.titleDupes
                .map { it.title }
                .distinct()
                .size,
        )
    }

    @Test
    fun `markdown fixtures carry their stress cases`() {
        val kotlinBlock = Corpus.mdCode.substringAfter("```kotlin\n").substringBefore("\n```")
        assertEquals(40, kotlinBlock.lines().size)
        assertEquals(200, Corpus.longCodeLine.length)
        assertEquals(200, Corpus.longUrl.length)
        assertTrue(Corpus.mdCode.endsWith("```\n${Corpus.longCodeLine}\n```"))
        assertTrue(
            Corpus.mdTable
                .lines()
                .first()
                .count { it == '|' } == 9,
        )
        assertTrue(Corpus.mdList.contains("- [ ] ") && Corpus.mdList.endsWith("[1]"))
        assertTrue((1..12).all { Corpus.mdCite.contains("[$it]") } && Corpus.mdCite.contains("[1, 2]"))
        assertTrue(Corpus.mdCitePieces.windowed(3).contains(listOf("[", "1", "]")))
    }

    @Test
    fun `conversations are deterministic, sized and dated back from now`() {
        val chat = Corpus.conversation(200)
        assertEquals(200, chat.size)
        assertEquals(chat, Corpus.conversation(200))
        assertEquals(Corpus.FIXTURE_NOW, chat.last().createdAt)
        assertTrue(chat[9].contentMd.contains("```kotlin"))
        assertTrue(chat[6].contentMd.contains("[1]"))
        assertEquals(500, Corpus.conversation(500).size)
        assertEquals(
            1,
            Corpus.chatShort
                .single { it.citations != null }
                .citations!!
                .cited.size,
        )
    }

    @Test
    fun `huge and long text fixtures have their stated sizes`() {
        assertEquals(5_000, Corpus.hugeUserMessage.length)
        assertEquals(20_000, Corpus.hugeAnswer.length)
        val draft = Corpus.draftLong.lines()
        assertEquals(12, draft.size)
        assertEquals(300, draft.maxOf { it.length })
    }

    @Test
    fun `chat history spans the groups, newest first, with the long titles`() {
        val history = Corpus.chatsHistory
        assertEquals(30, history.size)
        assertEquals(30, history.map { it.id }.toSet().size)
        assertEquals(history.sortedByDescending { it.updatedAt }, history)
        assertTrue(history.map { it.title }.containsAll(Corpus.titlesLong))
        assertTrue(
            history.any { it.id == Corpus.HISTORY_ANSWERING_ID } && history.any { it.id == Corpus.HISTORY_FINISHED_ID },
        )
        assertTrue(Corpus.FIXTURE_NOW - history.last().updatedAt > 60L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `sources spread passages over documents, highest score first`() {
        val sources = Corpus.sources()
        assertEquals(8, sources.size)
        assertEquals(3, sources.map { it.docId }.distinct().size)
        assertEquals(sources.sortedByDescending { it.score }, sources)
    }

    @Test
    fun `graphs and backlinks only link notes that exist`() {
        for (graph in listOf(Corpus.graph8, Corpus.graph80, Corpus.backlinks)) {
            val ids = graph.notes.map { it.id }.toSet() + Corpus.noteWithProperties.id
            assertTrue(graph.edges.isNotEmpty() && graph.edges.all { it.srcId in ids && it.dstId in ids })
        }
        assertEquals(8, Corpus.graph8.notes.size)
        assertEquals(80, Corpus.graph80.notes.size)
        assertEquals(40, Corpus.backlinks.edges.count { it.dstId == Corpus.noteWithProperties.id })
        assertTrue(Corpus.graphIsolated.edges.isEmpty())
    }

    @Test
    fun `errors cover every InferenceException subtype`() {
        assertEquals(
            InferenceException::class.java.declaredClasses.toSet(),
            Corpus.errors.map { it.javaClass }.toSet(),
        )
    }

    @Test
    fun `models and personas`() {
        assertEquals(
            4,
            Corpus.models
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(Corpus.OWNER_MODEL_ID, Corpus.modelDefault.id)
        assertEquals("Default", Corpus.personas.first().name)
        assertEquals(4, Corpus.personas.size)
    }
}
