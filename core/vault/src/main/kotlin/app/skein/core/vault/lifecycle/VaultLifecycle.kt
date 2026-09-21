// skein-yrp (E2.I13) — vault lifecycle: create, open, close, integrity check.
//
// This file wires together three modules that are each owned by a
// concurrent agent and are NOT modified here:
//   - `SkeinSQLiteDriver` (`app.skein.core.vault.db`, skein-e2ki / 29b) —
//     the keyed `androidx.sqlite.SQLiteDriver`. Its own KDoc is explicit
//     that a driver instance is a "single-use secret holder": the key
//     `ByteArray` passed to its constructor is zeroed the first time
//     `open()` is called, and a second `open()` call on the same instance
//     no longer applies the key PRAGMA. `VaultLifecycle` therefore never
//     reuses a driver instance across two connections — it asks
//     [driverFactory] for a fresh driver (with a fresh key copy) per
//     connection it needs.
//   - `Migrator` (`app.skein.core.vault.db.migrations`, skein-5my) — its
//     only public entry point, `migrate(path)`, opens its own connection
//     via the driver it was constructed with, applies pending migrations
//     inside their own transactions, and closes that connection before
//     returning. It is therefore always the FIRST connection opened
//     against a vault file: it both proves the key is correct (opening an
//     existing encrypted file with the wrong key throws from inside
//     `driver.open()`, before any migration SQL runs) and brings the
//     schema up to date.
//   - `VaultKeyProvider` (`app.skein.core.vault.key`, skein-3el) — not
//     referenced directly by this file. Callers obtain the raw master key
//     from `VaultKeyProvider.currentKey()` (copying it, per that
//     interface's contract) and hand the copy to [create] / [open]; this
//     file's obligation is to zero whatever `ByteArray` it was given by
//     the time each call returns.
//
// Connection lifecycle: after the migration connection closes, this class
// opens a SECOND, long-lived connection (a fresh [driverFactory] call with
// a second key copy) that is retained until [close] — this is the
// connection `isOpen`, `integrityCheck`, and `close`'s WAL checkpoint all
// operate on.

package app.skein.core.vault.lifecycle

import androidx.sqlite.SQLiteConnection
import app.skein.core.vault.db.EncryptedDatabaseWithoutKeyException
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.SkeinSQLiteException
import app.skein.core.vault.db.migrations.MigrateResult
import app.skein.core.vault.db.migrations.MigrationStatementSplitter
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.db.migrations.SchemaInspector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** Outcome of [VaultLifecycle.create]. */
public sealed class CreateResult {
    /** The DB file was created, migrated to [migration]'s [MigrateResult.toVersion], and the default persona seeded. */
    public data class Success(
        public val migration: MigrateResult,
        public val cipherVersion: String,
    ) : CreateResult()

    /** [VaultPaths.databaseFile] already exists — call [VaultLifecycle.open] instead. */
    public object AlreadyExists : CreateResult()

    /** Any other failure. [reason] is deliberately free of key material. */
    public data class Failed(
        public val reason: String,
    ) : CreateResult()
}

/** Outcome of [VaultLifecycle.open]. */
public sealed class OpenResult {
    /** Migrations (if any were pending) applied and the connection is now live. */
    public data class Success(
        public val migration: MigrateResult,
        public val cipherVersion: String,
    ) : OpenResult()

    /** [VaultPaths.databaseFile] does not exist — call [VaultLifecycle.create] first. */
    public object NotFound : OpenResult()

    /**
     * The supplied key could not decrypt the existing database file. Typed
     * result rather than an exception dump: a wrong key on an existing
     * file is an expected, user-facing outcome (mistyped passphrase /
     * biometric unwrap drift), not a programming error.
     */
    public object WrongKey : OpenResult()

    /** Any other failure. [reason] is deliberately free of key material. */
    public data class Failed(
        public val reason: String,
    ) : OpenResult()
}

/** Outcome of [VaultLifecycle.integrityCheck]. */
public sealed class IntegrityResult {
    /** `PRAGMA integrity_check` reported `ok` and no schema drift was found. */
    public object Ok : IntegrityResult()

    /** `PRAGMA integrity_check` returned one or more problem rows (not just `ok`). */
    public data class CorruptDatabase(
        public val problems: List<String>,
    ) : IntegrityResult()

