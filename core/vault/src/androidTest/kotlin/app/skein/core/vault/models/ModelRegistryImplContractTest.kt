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
//
// skein-gg11.11: `registry()` previously named each call's `SharedPreferences`
// file via a per-instance `AtomicInteger` counter. JUnit4 instantiates a new
// test-class instance per `@Test` method, so that counter reset to 0 for
// every method and every method's first (usually only) `registry()` call
// landed on the same file name (`ui_prefs_test_1`). `SharedPreferences`
// files are keyed by name and persist for the life of the instrumentation
// process, so a test that wrote a value (e.g.
// `setDefault_accepts_an_id_with_no_backing_row`, which sets the default to
// `"row-does-not-exist"`) leaked it into whichever test ran later against
// that same file name — including `default_is_null_for_a_fresh_registry`,
// which then saw `"row-does-not-exist"` instead of `null` (emulator run
// 35853455054). `default()` itself was never at fault: it is a plain
// `prefs.getString(KEY, null)`. Fixed by clearing the (fixed-name) prefs
// file synchronously on every `registry()` call, so each call is isolated
// regardless of how many test-class instances share the process.

package app.skein.core.vault.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.ModelRegistry
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.testutil.splitMigrationStatements
import app.skein.testing.ModelRegistryContractTest
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class ModelRegistryImplContractTest : ModelRegistryContractTest() {
    private val openConnections: MutableList<SkeinSQLiteConnection> = mutableListOf()

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
            for (statement in splitMigrationStatements(sql)) {
                conn.prepare(statement).use { it.step() }
            }
        }
        openConnections += conn

        val context = ApplicationProvider.getApplicationContext<Context>()
        // Fixed prefs file name, cleared synchronously on every call, so
        // `ModelRegistryContractTest`'s "fresh registry per test method"
        // contract holds regardless of how many test-class instances share
        // this instrumentation process — see the file header.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        return ModelRegistryImpl(connection = conn, prefs = prefs)
    }

    private companion object {
        const val PREFS_NAME = "ui_prefs_test"
    }
}
