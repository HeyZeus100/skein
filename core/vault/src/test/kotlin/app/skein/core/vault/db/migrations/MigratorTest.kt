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
    fun `main migrations index lists exactly 001_initial_sql and 007_drop_attachment_master_key_sql`() {
        // Exercises the real production manifest shipped in
        // src/main/resources/migrations/INDEX.txt against the default
        // constructor overload.
        val migrator = Migrator(SkeinSQLiteDriver(openableFake()))

        val indexText =
            requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/INDEX.txt")) {
                "migrations/INDEX.txt not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val listedFiles = indexText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertThat(listedFiles.toList()).containsExactly("001_initial.sql", "007_drop_attachment_master_key.sql")
        // migrator itself isn't exercised beyond construction here — the
        // functional discover-then-apply path is covered on-device.
        assertThat(migrator).isNotNull()
    }

    @Test
    fun `every file listed in the production INDEX_txt manifest exists as a sibling resource, and vice versa`() {
        // JVM-runnable INDEX.txt <-> on-disk-files agreement check
        // (skein-7d0l): catches "added a migration file but forgot to list
        // it in INDEX.txt" (or the reverse) without needing the Android
        // instrumented suite. Directory listing off a classloader doesn't
        // work for JAR/APK resources (see Migrator's discoverMigrations
        // doc), but a JVM unit test can resolve the resource URL back to a
        // real `file:` URL under `build/resources/.../migrations/` and list
        // that directory directly.
        val indexUrl =
            requireNotNull(Migrator::class.java.classLoader?.getResource("migrations/INDEX.txt")) {
                "migrations/INDEX.txt not on the classpath"
            }
        val migrationsDir =
            requireNotNull(java.io.File(indexUrl.toURI()).parentFile) {
                "migrations/INDEX.txt resolved to a file with no parent directory"
            }
        val indexText = migrationsDir.resolve("INDEX.txt").readText(Charsets.UTF_8)
        val listedFiles =
            indexText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()

        val actualFiles =
            migrationsDir
                .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
                .orEmpty()
                .map { it.name }
                .toSet()

        assertThat(listedFiles).isEqualTo(actualFiles)
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
