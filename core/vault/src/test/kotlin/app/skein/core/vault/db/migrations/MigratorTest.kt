package app.skein.core.vault.db.migrations

import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-JVM unit tests for [Migrator]'s discovery step, using
 * [FakeSkeinSQLiteNative] (no real SQL engine, no `.so`) — only enough to
 * get [SkeinSQLiteDriver.open] past its extension probes. Functional
 * coverage of applying real DDL/triggers against the real driver lives in
 * the instrumented `MigratorInstrumentedTest` (`E2.I2` / skein-5my), since
 * the fake native doesn't actually execute SQL.
 */
class MigratorTest {
    private fun openableFake(): FakeSkeinSQLiteNative =
        FakeSkeinSQLiteNative().apply {
            fakeCipherVersion = "4.17.0 community"
            fakeVecVersion = "v0.1.9"
        }

    @Test
    fun `migrate throws MigrationDiscoveryException when the index manifest is missing`() {
        val migrator = Migrator(SkeinSQLiteDriver(openableFake()), migrationsPath = "does-not-exist")

        try {
            migrator.migrate(":memory:")
            error("expected MigrationDiscoveryException")
        } catch (ex: MigrationDiscoveryException) {
            assertThat(ex.message).contains("does-not-exist/INDEX.txt")
        }
    }

    @Test
    fun `main migrations index lists exactly 001_initial_sql`() {
        // Exercises the real production manifest shipped in
        // src/main/resources/migrations/INDEX.txt against the default
        // constructor overload.
        val migrator = Migrator(SkeinSQLiteDriver(openableFake()))

        val indexText =
            requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/INDEX.txt")) {
                "migrations/INDEX.txt not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val listedFiles = indexText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertThat(listedFiles.toList()).containsExactly("001_initial.sql")
        // migrator itself isn't exercised beyond construction here — the
        // functional discover-then-apply path is covered on-device.
        assertThat(migrator).isNotNull()
    }

    @Test
    fun `bad-migrations-fixture index lists exactly 999_bad_sql`() {
        val indexText =
            requireNotNull(
                Migrator::class.java.classLoader?.getResourceAsStream("migrations-bad/INDEX.txt"),
            ) { "migrations-bad/INDEX.txt not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) }
        val listedFiles = indexText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertThat(listedFiles.toList()).containsExactly("999_bad.sql")
    }
}
