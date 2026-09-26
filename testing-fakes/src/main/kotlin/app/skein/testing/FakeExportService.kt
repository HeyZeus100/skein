// The `E0.I14` `FakeExportService`: a scripted, JVM-only stand-in for the
// `ExportService` contract locked in `core/model/…/Transfer.kt`. It powers
// `ExportServiceContractTest` on the JVM and is what downstream consumers
// (`E2.I10` Markdown export, `E2.I11` PDF export UI, feature-module tests)
// use in place of a real `VaultRepository`-backed implementation.
//
// The fake renders frontmatter with the exact `FrontmatterKeys` constants
// so a test asserting on the rendered bytes exercises the same key names
// the real exporter (`E2.I10`) will use. It does not implement the real
// Markdown-body materialization rules (wikilinks, attachment refs) — those
// belong to `E2.I3`/`E2.I10`; this fake only needs to be a faithful,
// swappable stand-in for the *shape* of the contract (arguments, ordering,
// cancellation, progress reporting), not the exact byte-for-byte output of
// the eventual production exporter.

package app.skein.testing

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.ExportService
import app.skein.core.model.FrontmatterKeys
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Scripted, in-memory `ExportService` for JVM tests.
 *
 * @param documents the vault's documents, in the order `exportVaultZip`
 *   iterates and reports progress over. Mutate via [addDocument] to grow
 *   the fake "vault" mid-test.
 * @param perDocEmitDelay suspension before each document is written during
 *   `exportVaultZip` — lets a test assert on progress ordering or on
 *   prompt cancellation without a real filesystem or clock.
 */
public class FakeExportService(
    private val documents: MutableList<Document> = mutableListOf(),
    public val perDocEmitDelay: Duration = ZERO,
) : ExportService {
    /** One recorded `exportDocx` invocation. */
    public data class DocxCall(
        val docId: DocId,
        val hadTemplate: Boolean,
    )

    private val _exportedMarkdownIds: MutableList<DocId> = mutableListOf()

    /** [DocId]s passed to `exportMarkdown`, in call order — for call-arg assertions. */
    public val exportedMarkdownIds: List<DocId> get() = _exportedMarkdownIds

    private var _vaultZipExportCount: Int = 0

    /** Number of `exportVaultZip` calls that ran to completion (a cancelled call does not increment this). */
    public val vaultZipExportCount: Int get() = _vaultZipExportCount

    private val _docxCalls: MutableList<DocxCall> = mutableListOf()

    /** Recorded `exportDocx` invocations, in call order. */
    public val docxCalls: List<DocxCall> get() = _docxCalls

    /** Adds a document to the fake vault so it is exportable / included in `exportVaultZip`. */
    public fun addDocument(document: Document) {
        documents.add(document)
    }

    override suspend fun exportMarkdown(
        docId: DocId,
        out: OutputStream,
    ) {
        val document =
            documents.firstOrNull { it.id == docId }
                ?: throw NoSuchElementException("no document with the given id")
        _exportedMarkdownIds.add(docId)
        out.write(renderMarkdown(document).toByteArray(Charsets.UTF_8))
    }

    override suspend fun exportVaultZip(
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val total = documents.size
        ZipOutputStream(out).use { zip ->
            documents.forEachIndexed { index, document ->
                // Cooperative cancellation checkpoint, independent of
                // `perDocEmitDelay` — a real implementation streaming
                // thousands of documents must remain cancellable even with
                // no configured delay.
                currentCoroutineContext().ensureActive()
                if (perDocEmitDelay > ZERO) delay(perDocEmitDelay)

                zip.putNextEntry(ZipEntry("${document.title}.md"))
                zip.write(renderMarkdown(document).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                onProgress(index + 1, total)
            }
        }
        _vaultZipExportCount++
    }

    override suspend fun exportDocx(
        docId: DocId,
        out: OutputStream,
        template: InputStream?,
    ) {
        val document =
            documents.firstOrNull { it.id == docId }
                ?: throw NoSuchElementException("no document with the given id")
        _docxCalls.add(DocxCall(docId = docId, hadTemplate = template != null))
        // Not a real OOXML writer (see file header) — a deterministic
        // placeholder is enough for contract-level assertions.
        out.write("FAKE-DOCX:${document.title}".toByteArray(Charsets.UTF_8))
    }

    private fun renderMarkdown(document: Document): String =
        buildString {
            appendLine("---")
            appendLine("${FrontmatterKeys.ID}: ${document.id}")
            appendLine("${FrontmatterKeys.KIND}: ${document.kind.db}")
            appendLine("${FrontmatterKeys.TITLE}: ${document.title}")
            appendLine("---")
            append(document.bodyMd.orEmpty())
        }
}
