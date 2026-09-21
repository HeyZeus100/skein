// E2.I12 (bd skein-jq8): hand-rolled minimal OOXML (ECMA-376) writer —
// `java.util.zip` + string-built XML (`DocxParts`/`XmlEscaper`), no
// third-party DOCX/XML library. `ExportServiceImpl.exportDocx`
// (`:core:vault`, skein-90d) delegates here.
//
// Package placement: this lives in `:core:vault` (not `:core:export`, where
// the plan's file list originally put it) because `ExportServiceImpl` lives
// in `:core:vault`, and `:core:export` already depends on `:core:vault`
// (`SafeFileName` reuse) — `:core:vault` depending back on `:core:export`
// would be a module cycle. `MarkdownFlattener`/`PrintBlock` moved to
// `:core:markdown` (pure Kotlin/JVM, already a dependency of both modules)
// so both the PDF (`:core:export`) and DOCX (here) writers can share them
// without that cycle. See `PrintBlock.kt`'s header comment in
// `:core:markdown` for the full rationale.
//
// Like `ExportServiceImpl.exportVaultZip` (skein-90d), output goes straight
// to the caller's `OutputStream` — no local plaintext staging (see
// `docs/design/export-plaintext-lifetime.md`) — and every `ZipEntry` gets
// the same fixed DOS-epoch-floor timestamp that file already uses, so two
// exports of the same document are byte-identical (bd `skein-jq8`
// acceptance criterion). `ZipOutputStream`'s default DEFLATE compression is
// itself a deterministic function of the input bytes for a fixed JDK/zlib
// version, matching that file's existing determinism argument.

package app.skein.core.vault.export.docx

import app.skein.core.markdown.ast.SkeinDocument
import app.skein.core.markdown.layout.MarkdownFlattener
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

public object DocxWriter {
    /** 1980-01-01T00:00:00 UTC epoch millis — same DOS-date floor `ExportServiceImpl.exportVaultZip` uses (skein-90d). */
    private const val FIXED_ZIP_TIME_MILLIS: Long = 315_532_800_000L

    /**
     * Renders [document] (the `:core:markdown` render tree, `E7.I2`) as a
     * `.docx` package to [out]. When [template] is non-null, its
     * `styles.xml` (and `settings.xml`/section properties, when present)
     * are reused instead of this writer's own defaults — see
     * `DocxTemplate`'s header comment for exactly what is and isn't
     * carried over. [template] is not closed by this function; the caller
     * owns its lifecycle, matching [out].
     *
     * @throws DocxTemplateException if [template] is non-null and is not a
     *   readable zip, or is a zip missing `word/document.xml`/`styles.xml`.
     */
    public fun write(
        title: String,
        document: SkeinDocument,
        out: OutputStream,
        template: InputStream? = null,
    ) {
        val hyperlinks = mutableListOf<HyperlinkRel>()
        val bodyXml = DocxParts.bodyXml(MarkdownFlattener.flatten(document), hyperlinks)

        val templateParts = template?.let(DocxTemplate::read)
        val stylesXmlBytes = templateParts?.stylesXmlBytes ?: DocxParts.defaultStylesXml().toByteArray(Charsets.UTF_8)
        val settingsXmlBytes = templateParts?.settingsXmlBytes
        val sectPrXml = templateParts?.sectPrXml ?: DocxParts.defaultSectPrXml()

        val parts = linkedMapOf<String, ByteArray>()
        parts[DocxParts.CONTENT_TYPES_PATH] =
            DocxParts.contentTypesXml(includeSettings = settingsXmlBytes != null).toUtf8()
        parts[DocxParts.ROOT_RELS_PATH] = DocxParts.rootRelsXml().toUtf8()
        parts[DocxParts.DOCUMENT_PATH] = DocxParts.documentXml(bodyXml, sectPrXml).toUtf8()
        parts[DocxParts.STYLES_PATH] = stylesXmlBytes
        parts[DocxParts.NUMBERING_PATH] = DocxParts.defaultNumberingXml().toUtf8()
        parts[DocxParts.DOCUMENT_RELS_PATH] =
            DocxParts.documentRelsXml(hyperlinks, includeSettings = settingsXmlBytes != null).toUtf8()
        parts[DocxParts.CORE_PATH] = DocxParts.coreXml(title).toUtf8()
        if (settingsXmlBytes != null) parts[DocxParts.SETTINGS_PATH] = settingsXmlBytes

        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in parts) {
                val entry = ZipEntry(path)
                entry.time = FIXED_ZIP_TIME_MILLIS
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        out.flush()
    }

    private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8)
}
