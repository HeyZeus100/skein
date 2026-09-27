// bd `skein-a0mm`: the archive half of `ImportService.importVaultZip`, the
// inverse of `ExportServiceImpl.exportVaultZip` (`docs/VAULT_FORMAT.md` §6).
// `ImportServiceImpl` owns what a `<title>.md` entry becomes; this file owns
// walking the archive, which is untrusted input.
//
// Design notes:
//   - One streaming pass over a `ZipInputStream`: the contract hands us an
//     `InputStream`, and spooling the archive to a temp file for random
//     access would put plaintext outside the vault.
//   - The manifest leads an export (since skein-a0mm) and is read first: it
//     is the only place an in-app document's kind and title are written
//     (their frontmatter carries neither), and an attachment's MIME type and
//     title. An archive whose manifest comes later (older exports) or is
//     missing still imports, from frontmatter, file names and extensions —
//     so a chat there reads as a note and a title as its file name.
//   - Attachments get fresh ids. `VaultRepository.createAttachment` mints
//     its own, and that keeps an archive-supplied string from ever naming a
//     blob file. Notes imported from the same archive that cite one through
//     `source:` are rewritten to the new id after the pass (an export writes
//     notes before attachments, so the rewrite cannot happen inline). An
//     attachment is skipped when its archive id is already in the vault, or
//     when every note in the archive citing it was skipped — so re-importing
//     an archive does not leave a second, orphaned copy of each PDF. One that
//     nothing cites gets a fresh copy on every import into another vault;
//     none exist today (`importImage` is unimplemented).
//   - Safety (every cap is in [VaultZipLimits]): every byte read, including
//     bytes of entries that are skipped, counts against the per-entry and
//     total caps, because `ZipInputStream` would otherwise inflate a skipped
//     entry in full inside `closeEntry`. A cap, a corrupt entry header or an
//     unreadable byte stops the pass (`truncated`); a merely unexpected entry
//     is skipped and counted. Entry names are never used as paths.
//   - Nothing here logs: entry names are file names and titles.

