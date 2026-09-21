// skein-4qol — small per-vault connection pool: one writer + N reader
// `SQLiteConnection`s, opened from a single key copy and each carrying the
// plan's `busy_timeout` PRAGMA so cross-connection write contention
// (persona/index writes vs. repository writes) surfaces as a bounded wait
// instead of an immediate SQLITE_BUSY. Owned by [VaultLifecycle] — see that
// file for how [VaultLifecycle.open] / [VaultLifecycle.create] build one of
// these via [ConnectionPool.open] and [VaultLifecycle.close] tears it down
// via [closeAll]. [app.skein.vault.DeviceVaultOpener] (`:app`) is the
// motivating caller: it used to construct a fresh `SkeinSQLiteDriver`
// itself for every one of the five service connections it needed (plus
// `VaultLifecycle`'s own retained connection — six keyed connections, none
// with a `busy_timeout`). It now draws every connection it needs from
// [VaultLifecycle.connectionPool] instead.
//
// The §4.9 PRAGMAs (key, cipher_memory_security, foreign_keys,
// journal_mode) are already applied per-connection by
// [SkeinSQLiteDriver.open] itself — this class's job is strictly the parts
// the driver does NOT already do: fanning one key copy out into N
// independent connections, and the `busy_timeout` PRAGMA neither the driver
// nor `Migrator` currently sets.

package app.skein.core.vault.lifecycle

import androidx.sqlite.SQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver

/**
 * Thrown by [ConnectionPool.writer] / [ConnectionPool.readers] once
 * [ConnectionPool.closeAll] has run. A typed exception rather than a
 * generic [IllegalStateException] dump — matches this module's convention
 * (see `SkeinSQLiteException.kt`, `VaultLifecycle`'s sealed results) of
 * naming an expected-misuse failure mode rather than leaving callers to
 * `catch (IllegalStateException)` and guess whether they hit this or
 * something else.
 */
public class ConnectionPoolClosedException(
    message: String,
) : IllegalStateException(message)

/**
 * One writer [SQLiteConnection] plus [readers] reader connections, all
 * opened against the same vault file from the same key. Construct via the
 * companion [ConnectionPool.open] — the private constructor only assembles
 * connections that are already open.
 *
 * Thread-safe: [writer], [readers], and [closeAll] may be called from any
 * thread without external synchronization — every accessor is guarded by
 * an internal lock, so a [closeAll] racing a [writer] call always sees a
 * consistent live-or-closed view rather than a torn one. [closeAll] is
 * idempotent (a second call is a no-op) and closes connections in a fixed
 * order — every reader first, in reverse-open order, then the writer last
 * — so the writer (the connection a WAL checkpoint would run against, see
 * [VaultLifecycle.close]) always outlives every reader that might still be
 * mid-statement against the same file. No connection this pool handed out
 * via [writer] / [readers] is ever returned again once [closeAll] has run:
 * both accessors throw [ConnectionPoolClosedException] instead.
 */
public class ConnectionPool private constructor(
    private var liveWriter: SQLiteConnection?,
    private var liveReaders: List<SQLiteConnection>?,
) {
    private val lock = Any()

    /**
     * The single writer connection. Throws [ConnectionPoolClosedException]
     * once [closeAll] has run.
     */
    public fun writer(): SQLiteConnection =
        synchronized(lock) {
            liveWriter ?: throw ConnectionPoolClosedException("ConnectionPool is closed")
        }

    /**
     * The reader connections, in the order they were opened. Throws
     * [ConnectionPoolClosedException] once [closeAll] has run.
     */
    public fun readers(): List<SQLiteConnection> =
        synchronized(lock) {
            liveReaders ?: throw ConnectionPoolClosedException("ConnectionPool is closed")
        }

    /**
     * Closes every connection this pool holds — readers first (in
     * reverse-open order), the writer last — then releases this instance's
     * own references so [writer] / [readers] throw afterward. A second
     * call is a harmless no-op. Each connection's own `close()` is
     * independently idempotent (`SkeinSQLiteConnection`'s contract), so a
     * connection a caller already closed directly (e.g. a service's own
     * `close()`, ahead of the owning [VaultLifecycle.close]) is simply
     * closed again here without error.
     */
    public fun closeAll() {
        synchronized(lock) {
            val readers = liveReaders
            val writer = liveWriter
            liveReaders = null
            liveWriter = null
            readers?.asReversed()?.forEach { runCatching { it.close() } }
            writer?.close()
        }
    }

    public companion object {
        /** Matches the plan's `ConnectionPool (1 writer + 2 readers)` note (E2.I4). */
        public const val DEFAULT_READER_COUNT: Int = 2

        /**
         * Default `PRAGMA busy_timeout`, in milliseconds. Neither the v1
         * plan nor the design docs under `docs/design` name a specific
         * value (skein-4qol's own DESCRIPTION only asks for one); 5000ms
         * is this class's chosen default — long enough to ride out a competing writer's
         * `BEGIN IMMEDIATE ... COMMIT` without surfacing `SQLITE_BUSY` to
         * an interactive caller, short enough not to make a genuinely
         * stuck lock look like a hang.
         */
        public const val DEFAULT_BUSY_TIMEOUT_MS: Long = 5000L

        /**
         * Opens one writer + [readerCount] reader connections against
         * [path] using [driverFactory], deriving each connection's own key
         * copy from a single [key] — [key] itself is zeroed once every
         * connection has its own copy, on every path (success or
         * failure); this call never reuses [key] itself as a connection's
         * key (each [SkeinSQLiteDriver] is single-use and zeroes whatever
         * array it is given — see that class's header — so passing the
         * same array to two `driverFactory` calls would leave the second
         * with an all-zero key).
         *
         * [busyTimeoutMs] is applied via `PRAGMA busy_timeout` on every
         * connection opened, in addition to the §4.9 PRAGMAs
         * [SkeinSQLiteDriver.open] already applies per-connection.
         *
         * On any failure partway through, every connection already opened
         * is closed (reverse-open order) before the triggering exception
         * propagates unchanged — no connection ever escapes a failed
         * [open] call.
         */
        public fun open(
            driverFactory: (key: ByteArray) -> SkeinSQLiteDriver,
            path: String,
            key: ByteArray,
            readerCount: Int = DEFAULT_READER_COUNT,
            busyTimeoutMs: Long = DEFAULT_BUSY_TIMEOUT_MS,
        ): ConnectionPool {
            val opened = ArrayList<SQLiteConnection>(readerCount + 1)
            try {
                val writer = openOne(driverFactory, path, key, busyTimeoutMs).also(opened::add)
                val readers =
                    List(readerCount) {
                        openOne(driverFactory, path, key, busyTimeoutMs).also(opened::add)
                    }
                key.fill(0)
                return ConnectionPool(writer, readers)
            } catch (t: Throwable) {
                key.fill(0)
                opened.asReversed().forEach { runCatching { it.close() } }
                throw t
            }
        }

        private fun openOne(
            driverFactory: (key: ByteArray) -> SkeinSQLiteDriver,
            path: String,
            key: ByteArray,
            busyTimeoutMs: Long,
        ): SQLiteConnection {
            val connection = driverFactory(key.copyOf()).open(path)
            connection.prepare("PRAGMA busy_timeout = $busyTimeoutMs;").use { it.step() }
            return connection
        }
    }
}