    /**
     * `sqlite_master` is missing one or more objects the bundled migration
     * manifest (`migrations/INDEX.txt`) declares — the live schema has
     * drifted from what the migrations would produce.
     */
    public data class SchemaDrift(
        public val missingObjects: List<String>,
    ) : IntegrityResult()

    /** [VaultLifecycle.integrityCheck] was called while the vault is not open. */
    public object NotOpen : IntegrityResult()
}

/**
 * Wires [SkeinSQLiteDriver] + [Migrator] into the vault's create / open /
 * close / integrity-check lifecycle (`skein-yrp`, `E2.I13`).
 *
 * [driverFactory] must return a fresh [SkeinSQLiteDriver] per call — see
 * this file's header note on why a driver instance is single-use.
 * [migrator] wraps a driver in a [Migrator]; production callers pass
 * `{ driver -> Migrator(driver) }` (the default `migrations/` manifest).
 * [paths] locates the vault directory and its expected database file name.
 *
 * Not thread-safe across concurrent [create] / [open] / [close] calls from
 * DIFFERENT coroutines is prevented by an internal [Mutex] — callers may
 * invoke these suspend functions from any dispatcher without external
 * synchronization, but a single [VaultLifecycle] instance still represents
 * one vault connection at a time (opening while already open is a no-op,
 * not a second connection).
 *
 * Connections: a successful [create] / [open] builds a single
 * [ConnectionPool] (one writer + [readerCount] readers, each carrying
 * `PRAGMA busy_timeout = `[busyTimeoutMs]) via [ConnectionPool.open], from
 * one key copy. [integrityCheck] and [close]'s WAL checkpoint run against
 * the pool's writer connection. [connectionPool] exposes it to callers
 * that need service connections (`app.skein.vault.DeviceVaultOpener`,
 * `:app`) — this is `skein-4qol`'s fix for those callers previously having
 * to construct their own `SkeinSQLiteDriver` per connection, each with a
 * fresh master-key copy and no `busy_timeout`. [close] tears the pool down
 * via [ConnectionPool.closeAll].
 */
