// The `E0.I14` `FakeImportService`: a scripted, JVM-only stand-in for the
// `ImportService` contract locked in `core/model/…/Transfer.kt`. It powers
// `ImportServiceContractTest` on the JVM and is what downstream consumers
// (`E2.I7` ImportService impl, feature-module tests) use in place of a
// real `VaultRepository`-backed implementation.
//
// Conflict handling follows the contract's KDoc exactly (see
// `ImportService.importText`, corrected by bd `skein-ddpt`): the fake
// sniffs the imported text for a `FrontmatterKeys.ID` frontmatter line,
// and never treats a known id as an update target. A known id always
// yields a *new* document under a freshly minted id, with
// `ImportResult.conflictWith` set to the id it collided with. There is no
// separate "overwrite" parameter in the locked contract — resolution is implicit in frontmatter `id` identity.

package app.skein.testing

import app.skein.core.model.DocId
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.ImportResult
import app.skein.core.model.ImportService
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultZipImportResult
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

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

    private val personas: MutableMap<DocId, PersonaId?> = mutableMapOf()

    /** The Space ([PersonaId]) the document [id] was imported into; `null` when unassigned or unknown. */
    public fun personaOf(id: DocId): PersonaId? = personas[id]

    private val _textImports: MutableList<ImportCall> = mutableListOf()
    public val textImports: List<ImportCall> get() = _textImports

    private val _pdfImports: MutableList<ImportCall> = mutableListOf()
    public val pdfImports: List<ImportCall> get() = _pdfImports

    private val _imageImports: MutableList<ImportCall> = mutableListOf()
    public val imageImports: List<ImportCall> get() = _imageImports

    private val _vaultZipImports: MutableList<PersonaId?> = mutableListOf()

    /** The target persona of each `importVaultZip` call, in call order. */
    public val vaultZipImports: List<PersonaId?> get() = _vaultZipImports

    override suspend fun importText(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        _textImports.add(ImportCall(displayName, mimeType, personaId))
        val text = input.readBytes().toString(Charsets.UTF_8)
        val frontmatterId = extractFrontmatterId(text)
        val conflict = frontmatterId?.takeIf { it in knownIds }

        // bd skein-ddpt: never overwrite — a known id always creates a new
        // document under a freshly minted id and reports the collision.
        val id = if (conflict == null) frontmatterId ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
        knownIds.add(id)
        personas[id] = personaId
        return ImportResult(documentId = id, attachmentId = null, created = true, conflictWith = conflict)
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
        personas[noteId] = personaId
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

    /**
     * Mirrors the contract without a store: a `.md` entry is skipped when
     * its frontmatter id is known or it is a chat; an attachment always
     * gets a fresh id and is skipped when its archive id is known or only
     * skipped notes cite it; unsafe or unexpected names are skipped. No
     * size caps — those are the real implementation's concern.
     */
    override suspend fun importVaultZip(
        input: InputStream,
        personaId: PersonaId?,
    ): VaultZipImportResult {
        _vaultZipImports.add(personaId)
        var imported = 0
        var skipped = 0
        val citedByImported = mutableSetOf<DocId>()
        val citedBySkipped = mutableSetOf<DocId>()
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            val text = zip.readBytes().toString(Charsets.UTF_8)
            val name = entry.name
            val normalized = name.replace('\\', '/')
            val unsafe =
                normalized.startsWith("/") ||
                    DRIVE_PREFIX.containsMatchIn(normalized) ||
                    normalized.split('/').any { it == ".." }
            when {
                entry.isDirectory || name == ".skein/manifest.json" -> Unit
                unsafe -> skipped += 1
                '/' !in name && name.endsWith(".md") -> {
                    val id = frontmatterValue(text, FrontmatterKeys.ID)
                    val source = frontmatterValue(text, FrontmatterKeys.SOURCE)
                    if (frontmatterValue(text, FrontmatterKeys.KIND) == "chat" || (id != null && id in knownIds)) {
                        skipped += 1
                        source?.let(citedBySkipped::add)
                    } else {
                        val newId = id ?: UUID.randomUUID().toString()
                        knownIds.add(newId)
                        personas[newId] = personaId
                        source?.let(citedByImported::add)
                        imported += 1
                    }
                }
                name.startsWith("attachments/") && name.count { it == '/' } == 1 -> {
                    val archiveId = name.substringAfter('/').substringBeforeLast('.')
                    if (archiveId in knownIds || (archiveId in citedBySkipped && archiveId !in citedByImported)) {
                        skipped += 1
                    } else {
                        knownIds.add(UUID.randomUUID().toString())
                        imported += 1
                    }
                }
                else -> skipped += 1
            }
        }
        return VaultZipImportResult(imported = imported, skipped = skipped)
    }

    private fun extractFrontmatterId(text: String): DocId? = frontmatterValue(text, FrontmatterKeys.ID)

    private fun frontmatterValue(
        text: String,
        key: String,
    ): String? {
        val lines = text.lines()
        if (lines.firstOrNull() != "---") return null
        val closingOffset = lines.drop(1).indexOfFirst { it == "---" }
        if (closingOffset < 0) return null
        return lines
            .subList(1, closingOffset + 1)
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(":")
            ?.trim()
    }

    private companion object {
        val DRIVE_PREFIX: Regex = Regex("^[A-Za-z]:")
    }
}
