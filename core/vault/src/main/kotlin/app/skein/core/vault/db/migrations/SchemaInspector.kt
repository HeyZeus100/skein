package app.skein.core.vault.db.migrations

import androidx.sqlite.SQLiteConnection

/**
 * Read-only introspection over `sqlite_master` / `PRAGMA user_version`,
 * used by tests to assert the schema a [Migrator] run produced (`E2.I2` /
 * skein-5my) without each test hand-rolling the same queries.
 *
 * Does not own [conn] — callers open/close the connection themselves
 * (typically the same connection a [Migrator.migrate] call used).
 */
public class SchemaInspector(
    private val conn: SQLiteConnection,
) {
    /** Ordinary and virtual table names (`sqlite_master.type = 'table'`). */
    public fun tables(): List<String> = namesOfType("table")

    /**
     * Virtual table names only — a subset of [tables]. Virtual tables
     * (`chunks_fts`, `chunks_vec`) are recorded in `sqlite_master` with
     * `type = 'table'` same as ordinary tables; their `CREATE VIRTUAL
     * TABLE` DDL text is how they're told apart.
     */
    public fun virtualTables(): List<String> =
        queryNames(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND sql LIKE '%VIRTUAL TABLE%'",
        )

    /** Trigger names (`sqlite_master.type = 'trigger'`). */
    public fun triggers(): List<String> = namesOfType("trigger")

    /** Index names (`sqlite_master.type = 'index'`), including auto-indexes. */
    public fun indexes(): List<String> = namesOfType("index")

    /** Current `PRAGMA user_version`. */
    public fun userVersion(): Int {
        conn.prepare("PRAGMA user_version;").use { stmt ->
            check(stmt.step()) { "PRAGMA user_version returned no row" }
            return stmt.getLong(0).toInt()
        }
    }

    private fun namesOfType(type: String): List<String> =
        queryNames("SELECT name FROM sqlite_master WHERE type = '$type'")

    private fun queryNames(sql: String): List<String> {
        val names = mutableListOf<String>()
        conn.prepare(sql).use { stmt ->
            while (stmt.step()) {
                names += stmt.getText(0)
            }
        }
        return names
    }
}