public class VaultLifecycle(
    private val driverFactory: (key: ByteArray) -> SkeinSQLiteDriver,
    private val migrator: (SkeinSQLiteDriver) -> Migrator,
    private val paths: VaultPaths,
    private val readerCount: Int = ConnectionPool.DEFAULT_READER_COUNT,
    private val busyTimeoutMs: Long = ConnectionPool.DEFAULT_BUSY_TIMEOUT_MS,
) {
    private val lock = Mutex()
    private var pool: ConnectionPool? = null
    private var lastOpen: OpenResult.Success? = null

    private val mutableIsOpen = MutableStateFlow(false)
    public val isOpen: StateFlow<Boolean> = mutableIsOpen.asStateFlow()

    /**
     * Creates a brand-new vault at [VaultPaths.databaseFile]. Fails with
     * [CreateResult.AlreadyExists] if that file is already present — this
     * method never overwrites an existing vault. [key] is zeroed before
     * this call returns, on every path (success, already-exists, failure).
     *
     * On success, the schema is migrated to the current version, a
     * "Default" persona is seeded (the app.skein.core.vault.lifecycle
     * module has no `VaultRepository` dependency yet — `E2.I4` — so the
     * seed row is written directly), and the vault is left OPEN (`isOpen`
     * becomes `true`), matching the "create implies usable" flow the
     * onboarding step (`E6.I13`) expects.
     */
    public suspend fun create(key: ByteArray): CreateResult =
        lock.withLock {
            val dbFile = paths.databaseFile
            if (dbFile.exists()) {
                key.fill(0)
                return@withLock CreateResult.AlreadyExists
            }

            val migrationKey = key.copyOf()
            val liveKey = key.copyOf()
            key.fill(0)

            val migrateResult =
                try {
                    runMigrations(migrationKey, dbFile.absolutePath)
                } catch (ex: SkeinSQLiteException) {
                    liveKey.fill(0)
                    return@withLock CreateResult.Failed(ex.message)
                } catch (ex: EncryptedDatabaseWithoutKeyException) {
                    liveKey.fill(0)
                    return@withLock CreateResult.Failed(ex.message ?: "migration failed")
                }

            val livePool =
                try {
                    ConnectionPool.open(
                        driverFactory = driverFactory,
                        path = dbFile.absolutePath,
                        key = liveKey,
                        readerCount = readerCount,
                        busyTimeoutMs = busyTimeoutMs,
                    )
                } catch (ex: SkeinSQLiteException) {
                    return@withLock CreateResult.Failed(ex.message)
                } catch (ex: EncryptedDatabaseWithoutKeyException) {
                    return@withLock CreateResult.Failed(ex.message ?: "open after create failed")
                }

            try {
                seedDefaultPersona(livePool.writer())
            } catch (ex: SkeinSQLiteException) {
                livePool.closeAll()
                dbFile.delete()
                return@withLock CreateResult.Failed(ex.message)
            }

            val cipherVersion = readCipherVersion(livePool.writer())
            pool = livePool
            // Also primes `lastOpen` so a subsequent `open()` call with the
            // vault already live (e.g. right after `create`) hits the
            // idempotent no-op branch below instead of opening a second,
            // redundant connection.
            lastOpen = OpenResult.Success(migrateResult, cipherVersion)
            mutableIsOpen.value = true
            CreateResult.Success(migrateResult, cipherVersion)
        }

    /**
     * Opens an existing vault at [VaultPaths.databaseFile] with [key],
     * running any pending migrations. [key] is zeroed before this call
     * returns, on every path. Idempotent: calling [open] again while
     * already open is a no-op that returns the previous [OpenResult].
     *
     * A wrong [key] surfaces as [OpenResult.WrongKey] — never as a raw
     * exception — per this module's "typed result over exception dump"
     * contract for expected, user-facing outcomes.
     */
    public suspend fun open(key: ByteArray): OpenResult =
        lock.withLock {
            val alreadyOpen = lastOpen
            if (mutableIsOpen.value && alreadyOpen != null) {
                key.fill(0)
                return@withLock alreadyOpen
            }

            val dbFile = paths.databaseFile
            if (!dbFile.exists()) {
                key.fill(0)
                return@withLock OpenResult.NotFound
            }

            val migrationKey = key.copyOf()
            val liveKey = key.copyOf()
            key.fill(0)

            val migrateResult =
                try {
                    runMigrations(migrationKey, dbFile.absolutePath)
                } catch (ex: SkeinSQLiteException) {
                    liveKey.fill(0)
                    return@withLock OpenResult.WrongKey
                } catch (ex: EncryptedDatabaseWithoutKeyException) {
                    liveKey.fill(0)
                    return@withLock OpenResult.WrongKey
                }

            if (migrateResult.toVersion < 1) {
                liveKey.fill(0)
                return@withLock OpenResult.Failed(
                    "schema user_version is ${migrateResult.toVersion} after migrate() — expected >= 1",
                )
            }

            val livePool =
                try {
                    // Defense-in-depth: SkeinSQLiteDriver.open() already probes
                    // cipher_version internally (and throws on a wrong key), so
                    // reaching this line means the same key just proved correct
                    // one call ago in runMigrations(). This pool's connections
                    // are fresh (per this file's driver single-use note), not a
                    // re-verification of the key.
                    ConnectionPool.open(
                        driverFactory = driverFactory,
                        path = dbFile.absolutePath,
                        key = liveKey,
                        readerCount = readerCount,
                        busyTimeoutMs = busyTimeoutMs,
                    )
                } catch (ex: SkeinSQLiteException) {
                    return@withLock OpenResult.WrongKey
                } catch (ex: EncryptedDatabaseWithoutKeyException) {
                    return@withLock OpenResult.WrongKey
                }

            val cipherVersion = readCipherVersion(livePool.writer())
            pool = livePool
            mutableIsOpen.value = true
            val result = OpenResult.Success(migrateResult, cipherVersion)
            lastOpen = result
            result
        }

    /**
     * Checkpoints the writer connection's WAL with `TRUNCATE` (so `close`
     * leaves no `-wal`/`-shm` residue larger than zero bytes), then closes
     * every connection in the pool via [ConnectionPool.closeAll] (readers
     * first, writer last), and transitions [isOpen] to `false`. A no-op
     * when already closed.
     */
    public suspend fun close() {
        lock.withLock {
            val livePool = pool
            if (livePool != null) {
                try {
                    livePool.writer().prepare("PRAGMA wal_checkpoint(TRUNCATE);").use { it.step() }
                } finally {
                    livePool.closeAll()
                }
            }
            pool = null
            lastOpen = null
            mutableIsOpen.value = false
        }
    }

    /**
     * The live [ConnectionPool] backing this vault — one writer +
     * [readerCount] readers, each with `busy_timeout` and the §4.9
     * PRAGMAs applied (see [ConnectionPool.open]). Callers that need their
     * own service connection (e.g. `app.skein.vault.DeviceVaultOpener`)
     * draw from this instead of constructing a `SkeinSQLiteDriver`
     * themselves. Throws [IllegalStateException] when the vault is not
     * currently open.
     */
    public fun connectionPool(): ConnectionPool = pool ?: throw IllegalStateException("VaultLifecycle is not open")

    /**
     * Runs `PRAGMA integrity_check` on the live connection and separately
     * verifies that every object the bundled migration manifest
     * (`migrations/INDEX.txt`) declares is present in `sqlite_master` —
     * i.e. the live schema has not drifted from what the migrations would
     * produce. Returns [IntegrityResult.NotOpen] if the vault is not
     * currently open.
     */
    public suspend fun integrityCheck(): IntegrityResult =
        lock.withLock {
            val conn = pool?.writer() ?: return@withLock IntegrityResult.NotOpen

            val problems = mutableListOf<String>()
            conn.prepare("PRAGMA integrity_check;").use { stmt ->
                while (stmt.step()) {
                    val row = stmt.getText(0)
                    if (row != "ok") problems += row
                }
            }
            if (problems.isNotEmpty()) return@withLock IntegrityResult.CorruptDatabase(problems)

            val inspector = SchemaInspector(conn)
            val actual = (inspector.tables() + inspector.indexes() + inspector.triggers()).toSet()
            val expected = expectedMigrationObjectNames()
            val missing = expected.filterNot { it in actual }
            if (missing.isNotEmpty()) return@withLock IntegrityResult.SchemaDrift(missing)

            IntegrityResult.Ok
        }

    /**
     * Applies pending migrations against a FRESH connection/driver pair
     * (per this file's header note) and returns the before/after
     * versions. The connection this opens is closed by [Migrator.migrate]
     * itself before returning — it is never the connection this class
     * retains as [connection].
     */
    private fun runMigrations(
        migrationKey: ByteArray,
        path: String,
    ): MigrateResult = migrator(driverFactory(migrationKey)).migrate(path)

    private fun seedDefaultPersona(conn: SQLiteConnection) {
        conn
            .prepare(
                "INSERT INTO personas (id, name, system_prompt, default_model, created_at) VALUES (?, ?, NULL, NULL, ?);",
            ).use { stmt ->
                stmt.bindText(1, newPersonaId())
                stmt.bindText(2, DEFAULT_PERSONA_NAME)
                stmt.bindLong(3, System.currentTimeMillis())
                stmt.step()
            }
    }

    private fun readCipherVersion(conn: SQLiteConnection): String =
        conn.prepare("PRAGMA cipher_version;").use { stmt ->
            if (stmt.step()) stmt.getText(0) else ""
        }

    /**
     * Parses `migrations/INDEX.txt` and the `NNN_*.sql` files it lists, in
     * version order (same classpath convention [Migrator] uses for
     * discovery), and replays each migration's statements to compute the
     * set of schema object names a fresh `migrate()` run should leave
     * behind — i.e. simulates `CREATE`/`DROP` the same way SQLite would,
     * rather than only ever accumulating `CREATE` matches.
     *
     * This matters once a later migration drops an object an earlier one
     * created (skein-7d0l, `007_drop_attachment_master_key.sql`): a
     * name-accumulating scan would keep reporting `attachment_master_key`
     * / `attachment_keys` / `idx_attachment_keys_version` as "expected"
     * forever, and [integrityCheck] would report permanent false-positive
     * [IntegrityResult.SchemaDrift] on every up-to-date vault. `DROP TABLE`
     * is also handled as SQLite itself handles it: dropping a table
     * implicitly drops the indexes and triggers created `ON` it, even
     * without an explicit `DROP INDEX`/`DROP TRIGGER` statement (verified
     * empirically against sqlite3 3.51.0 — see `007_drop_attachment_master_key.sql`'s
     * header comment) — [dependentsOf] tracks that association.
     *
     * `ALTER TABLE` statements are not object-creating/dropping and are
     * intentionally not matched. Returns an empty set (no drift is
     * reported) if the manifest can't be found — the JVM unit test
     * double's classpath does not always carry the production manifest,
     * and reporting drift from an inconclusive check would be a false
     * positive.
     */
    private fun expectedMigrationObjectNames(): Set<String> {
        val classLoader = requireNotNull(javaClass.classLoader) { "no class loader available" }
        val indexStream = classLoader.getResourceAsStream("$MIGRATIONS_PATH/INDEX.txt") ?: return emptySet()
        val fileNames =
            indexStream
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

        val names = mutableSetOf<String>()
        // Object name -> the table it was declared `ON` (indexes/triggers
        // only), so a later `DROP TABLE` can cascade to them too.
        val dependentsOf = mutableMapOf<String, String>()

        for (fileName in fileNames) {
            val sql =
                classLoader
                    .getResourceAsStream("$MIGRATIONS_PATH/$fileName")
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: continue
            for (statement in MigrationStatementSplitter.split(sql)) {
                applyStatement(statement, names, dependentsOf)
            }
        }
        return names
    }

    private fun applyStatement(
        statement: String,
        names: MutableSet<String>,
        dependentsOf: MutableMap<String, String>,
    ) {
        CREATE_TABLE_REGEX.find(statement)?.let { names += it.groupValues[1] }
        CREATE_INDEX_REGEX.find(statement)?.let { match ->
            val (indexName, table) = match.destructured
            names += indexName
            dependentsOf[indexName] = table
        }
        CREATE_TRIGGER_REGEX.find(statement)?.let { match ->
            val (triggerName, table) = match.destructured
            names += triggerName
            dependentsOf[triggerName] = table
        }
        DROP_TABLE_REGEX.find(statement)?.let { match ->
            val table = match.groupValues[1]
            names -= table
            val cascaded = dependentsOf.filterValues { it == table }.keys
            names -= cascaded
            cascaded.forEach { dependentsOf.remove(it) }
        }
        DROP_INDEX_REGEX.find(statement)?.let { match ->
            names -= match.groupValues[1]
            dependentsOf.remove(match.groupValues[1])
        }
        DROP_TRIGGER_REGEX.find(statement)?.let { match ->
            names -= match.groupValues[1]
            dependentsOf.remove(match.groupValues[1])
        }
    }

    private companion object {
        const val DEFAULT_PERSONA_NAME = "Default"
        const val MIGRATIONS_PATH = "migrations"
        val CREATE_TABLE_REGEX =
            Regex("""(?i)CREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        val CREATE_INDEX_REGEX =
            Regex(
                """(?i)CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?\s+ON\s+["`]?(\w+)["`]?""",
            )
        val CREATE_TRIGGER_REGEX =
            Regex(
                """(?is)CREATE\s+TRIGGER\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?.*?\bON\s+["`]?(\w+)["`]?""",
            )
        val DROP_TABLE_REGEX = Regex("""(?i)DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        val DROP_INDEX_REGEX = Regex("""(?i)DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        val DROP_TRIGGER_REGEX = Regex("""(?i)DROP\s+TRIGGER\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")
    }
}

/**
 * Generates a UUIDv7 (RFC 9562) id for the seeded default persona:
 * 48-bit big-endian Unix millisecond timestamp, a version-7 nibble, RFC
 * 4122 variant bits, and 74 bits of `SecureRandom` fill — matching the
 * convention `core/model/.../Vault.kt`'s `PersonaId` typealias documents.
 * Kept local to this file (not a shared `Uuid7` utility) since that
 * utility is a separate, not-yet-landed issue per the v1 plan's
 * `core/vault` file list.
 */
private fun newPersonaId(): String {
    val bytes = ByteArray(16)
    val timestampMs = System.currentTimeMillis()
    bytes[0] = (timestampMs shr 40).toByte()
    bytes[1] = (timestampMs shr 32).toByte()
    bytes[2] = (timestampMs shr 24).toByte()
    bytes[3] = (timestampMs shr 16).toByte()
    bytes[4] = (timestampMs shr 8).toByte()
    bytes[5] = timestampMs.toByte()

    val random = ByteArray(10)
    SecureRandom().nextBytes(random)
    System.arraycopy(random, 0, bytes, 6, 10)

    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    val hex = bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    return buildString {
        append(hex, 0, 8)
        append('-')
        append(hex, 8, 12)
        append('-')
        append(hex, 12, 16)
        append('-')
        append(hex, 16, 20)
        append('-')
        append(hex, 20, 32)
    }
}
