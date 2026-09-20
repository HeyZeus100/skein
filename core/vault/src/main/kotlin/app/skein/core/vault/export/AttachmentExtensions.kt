// `E2.I10` (bd `skein-90d`): mime-type -> file-extension mapping for the
// `attachments/<id>.<ext>` entries `ExportServiceImpl.exportVaultZip`
// writes. Deliberately not `android.webkit.MimeTypeMap` — this module's
// JVM unit tests (`VaultZipTest`) run with no Android runtime, and the
// mapping needed here is small and stable enough not to warrant pulling in
// a platform API.

package app.skein.core.vault.export

internal object AttachmentExtensions {
    /** Common types get a conventional extension even where it differs from the literal subtype (e.g. `jpeg` -> `jpg`). */
    private val KNOWN: Map<String, String> =
        mapOf(
            "application/pdf" to "pdf",
            "application/json" to "json",
            "application/zip" to "zip",
            "application/octet-stream" to "bin",
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/svg+xml" to "svg",
            "text/plain" to "txt",
            "text/markdown" to "md",
            "text/html" to "html",
            "text/csv" to "csv",
        )

    /**
     * The known extension for [mimeType], or one derived from its subtype
     * (e.g. `application/x-foo` -> `foo`), or `"bin"` when nothing usable
     * can be derived (unknown/empty subtype).
     */
    fun forMimeType(mimeType: String): String {
        KNOWN[mimeType.lowercase()]?.let { return it }

        val subtype = mimeType.substringAfter('/', missingDelimiterValue = "").substringBefore('+')
        val cleaned = subtype.filter { it.isLetterOrDigit() }.lowercase()
        return cleaned.ifEmpty { FALLBACK_EXTENSION }
    }

    private const val FALLBACK_EXTENSION: String = "bin"
}
