// `E2.I8` (bd `skein-qdo`): PDF text extraction backing `ImportServiceImpl.importPdf`.
//
// Mechanism (plan §4.5 E2.I8, verbatim): extract text with
// `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0) `PDFTextStripper`,
// page by page, joining page breaks with `\n\n---\n\n`. This is the
// plan-pinned dependency — not a hand-rolled parser — because it is
// explicitly named and pinned in `gradle/libs.versions.toml` (already
// present before this bead) and passes both supply-chain guards:
//   - License: Apache-2.0, on `tools/licenses/allowlist.txt`. Its only
//     transitives (`org.bouncycastle:bcprov/bcpkix/bcutil-jdk15to18`,
//     pulled in for encryption support) resolve to the `Bouncy-Castle`
//     SPDX id, also allowlisted.
//   - Dependency guard: `com.tom-roush` is not in `DependencyGuardTask.BANNED_GROUPS`
//     (Play Services / Firebase / Play Core / ML Kit).
// A hand-rolled minimal extractor (the fallback this bead's brief describes
// for an unpinned/failing library) is therefore not used.
//
// ## Resource loading
//
// pdfbox-android ships its AFM font-metric tables, the Adobe glyph list,
// predefined CMaps, and a fallback TTF as `assets/com/tom_roush/...` inside
// its AAR — Android `assets`, not JVM classpath resources (verified against
// the library's own source: `Standard14Fonts`, `GlyphList`,
// `LegacyPDFStreamEngine`, `PDFTextStripper`, `CMapParser`, `FontMapperImpl`,
// and `OpenTypeScript` all gate on `PDFBoxResourceLoader.isReady()` before
// falling back to `Class.getResourceAsStream`, and that fallback cannot see
// `assets/` entries). Real text extraction therefore requires
// [PDFBoxResourceLoader.init] to have run with a [android.content.Context]
// whose `AssetManager` sees the merged AAR assets — on-device this is any
// app `Context`; in tests it is Robolectric with
// `testOptions.unitTests.isIncludeAndroidResources = true` (see this
// module's `build.gradle.kts`).
//
// [ImportServiceImpl] is constructed with `context: Context? = null` for
// backward compatibility with call sites that predate this bead (existing
// unit/instrumented tests keep compiling unchanged); production wiring
// (`DeviceVaultOpener`, threaded from `VaultServices.forDevice`) supplies
// the real app context. Without one, [extract] still never throws — parsing
// bytes whose fonts need asset resources will fail with an `IOException`
// from the pdfbox-android call chain (no asset manager to fall back to),
// which [extract] treats exactly like a malformed/encrypted PDF: an
// attachment is still stored, and the note gets the "no text layer" notice
// rather than a crash.

package app.skein.core.vault.transfer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Idempotent wrapper around `PDFBoxResourceLoader.init` (bd `skein-qdo` AC:
 * "`PDFBoxResourceLoader.init(context)` called once"). The underlying call
 * is itself harmless to repeat (it just re-assigns the same `AssetManager`),
 * but this guard means a caller never touches [context] — or the real
 * `PDFBoxResourceLoader` — more than once per process, which is what
 * [PdfResourceLoaderTest] exercises with a counting fake context.
 */
internal object PdfResourceLoader {
    @Volatile
    private var initialized: Boolean = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PDFBoxResourceLoader.init(context)
            initialized = true
        }
    }

    /** Test-only: lets [PdfResourceLoaderTest] observe a fresh first call. */
    internal fun resetForTest() {
        initialized = false
    }
}

/** The outcome of attempting to read a PDF's text layer and metadata title. */
internal data class PdfExtraction(
    /** Page texts joined by [PdfImporter.PAGE_BREAK], or `null` when there is no usable text layer. */
    val text: String?,
    /** The PDF's `Title` document-information entry, trimmed and non-blank, or `null`. */
    val title: String?,
)

/**
 * Extracts text and metadata from a whole-buffered PDF. [bytes] is read
 * once by `PDDocument.load` (pdfbox-android has no true streaming parser —
 * a PDF's xref/trailer requires random access — so the file is buffered
 * once as a `ByteArray`, exactly as `ImportServiceImpl.readNormalized`
 * buffers a text import once as a `String`); the concern the memory smoke
 * test guards is a *second*, redundant materialization on top of that (e.g.
 * building one big string via `PDFTextStripper` in a single call and then
 * copying it again while inserting page breaks) — [extract] instead appends
 * each page's text directly into one [StringBuilder].
 */
internal object PdfImporter {
    const val PAGE_BREAK: String = "\n\n---\n\n"

    fun extract(
        bytes: ByteArray,
        context: Context?,
    ): PdfExtraction {
        context?.let(PdfResourceLoader::ensureInitialized)
        return try {
            PDDocument.load(bytes).use { document ->
                val title =
                    document.documentInformation
                        ?.title
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                val pageCount = document.numberOfPages
                if (pageCount == 0) return PdfExtraction(text = null, title = title)

                val stripper = PDFTextStripper()
                val body = StringBuilder()
                for (pageNumber in 1..pageCount) {
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    val pageText = stripper.getText(document).trim('\n')
                    if (pageNumber > 1) body.append(PAGE_BREAK)
                    body.append(pageText)
                }
                PdfExtraction(text = body.toString().ifBlank { null }, title = title)
            }
        } catch (_: Exception) {
            // Deliberately broad (never `Error`/`OutOfMemoryError`, which
            // this catch does not touch — and `extract` makes no suspend
            // calls, so no `CancellationException` can arise here either):
            // `IOException` covers `InvalidPasswordException` (encrypted —
            // the bd description's "encrypted" case) and any
            // malformed/truncated PDF structure pdfbox-android refuses to
            // parse, but a PDF parser fed adversarial or merely corrupt
            // input is well known to also surface
            // `IllegalArgumentException`/`ArrayIndexOutOfBoundsException`/
            // `NullPointerException` from deep in its content-stream and
            // font codecs (including, unready, `PDFBoxResourceLoader.getStream`
            // itself — see this file's header). Every one of those means
            // "could not extract text", never a crash out of `importPdf`:
            // the bd description is explicit that encrypted/scanned/malformed
            // PDFs "produce an attachment plus a NOTE containing a one-line
            // notice, not an error."
            PdfExtraction(text = null, title = null)
        }
    }
}
