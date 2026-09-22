// M0.5 contract file (`E0.I14`): lands the design plan §4.5 interfaces —
// `ExportService` and `ImportService` — plus the frontmatter wire format
// both services (and every downstream consumer) speak in.
//
// `:core:model` is pure Kotlin/JVM (no Android imports) so every process
// (`:app`, `:testing`, and the eventual `:core:export` / feature modules)
// can compile against the same contract. The only runtime types this file
// needs (`java.io.InputStream`/`OutputStream`) are JDK, not Android — no
// new `build.gradle.kts` dependency is required.
//
// `FrontmatterKeys` is the single source of truth for the YAML frontmatter
// key names (`id, kind, title, created, updated, persona, tags, source`)
// so `E2.I3` (UUIDv7 + frontmatter codec), `E2.I7` (`ImportService` impl),
// and `E2.I10` (Markdown export) agree on key names before any of them
// starts, per the plan's `E0.I14` description. `FrontmatterExamples.NOTE`
// pins the canonical example text so `docs/VAULT_FORMAT.md` (`E9.I5`) is
// authored against the same fixture this module's test asserts on, rather
// than an independently drifting copy.
//
// PDF export is Android-bound (`android.print.PrintManager`) and lives in
// `core/export` as `PdfExportService` (`E2.I11`, plan §4.5) — it is
// intentionally not part of this pure-Kotlin contract.

package us.aherrera.skein.core.model

import java.io.InputStream
import java.io.OutputStream

// -----------------------------------------------------------------------------
// Frontmatter wire format (plan §4.5 / spec §2.10)
// -----------------------------------------------------------------------------

/**
 * Canonical YAML frontmatter keys for every Markdown document Skein reads
 * or writes. Obsidian-compatible: an Obsidian vault opened read-only over
 * the same files understands `id`, `tags`, and any other scalar/array key
 * here even though it has no notion of `kind`/`persona`.
 *
 * These are wire-format keys (the string that appears before `:` in the
 * YAML block), not Kotlin property names — callers building or parsing
 * frontmatter must key off these constants rather than re-typing literals,
 * so a rename here is a compile error everywhere else.
 */
public object FrontmatterKeys {
    /** UUIDv7, lowercase 36-char RFC 9562. Mirrors `documents.id`. */
    public const val ID: String = "id"

    /** One of `DocumentKind.db` (`"note" | "chat" | "attachment" | "aiout"`). */
    public const val KIND: String = "kind"
    public const val TITLE: String = "title"

    /** ISO-8601 / RFC 3339 UTC timestamp (`Instant.toString()`), not epoch millis. */
    public const val CREATED: String = "created"
    public const val UPDATED: String = "updated"

    /** `Persona.name`, omitted entirely when the document has no persona. */
    public const val PERSONA: String = "persona"

    /** YAML flow sequence of lowercased tag names, e.g. `[project, idea]`. */
    public const val TAGS: String = "tags"

    /**
     * Present only on an `AIOUT`/imported-`ATTACHMENT`-derived `NOTE`: the
     * `DocId` of the attachment (PDF, image, …) this note's text was
     * extracted from. Absent on ordinary notes.
     */
    public const val SOURCE: String = "source"
}

/**
 * The canonical frontmatter example, pinned as a string constant so
 * `docs/VAULT_FORMAT.md` (`E9.I5`) can be authored against — and CI-tested
 * against — the exact same fixture text (see `FrontmatterExamplesTest`)
 * instead of an independently-typed copy that can silently drift from the
 * real `FrontmatterKeys` set.
 */
public object FrontmatterExamples {
    /** A single `NOTE` document, every key from `FrontmatterKeys` except the optional [FrontmatterKeys.SOURCE]. */
    public val NOTE: String =
        """
        ---
        id: 018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b
        kind: note
        title: Example Note
        created: 2026-09-20T12:00:00Z
        updated: 2026-09-20T12:00:00Z
        persona: default
        tags: [example, fixture]
        ---
        This is the body of the canonical example note. `docs/VAULT_FORMAT.md`
        (`E9.I5`) reproduces this exact text as its worked frontmatter example.
        """.trimIndent()
}

// -----------------------------------------------------------------------------
// ExportService (plan §4.5)
// -----------------------------------------------------------------------------

/**
 * Renders vault content to the caller-supplied stream. Implementations do
 * not choose or own the destination — the caller (e.g. a
 * `DocumentsProvider`-served `OutputStream` opened by the app's export UI)
 * is responsible for where the bytes end up; see
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §4 (DocumentsProvider is the
 * only export surface in v1 — no `FileProvider`).
 */
public interface ExportService {
    /** YAML frontmatter ([FrontmatterKeys]) + body. Obsidian-compatible. */
    public suspend fun exportMarkdown(
        docId: DocId,
        out: OutputStream,
    )

    /** Whole vault: `<title>.md` per document, `attachments/<id>.<ext>` blobs, `.skein/manifest.json`. */
    public suspend fun exportVaultZip(
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    )

    /** Minimal OOXML writer; when [template] is non-null its styles.xml and section properties are reused. */
    public suspend fun exportDocx(
        docId: DocId,
        out: OutputStream,
        template: InputStream? = null,
    )
}

// -----------------------------------------------------------------------------
// ImportService (plan §4.5)
// -----------------------------------------------------------------------------

public data class ImportResult(
    val documentId: DocId,
    val attachmentId: DocId?,
    val created: Boolean,
    /**
     * Set when the imported content's frontmatter carried an
     * [FrontmatterKeys.ID] that already names a document in the vault.
     * [documentId] is always a *different*, freshly created document in
     * that case (see [ImportService.importText]) — this field exists so
     * the caller can tell the user "a document with this id already
     * exists" rather than the import silently landing as an unrelated
     * new note. `null` when the import created or would-create under an
     * id nothing else already uses.
     */
    val conflictWith: DocId? = null,
)

public interface ImportService {
    /**
     * Any `text/…` MIME type, `text/markdown`, or source code.
     *
     * Never overwrites an existing document, regardless of its
     * [DocumentKind] (`NOTE`, `CHAT`, `AIOUT`, or `ATTACHMENT`). Document
     * ids are UUIDv7s that appear in every export and every
     * wikilink-resolved edge, so a shared or imported file naming one in
     * its frontmatter [FrontmatterKeys.ID] is not proof the user intends
     * to replace that document — a crafted file could otherwise replace
     * an existing note's body/frontmatter or turn a chat transcript into
     * arbitrary text with no confirmation. If the frontmatter `id`
     * already names a document, this always creates a *new* document
     * (under a freshly minted id, `created` is `true`) and reports the
     * collision via [ImportResult.conflictWith] so the caller can surface
     * it to the user. Otherwise creates a new document, reusing the
     * frontmatter `id` as that document's id when present so a file
     * exported from another Skein vault keeps its identity.
     */
    public suspend fun importText(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult

    /** Stores the PDF as an attachment and creates a NOTE with the extracted text and `source:` frontmatter. */
    public suspend fun importPdf(
        displayName: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult

    /** Stores the image as an attachment; if a VISION model is loaded, creates an AIOUT description linked with a CITE edge. */
    public suspend fun importImage(
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult
}
