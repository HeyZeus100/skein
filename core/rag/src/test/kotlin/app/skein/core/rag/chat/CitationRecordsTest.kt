// `CitationRecords.fromStream` (skein-n5q, E5.I16). Builds the
// citation-record-v1 payload (`core/model` `CitationRecordJson`,
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3) that the chat layer hands to
// `NewMessage.citations` once a turn finishes: `retrieved` = every item the
// assembled prompt offered, `cited` = the distinct markers the parsed
// `Segment` stream actually turned into chips.
//
// AAA throughout: one behavior asserted per test.

package app.skein.core.rag.chat

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.CitationRecordJson
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.RetrievedChunksPayload
import app.skein.core.model.Role
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CitationRecordsTest {
    private val hash = "b".repeat(64)

    private fun retrieved(
        chunkId: Long,
        docId: String = "doc-$chunkId",
        recalledBy: Set<RecallSource> = setOf(RecallSource.VECTOR),
        revisionHash: String? = hash,
        locator: Locator? = Locator(byteStart = 0, byteEnd = 10, chunkOrd = 0),
    ): Retrieved =
        Retrieved(
            chunkId = chunkId,
            docId = docId,
            docTitle = "Title $chunkId",
            text = "excerpt text $chunkId",
            score = 0.5,
            sourceKind = DocumentKind.NOTE,
            recalledBy = recalledBy,
            revisionHash = revisionHash,
            locator = locator,
        )

    private fun assembled(citations: Map<Int, Retrieved>): AssembledPrompt =
        AssembledPrompt(
            prompt = Prompt(messages = emptyList()),
            citations = citations,
            droppedHistoryTurns = 0,
            estimatedTokens = 0,
        )

    @Test
    fun `retrieved holds every offered item regardless of whether it was cited`() {
        val offered = mapOf(1 to retrieved(1), 2 to retrieved(2))
        val segments = listOf(Segment.Citation(1, offered.getValue(1)))

        val record = CitationRecords.fromStream(assembled(offered), segments)

        assertThat(record.retrieved.map { it.marker }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `cited holds only the distinct markers the segment stream actually emitted`() {
        val offered = mapOf(1 to retrieved(1), 2 to retrieved(2), 3 to retrieved(3))
        val segments =
            listOf(
                Segment.Text("see "),
                Segment.Citation(2, offered.getValue(2)),
                Segment.Text(" and "),
                Segment.Citation(2, offered.getValue(2)),
            )

        val record = CitationRecords.fromStream(assembled(offered), segments)

        assertThat(record.cited).containsExactly(2)
    }

    @Test
    fun `cited preserves first-appearance order of distinct markers`() {
        val offered = mapOf(1 to retrieved(1), 2 to retrieved(2))
        val segments =
            listOf(
                Segment.Citation(2, offered.getValue(2)),
                Segment.Citation(1, offered.getValue(1)),
            )

        val record = CitationRecords.fromStream(assembled(offered), segments)

        assertThat(record.cited).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `each retrieved entry carries document id, revision hash, locator and an excerpt`() {
        val offered = mapOf(1 to retrieved(1, docId = "doc-1"))

        val record = CitationRecords.fromStream(assembled(offered), emptyList())

        val citation = record.retrieved.single()
        assertThat(citation.documentId).isEqualTo("doc-1")
        assertThat(citation.revisionHash).isEqualTo(hash)
        assertThat(citation.locator).isEqualTo(Locator(byteStart = 0, byteEnd = 10, chunkOrd = 0))
        assertThat(citation.excerpt).isEqualTo("excerpt text 1")
    }

    @Test
    fun `the excerpt is capped at the schema maximum`() {
        val longText = "x".repeat(CitationRecordJson.MAX_EXCERPT_CHARS + 500)
        val offered = mapOf(1 to retrieved(1).copy(text = longText))

        val record = CitationRecords.fromStream(assembled(offered), emptyList())

        val excerptLength =
            record.retrieved
                .single()
                .excerpt.length
        assertThat(excerptLength).isEqualTo(CitationRecordJson.MAX_EXCERPT_CHARS)
    }

    @Test
    fun `source kind is derived from recalledBy via the locked RecallSource bridge`() {
        val offered = mapOf(1 to retrieved(1, recalledBy = setOf(RecallSource.LEXICAL)))

        val record = CitationRecords.fromStream(assembled(offered), emptyList())

        assertThat(record.retrieved.single().sourceKind).isEqualTo(CitationSourceKind.LEXICAL)
    }

    @Test
    fun `an item with no revision hash is excluded from the persisted record`() {
        val offered = mapOf(1 to retrieved(1, revisionHash = null), 2 to retrieved(2))
        val segments = listOf(Segment.Citation(1, offered.getValue(1)))

        val record = CitationRecords.fromStream(assembled(offered), segments)

        assertThat(record.retrieved.map { it.marker }).containsExactly(2)
        assertThat(record.cited).isEmpty()
    }

    @Test
    fun `an item with no locator is excluded from the persisted record`() {
        val offered = mapOf(1 to retrieved(1, locator = null))

        val record = CitationRecords.fromStream(assembled(offered), emptyList())

        assertThat(record.retrieved).isEmpty()
    }

    @Test
    fun `an empty offer with no citations produces an empty record`() {
        val record = CitationRecords.fromStream(assembled(emptyMap()), emptyList())

        assertThat(record.retrieved).isEmpty()
        assertThat(record.cited).isEmpty()
    }

    @Test
    fun `the record round-trips through CitationRecordJson`() {
        val offered = mapOf(1 to retrieved(1), 2 to retrieved(2))
        val segments = listOf(Segment.Citation(2, offered.getValue(2)))
        val record = CitationRecords.fromStream(assembled(offered), segments)

        val decoded = CitationRecordJson.decode(CitationRecordJson.encode(record))

        assertThat(decoded).isInstanceOf(RetrievedChunksPayload.V1::class.java)
        val back = (decoded as RetrievedChunksPayload.V1).record
        assertThat(back.retrieved.map { it.marker }).containsExactly(1, 2).inOrder()
        assertThat(back.cited).containsExactly(2)
    }

    @Test
    fun `the record round-trips through InMemoryVaultRepository appendMessage`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val chat = repo.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = ""))
            val offered = mapOf(1 to retrieved(1))
            val segments = listOf(Segment.Citation(1, offered.getValue(1)))
            val record = CitationRecords.fromStream(assembled(offered), segments)

            val appended =
                repo.appendMessage(
                    chat.id,
                    NewMessage(role = Role.ASSISTANT, contentMd = "answer [1]", citations = record),
                )

            val stored = repo.listMessages(chat.id).single { it.id == appended.id }
            assertThat(stored.citations).isEqualTo(record)
        }
}
