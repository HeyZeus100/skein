// Instrumented contract test for `ModelRegistryImpl` (skein-cyq, E4.I5).
// Runs the shared `ModelRegistryContractTest` suite from `:testing` against
// the real SQL-backed impl over an unencrypted `:memory:` connection —
// same approach as `VaultRepositoryImplContractTest`, which this class
// mirrors: migrations `001_initial.sql` and `009_model_origin.sql` are
// applied statement-by-statement on the connection the registry then runs
// against.
//
// Follow-up (skein-k3b2): no API 35 emulator was available in this
// worktree, matching every other `*InstrumentedTest`/`*ContractTest` in
// this module. This class is compiled (so any broken statement is caught)
// by the ordinary Gradle `check` path; running it for real is gated on
// that follow-up.

package app.skein.core.vault.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.ModelRegistry
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.testing.ModelRegistryContractTest
import org.junit.After
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
public class ModelRegistryImplContractTest : ModelRegistryContractTest() {
    private val openConnections: MutableList<SkeinSQLiteConnection> = mutableListOf()
    private val prefsCounter = AtomicInteger(0)

    @After
    public fun tearDown() {
        for (conn in openConnections) {
            try {
                conn.close()
            } catch (_: Throwable) {
                // Best effort — the assertion has already run or thrown.
            }
        }
        openConnections.clear()
    }

    override fun registry(): ModelRegistry {
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
        for (migration in listOf("001_initial.sql", "009_model_origin.sql")) {
            val sql =
                requireNotNull(
                    javaClass.classLoader?.getResourceAsStream("migrations/$migration"),
                ) { "migrations/$migration not on the classpath" }
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            for (statement in splitOnSentinel(sql)) {
                conn.prepare(statement).use { it.step() }
            }
        }
        openConnections += conn

        val context = ApplicationProvider.getApplicationContext<Context>()
        // A uniquely-named prefs file per registry() call, so
        // `ModelRegistryContractTest`'s "fresh registry per test method"
        // contract holds even though `SharedPreferences` files persist
        // across an app's process — this instrumented run is one process
        // for many test methods.
        val prefs =
            context.getSharedPreferences(
                "ui_prefs_test_${prefsCounter.incrementAndGet()}",
                Context.MODE_PRIVATE,
            )

        return ModelRegistryImpl(connection = conn, prefs = prefs)
    }

    private companion object {
        /**
         * Split the migration SQL on the `--;` sentinel used by
         * `001_initial.sql` (see its file header) — mirrors what the
         * production `MigrationStatementSplitter` does, and the identical
         * helper in `VaultRepositoryImplContractTest`.
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
