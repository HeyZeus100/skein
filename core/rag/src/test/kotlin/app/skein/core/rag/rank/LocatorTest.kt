// POST_REVIEW_RESOLUTIONS.md §1.4 `LocatorTest` calls, at the citation
// producer (`RetrievedAssembler`, skein-g32i): "`body_md.substring(byte_start,
// byte_end)` handles multi-byte UTF-8 correctly (round-trip on Cyrillic,
// CJK, emoji fixtures)". `IngestPipelineTest` (skein-zx15) already covers
// the ingest-time half of this — `chunks.byte_start`/`byte_end` are correct
// UTF-8 byte offsets into the raw `documents.body_md`. This file covers the
// retrieval-time half: `RetrievedAssembler.assemble` turning those raw
// offsets into a `Retrieved.locator` that still round-trips, including the
// CRLF remap onto the canonical `document_revisions.body_md_snapshot`
// documented on `RetrievedAssembler`'s file header (skein-g32i, resolving
// skein-zx15's deviation 6).
//
// AAA throughout: one behavior asserted per test.

package app.skein.core.rag.rank

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.ScoredChunk
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocatorTest {
    @Test
    fun `locator byte range round-trips Cyrillic and CJK text through body_md`(): Unit =
        runBlocking {
            val body = "Привет 日本語 test more"
            val term = "日本語"
            val charStart = body.indexOf(term)
            val byteStart = body.substring(0, charStart).toByteArray(Charsets.UTF_8).size
            val byteEnd = byteStart + term.toByteArray(Charsets.UTF_8).size

            val retrieved = assembleSingle(body, term, byteStart, byteEnd)

            val locator = requireNotNull(retrieved.locator)
            val slice = body.toByteArray(Charsets.UTF_8).sliceArray(locator.byteStart until locator.byteEnd)
            assertThat(slice.toString(Charsets.UTF_8)).isEqualTo(term)
        }

    @Test
    fun `locator byte range round-trips a surrogate-pair emoji at the start of the body`(): Unit =
        runBlocking {
            // U+1F600 is a UTF-16 surrogate pair (2 chars) but 4 UTF-8
            // bytes — exercises a chunk starting at byte 0.
            val emoji = "😀"
            val body = "$emoji hello"
            val byteEnd = emoji.toByteArray(Charsets.UTF_8).size

            val retrieved = assembleSingle(body, emoji, byteStart = 0, byteEnd = byteEnd)

            val locator = requireNotNull(retrieved.locator)
            val slice = body.toByteArray(Charsets.UTF_8).sliceArray(locator.byteStart until locator.byteEnd)
            assertThat(slice.toString(Charsets.UTF_8)).isEqualTo(emoji)
        }

    @Test
    fun `CRLF body remaps the raw byte locator onto the canonical LF-only snapshot`(): Unit =
        runBlocking {
            // Raw body has CRLF line endings; the chunk sits on the second
            // line, so its raw byte offsets straddle one preceding "\r\n".
            val rawBody = "first line\r\nsecond line\r\nthird"
            val term = "second line"
            val rawByteStart = rawBody.indexOf(term) // ASCII-only prefix: char index == byte index
            val rawByteEnd = rawByteStart + term.length

            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = rawBody))
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks =
                            listOf(
                                NewChunk(
                                    ord = 0,
                                    text = term,
                                    tokenCount = 1,
                                    byteStart = rawByteStart,
                                    byteEnd = rawByteEnd,
                                ),
                            ),
                        embedderId = "fake",
                        embedderVersion = 1,
                        revisionHash = repo.currentRevision(doc.id)?.revisionHash,
                    ).single()

            val retrieved =
                RetrievedAssembler(index, repo)
                    .assemble(listOf(ScoredChunk(chunkId, 1.0)), emptyMap())
                    .single()

            val locator = requireNotNull(retrieved.locator)
            val snapshot = requireNotNull(repo.currentRevision(doc.id)).bodyMdSnapshot
            val slice = snapshot.toByteArray(Charsets.UTF_8).sliceArray(locator.byteStart until locator.byteEnd)
            assertThat(slice.toString(Charsets.UTF_8)).isEqualTo(term)
            // The remap did something real: raw and canonical offsets differ
            // by exactly the one "\r" (of the "\r\n" pair) preceding the chunk.
            assertThat(locator.byteStart).isEqualTo(rawByteStart - 1)
            assertThat(locator.byteEnd).isEqualTo(rawByteEnd - 1)
        }

    @Test
    fun `a lone CR with no following LF is rewritten in place and does not shift the locator`(): Unit =
        runBlocking {
            // Old-Mac-style lone "\r" is rewritten to "\n" in place by
            // RevisionHashing.canonicalBody, never deleted — offsets after
            // it must NOT shift, unlike a "\r\n" pair.
            val rawBody = "first\rsecond"
            val term = "second"
            val rawByteStart = rawBody.indexOf(term)
            val rawByteEnd = rawByteStart + term.length

            val retrieved = assembleSingle(rawBody, term, rawByteStart, rawByteEnd)

            val locator = requireNotNull(retrieved.locator)
            assertThat(locator.byteStart).isEqualTo(rawByteStart)
            assertThat(locator.byteEnd).isEqualTo(rawByteEnd)
        }

    @Test
    fun `Retrieved revisionHash is the chunk's own stamped revision hash`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = "body text"))
            val expectedHash = requireNotNull(repo.currentRevision(doc.id)).revisionHash
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks =
                            listOf(
                                NewChunk(ord = 0, text = "body text", tokenCount = 2, byteStart = 0, byteEnd = 9),
                            ),
                        embedderId = "fake",
                        embedderVersion = 1,
                        revisionHash = expectedHash,
                    ).single()

            val retrieved =
                RetrievedAssembler(index, repo)
                    .assemble(listOf(ScoredChunk(chunkId, 1.0)), emptyMap())
                    .single()

            assertThat(retrieved.revisionHash).isEqualTo(expectedHash)
        }

    /** Ingests one document/chunk pair with the given raw byte offsets and returns its lone [Retrieved]. */
    private suspend fun assembleSingle(
        body: String,
        chunkText: String,
        byteStart: Int,
        byteEnd: Int,
    ) = run {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = body))
        val chunkId =
            index
                .replaceChunks(
                    docId = doc.id,
                    chunks =
                        listOf(
                            NewChunk(
                                ord = 0,
                                text = chunkText,
                                tokenCount = 1,
                                byteStart = byteStart,
                                byteEnd = byteEnd,
                            ),
                        ),
                    embedderId = "fake",
                    embedderVersion = 1,
                ).single()

        RetrievedAssembler(index, repo)
            .assemble(listOf(ScoredChunk(chunkId, 1.0)), emptyMap())
            .single()
    }
}
