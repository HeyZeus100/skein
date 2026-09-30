package app.skein.core.rag.retrieval

import app.skein.core.model.CitationSourceKind
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.DocumentRevision
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import app.skein.core.model.ScoredChunk
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.rank.RetrievedAssembler
import app.skein.core.rag.tokenizers.TokenizerFixtures
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EvidenceUnitSelectorTest {
    @Test
    fun `partial UTF8 source becomes whole paragraph with exact byte provenance`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val body = "Earlier paragraph.\n\nCafé packing uses seven copper hooks.\nThe box is violet.\n\nLater."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Packing", body))
            val candidate = candidate(doc, "seven copper hooks")
            val unit = EvidenceUnitSelector(repo).select(listOf(candidate), null).units.single()
            assertThat(unit.source.text).isEqualTo("Café packing uses seven copper hooks.\nThe box is violet.\n")
            val bytes = body.toByteArray()
            val locator = checkNotNull(unit.source.locator)
            assertThat(bytes.copyOfRange(locator.byteStart, locator.byteEnd).toString(Charsets.UTF_8))
                .isEqualTo(unit.source.text)
            assertThat(unit.source.revisionHash).isEqualTo(candidate.revisionHash)
            assertThat(unit.members).containsExactly(candidate)
            assertThat(unit.source.recallScores).isEqualTo(candidate.recallScores)
        }

    @Test
    fun `canonical CRLF source is verified before expansion`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Packing", "First.\r\nSecond.\r\n\r\nOther."))
            val candidate = candidate(doc, "First.\nSecond.").copy(text = "First.\r\nSecond.")
            assertThat(
                EvidenceUnitSelector(repo)
                    .select(listOf(candidate), null)
                    .evidence
                    .single()
                    .text,
            ).isEqualTo("First.\nSecond.\n")
        }

    @Test
    fun `fenced block retains internal blank lines and closing fence`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc =
                repo.createDocument(
                    NewDocument(DocumentKind.NOTE, "Commands", "Intro.\n\n```text\nalpha\n\nbeta\n```\n\nEnd."),
                )
            assertThat(
                EvidenceUnitSelector(repo)
                    .select(listOf(candidate(doc, "alpha")), null)
                    .evidence
                    .single()
                    .text,
            ).isEqualTo("```text\nalpha\n\nbeta\n```\n")
        }

    @Test
    fun `unclosed fence and oversized unit are refused rather than clipped`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val open = repo.createDocument(NewDocument(DocumentKind.NOTE, "Open", "```text\nalpha"))
            val large = repo.createDocument(NewDocument(DocumentKind.NOTE, "Large", "alpha beta gamma"))
            val selected =
                EvidenceUnitSelector(repo, maxUnitBytes = 8).select(
                    listOf(candidate(open, "alpha"), candidate(large, "alpha")),
                    null,
                )
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions)
                .containsExactly(EvidenceExclusion.INCOMPLETE_UNIT, 1, EvidenceExclusion.UNIT_TOO_LARGE, 1)
        }

    @Test
    fun `duplicate expanded anchors retain original rank signals and first priority`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Parts", "alpha beta gamma"))
            val first = candidate(doc, "alpha")
            val second =
                candidate(doc, "gamma").copy(
                    chunkId = 2,
                    score = 0.2,
                    recallScores = mapOf(RecallSource.GRAPH to 99.0),
                )
            val selected = EvidenceUnitSelector(repo).select(listOf(first, second), null)
            assertThat(selected.units).hasSize(1)
            assertThat(selected.units.single().members).containsExactly(first, second).inOrder()
            assertThat(selected.evidence.single().score).isEqualTo(first.score)
            assertThat(selected.evidence.single().recallScores).isEqualTo(first.recallScores)
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.DUPLICATE, 1)
        }

    @Test
    fun `mismatched text missing and split UTF8 anchors are never repaired`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Accent", "éclair seven"))
            val valid = candidate(doc, "éclair")
            val selected =
                EvidenceUnitSelector(repo).select(
                    listOf(
                        valid.copy(text = "eight"),
                        valid.copy(locator = null),
                        valid.copy(locator = Locator(1, 2), text = "�"),
                    ),
                    null,
                )
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.INVALID_ANCHOR, 3)
        }

    @Test
    fun `old revision and missing revision are excluded`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Count", "Seven hooks."))
            val old = candidate(doc, "Seven")
            repo.updateBody(doc.id, "Count", "Eight hooks.")
            val selected = EvidenceUnitSelector(repo).select(listOf(old, old.copy(revisionHash = null)), null)
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions)
                .containsExactly(EvidenceExclusion.STALE_REVISION, 1, EvidenceExclusion.MISSING_REVISION, 1)
        }

    @Test
    fun `snapshot bytes must independently match revision hash`() =
        runTest {
            val backing = InMemoryVaultRepository()
            val doc = backing.createDocument(NewDocument(DocumentKind.NOTE, "Count", "Seven hooks."))
            val repo =
                object : VaultRepository by backing {
                    override suspend fun currentRevision(id: String): DocumentRevision? =
                        backing.currentRevision(id)?.copy(bodyMdSnapshot = "Altered snapshot")
                }
            assertThat(EvidenceUnitSelector(repo).select(listOf(candidate(doc, "Seven")), null).exclusions)
                .containsEntry(EvidenceExclusion.STALE_REVISION, 1)
        }

    @Test
    fun `Space legacy scope and generated source restrictions fail closed`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val shared = repo.createDocument(NewDocument(DocumentKind.NOTE, "Shared", "Hooks."))
            val private = repo.createDocument(NewDocument(DocumentKind.NOTE, "Private", "Hooks.", personaId = "other"))
            val generated = repo.createDocument(NewDocument(DocumentKind.AIOUT, "Generated", "Hooks."))
            val candidates = listOf(shared, private, generated).map { candidate(it, "Hooks") }
            val selected = EvidenceUnitSelector(repo, legacyPersonaId = "legacy").select(candidates, "active")
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions)
                .containsExactly(EvidenceExclusion.OUT_OF_SCOPE, 2, EvidenceExclusion.UNSUPPORTED_SOURCE, 1)
            assertThat(EvidenceUnitSelector(repo).select(listOf(candidates.first()), "active").evidence).hasSize(1)
        }

    @Test
    fun `delete observed during final validation refuses publication`() =
        runTest {
            val backing = InMemoryVaultRepository()
            val doc = backing.createDocument(NewDocument(DocumentKind.NOTE, "Count", "Seven hooks."))
            var reads = 0
            val repo =
                object : VaultRepository by backing {
                    override suspend fun getDocument(id: String): Document? {
                        if (++reads == 2) backing.deleteDocument(id)
                        return backing.getDocument(id)
                    }
                }
            val selected = EvidenceUnitSelector(repo).select(listOf(candidate(doc, "Seven")), null)
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.MISSING_SOURCE, 1)
        }

    @Test
    fun `multi paragraph chunk expands both complete boundaries without inventing join text`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc =
                repo.createDocument(
                    NewDocument(DocumentKind.NOTE, "Parts", "Start alpha.\n\nBeta end.\n\nOther."),
                )
            val selected = EvidenceUnitSelector(repo).select(listOf(candidate(doc, "alpha.\n\nBeta")), null)
            assertThat(selected.evidence.single().text).isEqualTo("Start alpha.\n\nBeta end.\n")
        }

    @Test
    fun `overlapping complete units union exact source bytes and retain first rank signals`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val body = "Alpha first.\n\nBeta middle.\n\nGamma last."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Three parts", body))
            val first = candidate(doc, "first.\n\nBeta")
            val second = candidate(doc, "middle.\n\nGamma").copy(chunkId = 2, score = 0.1)
            val selected = EvidenceUnitSelector(repo).select(listOf(first, second), null)
            assertThat(selected.units).hasSize(1)
            assertThat(selected.evidence.single().text).isEqualTo(body)
            assertThat(selected.evidence.single().locator).isEqualTo(Locator(0, body.toByteArray().size))
            assertThat(selected.evidence.single().score).isEqualTo(first.score)
            assertThat(selected.units.single().members).containsExactly(first, second).inOrder()
        }

    @Test
    fun `later source span can bridge two prior units without repeated paragraphs`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val body = "Alpha.\n\nBeta.\n\nGamma."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Three parts", body))
            val first = candidate(doc, "Alpha")
            val last = candidate(doc, "Gamma").copy(chunkId = 2)
            val bridge = candidate(doc, body).copy(chunkId = 3)
            val selected = EvidenceUnitSelector(repo).select(listOf(first, last, bridge), null)
            assertThat(selected.evidence.single().text).isEqualTo(body)
            assertThat(selected.units.single().members).containsExactly(first, last, bridge).inOrder()
        }

    @Test
    fun `oversized overlap union refuses later member without clipping or repeated overlap`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val body = "Alpha.\n\nBeta.\n\nGamma."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Three parts", body))
            val first = candidate(doc, "Alpha.\n\nBeta")
            val second = candidate(doc, "Beta.\n\nGamma").copy(chunkId = 2)
            val selected = EvidenceUnitSelector(repo, maxUnitBytes = 16).select(listOf(first, second), null)
            assertThat(selected.evidence.single().text).isEqualTo("Alpha.\n\nBeta.\n")
            assertThat(selected.units.single().members).containsExactly(first)
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.OVERLAP_TOO_LARGE, 1)
        }

    @Test
    fun `expanded unit also contains original anchor boundary whitespace`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val body = "\n\nAlpha.\n\nBeta."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Boundary", body))
            val original = candidate(doc, "\nAlpha.\n\n")
            val unit = EvidenceUnitSelector(repo).select(listOf(original), null).evidence.single()
            val source = checkNotNull(unit.locator)
            val anchor = checkNotNull(original.locator)
            assertThat(source.byteStart).isAtMost(anchor.byteStart)
            assertThat(source.byteEnd).isAtLeast(anchor.byteEnd)
            assertThat(unit.text).isEqualTo("\nAlpha.\n\n")
        }

    @Test
    fun `actual production heading ingest expands without embedding metadata as evidence`() =
        runTest {
            val body = "# Cargo\n\nSeven hooks.\n\n## Case\n\nThe box is violet."
            val (repo, indexed) = productionIndexed(body)
            assertThat(indexed).hasSize(1)
            assertThat(indexed.single().text).startsWith("# Cargo › ## Case\n\n")
            val selected = EvidenceUnitSelector(repo).select(indexed, null)
            assertThat(selected.evidence.single().text).isEqualTo(body)
            assertThat(selected.units.single().members).containsExactlyElementsIn(indexed)
            assertThat(selected.evidence.single().recallScores).isEqualTo(indexed.single().recallScores)
        }

    @Test
    fun `actual Setext Unicode CRLF ingest retains canonical byte evidence`() =
        runTest {
            val body = "Café cargo\r\n==========\r\n\r\nSeven hooks.\r\n\r\nCase\r\n----\r\n\r\nViolet box."
            val (repo, indexed) = productionIndexed(body)
            assertThat(indexed.single().text).startsWith("# Café cargo › ## Case\n\n")
            val selected = EvidenceUnitSelector(repo).select(indexed, null)
            assertThat(selected.evidence.single().text).isEqualTo(RevisionHashing.canonicalBody(body))
            assertThat(selected.units.single().members).containsExactlyElementsIn(indexed)
        }

    @Test
    fun `actual small chunks preserve last heading context through overlap and heading changes`() =
        runTest {
            val body =
                "# Cargo\n\n" + "Seven copper hooks fill the box. ".repeat(16) +
                    "\n\n## Case\n\n" + "A violet case protects the hooks. ".repeat(16) +
                    "\n\n# Elsewhere\n\nA silver handle closes the door."
            val (repo, indexed) = productionIndexed(body, targetTokens = 48)
            assertThat(indexed.size).isGreaterThan(2)
            val selected = EvidenceUnitSelector(repo).select(indexed, null)
            assertThat(selected.exclusions.keys).doesNotContain(EvidenceExclusion.INVALID_ANCHOR)
            assertThat(selected.units.flatMap { it.members }).containsExactlyElementsIn(indexed).inOrder()
            val canonical = RevisionHashing.canonicalBody(body).toByteArray()
            for (unit in selected.evidence) {
                val locator = checkNotNull(unit.locator)
                assertThat(unit.text)
                    .isEqualTo(canonical.copyOfRange(locator.byteStart, locator.byteEnd).toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `forged prefix or wrong end heading cannot pass a source suffix check`() =
        runTest {
            val body = "# Cargo\n\nSeven hooks.\n\n## Case\n\nViolet box."
            val (repo, indexed) = productionIndexed(body)
            val actual = indexed.single()
            val canonical = RevisionHashing.canonicalBody(body)
            val forged =
                listOf("Invented fact", "# Cargo", "# Missing › ## Case", "# Cargo › ## Case\nextra")
                    .map { actual.copy(text = "$it\n\n$canonical") }
            val selected = EvidenceUnitSelector(repo).select(forged, null)
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.INVALID_ANCHOR, forged.size)
        }

    @Test
    fun `fenced heading text is never promoted into breadcrumb metadata`() =
        runTest {
            val body = "# Real\n\n```text\n# Forged\n```\n\nSeven hooks."
            val (repo, indexed) = productionIndexed(body)
            val actual = indexed.single()
            assertThat(actual.text).startsWith("# Real\n\n")
            assertThat(
                EvidenceUnitSelector(repo)
                    .select(indexed, null)
                    .evidence
                    .single()
                    .text,
            ).isEqualTo(body)
            val forged = actual.copy(text = "# Forged\n\n$body")
            assertThat(EvidenceUnitSelector(repo).select(listOf(forged), null).evidence).isEmpty()
        }

    @Test
    fun `heading prefix cannot legitimize a split UTF8 locator or later heading`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc =
                repo.createDocument(
                    NewDocument(DocumentKind.NOTE, "Boundary", "# Cargo\n\nCafé hooks.\n\n# Later\n\nOther."),
                )
            val valid = candidate(doc, "Café hooks.")
            val wrongHeading = valid.copy(text = "# Later\n\n${valid.text}")
            val start = checkNotNull(valid.locator).byteStart + "Caf".toByteArray().size + 1
            val split = valid.copy(locator = Locator(start, start + 1), text = "# Cargo\n\n�")
            val selected = EvidenceUnitSelector(repo).select(listOf(wrongHeading, split), null)
            assertThat(selected.evidence).isEmpty()
            assertThat(selected.exclusions).containsEntry(EvidenceExclusion.INVALID_ANCHOR, 2)
        }

    private suspend fun productionIndexed(
        body: String,
        targetTokens: Int = 512,
    ): Pair<InMemoryVaultRepository, List<Retrieved>> {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Synthetic heading source", body))
        val tokenizer = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        val chunks = Chunker(tokenizer, targetTokens = targetTokens, overlapTokens = 8).chunk(body)
        val ids = IngestSteps(index, warn = {}).indexLexical(doc.id, chunks, doc.contentHash, body)
        val ranked = ids.mapIndexed { position, id -> ScoredChunk(id, 1.0 / (position + 1), 0.003 / (position + 1)) }
        val assembled = RetrievedAssembler(index, repo).assemble(ranked, mapOf(CitationSourceKind.LEXICAL to ranked))
        return repo to assembled
    }

    private fun candidate(
        doc: Document,
        excerpt: String,
    ): Retrieved {
        val canonical = RevisionHashing.canonicalBody(doc.bodyMd)
        val index = canonical.indexOf(excerpt)
        check(index >= 0)
        val start = canonical.substring(0, index).toByteArray().size
        return Retrieved(
            chunkId = 1,
            docId = doc.id,
            docTitle = doc.title,
            text = excerpt,
            score = 0.8,
            sourceKind = doc.kind,
            recalledBy = setOf(RecallSource.LEXICAL),
            revisionHash = doc.contentHash,
            locator = Locator(start, start + excerpt.toByteArray().size, 0),
            recallScores = mapOf(RecallSource.LEXICAL to 0.0002),
        )
    }
}
