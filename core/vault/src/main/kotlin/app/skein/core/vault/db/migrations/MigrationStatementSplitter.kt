package app.skein.core.vault.db.migrations

/**
 * Splits a migration file's SQL text into individual top-level statements
 * on the `--;` sentinel (`E2.I2` / skein-5my) — NOT on a bare `;`.
 *
 * A bare `;` can legitimately appear where it must NOT end a statement:
 *  - inside a multi-line trigger body (`CREATE TRIGGER ... BEGIN ... END;`
 *    — `001_initial.sql`'s FTS5-sync and ingest-queue triggers all have
 *    an internal `;` terminating each inner statement before `END;`)
 *  - inside a string literal (e.g. a default value or a WHEN clause).
 *
 * Splitting only on `--;` sidesteps both cases entirely: the caller
 * decides where statements end by placing the sentinel there, so this
 * splitter never has to parse trigger bodies or string-literal quoting
 * itself.
 *
 * The sentinel is only recognized when `--;` is immediately followed by
 * end-of-line (any trailing spaces/tabs allowed) or end-of-input. That
 * keeps ordinary prose that merely *mentions* the sentinel inside a `--`
 * comment — for example this file's own doc comment, or the header
 * comment in `001_initial.sql` that documents the convention — from
 * being misread as a real separator: such mentions are never the last
 * thing on their line.
 */
internal object MigrationStatementSplitter {
    private val SENTINEL = Regex("--;[ \t]*(?:\r\n|\r|\n|\\z)")

    /**
     * Returns the trimmed, non-empty statements in [sql], in file order.
     * A trailing sentinel (or none at all on the last statement) does not
     * produce a spurious empty final entry.
     */
    internal fun split(sql: String): List<String> =
        SENTINEL
            .split(sql)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
