// skein-a0mm: `FolderImportJob` over a real SAF tree (Robolectric hosting
// `DirectoryDocumentsProvider` over a temp directory) into the real
// `ImportServiceImpl` over an in-memory vault — and then the production
// ingest composition (`IngestPipelines.forSession`), to show a folder whose
// notes `[[link]]` each other arrives as a connected graph.

package app.skein.transfer

import android.Manifest
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.EdgeKind
import app.skein.core.model.ImportResult
import app.skein.core.model.ImportService
import app.skein.core.model.PersonaId
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.transfer.ImportServiceImpl
import app.skein.core.vault.transfer.ImportedLinkTargets
import app.skein.export.stage.FakeExportStageRepository
import app.skein.ingest.IngestPipelines
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryPersonaService
import app.skein.testing.InMemoryVaultRepository
import app.skein.vault.VaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderImportJobTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = InMemoryVaultRepository()
    private val index = InMemoryIndexStore()
    private lateinit var folder: File
    private lateinit var resolver: ContentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(DirectoryDocumentsProvider.AUTHORITY, "root")

    @Before
    fun setUp() {
        folder = temp.newFolder("notes")
        DirectoryDocumentsProvider.root = folder
        val info =
            ProviderInfo().apply {
                authority = DirectoryDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
        Robolectric.buildContentProvider(DirectoryDocumentsProvider::class.java).create(info)
        resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun write(
        path: String,
        text: String,
    ) = File(folder, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun session(importService: ImportService = ImportServiceImpl(repository)): VaultSession =
        VaultSession(
            repository = repository,
            indexStore = index,
            personaService = InMemoryPersonaService(),
            exportService = ExportServiceImpl(repository),
            importService = importService,
            exportStages = FakeExportStageRepository(),
        ) {}

    private fun job(open: VaultSession? = session()): FolderImportJob =
        FolderImportJob(resolver, MutableStateFlow(open), scope)

    private suspend fun FolderImportJob.finished(): FolderImportState.Finished =
        withTimeout(10_000L) { state.first { it is FolderImportState.Finished } as FolderImportState.Finished }

    @Test
    fun imports_markdown_and_text_notes_from_every_level_into_the_space_and_ignores_the_rest() =
        runBlocking {
            write("Alpha.md", "Alpha body")
            write("Beta.markdown", "Beta body")
            write("sub/Gamma.txt", "Gamma body")
            write("sub/deeper/Delta.MD", "Delta body")
            write(".obsidian/workspace.md", "hidden folder")
            write(".hidden.md", "hidden file")
            write("paper.pdf", "%PDF-1.4")
            write("image.png", "png")
            val job = job()

            assertTrue(job.start(treeUri, personaId = "space-1"))
            val finished = job.finished()

            assertEquals(FolderImportState.Finished(4, 0, FolderImportOutcome.COMPLETED), finished)
            for (title in listOf("Alpha", "Beta", "Gamma", "Delta")) {
                val document = repository.findByTitle(title)
                assertNotNull("expected a note titled $title", document)
                assertEquals("space-1", document?.personaId)
            }
            assertNull("hidden files and folders are ignored", repository.findByTitle("workspace"))
        }

    @Test
    fun filename_and_relative_links_resolve_with_heading_titles_and_duplicate_names_stay_unresolved() =
        runBlocking {
            write("Source.md", "[[one/Filename]] [[Filename]] [[Nickname]]")
            write("one/Filename.md", "---\naliases: [Nickname]\n---\n# First display title\n[[../Source]]")
            write("two/Filename.md", "# Second display title")
            val job = job()
            assertTrue(job.start(treeUri, personaId = null))
            assertEquals(FolderImportState.Finished(3, 0, FolderImportOutcome.COMPLETED), job.finished())

            val source = requireNotNull(repository.findByTitle("Source"))
            val first = requireNotNull(repository.findByTitle("First display title"))
            assertEquals("[[${first.id}|one/Filename]] [[Filename]] [[${first.id}|Nickname]]", source.bodyMd)
            assertEquals("# First display title\n[[${source.id}|../Source]]", first.bodyMd)
            assertTrue(ImportedLinkTargets.isAmbiguous(source.frontmatter, "Filename"))
        }

    @Test
    fun a_folder_whose_notes_link_each_other_arrives_connected() =
        runBlocking {
            write("Alpha.md", "Alpha points at [[Beta]] and [[Gamma]].")
            write("Beta.md", "Beta points back at [[Alpha]].")
            write("sub/Gamma.md", "# Gamma\n\nAlso [[Alpha]].")
            val open = session()
            val job = job(open)

            assertTrue(job.start(treeUri, personaId = "space-1"))
            assertEquals(FolderImportOutcome.COMPLETED, job.finished().outcome)
            val outcome = IngestPipelines.forSession(open, pace = { IngestPace.FULL }).run()

            assertTrue("ingest drained: $outcome", outcome is IngestOutcome.Drained)
            val alpha = requireNotNull(repository.findByTitle("Alpha")).id
            val beta = requireNotNull(repository.findByTitle("Beta")).id
            val gamma = requireNotNull(repository.findByTitle("Gamma")).id

            suspend fun linksFrom(id: String) =
                index
                    .edgesFrom(id)
                    .filter { it.kind == EdgeKind.WIKILINK }
                    .map { it.dstId }
                    .toSet()
            assertEquals(
                "links resolve to the imported notes, not dangling titles",
                setOf(beta, gamma),
                linksFrom(alpha),
            )
            assertEquals(setOf(alpha), linksFrom(beta))
            assertEquals(setOf(alpha), linksFrom(gamma))
        }

    @Test
    fun an_oversized_file_is_skipped_and_invalid_utf8_still_imports() =
        runBlocking {
            File(folder, "Huge.md").writeBytes(ByteArray(FolderImportJob.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })
            File(
                folder,
                "Garbled.md",
            ).writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 'o'.code.toByte(), 'k'.code.toByte()))
            val job = job()

            assertTrue(job.start(treeUri, personaId = null))

            assertEquals(FolderImportState.Finished(1, 1, FolderImportOutcome.COMPLETED), job.finished())
            assertTrue(
                repository
                    .findByTitle("Garbled")
                    ?.bodyMd
                    .orEmpty()
                    .endsWith("ok"),
            )
        }

    @Test
    fun cancel_stops_the_import_and_keeps_what_it_imported() =
        runBlocking {
            write("A.md", "a")
            write("B.md", "b")
            val gate = GatedImportService(ImportServiceImpl(repository))
            val job = job(session(gate))

            assertTrue(job.start(treeUri, personaId = null))
            gate.awaitFirstCall()
            job.cancel()

            assertEquals(FolderImportState.Finished(0, 0, FolderImportOutcome.CANCELLED), job.finished())
            assertNull(repository.findByTitle("A"))
            assertNull(repository.findByTitle("B"))
        }

    @Test
    fun a_lock_stops_the_import_and_says_so() =
        runBlocking {
            write("A.md", "a")
            write("B.md", "b")
            val gate = GatedImportService(ImportServiceImpl(repository))
            val job = job(session(gate))

            assertTrue(job.start(treeUri, personaId = null))
            gate.awaitFirstCall()
            job.onLocking(epoch = 1L, budgetMillis = 1_000L)

            assertEquals(FolderImportOutcome.LOCKED, job.finished().outcome)
        }

    @Test
    fun start_refuses_while_locked_or_while_another_import_runs() =
        runBlocking {
            write("A.md", "a")
            assertFalse("no session, no import", job(open = null).start(treeUri, personaId = null))

            val gate = GatedImportService(ImportServiceImpl(repository))
            val job = job(session(gate))
            assertTrue(job.start(treeUri, personaId = null))
            gate.awaitFirstCall()
            assertFalse("one import at a time", job.start(treeUri, personaId = null))

            gate.release.complete(Unit)
            assertEquals(FolderImportState.Finished(1, 0, FolderImportOutcome.COMPLETED), job.finished())
        }

    /** Holds every `importText` until [release] completes, so a test can act while one is in flight. */
    private class GatedImportService(
        private val delegate: ImportService,
    ) : ImportService by delegate {
        private val firstCallStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun awaitFirstCall() = withTimeout(10_000L) { firstCallStarted.await() }

        override suspend fun importText(
            displayName: String,
            mimeType: String,
            input: InputStream,
            personaId: PersonaId?,
        ): ImportResult {
            firstCallStarted.complete(Unit)
            release.await()
            return delegate.importText(displayName, mimeType, input, personaId)
        }
    }
}
