// `E2.I7` (bd `skein-ad5`): `ImportService` implementation backing text,
// Markdown, and source-code import — the `importText` branch of the
// contract locked by `E0.I14` (`core/model/.../Transfer.kt`, skein-18j).
// `importImage` is intentionally left unimplemented here: `E2.I9` (bd
// `skein-rni`) owns it, exactly as `ExportServiceImpl` defers `exportDocx`
// to `E2.I11`/`E2.I12`.
//
// `importPdf` (`E2.I8`, bd `skein-qdo`) — see `PdfImporter.kt` for the
// extraction mechanism itself:
//   1. Buffer the whole stream once (`input.readBytes()` — a PDF's
//      xref/trailer needs random access, so, like `readNormalized`, there is
//      no true streaming parse) and store it as an `ATTACHMENT` via
//      `VaultRepository.createAttachment` unconditionally — encrypted,
//      scanned, and malformed PDFs all still get their bytes preserved.
//   2. `PdfImporter.extract` tries to read the text layer + `Title` document
//      info. A `null` text result (no layer, encrypted, or unparseable —
//      never thrown as an exception) becomes the one-line notice body the
//      bd description specifies; a `null` title falls back to the display
//      name minus its extension, exactly like `importProse`'s title
//      derivation.
//   3. The NOTE is always freshly created (there is no PDF-side notion of a
//      frontmatter `id` to update in place, unlike `importText`) with
//      `source: <attachmentId>` frontmatter — the CITE edge from note to
//      attachment is written by the ingest pipeline (`E5.I8`), not here.
//
// Design notes:
//   - Like `ExportServiceImpl` (`E2.I10`), this class depends only on
//     `VaultRepository` (`core/model`), not the SQLCipher-backed
//     `VaultRepositoryImpl` — so it works against both the real vault and
//     the JVM `InMemoryVaultRepository` fake (`:testing`). It does not own
//     the source of the bytes either (no SAF / `Uri` here): the caller hands
//     in an `InputStream` plus the display name and MIME type it knows.
//   - `importText` (plan §4.5 / bd `skein-ad5` DESCRIPTION):
//       1. Decode the stream as UTF-8 in a single streaming pass —
//          tolerating a leading BOM, replacing malformed sequences rather
//          than failing, and normalizing CRLF / lone CR to `\n` on the fly
//          (`readNormalized`). The stream is read to EOF but not closed; the
//          caller owns it, as with `ExportService`'s `OutputStream`.
//       2. Classify via `MimeSniffer` (extension → MIME → content sniff).
//       3. Source code: the whole decoded text is wrapped in a fenced block
//          tagged with the language, titled with the file name as-is, and
//          always created as a fresh NOTE. Frontmatter is deliberately *not*
//          parsed for code — a YAML file's own `---` document markers are
//          content, not metadata.
//       4. Prose (Markdown / plain text): split frontmatter from body with
//          `Frontmatter.parse`. `importText` never overwrites an existing
//          document (bd `skein-ddpt`, P2: an imported/shared file's
//          frontmatter `id` is not proof the user intends to replace
//          whatever it names — ids are visible in every export and every
//          wikilink-resolved edge). If the frontmatter carries an `id`
//          that already names a document (any `DocumentKind`), a *new*
//          NOTE is created under a freshly minted id and
//          `ImportResult.conflictWith` is set to the existing document's
//          id. Otherwise create a NOTE — under the frontmatter `id` if
//          there was one and it collides with nothing, so a file exported
//          from another Skein vault keeps its identity — titled from the
//          frontmatter `title`, else the first `# heading`, else the file
//          name without its extension.
//   - Malformed frontmatter (an unterminated header) is not an error: the
//     locked `ImportResult` has no failure variant, and `Frontmatter.parse`
//     returns empty frontmatter + the whole text as the body in that case,
//     so the file simply imports as a plain note (see `ImportTextTest`).
//   - Conflict policy (bd `skein-ddpt`): resolution is "always create,
//     never update", uniformly for every `DocumentKind` a colliding id
//     might name (NOTE, CHAT, AIOUT, or ATTACHMENT) — there is no special
//     case for ATTACHMENT any more, since nothing is ever updated in
//     place. `FakeImportService` mirrors this. `personaId` applies to the
//     created document as usual.
//   - Memory (bd `skein-ad5` AC 4): the decode pass keeps one growable
//     buffer (pre-sized from `available()` when the stream knows its
//     length) and hands the repository a single `String`; the title scan
//     and the frontmatter split both walk the text with `indexOf` rather
//     than materializing a line list, and only the small header is passed
//     to `Frontmatter.parse`. `ImportTextMemorySmokeTest` bounds the
//     result.
//   - Persisted frontmatter is the parsed frontmatter with the canonical
//     keys reconciled to the row: `kind` mirrors the document kind, `title`
//     the derived title, `updated` this import's timestamp, and `created`
//     is kept from the file, else from the existing document, else set now.
//     `VaultRepository` itself overwrites the `id` key with the row's id, so
//     this class never manages that key.

