// Instrumented contract test for `PersonaServiceImpl` (`E2.I14`, bd
// `skein-bkp`). Runs the shared `PersonaServiceContractTest` suite from
// `:testing` against the real SQL-backed impl over an unencrypted
// `:memory:` connection with sqlite-vec + FTS5 live — same approach as
// `IndexStoreImplContractTest` (`E2.I15`) and
// `VaultRepositoryImplContractTest` (`E2.I4`), which this class mirrors:
// migration 001 is applied statement-by-statement on the same connection
// the service then runs against (a fresh `SkeinSQLiteDriver` with no key —
// `verifyExtensions` still asserts vec/FTS5 linkage even unencrypted).
//
// Follow-up (skein-k3b2): no API 35 emulator was available in this
// worktree, matching every other `*InstrumentedTest`/`*ContractTest` in
// this module. This class is compiled (and so any broken statement is
// caught) by the ordinary Gradle `check` path; running it for real is
// gated on that follow-up.

package app.skein.core.vault.persona

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import org.junit.After
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.PersonaService
import us.aherrera.skein.testing.PersonaServiceContractTest

@RunWith(AndroidJUnit4::class)
public class PersonaServiceImplContractTest : PersonaServiceContractTest() {
    private val openImpls: MutableList<PersonaServiceImpl> = mutableListOf()

    @After
    public fun tearDown() {
        // Close every impl handed out during the test. `close()` shuts the
        // SQLiteConnection, which in turn drops the SQLCipher-keyed
        // in-memory DB.
        for (impl in openImpls) {
            try {
                impl.close()
            } catch (_: Throwable) {
                // Best effort — the assertion has already run or thrown.
            }
        }
        openImpls.clear()
    }

    override fun service(): PersonaService {
        // Fresh unencrypted in-memory DB per test (no key argument to
        // SkeinSQLiteDriver; verifyExtensions still asserts vec/FTS5
        // linkage). Then run migration 001 statement-by-statement, using
        // the same trigger-aware splitter as `IndexStoreImplContractTest`.
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
        val sql =
            requireNotNull(
                javaClass.classLoader?.getResourceAsStream("migrations/001_initial.sql"),
            ) { "migrations/001_initial.sql not on the classpath" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
        for (statement in splitOnSentinel(sql)) {
            conn.prepare(statement).use { it.step() }
        }
        val impl = PersonaServiceImpl(conn)
        openImpls += impl
        return impl
    }

    private companion object {
        /**
         * Split the migration SQL on the `--;` sentinel used by
         * `001_initial.sql` (see file header) — mirrors what the
         * production `MigrationStatementSplitter` does. Trailing
         * whitespace and empty statements are dropped.
         */
        fun splitOnSentinel(sql: String): List<String> {
            val raw = sql.split("--;")
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
    }
}
