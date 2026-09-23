// skein-gg11.11: host-JVM regression for `ModelRegistryImpl.default()` /
// `setDefault()` over the fake driver (no `.so`), covering the case the
// instrumented contract suite's `default_is_null_for_a_fresh_registry`
// failed on the real driver (emulator run 35853455054).
//
// That failure was cross-test contamination in the androidTest harness
// (`ModelRegistryImplContractTest`), not a defect in `default()` itself —
// see that file's header for the full diagnosis. `default()` reads a plain
// Android `SharedPreferences` (`ui_prefs`), per this class's own file
// header and `ModelManager`'s KDoc; it is not backed by the `models` table
// or any other SQL read, so `FakeSkeinSQLiteNative` needs no `ui_prefs`
// modelling here — the fake connection below exists only because
// `ModelRegistryImpl`'s constructor requires one, never touched by
// `default()`/`setDefault()`.
//
// The "preference pointing at a missing row" case names a `default()` that
// nulls out a dangling id. `ModelRegistry`'s own KDoc
// (`core/model/.../ModelRegistry.kt`) is explicit that the default pointer
// "need not currently `get` to a row", and the shared contract suite
// (`ModelRegistryContractTest.setDefault_accepts_an_id_with_no_backing_row`,
// out of this bead's scope) asserts exactly that — a dangling id round-trips
// verbatim, never null. This suite proves that instead of the nulling
// behavior, since nulling would contradict the documented contract and that
// passing test.
package app.skein.core.vault.models

import android.content.Context
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRegistryImplDefaultTest {
    /**
     * A fresh [ModelRegistryImpl] with its own connection and its own prefs
     * file. Uses [RuntimeEnvironment.getApplication] (plain Robolectric, no
     * `androidx.test:core`) since `:core:vault`'s `testImplementation` does
     * not pull in `androidx.test.ext.junit`/`ApplicationProvider` the way
     * `:app`'s does — see this module's `build.gradle.kts`, out of this
     * bead's scope to change.
     */
    private fun registry(): ModelRegistryImpl {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val connection = SkeinSQLiteDriver(fake).open("vault.db")
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs =
            context.getSharedPreferences(
                "ui_prefs_default_test_${System.nanoTime()}",
                Context.MODE_PRIVATE,
            )
        return ModelRegistryImpl(connection = connection, prefs = prefs)
    }

    @Test
    fun `default is null when the preference was never written`() =
        runTest {
            // Arrange
            val registry = registry()
            // Act
            val default = registry.default()
            // Assert
            assertThat(default).isNull()
        }

    @Test
    fun `default returns the id after setDefault wrote it`() =
        runTest {
            // Arrange
            val registry = registry()
            registry.setDefault("model-a")
            // Act
            val default = registry.default()
            // Assert
            assertThat(default).isEqualTo("model-a")
        }

    @Test
    fun `default returns a dangling id verbatim, never null, per ModelRegistry's contract`() =
        runTest {
            // Arrange — no row named "row-does-not-exist" was ever upserted.
            val registry = registry()
            registry.setDefault("row-does-not-exist")
            // Act
            val default = registry.default()
            // Assert
            assertThat(default).isEqualTo("row-does-not-exist")
        }

    @Test
    fun `setDefault null clears a previously-written preference rather than writing a sentinel`() =
        runTest {
            // Arrange
            val registry = registry()
            registry.setDefault("model-a")
            // Act
            registry.setDefault(null)
            // Assert
            assertThat(registry.default()).isNull()
        }
}
