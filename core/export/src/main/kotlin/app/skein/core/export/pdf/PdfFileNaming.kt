// E2.I11 (bd skein-80m): the PDF's display/staged file name, built from
// `SafeFileName` (`E2.I10`, `:core:vault`) so a document titled e.g.
// "Q3 Report/Draft" produces the same kind of sanitized name the Markdown
// zip exporter already does, rather than a second ad-hoc sanitizer.
//
// No `android.*` import — plain-JUnit-testable alongside the `layout`
// package despite living next to the Android-specific PDF classes.

package app.skein.core.export.pdf

import app.skein.core.vault.export.SafeFileName

public object PdfFileNaming {
    private const val EXTENSION = "pdf"

    /** `"<sanitized title>.pdf"` — used both for `PrintDocumentInfo`'s display name and the staged file on disk (prefixed with a stage id by [PdfStaging] to avoid collisions between concurrent exports). */
    public fun fileName(title: String): String = "${SafeFileName.sanitize(title)}.$EXTENSION"
}
