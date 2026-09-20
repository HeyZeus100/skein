package app.skein.core.vault.db.migrations

import androidx.sqlite.SQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver

/**
 * Result of a [Migrator.migrate] call.
 *
 * [fromVersion] is `PRAGMA user_version` as read at the start of the
 * call (before any pending migration ran); [toVersion] is the same
 * PRAGMA read again after all pending migrations committed. A no-op
 * `migrate` (nothing pending) reports `fromVersion == toVersion`.
 */
public data class MigrateResult(
    public val fromVersion: Int,
    public val toVersion: Int,
)

/** A single discovered `migrations/NNN_*.sql` resource. */
internal data class Migration(
    val version: Int,
    val fileName: String,
    val sql: String,
)

/**
 * Applies `migrations/NNN_*.sql` resources to a vault database opened via
 * [driver], in ascending version order, each inside its own
 * `BEGIN IMMEDIATE` transaction (`E2.I2` / skein-5my).
 *
 * Discovery reads a manifest file, `<migrationsPath>/INDEX.txt` (default
 * `migrations/INDEX.txt`, shipped alongside `001_initial.sql`), rather
 * than listing the resource directory: JVM/Android class loaders don't
 * support directory listing against a JAR/APK
 * (`ClassLoader.getResources("migrations/")` yields the directory URL
 * itself, not its entries). Each line names one `NNN_*.sql` file; the
 * leading `NNN` (any run of digits before the first `_`) is the
 * migration's `PRAGMA user_version` value. Lines are sorted numerically
 * by that version — the manifest's own line order does not matter.
 *
 * Each migration's SQL is split into individual top-level statements on
 * the `--;` sentinel (see [MigrationStatementSplitter]) so multi-line
 * trigger bodies with an internal `;` survive intact. All of a
 * migration's statements run inside one transaction; the transaction
 * commits with `PRAGMA user_version` set to that migration's version.
 * Any statement error rolls the whole transaction back — including
 * `user_version` — and the original exception propagates to the caller,
 * so a partially-applied migration never leaves partial schema objects
 * behind.
 *
 * [migrationsPath] and [classLoader] are overridable for tests only (for
 * example to point at the deliberately-broken
 * `src/test/resources/migrations-bad/` fixture); production callers use
 * the single-argument constructor.
 */
public class Migrator(
    private val driver: SkeinSQLiteDriver,
    private val migrationsPath: String = DEFAULT_MIGRATIONS_PATH,
    private val classLoader: ClassLoader =
        requireNotNull(Migrator::class.java.classLoader) {
            "Migrator requires a non-null class loader to load bundled migration resources"
        },
) {
    /**
     * Opens [path] via [driver], applies every migration newer than the
     * database's current `PRAGMA user_version`, and returns the before/
     * after versions. Running this twice in a row with nothing new to
     * apply is a no-op: no transaction is opened and no error is thrown.
     */
    public fun migrate(path: String): MigrateResult {
        val conn = driver.open(path)
        return conn.use {
            val current = readUserVersion(it)
            val pending = discoverMigrations().filter { m -> m.version > current }.sortedBy { m -> m.version }
            for (m in pending) applyMigration(it, m)
            MigrateResult(fromVersion = current, toVersion = readUserVersion(it))
        }
    }

    private fun readUserVersion(conn: SQLiteConnection): Int {
        conn.prepare("PRAGMA user_version;").use { stmt ->
            check(stmt.step()) { "PRAGMA user_version returned no row" }
            return stmt.getLong(0).toInt()
        }
    }

    /**
     * Runs [migration]'s statements inside one `BEGIN IMMEDIATE`
     * transaction, sets `PRAGMA user_version` to [Migration.version], and
     * commits. On any failure the transaction is rolled back (so
     * `user_version` and any partial objects created by earlier
     * statements in this same migration revert) and the original
     * exception is rethrown. A failure during the rollback itself is
     * attached as a suppressed exception rather than replacing the
     * original cause.
     */
    private fun applyMigration(
        conn: SQLiteConnection,
        migration: Migration,
    ) {
        val statements = MigrationStatementSplitter.split(migration.sql)
        execStatement(conn, "BEGIN IMMEDIATE;")
        try {
            for (statement in statements) {
                execStatement(conn, statement)
            }
            execStatement(conn, "PRAGMA user_version = ${migration.version};")
            execStatement(conn, "COMMIT;")
        } catch (failure: Throwable) {
            try {
                execStatement(conn, "ROLLBACK;")
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun execStatement(
        conn: SQLiteConnection,
        sql: String,
    ) {
        conn.prepare(sql).use { it.step() }
    }

    /**
     * Reads `<migrationsPath>/INDEX.txt` off [classLoader], loads each
     * listed `NNN_*.sql` resource, and returns them sorted ascending by
     * version. Throws [MigrationDiscoveryException] if the manifest, or
     * any file it lists, is missing or malformed.
     */
    private fun discoverMigrations(): List<Migration> {
        val indexPath = "$migrationsPath/INDEX.txt"
        val indexText =
            classLoader.getResourceAsStream(indexPath)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw MigrationDiscoveryException("migration index not found on classpath: $indexPath")

        return indexText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { fileName -> loadMigration(fileName) }
            .sortedBy { it.version }
            .toList()
    }

    private fun loadMigration(fileName: String): Migration {
        val match =
            MIGRATION_FILE_NAME_REGEX.matchEntire(fileName)
                ?: throw MigrationDiscoveryException(
                    "migration file name in $migrationsPath/INDEX.txt doesn't match NNN_*.sql: $fileName",
                )
        val version = match.groupValues[1].toInt()
        val resourcePath = "$migrationsPath/$fileName"
        val sql =
            classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw MigrationDiscoveryException(
                    "migration file listed in $migrationsPath/INDEX.txt not found on classpath: $resourcePath",
                )
        return Migration(version = version, fileName = fileName, sql = sql)
    }

    private companion object {
        const val DEFAULT_MIGRATIONS_PATH = "migrations"
        val MIGRATION_FILE_NAME_REGEX = Regex("""(\d+)_[^/]+\.sql""")
    }
}

/**
 * Thrown when the migration manifest (`migrations/INDEX.txt`) or a file
 * it lists can't be found or doesn't follow the `NNN_*.sql` naming
 * convention. Distinct from a plain [app.skein.core.vault.db.SkeinSQLiteException],
 * which signals a SQL statement failure rather than a packaging/resource
 * problem.
 */
public class MigrationDiscoveryException(
    message: String,
) : RuntimeException(message)
