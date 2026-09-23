package app.skein.core.vault.testutil

import app.skein.core.vault.db.migrations.MigrationStatementSplitter

/**
 * Splits migration SQL on the `--;` sentinel used by `001_initial.sql`
 * (see file header) — delegates to the production [MigrationStatementSplitter]
 * to ensure tests load schema exactly as production does.
 *
 * The sentinel is only recognized when `--;` is immediately followed by
 * end-of-line or end-of-input, so prose that merely *mentions* the sentinel
 * inside a `--` comment is never misread as a real separator.
 *
 * Trailing whitespace and empty statements are dropped.
 */
internal fun splitMigrationStatements(sql: String): List<String> {
    val raw = MigrationStatementSplitter.split(sql)
    val cleaned =
        raw.map { chunk ->
            chunk
                .lineSequence()
                .map { it.trimEnd() }
                .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("--") }
                .joinToString(separator = "\n")
                .trim()
                .removeSuffix(";")
                .trim()
        }
    return cleaned.filter { it.isNotEmpty() }
}
