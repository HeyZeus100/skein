// Text-level schema check over the whole migration set.
//
// The JVM suite runs the migrations against `FakeSkeinSQLiteNative`, which
// never executes DDL, so a migration that re-adds a column an earlier
// migration already created passes every JVM test and then fails on every
// real device with "duplicate column name" — which is exactly what
// `009_model_origin.sql` did on 2026-09-23 (emulator run 35851243301: every
// fresh vault open failed, `context_length`/`companions`/`license_spdx` had
// existed since `001_initial.sql`). This test replays the DDL that matters
// for that failure class — `CREATE TABLE` column lists, `ALTER TABLE … ADD
// COLUMN`, `DROP TABLE` — in `INDEX.txt` order and fails on the first
// repeat, so the mistake is caught before a device ever sees it.

package app.skein.core.vault.db.migrations

import org.junit.Assert.assertTrue
import org.junit.Test

public class MigrationColumnUniquenessTest {
    private val createTable =
        Regex("""(?is)\bCREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*(?:USING\s+\w+\s*)?\((.*)\)""")
    private val addColumn = Regex("""(?i)\bALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)""")
    private val dropTable = Regex("""(?i)\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)""")
    private val constraintKeywords = setOf("primary", "foreign", "unique", "check", "constraint")

    @Test
    public fun `no migration adds a column that an earlier statement already created`() {
        val columns = mutableMapOf<String, MutableSet<String>>()
        for (file in migrationFiles()) {
            val sql = resource("migrations/$file")
            for (statement in MigrationStatementSplitter.split(sql)) {
                val body = stripCommentLines(statement)
                createTable.find(body)?.let { m ->
                    val table = m.groupValues[1].lowercase()
                    val set = columns.getOrPut(table) { mutableSetOf() }
                    for (col in columnNames(m.groupValues[2])) {
                        assertTrue("$file: CREATE TABLE $table repeats column $col", set.add(col))
                    }
                }
                addColumn.find(body)?.let { m ->
                    val table = m.groupValues[1].lowercase()
                    val col = m.groupValues[2].lowercase()
                    val set =
                        columns[table]
                            ?: error(
                                "$file: ALTER TABLE $table ADD COLUMN $col but no CREATE TABLE $table precedes it in INDEX.txt order",
                            )
                    assertTrue(
                        "$file: ALTER TABLE $table ADD COLUMN $col — that column already exists " +
                            "(an earlier migration created it); a real SQLite refuses with " +
                            "'duplicate column name' and every vault open fails",
                        set.add(col),
                    )
                }
                dropTable.find(body)?.let { m -> columns.remove(m.groupValues[1].lowercase()) }
            }
        }
        assertTrue("expected at least the models table to be tracked", columns.containsKey("models"))
    }

    private fun columnNames(columnList: String): List<String> =
        columnList
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("--") }
            .mapNotNull { line ->
                Regex("""^(\w+)""")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            }.filter { it !in constraintKeywords }

    private fun stripCommentLines(statement: String): String =
        statement.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

    private fun migrationFiles(): List<String> =
        resource("migrations/INDEX.txt")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader()
            .use { it.readText() }
}
