// `E2.I10` (bd `skein-90d`): filename sanitization + collision
// disambiguation for `ExportServiceImpl.exportVaultZip`'s `<safe title>.md`
// zip entries (plan `E2.I10`, Transfer.kt's `ExportService.exportVaultZip`
// KDoc). Kept as pure, stateless string functions so they're trivially
// testable without a `VaultRepository` or any I/O.

package app.skein.core.vault.export

/**
 * Turns an arbitrary document `title` into a filesystem/zip-entry-safe
 * base name, and disambiguates repeated base names with a `" (2)"`,
 * `" (3)"`, ... suffix (matching common "Save As" conventions and the
 * plan's `E2.I10` description: "collision suffix ` (2)`").
 */
public object SafeFileName {
    /**
     * Strips path separators (`/`, `\`) and control characters entirely,
     * and collapses any run of one or more non-ASCII characters into a
     * single `_`. Leading/trailing whitespace left behind by stripped
     * characters is trimmed. Falls back to `"untitled"` when nothing
     * survives (e.g. a title that was only path separators/control chars).
     */
    public fun sanitize(title: String): String {
        val out = StringBuilder(title.length)
        var pendingUnderscore = false
        for (ch in title) {
            when {
                ch == '/' || ch == '\\' || Character.isISOControl(ch) -> {
                    pendingUnderscore = false
                }
                ch.code > MAX_ASCII_CODE -> {
                    if (!pendingUnderscore) {
                        out.append('_')
                        pendingUnderscore = true
                    }
                }
                else -> {
                    out.append(ch)
                    pendingUnderscore = false
                }
            }
        }
        val trimmed = out.toString().trim()
        return trimmed.ifEmpty { DEFAULT_NAME }
    }

    /**
     * Returns `"$baseName.$extension"`, or `"$baseName (n).$extension"` for
     * the smallest `n >= 2` not already present in [used]. The returned
     * name is added to [used] before returning, so repeated calls against
     * the same [used] set never collide with each other or with a name
     * this function already returned.
     */
    public fun uniqueName(
        baseName: String,
        extension: String,
        used: MutableSet<String>,
    ): String {
        val plain = "$baseName.$extension"
        if (used.add(plain)) return plain

        var suffix = 2
        while (true) {
            val candidate = "$baseName ($suffix).$extension"
            if (used.add(candidate)) return candidate
            suffix += 1
        }
    }

    private const val MAX_ASCII_CODE: Int = 127
    private const val DEFAULT_NAME: String = "untitled"
}
