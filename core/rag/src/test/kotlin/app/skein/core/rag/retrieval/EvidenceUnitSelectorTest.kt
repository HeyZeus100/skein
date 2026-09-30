package app.skein.core.rag.retrieval

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.DocumentRevision
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import app.skein.core.model.VaultRepository
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