package app.skein.core.vault.transfer

import android.content.Context
import app.skein.core.vault.codec.Frontmatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.ImportResult
import us.aherrera.skein.core.model.ImportService
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.VaultRepository
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant

/**
 * `ImportService` implementation backing text / Markdown / source-code /
 * PDF import (`E2.I7`, `E2.I8`).
 *
 * @param now source of the ISO-8601 `created` / `updated` frontmatter
 *   timestamps. The `Document.createdAt` / `updatedAt` columns come from the
 *   repository's own clock; tests inject both to make them deterministic.
 * @param context an app `Context`, used only by `importPdf` to initialize
 *   `PDFBoxResourceLoader` (see `PdfImporter.kt`) so pdfbox-android can load
 *   its bundled font/glyph resources. Optional and defaulting to `null` so
 *   call sites that predate `E2.I8` (existing tests, and any future caller
 *   that never imports a PDF) keep compiling unchanged; without one,
 *   `importPdf` still never throws — it degrades to the "no text layer"
 *   notice for every PDF (see `PdfImporter.extract`'s KDoc).
 */
public class ImportServiceImpl(
    private val repository: VaultRepository,
    private val now: () -> Instant = Instant::now,
    private val context: Context? = null,
) : ImportService {
    override suspend fun importText(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        val text = readNormalized(input)
        val language = MimeSniffer.sourceLanguage(displayName, mimeType, text)
        return if (language != null) {
            importSourceCode(displayName, language, text, personaId)
        } else {
            importProse(displayName, text, personaId)
        }
    }

    override suspend fun importPdf(
        displayName: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult {
        val bytes = input.readBytes()
        val attachment =
            repository.createAttachment(title = displayName, mimeType = PDF_MIME_TYPE) { out ->
                out.write(bytes)
            }

        val extraction = PdfImporter.extract(bytes, context)
        val title = extraction.title ?: titleFromDisplayName(displayName)
        val body = extraction.text ?: "_No text layer found in $displayName._"

        val sourceFrontmatter = buildJsonObject { put(FrontmatterKeys.SOURCE, JsonPrimitive(attachment.id)) }
        val note =
            repository.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = title,
                    bodyMd = body,
                    personaId = personaId,
                    frontmatter =
                        reconcileFrontmatter(
                            sourceFrontmatter,
                            DocumentKind.NOTE,
                            title,
                            existing = null,
                        ),
                ),
            )
        return ImportResult(documentId = note.id, attachmentId = attachment.id, created = true)
    }

    private fun titleFromDisplayName(displayName: String): String =
        displayName.substringBeforeLast('.').ifBlank { displayName }.ifBlank { UNTITLED }

    override suspend fun importImage(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult =
        throw UnsupportedOperationException(
            "ImportServiceImpl.importImage is not implemented yet (see plan E2.I9 / bd skein-rni)",
        )

    // ------------------------------------------------------------------
    // Source code
    // ------------------------------------------------------------------

    private suspend fun importSourceCode(
        displayName: String,
        language: String,
        text: String,
        personaId: PersonaId?,
    ): ImportResult {
        val title = displayName.ifBlank { UNTITLED }
        val created =
            repository.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = title,
                    bodyMd = fence(language, text),
                    personaId = personaId,
                    frontmatter =
                        reconcileFrontmatter(
                            JsonObject(emptyMap()),
                            DocumentKind.NOTE,
                            title,
                            existing = null,
                        ),
                ),
            )
        return ImportResult(documentId = created.id, attachmentId = null, created = true)
    }

    /**
     * Wraps [content] in a fenced code block tagged [language]. The closing
     * fence always lands on its own line, and the fence is one backtick
     * longer than any backtick run inside [content] (CommonMark's rule for
     * nesting fences), so the block can never be closed early.
     */
    private fun fence(
        language: String,
        content: String,
    ): String {
        val fence = "`".repeat(maxOf(MIN_FENCE_LENGTH, longestBacktickRun(content) + 1))
        val needsNewline = !content.endsWith('\n')
        return buildString(content.length + fence.length * 2 + language.length + 2) {
            append(fence).append(language).append('\n')
            append(content)
            if (needsNewline) append('\n')
            append(fence)
        }
    }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0
        var run = 0
        for (c in text) {
            if (c == '`') {
                run += 1
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return longest
    }

    // ------------------------------------------------------------------
    // Prose (Markdown / plain text)
    // ------------------------------------------------------------------

    private suspend fun importProse(
        displayName: String,
        text: String,
        personaId: PersonaId?,
    ): ImportResult {
        val (frontmatter, body) = splitFrontmatter(text)
        val frontmatterId = frontmatter.string(FrontmatterKeys.ID)?.takeIf { it.isNotBlank() }
        val title = deriveTitle(frontmatter, body, displayName)

        // bd skein-ddpt: never overwrite an existing document, regardless of
        // its kind. A frontmatter `id` that already names one is a
        // collision to report, not an update to perform — reuse the
        // frontmatter id for the new document only when nothing already
        // has it.
        val conflict = frontmatterId?.let { repository.getDocument(it) }
        // On a conflict, the parsed frontmatter's `id` key names the
        // *existing* document — it must not leak into the new document's
        // frontmatter, or `VaultRepository.createDocument`'s "no explicit
        // id? fall back to the frontmatter's id" rule would mint the new
        // document under the very id that just collided (`require(chosenId
        // !in documents)` then rejects it as a duplicate).
        val sourceFrontmatter =
            if (conflict == null) {
                frontmatter
            } else {
                JsonObject(frontmatter.filterKeys { it != FrontmatterKeys.ID })
            }

        val created =
            repository.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = title,
                    bodyMd = body,
                    personaId = personaId,
                    frontmatter = reconcileFrontmatter(sourceFrontmatter, DocumentKind.NOTE, title, existing = null),
                    id = if (conflict == null) frontmatterId else null,
                ),
            )
        return ImportResult(
            documentId = created.id,
            attachmentId = null,
            created = true,
            conflictWith = conflict?.id,
        )
    }

    /** See the file header for which canonical keys are kept, refreshed, or filled in. */
    private fun reconcileFrontmatter(
        parsed: JsonObject,
        kind: DocumentKind,
        title: String,
        existing: Document?,
    ): JsonObject {
        val nowIso = now().toString()
        val created =
            parsed[FrontmatterKeys.CREATED]
                ?: existing?.frontmatter?.get(FrontmatterKeys.CREATED)
                ?: JsonPrimitive(nowIso)
        return buildJsonObject {
            parsed.forEach { (key, value) -> put(key, value) }
            put(FrontmatterKeys.KIND, JsonPrimitive(kind.db))
            put(FrontmatterKeys.TITLE, JsonPrimitive(title))
            put(FrontmatterKeys.CREATED, created)
            put(FrontmatterKeys.UPDATED, JsonPrimitive(nowIso))
        }
    }

    private fun deriveTitle(
        frontmatter: JsonObject,
        body: String,
        displayName: String,
    ): String =
        frontmatter.string(FrontmatterKeys.TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstHeading(body)
            ?: titleFromDisplayName(displayName)

    /**
     * The text of the first level-one ATX heading (`# Title`, optionally
     * indented up to three spaces and optionally closed with ` #…`), or
     * `null`. Walks the body with `indexOf` so a heading-less 10 MB file
     * costs no allocation.
     */
    private fun firstHeading(body: String): String? {
        var lineStart = 0
        while (lineStart < body.length) {
            val newline = body.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) body.length else newline
            var i = lineStart
            while (i < lineEnd && i - lineStart < MAX_HEADING_INDENT && body[i] == ' ') i += 1
            if (body.startsWith("# ", i)) {
                val heading = stripClosingHashes(body.substring(i + 2, lineEnd).trim())
                if (heading.isNotEmpty()) return heading
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        return null
    }

    /** `Title ##` → `Title`; `C#` stays `C#` (a closing run must follow a space). */
    private fun stripClosingHashes(heading: String): String {
        val withoutHashes = heading.trimEnd('#')
        if (withoutHashes.length == heading.length) return heading
        return if (withoutHashes.isEmpty() || withoutHashes.last() == ' ') withoutHashes.trimEnd() else heading
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Decodes [input] as UTF-8 to EOF in one pass: a leading BOM is dropped,
     * malformed byte sequences become U+FFFD (the platform decoder's
     * replacement behaviour — tolerant, never throwing), and every `\r\n` or
     * lone `\r` becomes `\n`. Leaves [input] open; the caller owns it.
     */
    private fun readNormalized(input: InputStream): String {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val out = StringBuilder(maxOf(sizeHint(input), DECODE_BUFFER_CHARS))
        val buffer = CharArray(DECODE_BUFFER_CHARS)
        var atStart = true
        var pendingCarriageReturn = false
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            var from = 0
            if (atStart && read > 0) {
                if (buffer[0] == BOM) from = 1
                atStart = false
            }
            for (i in from until read) {
                when (val c = buffer[i]) {
                    '\r' -> {
                        out.append('\n')
                        pendingCarriageReturn = true
                    }
                    '\n' -> {
                        if (pendingCarriageReturn) pendingCarriageReturn = false else out.append('\n')
                    }
                    else -> {
                        pendingCarriageReturn = false
                        out.append(c)
                    }
                }
            }
        }
        return out.toString()
    }

    /** `available()` is only a hint (and may throw); it's exact for in-memory and plain file streams, which is all we need. */
    private fun sizeHint(input: InputStream): Int =
        try {
            input.available()
        } catch (_: IOException) {
            0
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    internal companion object {
        private const val UNTITLED: String = "Untitled"
        private const val MIN_FENCE_LENGTH: Int = 3
        private const val MAX_HEADING_INDENT: Int = 3
        private const val DECODE_BUFFER_CHARS: Int = 8 * 1024
        private const val DELIMITER: String = "---"
        private const val PDF_MIME_TYPE: String = "application/pdf"

        /** U+FEFF, spelled as a code point so no invisible character hides in this source file. */
        private const val BOM: Char = 0xFEFF.toChar()

        /**
         * Semantically identical to `Frontmatter.parse(text)` — same
         * delimiter rules, same unterminated-header fallback — but only the
         * header (found by scanning line starts with `indexOf`) is handed to
         * the codec, and the body is a single substring. `Frontmatter.parse`
         * splits the *entire* text into a line list first, which for a
         * 10 MB import is a second full copy plus one object per line.
         * Internal; exposed for `ImportTextTest`'s equivalence check.
         */
        internal fun splitFrontmatter(text: String): Pair<JsonObject, String> {
            if (text != DELIMITER && !text.startsWith("$DELIMITER\n")) return JsonObject(emptyMap()) to text
            var lineStart = DELIMITER.length + 1
            while (lineStart <= text.length) {
                val newline = text.indexOf('\n', lineStart)
                val lineEnd = if (newline < 0) text.length else newline
                if (lineEnd - lineStart == DELIMITER.length && text.startsWith(DELIMITER, lineStart)) {
                    val (frontmatter, _) = Frontmatter.parse(text.substring(0, lineEnd))
                    val body = if (newline < 0) "" else text.substring(newline + 1)
                    return frontmatter to body
                }
                if (newline < 0) break
                lineStart = newline + 1
            }
            return JsonObject(emptyMap()) to text
        }
    }
}
