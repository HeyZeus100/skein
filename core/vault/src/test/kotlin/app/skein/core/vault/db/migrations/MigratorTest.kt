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
    fun `main migrations index lists exactly 001, 003, 005, 007 and 008`() {
        // Exercises the real production manifest shipped in
        // src/main/resources/migrations/INDEX.txt against the default
        // constructor overload.
        val migrator = Migrator(SkeinSQLiteDriver(openableFake()))

        val indexText =
            requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/INDEX.txt")) {
                "migrations/INDEX.txt not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val listedFiles = indexText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertThat(listedFiles.toList()).containsExactly(
            "001_initial.sql",
            "003_document_revisions.sql",
            // 005_export_stages.sql (skein-0m1z) fills a number RESERVED for
            // it (bd skein-voys, docs/VAULT_FORMAT.md §7) that is lower than
            // the already-landed 007/008. `Migrator` sorts by the leading
            // NNN, not by this file's line order, so a fresh database still
            // applies 001 -> 003 -> 005 -> 007 -> 008 and still ends at
            // user_version 8. The `schema_migrations` ledger (skein-p8rn,
            // see `pendingMigrations`/`seedVersions` below) is what makes a
            // database that already reached user_version 8 BEFORE this file
            // landed still apply it -- see that migration's header.
            "005_export_stages.sql",
            "007_drop_attachment_master_key.sql",
            "008_ingest_attempts.sql",
        )
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

    // --- pendingMigrations (skein-p8rn): ledger membership, not a high-water mark ---

    private fun fakeMigration(version: Int): Migration =
        Migration(version = version, fileName = "$version.sql", sql = "")

    @Test
    fun `pendingMigrations includes a gap-filler the ledger never recorded, even past a higher applied version`() {
        // Models the exact hazard skein-p8rn fixes: a database whose
        // schema_migrations ledger recorded 001, 003, 007 and 008 (it
        // reached user_version 8 through those four) but never recorded
        // 005 -- because 005_export_stages.sql landed after 007/008 already
        // shipped, the OLD `version > user_version` rule would silently
        // skip it forever. Against the ledger-based `pendingMigrations`,
        // it must come back pending regardless of how high the ledger's
        // other entries already reach.
        val all = listOf(1, 3, 5, 7, 8).map(::fakeMigration)
        val ledger = setOf(1, 3, 7, 8) // 005 never ran

        val pending = pendingMigrations(all, ledger)

        assertThat(pending.map { it.version }).containsExactly(5)
    }

    @Test
    fun `pendingMigrations returns every migration in ascending order for an empty ledger`() {
        val all = listOf(8, 1, 7, 3, 5).map(::fakeMigration) // deliberately out of order

        val pending = pendingMigrations(all, applied = emptySet())

        assertThat(pending.map { it.version }).containsExactly(1, 3, 5, 7, 8).inOrder()
    }

    @Test
    fun `pendingMigrations is empty once the ledger already recorded every version`() {
        val all = listOf(1, 3, 5, 7, 8).map(::fakeMigration)

        val pending = pendingMigrations(all, applied = setOf(1, 3, 5, 7, 8))

        assertThat(pending).isEmpty()
    }

    // --- seedVersions (skein-p8rn): seeding decision, isolated from real DB access ---

    @Test
    fun `seedVersions excludes a gap-filler whose effects are not observable on the live schema`() {
        // The legacy-database seeding case this bead exists to get right:
        // candidates are every migration with version less than or equal to
        // the database's pre-ledger user_version (8), but 005's witness
        // table (`export_stages`) does not actually exist on this
        // particular database -- it must NOT be seeded as applied.
        val candidates = listOf(1, 3, 5, 7, 8).map(::fakeMigration)

        val seeded = seedVersions(candidates) { m -> m.version != 5 }

        assertThat(seeded).containsExactly(1, 3, 7, 8)
    }

    @Test
    fun `seedVersions seeds only migration 1 for a database migrated to v1 before the ledger existed`() {
        // Mirrors MigratorInstrumentedTest's
        // rowsSeededBeforeMigration007SurviveTheDropAndDocumentsIsUnaffected
        // fixture: a database at user_version 1 only ever ran 001, so only
        // 001 is a seeding candidate at all (003/005/007/008 all have
        // version > 1 and are ordinary pending migrations, not seeding
        // candidates).
        val candidates = listOf(1).map(::fakeMigration)

        val seeded = seedVersions(candidates) { true }

        assertThat(seeded).containsExactly(1)
    }

    @Test
    fun `seedVersions seeds nothing when no candidate's effects are observable`() {
        val candidates = listOf(1, 3).map(::fakeMigration)

        val seeded = seedVersions(candidates) { false }

        assertThat(seeded).isEmpty()
    }

    // --- migrationWitness (skein-p8rn): DDL-form detection against the real, shipped migration files ---

    private fun productionMigrationSql(fileName: String): String =
        requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/$fileName")) {
            "migrations/$fileName not on the classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `migrationWitness finds 001's first CREATE TABLE, not any earlier mention`() {
        val witness = migrationWitness(productionMigrationSql("001_initial.sql"))

        assertThat(witness).isEqualTo(Witness.TableExists("documents"))
    }

    @Test
    fun `migrationWitness finds 003's CREATE TABLE document_revisions`() {
        val witness = migrationWitness(productionMigrationSql("003_document_revisions.sql"))

        assertThat(witness).isEqualTo(Witness.TableExists("document_revisions"))
    }

    @Test
    fun `migrationWitness finds 005's CREATE TABLE export_stages`() {
        val witness = migrationWitness(productionMigrationSql("005_export_stages.sql"))

        assertThat(witness).isEqualTo(Witness.TableExists("export_stages"))
    }

    @Test
    fun `migrationWitness finds 007's real DROP TABLE, not the illustrative one in its own header prose`() {
        // 007_drop_attachment_master_key.sql's header literally contains the
        // text "DROP TABLE attachment_keys; DROP TABLE
        // attachment_master_key;" while explaining FK-drop ordering -- a
        // witness detector that doesn't strip comment lines before matching
        // would find that prose instead of (in this case, coincidentally
        // identical to) the real statement at the bottom of the file.
        val witness = migrationWitness(productionMigrationSql("007_drop_attachment_master_key.sql"))

        assertThat(witness).isEqualTo(Witness.TableAbsent("attachment_keys"))
    }

    @Test
    fun `migrationWitness finds 008's first ALTER TABLE ADD COLUMN`() {
        val witness = migrationWitness(productionMigrationSql("008_ingest_attempts.sql"))

        assertThat(witness).isEqualTo(Witness.ColumnExists("ingest_queue", "attempts"))
    }

    @Test
    fun `migrationWitness returns null for SQL with no recognized DDL form`() {
        assertThat(migrationWitness("-- just a comment, no statements\n")).isNull()
    }
}
