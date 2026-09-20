package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

/**
 * `SQLiteDriver` implementation over Skein's SQLCipher+sqlite-vec+FTS5
 * bundle.
 *
 * The primary constructor takes the raw vault key (32 bytes, sourced from
 * `VaultKeyProvider`, `E3.I2`). [open] runs the §4.9 connection setup on
 * every call where a key was supplied to the constructor:
 *  1. `PRAGMA key = "x'<hex>'"` — the key hex-encoded and passed through
 *     SQLCipher's `x'...'` blob-literal form so raw (non-UTF8) key
 *     material round-trips correctly.
 *  2. `PRAGMA cipher_memory_security = ON;`
 *  3. `PRAGMA foreign_keys = ON;`
 *  4. `PRAGMA journal_mode = WAL;`
 *
 * The constructor's [key] `ByteArray` is zeroed in place immediately after
 * those pragmas run — whether [open] succeeds or throws. This module never
 * persists the key; callers must not reuse the array afterward. A driver
 * instance is a single-use secret holder: it is intended to back exactly
 * one keyed [open] call. Construct a fresh driver (with a fresh key copy
 * from `VaultKeyProvider`) per connection that needs the key applied.
 *
 * After the pragmas (or immediately, when no key was supplied — e.g. the
 * no-arg constructor used for `:memory:` / dev harnesses), [open] runs
 * three sanity probes and throws [SkeinSQLiteException] if any of them
 * fails to return / execute:
 *  1. `PRAGMA cipher_version;` — asserts SQLCipher is active. If the file
 *     exists and holds ciphertext but no key was supplied, the read raises
 *     [EncryptedDatabaseWithoutKeyException] instead. If a key WAS
 *     supplied (i.e. it was simply the wrong one), the raw
 *     [SkeinSQLiteException] — whose message includes SQLite's own
 *     "not a database" text — propagates unchanged, since "no key" is not
 *     an accurate diagnosis in that case.
 *  2. `SELECT vec_version();` — asserts sqlite-vec auto-registered.
 *  3. `CREATE VIRTUAL TABLE temp.__fts_probe USING fts5(x); DROP TABLE
 *     temp.__fts_probe;` — asserts FTS5 is compiled in.
 */
