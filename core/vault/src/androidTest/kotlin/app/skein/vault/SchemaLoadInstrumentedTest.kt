// Instrumented smoke test (`E0.I11`): opens an encrypted in-memory DB via
// `SkeinSQLiteDriver` (skein-e2ki) and executes `001_initial.sql`
// statement-by-statement, asserting none throws. If a statement fails on
// a fresh in-memory SQLCipher DB, the failure is a spec/DDL bug (the DDL
// is authored to match spec §5 + plan §4.9 with driver-agnostic syntax).
//
// Follow-up: skein-k3b2 tracks provisioning an API 35 emulator inside CI
// so `connectedFossDebugAndroidTest` can run headless. This class is
// compiled in the ordinary Gradle `check` (which does not run
// instrumented tests) so a broken statement surfaces as a compile-time
// error even without the device queue.

package app.skein.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class SchemaLoadInstrumentedTest {
    @Test
    public fun test001InitialSqlLoadsStatementByStatementOnTheSpikeDriver() {
        val sql =
            requireNotNull(
                SchemaLoadInstrumentedTest::class.java.classLoader
                    ?.getResourceAsStream("migrations/001_initial.sql"),
            ) {
                "migrations/001_initial.sql not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }

        val statements = splitSqlRespectingTriggers(sql)
        require(statements.isNotEmpty()) { "expected at least one statement in 001_initial.sql" }

        SkeinSQLiteDriver().openWithKey(":memory:", passphrase = null).use { raw ->
            val conn = raw as SkeinSQLiteConnection
            for ((index, stmt) in statements.withIndex()) {
                try {
                    conn.prepare(stmt).use { it.step() }
                } catch (t: Throwable) {
                    throw AssertionError(
                        "statement #${index + 1} failed to execute:\n$stmt",
                        t,
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * Split [sql] on top-level `;` boundaries while respecting SQLite
         * `BEGIN ... END` blocks (used by our FTS5 sync triggers and the
         * ingest-queue triggers). Comment lines (`-- …`) and blank lines
         * are stripped before splitting so an accidental trailing `;` in a
         * comment does not truncate the file.
         */
        fun splitSqlRespectingTriggers(sql: String): List<String> {
            val cleaned =
                sql
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("--") }
                    .joinToString(separator = "\n")

            val out = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = 0
            while (i < cleaned.length) {
                val c = cleaned[i]
                // Case-insensitive word peek for BEGIN / END. We only bump
                // the block-depth counter at a word boundary — a substring
                // like "PENDING" or "APPENDIX" must not count as BEGIN.
                if (c.isLetter() && (i == 0 || !cleaned[i - 1].isLetterOrDigit())) {
                    val remaining = cleaned.length - i
                    if (remaining >= BEGIN.length &&
                        cleaned.regionMatches(i, BEGIN, 0, BEGIN.length, ignoreCase = true) &&
                        (i + BEGIN.length == cleaned.length || !cleaned[i + BEGIN.length].isLetterOrDigit())
                    ) {
                        depth++
                        current.append(cleaned, i, i + BEGIN.length)
                        i += BEGIN.length
                        continue
                    }
                    if (remaining >= END.length &&
                        cleaned.regionMatches(i, END, 0, END.length, ignoreCase = true) &&
                        (i + END.length == cleaned.length || !cleaned[i + END.length].isLetterOrDigit())
                    ) {
                        if (depth > 0) depth--
                        current.append(cleaned, i, i + END.length)
                        i += END.length
                        continue
                    }
                }
                if (c == ';' && depth == 0) {
                    val stmt = current.toString().trim()
                    if (isStatement(stmt)) out += stmt
                    current.setLength(0)
                    i++
                    continue
                }
                current.append(c)
                i++
            }
            val tail = current.toString().trim()
            if (isStatement(tail)) out += tail
            return out
        }

        /**
         * The migration files end every statement with the `--;` sentinel
         * (`MigrationStatementSplitter`), so splitting on a bare `;` leaves
         * a comment-only `--` fragment after each real statement. Preparing
         * a comment-only string yields no statement (SQLite returns a null
         * handle; `nativeStep` refuses it), so such fragments are not
         * statements and must not be executed.
         */
        private fun isStatement(fragment: String): Boolean = fragment.isNotEmpty() && !fragment.startsWith("--")

        const val BEGIN: String = "BEGIN"
        const val END: String = "END"
    }
}
