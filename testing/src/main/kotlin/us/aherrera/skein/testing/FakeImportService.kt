// The `E0.I14` `FakeImportService`: a scripted, JVM-only stand-in for the
// `ImportService` contract locked in `core/model/…/Transfer.kt`. It powers
// `ImportServiceContractTest` on the JVM and is what downstream consumers
// (`E2.I7` ImportService impl, feature-module tests) use in place of a
// real `VaultRepository`-backed implementation.
//
// Conflict handling follows the contract's KDoc exactly (see
// `ImportService.importText`): the fake sniffs the imported text for a
// `FrontmatterKeys.ID` frontmatter line, and only treats the import as an
// update (`ImportResult.created == false`) when that id is already known
// to this fake. There is no separate "conflict policy" parameter in the
// locked contract — resolution is implicit in frontmatter `id` identity.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.ImportResult
import us.aherrera.skein.core.model.ImportService
import us.aherrera.skein.core.model.PersonaId
import java.io.InputStream
import java.util.UUID

/**
 * Scripted, in-memory `ImportService` for JVM tests.
 *
 * @param visionModelLoaded when true, `importImage` also synthesizes an
 *   AIOUT description document (per the contract's KDoc), returning it as
 *   [ImportResult.documentId] with the stored attachment as
 *   [ImportResult.attachmentId]. When false (the default), `importImage`
 *   returns the attachment itself as [ImportResult.documentId] and leaves
 *   [ImportResult.attachmentId] `null` — mirroring "no vision model, no
 *   description document."
 */
public class FakeImportService(
    public val visionModelLoaded: Boolean = false,
) : ImportService {
    /** One recorded import invocation, common shape across the three entry points. */
    public data class ImportCall(
        val displayName: String,
        val mimeType: String?,
        val personaId: PersonaId?,
    )

    private val knownIds: MutableSet<DocId> = mutableSetOf()

    private val _textImports: MutableList<ImportCall> = mutableListOf()
    public val textImports: List<ImportCall> get() = _textImports

    private val _pdfImports: MutableList<ImportCall> = mutableListOf()
    public val pdfImports: List<ImportCall> get() = _pdfImports

    private val _imageImports: MutableList<ImportCall> = mutableListOf()
    public val imageImports: List<ImportCall> get() = _imageImports

    override suspend fun importText(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        _textImports.add(ImportCall(displayName, mimeType, personaId))
        val text = input.readBytes().toString(Charsets.UTF_8)
        val frontmatterId = extractFrontmatterId(text)

        return if (frontmatterId != null && frontmatterId in knownIds) {
            ImportResult(documentId = frontmatterId, attachmentId = null, created = false)
        } else {
            val id = frontmatterId ?: UUID.randomUUID().toString()
            knownIds.add(id)
            ImportResult(documentId = id, attachmentId = null, created = true)
        }
    }

    override suspend fun importPdf(
        displayName: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        _pdfImports.add(ImportCall(displayName, "application/pdf", personaId))
        input.readBytes() // drain, mirroring a real reader consuming the stream once

        val attachmentId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        knownIds.add(noteId)
        return ImportResult(documentId = noteId, attachmentId = attachmentId, created = true)
    }

    override suspend fun importImage(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        _imageImports.add(ImportCall(displayName, mimeType, personaId))
        input.readBytes()

        val attachmentId = UUID.randomUUID().toString()
        knownIds.add(attachmentId)
        return if (visionModelLoaded) {
            val descriptionId = UUID.randomUUID().toString()
            knownIds.add(descriptionId)
            ImportResult(documentId = descriptionId, attachmentId = attachmentId, created = true)
        } else {
            ImportResult(documentId = attachmentId, attachmentId = null, created = true)
        }
    }

    private fun extractFrontmatterId(text: String): DocId? {
        val lines = text.lines()
        if (lines.firstOrNull() != "---") return null
        val closingOffset = lines.drop(1).indexOfFirst { it == "---" }
        if (closingOffset < 0) return null
        return lines
            .subList(1, closingOffset + 1)
            .firstOrNull { it.startsWith("${FrontmatterKeys.ID}:") }
            ?.substringAfter(":")
            ?.trim()
    }
}