public class SkeinSQLiteDriver internal constructor(
    private val native: SkeinSQLiteNative,
    private var key: ByteArray?,
) : SQLiteDriver {
    /** Production constructor: applies [key] via the §4.9 pragmas on open(). */
    public constructor(key: ByteArray) : this(SkeinSQLiteNativeImpl, key)

    /** No key: parameterless open() — only appropriate for `:memory:` / dev harnesses. */
    public constructor() : this(SkeinSQLiteNativeImpl, null)

    /** Test-only: inject a fake native bridge with no key. */
    internal constructor(native: SkeinSQLiteNative) : this(native, null)

    override fun open(fileName: String): SQLiteConnection {
        val handle = native.nativeOpen(fileName)
        val keySupplied = key != null
        try {
            val k = key
            if (k != null) {
                try {
                    applyKeyAndPragmas(handle, k)
                } finally {
                    // Zero the constructor's copy whether keying/pragma
                    // application succeeded or threw, then drop the
                    // reference — this driver instance is single-use.
                    k.fill(0)
                    key = null
                }
            }
            return finishOpen(handle, keySupplied)
        } catch (t: Throwable) {
            closeQuietly(handle)
            throw t
        }
    }

    /**
     * Open [fileName] with an optional SQLCipher [passphrase], keyed via
     * `sqlite3_key_v2` rather than the `PRAGMA key` blob-literal form used
     * by the primary [key]-bearing constructor.
     *
     * The [passphrase] is zeroed on return; do not reuse it.
     */
    @Deprecated(
        message =
            "Use the primary SkeinSQLiteDriver(key: ByteArray) constructor and open(fileName) " +
                "instead; openWithKey predates the §4.9 PRAGMA-based key setup and is kept only " +
                "for callers that have not migrated yet.",
    )
    public fun openWithKey(
        fileName: String,
        passphrase: ByteArray?,
    ): SQLiteConnection {
        val handle = native.nativeOpen(fileName)
        val keySupplied = passphrase != null
        try {
            if (passphrase != null) {
                try {
                    native.nativeKey(handle, passphrase, passphrase.size)
                } finally {
                    // Zero the caller's buffer whether keying succeeded or threw.
                    passphrase.fill(0)
                }
            }
            return finishOpen(handle, keySupplied)
        } catch (t: Throwable) {
            // Best-effort close; also zero the passphrase if we didn't
            // reach the finally above.
            passphrase?.fill(0)
            closeQuietly(handle)
            throw t
        }
    }

    /** Applies the §4.9 connection setup PRAGMAs using the raw vault key [k]. */
    private fun applyKeyAndPragmas(
        handle: Long,
        k: ByteArray,
    ) {
        val hex = k.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        native.nativeExec(handle, "PRAGMA key = \"x'$hex'\";")
        native.nativeExec(handle, "PRAGMA cipher_memory_security = ON;")
        native.nativeExec(handle, "PRAGMA foreign_keys = ON;")
        native.nativeExec(handle, "PRAGMA journal_mode = WAL;")
    }

    private fun finishOpen(
        handle: Long,
        keySupplied: Boolean,
    ): SQLiteConnection {
        val connection = SkeinSQLiteConnection(native, handle)
        verifyExtensions(connection, keySupplied)
        return connection
    }

    private fun closeQuietly(handle: Long) {
        try {
            native.nativeClose(handle)
        } catch (_: Throwable) {
            // Swallow — the outer failure is more informative.
        }
    }

    /**
     * Assert cipher/vec/FTS5 are all live on this connection. Order is
     * deterministic so error messages point at the specific missing
     * component.
     *
     * [keySupplied] distinguishes "no key was given" (heuristically
     * reported as [EncryptedDatabaseWithoutKeyException]) from "a key WAS
     * given but it's the wrong one" (the raw [SkeinSQLiteException], whose
     * message carries SQLite's own diagnosis, propagates unchanged).
     */
    private fun verifyExtensions(
        conn: SkeinSQLiteConnection,
        keySupplied: Boolean,
    ) {
        // 1. cipher_version — errors on an encrypted DB opened without (or
        // with the wrong) key.
        try {
            conn.prepare("PRAGMA cipher_version").use { stmt ->
                if (!stmt.step() || stmt.getColumnCount() == 0) {
                    throw SkeinSQLiteException(
                        resultCode = SQLITE_ERROR,
                        message = "extension not available: cipher_version returned no rows",
                    )
                }
                val version = stmt.getText(0)
                if (version.isEmpty()) {
                    throw SkeinSQLiteException(
                        resultCode = SQLITE_ERROR,
                        message = "extension not available: cipher_version empty (is SQLCipher linked?)",
                    )
                }
            }
        } catch (ex: SkeinSQLiteException) {
            // NOTADB on the first read of an encrypted file usually means
            // "no key was supplied" — but only when that's actually true.
            // When a key WAS supplied, NOTADB means "wrong key", and the
            // raw exception (message includes SQLite's "not a database"
            // text) is the more accurate diagnosis.
            if (ex.resultCode == SQLITE_NOTADB && !keySupplied) {
                throw EncryptedDatabaseWithoutKeyException(
                    "database appears encrypted; supply a key via the SkeinSQLiteDriver(key) " +
                        "constructor or openWithKey()",
                )
            }
            throw ex
        }

        // 2. vec_version — sqlite-vec is registered via sqlite3_auto_extension.
        conn.prepare("SELECT vec_version()").use { stmt ->
            if (!stmt.step()) {
                throw SkeinSQLiteException(
                    resultCode = SQLITE_ERROR,
                    message = "extension not available: sqlite-vec",
                )
            }
        }

        // 3. FTS5 probe — creating and dropping a temp virtual table.
        try {
            conn.exec("CREATE VIRTUAL TABLE temp.__skein_fts_probe USING fts5(x)")
            conn.exec("DROP TABLE temp.__skein_fts_probe")
        } catch (ex: SkeinSQLiteException) {
            throw SkeinSQLiteException(
                resultCode = ex.resultCode,
                message = "extension not available: FTS5 (${ex.message})",
            )
        }
    }

    private companion object {
        // sqlite3.h result codes we branch on. Kept private and named to
        // avoid pulling a whole constants file for three ints.
        const val SQLITE_ERROR = 1
        const val SQLITE_NOTADB = 26
    }
}
