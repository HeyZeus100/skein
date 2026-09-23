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
 * ## The `schema_migrations` ledger (skein-p8rn)
 *
 * A database is no longer judged solely by `PRAGMA user_version` (an
 * unencrypted high-water mark). [migrate] also maintains a
 * `schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER)`
 * ledger table — created by this class itself, not by a numbered
 * migration, so it exists before any migration is judged — and treats a
 * migration as pending iff its version is absent from the ledger, not
 * merely "greater than the current high-water mark". This closes a real
 * hazard: the 002/004-006 numbering scheme (`bd skein-voys`,
 * `docs/VAULT_FORMAT.md` §7) deliberately reserves gaps to be filled
 * later, after higher-numbered migrations have already landed — exactly
 * what `005_export_stages.sql` (skein-0m1z) did. Under the old
 * `version > user_version` rule, a database that already reached
 * `user_version` 8 before 005 landed would NEVER apply it: 5 is not
 * greater than 8, so `export_stages` — the table
 * `StagedPlaintextSweeper` depends on — would silently never exist,
 * turning a missing table into an indistinguishable-from-success "nothing
 * to sweep". The ledger tracks exact membership instead of a threshold,
 * so a gap-filler is applied exactly once no matter how many
 * higher-numbered migrations already shipped.
 *
 * `PRAGMA user_version` is still kept up to date, as the max version ever
 * applied (never lowered — see [applyMigration]), for compatibility with
 * anything that still reads it directly (backup tooling, `sqlite3`
 * inspection, etc).
 *
 * ### Seeding a pre-ledger database
 *
 * A database migrated entirely by pre-skein-p8rn code has `user_version`
 * set but no `schema_migrations` row. The very first `migrate()` call
 * under this class seeds the ledger for such a database (detected as: the
 * table did not exist before this call, and `user_version > 0`). Seeding
 * cannot simply mark every migration with `version <= user_version` as
 * applied — for exactly the gap-filling case above, that would
 * permanently (and wrongly) mark a never-applied gap-filler as done,
 * reintroducing the same silent-skip bug this class exists to close: a
 * database that reached `user_version` 8 via 001→003→007→008 (005 never
 * ran, because it landed after 007/008) is bit-for-bit indistinguishable,
 * by `user_version` alone, from one that reached 8 via
 * 001→003→005→007→008 (005 did run) — `applyMigration` always sets
 * `user_version` to the *migration's own* version historically, so
 * whichever migration ran last numerically determines the final value
 * regardless of what ran in between.
 *
 * Seeding therefore checks each candidate migration (`version <=
 * user_version`) against the database's actual live schema — see
 * [migrationWitness] / [migrationEffectsPresent] — rather than trusting
 * the version number alone. A migration whose witness object (the table
 * it creates, the table it drops, or the column it adds — DDL forms are
 * closed and enumerated in [migrationWitness]) is not observed on the
 * live schema is left pending, so it runs for real on this same
 * `migrate()` call. A migration whose own effects are visible in
 * `sqlite_master` is marked as seeded — it must have already run, because
 * every migration here applies inside one all-or-nothing transaction
 * (see [applyMigration]), so a partially-applied migration can never
 * leave its witness object behind without the rest of its schema too.
 *
 * If a future migration ever ships a DDL shape [migrationWitness] doesn't
 * recognize, seeding treats it as NOT applied (the safe default): a
 * spurious re-run fails loudly (the `CREATE TABLE`/etc. errors out, same
 * as any other broken migration) rather than silently perpetuating a skip
 * — this codebase's stated preference (`005_export_stages.sql`'s own
 * header: "a missing table must never be mistaken for nothing to
 * sweep").
 *
 * ### What the ledger does NOT fix: relative ordering
 *
 * The ledger guarantees a migration eventually runs exactly once. It does
 * NOT guarantee a gap-filler runs *before* the higher-numbered migrations
 * that already shipped ahead of it — on a database that already applied
 * 007/008, a later-landing 005 necessarily applies chronologically AFTER
 * them, even though its version number is lower. `005_export_stages.sql`
 * is safe under this because its header states it references only
 * `documents` (from 001) and is independent of 003/007/008 in either
 * direction. A *future* reserved migration (002/004/006) that needed to
 * run before an already-shipped higher-numbered migration's schema change
 * would NOT be safe here — that is a distinct hazard the ledger cannot
 * close, tracked separately (skein-ncdf).
 *
 * Each migration's SQL is split into individual top-level statements on
 * the `--;` sentinel (see [MigrationStatementSplitter]) so multi-line
 * trigger bodies with an internal `;` survive intact. All of a
 * migration's statements, plus its ledger insert and the `user_version`
 * update, run inside one transaction; the transaction commits with
 * `PRAGMA user_version` set to the max version applied so far. Any
 * statement error rolls the whole transaction back — including
 * `user_version` and the ledger row — and the original exception
 * propagates to the caller, so a partially-applied migration never leaves
 * partial schema objects (or a false ledger entry) behind.
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
     * Opens [path] via [driver], applies every migration absent from the
     * `schema_migrations` ledger (seeding that ledger first if this
     * database predates it — see the class doc), and returns the before/
     * after `PRAGMA user_version`. Running this twice in a row with
     * nothing new to apply is a no-op: no migration transaction is
     * opened and no error is thrown.
     */
    public fun migrate(path: String): MigrateResult {
        val conn = driver.open(path)
        return conn.use {
            val fromVersion = readUserVersion(it)
            val allMigrations = discoverMigrations()

            val ledgerPreexisted = ledgerTableExists(it)
            ensureLedgerTable(it)
            if (!ledgerPreexisted && fromVersion > 0) {
                seedLedger(it, allMigrations, fromVersion)
            }

            val applied = readLedgerVersions(it)
            val pending = pendingMigrations(allMigrations, applied)

            var runningMax = maxOf(fromVersion, applied.maxOrNull() ?: 0)
            for (m in pending) {
                runningMax = maxOf(runningMax, m.version)
                applyMigration(it, m, userVersionToSet = runningMax)
            }

            MigrateResult(fromVersion = fromVersion, toVersion = readUserVersion(it))
        }
    }

    private fun readUserVersion(conn: SQLiteConnection): Int {
        conn.prepare("PRAGMA user_version;").use { stmt ->
            check(stmt.step()) { "PRAGMA user_version returned no row" }
            return stmt.getLong(0).toInt()
        }
    }

    private fun ledgerTableExists(conn: SQLiteConnection): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$LEDGER_TABLE';").use { stmt ->
            stmt.step()
        }

    /**
     * Creates the `schema_migrations` ledger table if it is not already
     * present. This is NOT a numbered migration — it must exist before
     * any migration (including a database's very first one) can be
     * judged against it — but it follows the same
     * `BEGIN IMMEDIATE` / commit-or-rollback discipline every other
     * schema change in this class uses.
     */
    private fun ensureLedgerTable(conn: SQLiteConnection) {
        execStatement(conn, "BEGIN IMMEDIATE;")
        try {
            execStatement(
                conn,
                "CREATE TABLE IF NOT EXISTS $LEDGER_TABLE (" +
                    "version INTEGER PRIMARY KEY, " +
                    "applied_at INTEGER NOT NULL" +
                    ");",
            )
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

    /**
     * Seeds the ledger for a database that predates it (see the class
     * doc's "Seeding a pre-ledger database" section): every migration
     * with `version <= fromVersion` whose schema effects are already
     * observable on [conn] is recorded as applied. A candidate whose
     * effects are NOT observable (the gap-filling hazard this class
     * exists to close) is left out, so it is applied for real by the
     * caller's normal pending-migration loop.
     */
    private fun seedLedger(
        conn: SQLiteConnection,
        allMigrations: List<Migration>,
        fromVersion: Int,
    ) {
        val candidates = allMigrations.filter { it.version <= fromVersion }.sortedBy { it.version }
        val toSeed = seedVersions(candidates) { m -> migrationEffectsPresent(conn, m) }
        if (toSeed.isEmpty()) return

        execStatement(conn, "BEGIN IMMEDIATE;")
        try {
            val appliedAt = System.currentTimeMillis()
            for (version in toSeed) {
                execStatement(
                    conn,
                    "INSERT INTO $LEDGER_TABLE(version, applied_at) VALUES ($version, $appliedAt);",
                )
            }
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

    private fun readLedgerVersions(conn: SQLiteConnection): Set<Int> {
        val versions = mutableSetOf<Int>()
        conn.prepare("SELECT version FROM $LEDGER_TABLE;").use { stmt ->
            while (stmt.step()) versions += stmt.getLong(0).toInt()
        }
        return versions
    }

    /**
     * True iff [migration]'s [migrationWitness] object is already
     * observable on [conn]. Returns `false` (safe default — see the
     * class doc) when the migration's SQL contains no DDL form
     * [migrationWitness] recognizes.
     */
    private fun migrationEffectsPresent(
        conn: SQLiteConnection,
        migration: Migration,
    ): Boolean =
        when (val witness = migrationWitness(migration.sql)) {
            is Witness.TableExists -> tableExists(conn, witness.table)
            is Witness.TableAbsent -> !tableExists(conn, witness.table)
            is Witness.ColumnExists -> columnExists(conn, witness.table, witness.column)
            null -> false
        }

    private fun tableExists(
        conn: SQLiteConnection,
        table: String,
    ): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?;").use { stmt ->
            stmt.bindText(1, table)
            stmt.step()
        }

    private fun columnExists(
        conn: SQLiteConnection,
        table: String,
        column: String,
    ): Boolean {
        conn.prepare("PRAGMA table_info($table);").use { stmt ->
            while (stmt.step()) {
                if (stmt.getText(1) == column) return true
            }
        }
        return false
    }

    /**
     * Runs [migration]'s statements inside one `BEGIN IMMEDIATE`
     * transaction, records it in the `schema_migrations` ledger, sets
     * `PRAGMA user_version` to [userVersionToSet] (the max version
     * applied so far this `migrate()` call — never [Migration.version]
     * directly, so a gap-filler applied after a higher migration never
     * lowers `user_version`), and commits. On any failure the transaction
     * is rolled back (so `user_version`, the ledger row, and any partial
     * objects created by earlier statements in this same migration all
     * revert) and the original exception is rethrown. A failure during
     * the rollback itself is attached as a suppressed exception rather
     * than replacing the original cause.
     */
    private fun applyMigration(
        conn: SQLiteConnection,
        migration: Migration,
        userVersionToSet: Int,
    ) {
        val statements = MigrationStatementSplitter.split(migration.sql)
        execStatement(conn, "BEGIN IMMEDIATE;")
        try {
            for (statement in statements) {
                execStatement(conn, statement)
            }
            val appliedAt = System.currentTimeMillis()
            execStatement(
                conn,
                "INSERT INTO $LEDGER_TABLE(version, applied_at) VALUES (${migration.version}, $appliedAt);",
            )
            execStatement(conn, "PRAGMA user_version = $userVersionToSet;")
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
        const val LEDGER_TABLE = "schema_migrations"
        val MIGRATION_FILE_NAME_REGEX = Regex("""(\d+)_[^/]+\.sql""")
    }
}

/**
 * The single schema object a migration's application can be verified
 * against — see [migrationWitness]. Every migration here applies
 * atomically (one `BEGIN IMMEDIATE` transaction — [Migrator.applyMigration]),
 * so any one object it creates/drops/adds is either present alongside the
 * rest of its schema effects, or the whole migration never ran; one
 * witness per migration is therefore sufficient.
 */
internal sealed class Witness {
    internal data class TableExists(
        val table: String,
    ) : Witness()

    internal data class TableAbsent(
        val table: String,
    ) : Witness()

    internal data class ColumnExists(
        val table: String,
        val column: String,
    ) : Witness()
}

/**
 * Returns the [Witness] that proves [sql] (a migration's full file
 * contents) has already run against a live database, or `null` if none
 * of the DDL forms currently shipped by this module's migrations
 * (`CREATE TABLE`, `CREATE VIRTUAL TABLE`, `DROP TABLE`, and
 * `ALTER TABLE ... ADD COLUMN`) is found. Only the FIRST recognized DDL
 * statement (in file order) is used — see [Witness]'s doc for why one is
 * enough.
 *
 * Statements are split the same way [Migrator] itself splits them
 * ([MigrationStatementSplitter]) and then have their `--`-prefixed
 * comment lines stripped before matching: several of these files'
 * headers use the exact SQL keywords being detected here as prose (for
 * example `007_drop_attachment_master_key.sql`'s header literally
 * illustrates `DROP TABLE attachment_keys; DROP TABLE
 * attachment_master_key;` while explaining FK-drop ordering) — matching
 * against the raw, comment-inclusive statement text risks picking up a
 * name mentioned only in passing rather than the real DDL.
 */
internal fun migrationWitness(sql: String): Witness? {
    for (rawStatement in MigrationStatementSplitter.split(sql)) {
        val statement = stripCommentLines(rawStatement)
        if (statement.isBlank()) continue

        CREATE_TABLE_REGEX.find(statement)?.let { return Witness.TableExists(it.groupValues[1]) }
        CREATE_VIRTUAL_TABLE_REGEX.find(statement)?.let { return Witness.TableExists(it.groupValues[1]) }
        ALTER_ADD_COLUMN_REGEX.find(statement)?.let {
            return Witness.ColumnExists(it.groupValues[1], it.groupValues[2])
        }
        DROP_TABLE_REGEX.find(statement)?.let { return Witness.TableAbsent(it.groupValues[1]) }
    }
    return null
}

/**
 * Drops every line of [statement] that is (after trimming) a `--`
 * comment, mirroring the same convention already duplicated across this
 * module's contract tests (e.g. `MigratorInstrumentedTest.splitOnSentinel`)
 * for stripping a migration file's prose out of its SQL.
 */
private fun stripCommentLines(statement: String): String =
    statement
        .lineSequence()
        .filter { line -> !line.trimStart().startsWith("--") }
        .joinToString("\n")

/**
 * Computes the migrations that are absent from [applied] — the
 * `schema_migrations` ledger's version set — rather than merely greater
 * than some high-water mark. This is the core of skein-p8rn's fix: a
 * gap-filling migration (lower version, landed after a higher one
 * already shipped) stays pending until it is actually recorded in
 * [applied], no matter how high [applied]'s maximum already is.
 */
internal fun pendingMigrations(
    all: List<Migration>,
    applied: Set<Int>,
): List<Migration> = all.filterNot { it.version in applied }.sortedBy { it.version }

/**
 * Selects, from [candidates] (migrations already known to have
 * `version <= ` the database's pre-ledger `user_version`), the subset
 * whose effects [isApplied] reports as already observable on the live
 * schema — the ledger-seeding decision, isolated from actual database
 * access so it can be unit-tested with a fake predicate.
 */
internal fun seedVersions(
    candidates: List<Migration>,
    isApplied: (Migration) -> Boolean,
): List<Int> = candidates.filter(isApplied).map { it.version }

/**
 * Name of the `schema_migrations` ledger table [Migrator.migrate] creates
 * (see this class's own doc, "The `schema_migrations` ledger") before
 * judging any migration. Mirrors [Migrator]'s private `LEDGER_TABLE`
 * constant — kept in sync by hand since the ledger table's name is a
 * fixed architectural constant of this class, not something derived from
 * the migration manifest and therefore not something that can drift the
 * way a migration-produced object name could.
 *
 * Exposed (skein-hctx) so `VaultLifecycle`'s integrity-check catalogue can
 * require this table's presence without a second, disconnected hand-kept
 * literal of its own: the table is created by this class directly, never
 * by a file `migrations/INDEX.txt` lists, so nothing that walks the
 * migration manifest could ever discover it on its own.
 */
public const val SCHEMA_MIGRATIONS_TABLE_NAME: String = "schema_migrations"

/**
 * The highest migration version listed on [classLoader]'s
 * `<migrationsPath>/INDEX.txt` manifest — the `PRAGMA user_version` (and
 * `schema_migrations` high-water mark) a fresh [Migrator.migrate] call
 * converges on for a vault with nothing already applied.
 *
 * Exposed (skein-hctx) so a caller — a test in particular — can assert
 * against "whatever the manifest currently lists" instead of a version
 * number pinned as a literal, which silently goes stale the moment a new
 * migration file is appended to the manifest: exactly what left
 * `VaultLifecycleInstrumentedTest` asserting `toVersion == 1` while the
 * shipped manifest had already grown to 008.
 */
public fun latestMigrationVersion(
    migrationsPath: String = "migrations",
    classLoader: ClassLoader =
        requireNotNull(Migrator::class.java.classLoader) {
            "latestMigrationVersion requires a non-null class loader to load the bundled migration manifest"
        },
): Int {
    val indexPath = "$migrationsPath/INDEX.txt"
    val indexText =
        classLoader.getResourceAsStream(indexPath)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw MigrationDiscoveryException("migration index not found on classpath: $indexPath")
    return indexText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { fileName ->
            val match =
                LATEST_VERSION_FILE_NAME_REGEX.matchEntire(fileName)
                    ?: throw MigrationDiscoveryException(
                        "migration file name in $migrationsPath/INDEX.txt doesn't match NNN_*.sql: $fileName",
                    )
            match.groupValues[1].toInt()
        }.max()
}

private val LATEST_VERSION_FILE_NAME_REGEX = Regex("""(\d+)_[^/]+\.sql""")

private val CREATE_TABLE_REGEX = Regex("""(?i)\bCREATE\s+TABLE\s+(\w+)""")
private val CREATE_VIRTUAL_TABLE_REGEX = Regex("""(?i)\bCREATE\s+VIRTUAL\s+TABLE\s+(\w+)""")
private val ALTER_ADD_COLUMN_REGEX = Regex("""(?i)\bALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)""")
private val DROP_TABLE_REGEX = Regex("""(?i)\bDROP\s+TABLE\s+(\w+)""")

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
