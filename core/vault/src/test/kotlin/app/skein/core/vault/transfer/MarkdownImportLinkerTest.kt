package app.skein.core.vault.transfer

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.core.vault.extract.WikilinkExtractor
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class MarkdownImportLinkerTest {
    private val repository = InMemoryVaultRepository()
    private val service = ImportServiceImpl(repository)
    private val links = MarkdownImportLinker(repository)

    private suspend fun note(
        path: String,
        text: String,
    ): Document {
        val result =
            links.importText(
                service,
                path,
                path.substringAfterLast('/'),
                "text/markdown",
                text.byteInputStream(),
                null,
            )
        return requireNotNull(repository.getDocument(result.documentId))
    }

    private suspend fun current(document: Document): Document = requireNotNull(repository.getDocument(document.id))

    @Test
    fun `filenames frontmatter titles headings and aliases resolve without overwriting display titles`() =
        runTest {
            val first =
                note(
                    "Filename.md",
                    "---\ntitle: Display title\naliases: [Nickname, Other name]\ncustom: preserved\n---\n# Different heading",
                )
            val second = note("HeadingFile.md", "# Heading title")
            val source =
                note(
                    "Source.md",
                    "[[Filename]] [[Display title]] [[Nickname#Section|Shown]] [[Other name]] [[HeadingFile]] [[Heading title]]",
                )
            links.finish()

            assertThat(current(first).title).isEqualTo("Display title")
            assertThat(current(first).bodyMd).isEqualTo("# Different heading")
            assertThat(current(first).frontmatter["custom"]).isEqualTo(JsonPrimitive("preserved"))
            assertThat(current(second).title).isEqualTo("Heading title")
            assertThat(current(source).bodyMd).isEqualTo(
                "[[${first.id}|Filename]] [[${first.id}|Display title]] [[${first.id}#Section|Shown]] " +
                    "[[${first.id}|Other name]] [[${second.id}|HeadingFile]] [[${second.id}|Heading title]]",
            )
            assertThat(current(source).frontmatter).doesNotContainKey(ImportedLinkTargets.UNRESOLVED)
        }

    @Test
    fun `directory qualified relative extension case and Unicode normalized paths resolve within tree`() =
        runTest {
            val root = note("Root.md", "root")
            val cafe = note("Folder/Café.md", "cafe")
            val other = note("Other/Café.md", "other")
            val source =
                note("Folder/Source.md", "[[./CAFE\u0301.MD]] [[../Root]] [[Other/Café]] [[Folder\\Café.md]] [[Café]]")
            links.finish()

            val parsed = WikilinkExtractor.extract(current(source).bodyMd.orEmpty())
            assertThat(parsed.map { it.target }).containsExactly(cafe.id, root.id, other.id, cafe.id, "Café").inOrder()
            assertThat(ImportedLinkTargets.isAmbiguous(current(source).frontmatter, "Café")).isTrue()
        }

    @Test
    fun `ambiguous basenames aliases and titles cannot fall through to newest title or dangling resolution`() =
        runTest {
            val a = note("one/Duplicate.md", "---\naliases: [Shared]\n---\n# Duplicate")
            val b = note("two/Duplicate.md", "---\nalias: Shared\n---\n# Duplicate")
            val source = note("Source.md", "[[Duplicate]] [[Shared]] [[one/Duplicate]]")
            links.finish()
            val index = InMemoryIndexStore()
            EdgeUpserter(repository, index).upsert(current(source))
            DanglingResolver(repository, index).resolveFor(b)

            val edges = index.edgesFrom(source.id)
            assertThat(edges.map { it.dstId }).containsExactly(
                ImportedLinkTargets.unresolvedTarget(source.id, "Duplicate"),
                ImportedLinkTargets.unresolvedTarget(source.id, "Shared"),
                a.id,
            )
            assertThat(edges.map { it.dstId }).doesNotContain(b.id)
            val parsed =
                Frontmatter.parse(
                    Frontmatter.render(current(source).frontmatter, current(source).bodyMd.orEmpty()),
                )
            assertThat(ImportedLinkTargets.isAmbiguous(parsed.first, "Duplicate")).isTrue()
            assertThat(ImportedLinkTargets.isUnresolved(parsed.first, "Shared")).isTrue()
        }

    @Test
    fun `missing and escaping links never bind unrelated existing or subsequently created notes`() =
        runTest {
            val unrelated = repository.createDocument(NewDocument(DocumentKind.NOTE, "Missing", "elsewhere"))
            val source = note("Source.md", "[[Missing]] [[../Missing]] [[/Missing]] [[C:/Missing]] [[https://Missing]]")
            links.finish()
            val index = InMemoryIndexStore()
            EdgeUpserter(repository, index).upsert(current(source))
            DanglingResolver(repository, index).resolveFor(unrelated)

            assertThat(current(source).bodyMd).isEqualTo(source.bodyMd)
            assertThat(index.edgesFrom(source.id).map { it.dstId }).doesNotContain(unrelated.id)
            assertThat(index.edgesFrom(source.id).all { it.dstId.startsWith("import:") }).isTrue()
        }

    @Test
    fun `UUID links survive rename and existing UUID spelling is preserved`() =
        runTest {
            val target = note("Filename.md", "# Old title")
            val explicit = "[[${target.id}#Section|Alias]]"
            val source = note("Source.md", "[[Filename]] $explicit")
            links.finish()
            repository.renameDocument(target.id, "New title")
            val index = InMemoryIndexStore()
            EdgeUpserter(repository, index).upsert(current(source))

            assertThat(current(source).bodyMd).isEqualTo("[[${target.id}|Filename]] $explicit")
            assertThat(index.edgesFrom(source.id).map { it.dstId }).containsExactly(target.id)
            assertThat(repository.findByTitle("Old title")).isNull()
        }

    @Test
    fun `code spans fences and self heading links are untouched`() =
        runTest {
            val target = note("Target.md", "target")
            val body = "`[[Target]]`\n```md\n[[Target]]\n```\n[[#Local]] [[Target#Section]]"
            val source = note("Source.md", body)
            links.finish()

            assertThat(
                current(source).bodyMd,
            ).isEqualTo(body.replace("[[Target#Section]]", "[[${target.id}#Section|Target#Section]]"))
        }

    @Test
    fun `cancelled session stays guarded before finish and never binds existing same-title note`() =
        runTest {
            val existing = repository.createDocument(NewDocument(DocumentKind.NOTE, "Target", "pre-existing"))
            val source = note("Source.md", "[[Target]]")
            val index = InMemoryIndexStore()
            EdgeUpserter(repository, index).upsert(current(source))
            DanglingResolver(repository, index).resolveFor(existing)

            assertThat(ImportedLinkTargets.isUnresolved(current(source).frontmatter, "Target")).isTrue()
            assertThat(
                index.edgesFrom(source.id).single().dstId,
            ).isEqualTo(ImportedLinkTargets.unresolvedTarget(source.id, "Target"))
        }

    @Test
    fun `ingest of an older imported revision keeps its guards after final rewrite`() =
        runTest {
            val unrelated = repository.createDocument(NewDocument(DocumentKind.NOTE, "Filename", "elsewhere"))
            val source = note("Source.md", "[[Filename]]")
            val target = note("Filename.md", "# Display title")
            links.finish()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repository, index)
            upserter.upsert(source)
            assertThat(
                index.edgesFrom(source.id).single().dstId,
            ).isEqualTo(ImportedLinkTargets.unresolvedTarget(source.id, "Filename"))
            assertThat(index.edgesFrom(source.id).map { it.dstId }).doesNotContain(unrelated.id)

            upserter.upsert(current(source))
            assertThat(index.edgesFrom(source.id).single().dstId).isEqualTo(target.id)
        }

    @Test
    fun `non UUID frontmatter IDs stay unchanged but are never interpolated as stable links`() =
        runTest {
            val target = note("Target.md", "---\nid: \"unsafe|alias]] [[other\"\n---\nbody")
            val legacy = note("Legacy.md", "---\nid: legacy-id\n---\nbody")
            val source = note("Source.md", "[[Target]] [[Legacy]]")
            links.finish()
            assertThat(current(source).bodyMd).isEqualTo("[[Target]] [[Legacy]]")
            assertThat(current(target).id).isEqualTo("unsafe|alias]] [[other")
            assertThat(current(legacy).id).isEqualTo("legacy-id")
            assertThat(ImportedLinkTargets.isUnresolved(current(source).frontmatter, "Target")).isTrue()
            assertThat(ImportedLinkTargets.isUnresolved(current(source).frontmatter, "Legacy")).isTrue()
        }

    @Test
    fun `linked index preserves ID edge on rename and deletion cannot retarget it through title resolver`() =
        runTest {
            val index = InMemoryIndexStore()
            val repository = InMemoryVaultRepository(index = index)
            val target = repository.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "body"))
            val source = repository.createDocument(NewDocument(DocumentKind.NOTE, "Source", "[[${target.id}|Plan]]"))
            val upserter = EdgeUpserter(repository, index)
            upserter.upsert(source)
            repository.renameDocument(target.id, "Renamed")
            assertThat(index.edgesFrom(source.id).single().dstId).isEqualTo(target.id)

            repository.deleteDocument(target.id)
            val replacement = repository.createDocument(NewDocument(DocumentKind.NOTE, "Renamed", "new body"))
            DanglingResolver(repository, index).resolveFor(replacement)
            assertThat(
                index.edgesFrom(source.id).single().dstId,
            ).isEqualTo(ImportedLinkTargets.unresolvedTarget(source.id, target.id))
            upserter.upsert(source)
            assertThat(
                index.edgesFrom(source.id).single().dstId,
            ).isEqualTo(ImportedLinkTargets.unresolvedTarget(source.id, target.id))
        }

    @Test
    fun `uppercase archived UUID resolves by its persisted ID`() =
        runTest {
            val id = "018F2B6E-6C3A-7C3E-8F2A-6B1E2D3C4A5B"
            val target = note("Filename.md", "---\nid: $id\n---\n# Display title")
            val source = note("Source.md", "[[Filename]] [[$id]]")
            links.finish()
            val index = InMemoryIndexStore()
            EdgeUpserter(repository, index).upsert(current(source))
            assertThat(index.edgesFrom(source.id).single().dstId).isEqualTo(target.id)
        }

    @Test
    fun `target deleted during import is left unresolved`() =
        runTest {
            val target = note("Target.md", "target")
            val source = note("Source.md", "[[Target]]")
            repository.deleteDocument(target.id)
            links.finish()

            assertThat(current(source).bodyMd).isEqualTo("[[Target]]")
            assertThat(ImportedLinkTargets.isUnresolved(current(source).frontmatter, "Target")).isTrue()
        }
}
