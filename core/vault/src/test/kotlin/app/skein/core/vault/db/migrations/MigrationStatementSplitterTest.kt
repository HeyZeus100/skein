package app.skein.core.vault.db.migrations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-JVM unit tests for [MigrationStatementSplitter] — no driver, no
 * `.so`, pure string processing (`E2.I2` / skein-5my).
 */
class MigrationStatementSplitterTest {
    @Test
    fun `splits two statements separated by the sentinel`() {
        val sql = "CREATE TABLE a(x);--;\nCREATE TABLE b(y);--;\n"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).containsExactly("CREATE TABLE a(x);", "CREATE TABLE b(y);").inOrder()
    }

    @Test
    fun `single statement with no sentinel at all is returned whole`() {
        val sql = "CREATE TABLE a(x);"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).containsExactly("CREATE TABLE a(x);")
    }

    @Test
    fun `trailing sentinel does not produce a spurious empty final statement`() {
        val sql = "CREATE TABLE a(x);--;\n"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).containsExactly("CREATE TABLE a(x);")
    }

    @Test
    fun `blank lines and surrounding whitespace around a statement are trimmed`() {
        val sql = "\n\n  CREATE TABLE a(x);  \n--;\n\nCREATE TABLE b(y);--;"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).containsExactly("CREATE TABLE a(x);", "CREATE TABLE b(y);").inOrder()
    }

    @Test
    fun `internal semicolon inside a multi-line trigger body survives as one statement`() {
        val sql =
            """
            CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
              INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
              DELETE FROM chunks_vec WHERE rowid = new.id;
            END;--;
            CREATE TABLE next(x INT);--;
            """.trimIndent()

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).hasSize(2)
        assertThat(statements[0]).contains("INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);")
        assertThat(statements[0]).contains("DELETE FROM chunks_vec WHERE rowid = new.id;")
        assertThat(statements[0]).endsWith("END;")
    }

    @Test
    fun `semicolon inside a string literal does not split the statement`() {
        val sql = "INSERT INTO t(x) VALUES ('a;b;c');--;\nCREATE TABLE u(y INT);--;\n"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements)
            .containsExactly(
                "INSERT INTO t(x) VALUES ('a;b;c');",
                "CREATE TABLE u(y INT);",
            ).inOrder()
    }

    @Test
    fun `a comment merely mentioning the sentinel mid-sentence is not treated as a separator`() {
        // Regression for 001_initial.sql's own header comment, which
        // documents the `--;` convention in prose ("... MUST use the
        // `--;` sentinel convention ...") without it being the last thing
        // on the line.
        val sql =
            """
            -- Statements MUST use the `--;` sentinel convention when needed.
            CREATE TABLE a(x);--;
            """.trimIndent()

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).hasSize(1)
        assertThat(statements[0]).contains("-- Statements MUST use the `--;` sentinel convention when needed.")
        assertThat(statements[0]).contains("CREATE TABLE a(x);")
    }

    @Test
    fun `sentinel followed by trailing spaces before the newline is still recognized`() {
        val sql = "CREATE TABLE a(x);--;   \nCREATE TABLE b(y);--;"

        val statements = MigrationStatementSplitter.split(sql)

        assertThat(statements).containsExactly("CREATE TABLE a(x);", "CREATE TABLE b(y);").inOrder()
    }

    @Test
    fun `empty input produces no statements`() {
        val statements = MigrationStatementSplitter.split("")

        assertThat(statements).isEmpty()
    }
}