package app.skein.core.vault.transfer

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.VaultRepository
import app.skein.core.model.VaultZipImportResult
import app.skein.core.vault.export.AttachmentExtensions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Safety caps for one [VaultZipImporter] pass. The defaults are the
 * production values; tests shrink them.
 *
 * @property maxEntries 50,000 entries: a 40,000-note vault's export still
 *   fits, and no archive past it is one Skein wrote.
 * @property maxEntryBytes 256 MiB uncompressed per entry: past any attachment
 *   `importPdf` handles well (it holds a whole PDF in memory).
 * @property maxTotalBytes 2 GiB uncompressed per archive, skipped entries
 *   included: bounds both the plaintext written into the vault and the
 *   inflate work a zip bomb can demand.
 * @property maxDocumentBytes 10 MiB per `.md` entry, which is held in memory
 *   while it is parsed (`ImportTextMemorySmokeTest`'s bound); a larger one
 *   is skipped.
 * @property maxManifestBytes 32 MiB for `.skein/manifest.json`, parsed in
 *   memory: ~600 bytes for each of [maxEntries] entries. A larger one is
 *   ignored, not fatal.
 */
internal data class VaultZipLimits(
    val maxEntries: Int = 50_000,
    val maxEntryBytes: Long = 256L * MIB,
    val maxTotalBytes: Long = 2_048L * MIB,
    val maxDocumentBytes: Int = 10 * MIB.toInt(),
    val maxManifestBytes: Int = 32 * MIB.toInt(),
) {
    private companion object {
        const val MIB: Long = 1024L * 1024L
    }
}

/** What the manifest says about one `.md` entry; any field may be missing. */
internal class ManifestHint(
    val id: DocId?,
    val kind: String?,
    val title: String?,
)

/** What [ImportServiceImpl] made of one `<title>.md` entry: the new document, or `null` when it skipped it. */
internal class ArchivedDocument(
    val created: Document?,
    /** The entry's `source:` frontmatter (an attachment id in the archive's vault), created or not. */
    val source: DocId?,
)

/**
 * One pass over a vault zip. Single use.
 *
 * @param importDocument turns a top-level `.md` entry's bytes into a document
 *   (or declines it); see `ImportServiceImpl.importArchivedDocument`.
 */
internal class VaultZipImporter(
    private val repository: VaultRepository,
    private val limits: VaultZipLimits,
    private val importDocument: suspend (fileName: String, bytes: ByteArray, hint: ManifestHint?) -> ArchivedDocument,
) {
    private var imported = 0
    private var skipped = 0
    private var totalBytes = 0L

    /** Manifest entries by archive path; empty until (and unless) the manifest leads the archive. */
    private var manifest: Map<String, JsonObject> = emptyMap()

    /** Archive attachment ids cited through `source:` by notes that were imported / skipped. */
    private val citedByImported = HashSet<DocId>()
    private val citedBySkipped = HashSet<DocId>()

    /** Archive attachment id → the id it was imported under. */
    private val newAttachmentIds = HashMap<DocId, DocId>()
    private val citingDocuments = ArrayList<Document>()

    suspend fun run(input: InputStream): VaultZipImportResult {
        // Not closed: closing a ZipInputStream closes the caller's stream.
        val zip = ZipInputStream(input)
        var entries = 0
        var contentSeen = false
        var truncated = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = readArchive { zip.nextEntry } ?: break
                if (++entries > limits.maxEntries) {
                    truncated = true
                    break
                }
                val reader = EntryReader(zip)
                when {
                    entry.isDirectory -> Unit
                    entry.name == MANIFEST_PATH -> if (!contentSeen) readManifest(reader)
                    else -> {
                        contentSeen = true
                        importEntry(entry.name, reader)
                    }
                }
                reader.drain()
            }
        } catch (_: StopImport) {
            truncated = true
        }
        rewriteCitations()
        return VaultZipImportResult(imported = imported, skipped = skipped, truncated = truncated)
    }

    private fun readManifest(reader: EntryReader) {
        val bytes = reader.readAtMost(limits.maxManifestBytes) ?: return
        manifest =
            try {
                val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                listOf(MANIFEST_DOCUMENTS, MANIFEST_ATTACHMENTS)
                    .flatMap { key -> (root[key] as? JsonArray).orEmpty() }
                    .filterIsInstance<JsonObject>()
                    .mapNotNull { entry -> entry.string("path")?.let { it to entry } }
                    .toMap()
            } catch (_: IllegalArgumentException) {
                // Not JSON, or not the shape we wrote (SerializationException is one too): import without it.
                emptyMap()
            }
    }

    private suspend fun importEntry(
        name: String,
        reader: EntryReader,
    ) {
        when {
            !isSafeEntryName(name) -> skipped += 1
            '/' !in name && name.endsWith(DOCUMENT_SUFFIX, ignoreCase = true) -> importDocumentEntry(name, reader)
            name.startsWith(ATTACHMENTS_PREFIX) && name.indexOf('/', ATTACHMENTS_PREFIX.length) < 0 ->
                importAttachmentEntry(name, reader)
            else -> skipped += 1
        }
    }

    private suspend fun importDocumentEntry(
        name: String,
        reader: EntryReader,
    ) {
        val bytes = reader.readAtMost(limits.maxDocumentBytes)
        if (bytes == null) {
            skipped += 1
            return
        }
        val hint = manifest[name]?.let { ManifestHint(it.string("id"), it.string("kind"), it.string("title")) }
        val outcome = importDocument(name, bytes, hint)
        val created = outcome.created
        if (created == null) {
            skipped += 1
            outcome.source?.let { citedBySkipped += it }
            return
        }
        imported += 1
        outcome.source?.let { source ->
            citedByImported += source
            citingDocuments += created
        }
    }

    private suspend fun importAttachmentEntry(
        name: String,
        reader: EntryReader,
    ) {
        val fileName = name.substring(ATTACHMENTS_PREFIX.length)
        val archiveId = fileName.substringBeforeLast('.')
        val onlyCitedBySkipped = archiveId in citedBySkipped && archiveId !in citedByImported
        if (archiveId.isEmpty() || onlyCitedBySkipped || repository.getDocument(archiveId) != null) {
            skipped += 1
            return
        }
        val hint = manifest[name]
        val mimeType =
            hint?.string("mime")
                ?: AttachmentExtensions.mimeTypeFor(fileName.substringAfterLast('.', missingDelimiterValue = ""))
        val attachment =
            repository.createAttachment(title = hint?.string("title") ?: fileName, mimeType = mimeType) { out ->
                reader.copyTo(out)
            }
        newAttachmentIds[archiveId] = attachment.id
        imported += 1
    }

    /** Points each imported note's `source:` at the id its attachment was imported under. */
    private suspend fun rewriteCitations() {
        for (document in citingDocuments) {
            val source = (document.frontmatter[FrontmatterKeys.SOURCE] as? JsonPrimitive)?.content ?: continue
            val newId = newAttachmentIds[source] ?: continue
            repository.updateFrontmatter(
                document.id,
                JsonObject(document.frontmatter + (FrontmatterKeys.SOURCE to JsonPrimitive(newId))),
            )
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Runs a read of the archive; an unreadable or corrupt archive stops the pass rather than failing it. */
    private inline fun <T> readArchive(read: () -> T): T =
        try {
            read()
        } catch (_: IOException) {
            throw StopImport()
        } catch (_: IllegalArgumentException) {
            // `ZipInputStream` throws this for an entry name that is not valid UTF-8.
            throw StopImport()
        }

    /** The current entry's bytes, each one counted against [VaultZipLimits.maxEntryBytes] and [VaultZipLimits.maxTotalBytes]. */
    private inner class EntryReader(
        private val zip: ZipInputStream,
    ) : InputStream() {
        private var entryBytes = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = readArchive { zip.read(b, off, len) }
            if (n > 0) {
                entryBytes += n
                totalBytes += n
                if (entryBytes > limits.maxEntryBytes || totalBytes > limits.maxTotalBytes) throw StopImport()
            }
            return n
        }

        /** The rest of the entry, or `null` (with up to [max] + 1 bytes consumed) when it is longer than [max]. */
        fun readAtMost(max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = read(buffer, 0, minOf(buffer.size, max + 1 - out.size()))
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > max) return null
            }
        }

        fun drain() {
            val buffer = ByteArray(BUFFER_BYTES)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    /** Thrown out of any read to end the pass; never escapes [run]. */
    private class StopImport : RuntimeException()

    internal companion object {
        const val MANIFEST_PATH: String = ".skein/manifest.json"
        private const val MANIFEST_DOCUMENTS: String = "documents"
        private const val MANIFEST_ATTACHMENTS: String = "attachments"
        private const val ATTACHMENTS_PREFIX: String = "attachments/"
        private const val DOCUMENT_SUFFIX: String = ".md"
        private const val BUFFER_BYTES: Int = 8 * 1024
        private const val BYTE_MASK: Int = 0xFF
        private val DRIVE_PREFIX: Regex = Regex("^[A-Za-z]:")

        /**
         * `false` for a name that would escape a directory if it were ever
         * used as a path: a `..` segment, a leading `/` or `\`, a drive
         * prefix (`C:`), or a NUL. Both separators count, since archives
         * written on Windows use `\`.
         */
        fun isSafeEntryName(name: String): Boolean {
            val normalized = name.replace('\\', '/')
            return normalized.isNotEmpty() &&
                !normalized.startsWith('/') &&
                !DRIVE_PREFIX.containsMatchIn(normalized) &&
                '\u0000' !in normalized &&
                normalized.split('/').none { it == ".." }
        }
    }
}
