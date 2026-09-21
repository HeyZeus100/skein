// `E2.I8` (bd `skein-qdo`) test support: builds tiny PDFs in-process via
// pdfbox-android itself, so `ImportPdfTest`/`ImportPdfMemorySmokeTest` need
// no binary fixtures checked into the repo. Shared across both files.

package app.skein.core.vault.transfer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream

/**
 * Builds a PDF with one page per entry of [pages]. [title] sets the `Title`
 * document-information entry when non-null (else the PDF has no title,
 * exercising `PdfImporter`'s fallback-to-filename path). [compress] toggles
 * the FlateDecode filter pdfbox-android applies to content streams by
 * default (`true`) — passing `false` exercises the uncompressed path,
 * proving `PdfImporter` doesn't rely on compression being present.
 * [ownerPassword]/[userPassword], when both non-null, encrypt the result
 * with `StandardProtectionPolicy` so `PDDocument.load` (no password
 * supplied) throws `InvalidPasswordException` — the "encrypted" fixture.
 */
internal fun buildPdf(
    title: String? = null,
    compress: Boolean = true,
    ownerPassword: String? = null,
    userPassword: String? = null,
    pages: List<(PDPageContentStream) -> Unit>,
): ByteArray {
    val out = ByteArrayOutputStream()
    PDDocument().use { document ->
        title?.let { document.documentInformation.title = it }
        pages.forEach { writePage ->
            val page = PDPage()
            document.addPage(page)
            val mode = PDPageContentStream.AppendMode.OVERWRITE
            PDPageContentStream(document, page, mode, compress, false).use { contentStream ->
                writePage(contentStream)
            }
        }
        if (ownerPassword != null && userPassword != null) {
            document.protect(StandardProtectionPolicy(ownerPassword, userPassword, AccessPermission()))
        }
        document.save(out)
    }
    return out.toByteArray()
}

/** A page whose content stream shows [text] with the plain `Tj` operator (no kerning). */
internal fun textPage(text: String): (PDPageContentStream) -> Unit =
    { contentStream ->
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 12f)
        contentStream.newLineAtOffset(50f, 700f)
        contentStream.showText(text)
        contentStream.endText()
    }

/**
 * A page whose content stream shows [runs] with the `TJ` operator — a flat
 * list alternating text runs and kerning adjustments (in thousandths of a
 * text-space unit; PDF spec §9.4.3: a *positive* number moves the next
 * glyph left, i.e. tighter, not a gap), e.g. `"Hel", 120f, "lo"` for a
 * kerned "Hello" split between "Hel" and "lo".
 */
internal fun kernedTextPage(vararg runs: Any): (PDPageContentStream) -> Unit =
    { contentStream ->
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 12f)
        contentStream.newLineAtOffset(50f, 700f)
        contentStream.showTextWithPositioning(arrayOf(*runs))
        contentStream.endText()
    }

/** A page with no text operators at all — the "scanned image" no-text-layer fixture. */
internal fun blankPage(): (PDPageContentStream) -> Unit = { _ -> }
