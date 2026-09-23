package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

/**
 * `SQLiteDriver` implementation over Skein's SQLCipher+sqlite-vec+FTS5
 * bundle.
 *
 * The primary constructor takes the raw vault key (32 bytes, sourced from
 * `VaultKeyProvider`, `E3.I2`). [open] runs the §4.9 connection setup PRAGMA
 * sequence on every call:
 *  1. `PRAGMA key = "x'<hex>'"` — the key hex-encoded and passed through
 *     SQLCipher's `x'...'` blob-literal form so raw (non-UTF8) key
 *     material round-trips correctly. Only run when a key was supplied to
 *     the constructor ([applyKey]).
 *  2. `PRAGMA cipher_memory_security = ON;` — also key-only ([applyKey]).
 *  3. `PRAGMA foreign_keys = ON;`
 *  4. `PRAGMA journal_mode = WAL;`
 *
 * Steps 3 and 4 ([applyConnectionPragmas]) are **unconditional** — they run
 * whether or not a key was supplied, and whether the connection was opened
 * via [open] or the deprecated [openWithKey]. Gating them on "was a key
 * supplied" was `skein-gg11.10`'s bug: an unkeyed connection (`:memory:` /
 * dev harness, or `openWithKey(fileName, passphrase = null)`) silently ran
 * with `PRAGMA foreign_keys` at SQLite's OFF default, so a
 * `document_revisions` row never cascaded off its deleted `documents` row —
 * see [applyConnectionPragmas]'s KDoc.
 *
 * The constructor's [key] `ByteArray` is zeroed in place immediately after
 * [applyKey] runs — whether [open] succeeds or throws. This module never
 * persists the key; callers must not reuse the array afterward. A driver
 * instance is a single-use secret holder: it is intended to back exactly
 * one keyed [open] call. Construct a fresh driver (with a fresh key copy
 * from `VaultKeyProvider`) per connection that needs the key applied.
 *
 * After the pragmas, [open] runs three sanity probes and throws
 * [SkeinSQLiteException] if any of them fails to return / execute:
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
                    applyKey(handle, k)
                } finally {
                    // Zero the constructor's copy whether keying/pragma
                    // application succeeded or threw, then drop the
                    // reference — this driver instance is single-use.
                    k.fill(0)
                    key = null
                }
            }
            // Unconditional, regardless of whether a key was supplied: an
            // unkeyed (`:memory:` / dev harness) connection must still
            // enforce FK constraints and use WAL, exactly like a keyed
            // production connection — see [applyConnectionPragmas]'s KDoc
            // for why these must NOT be gated on `keySupplied`.
            applyConnectionPragmas(handle)
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
            // Same [applyConnectionPragmas] call the primary open() makes —
            // this deprecated entry point predates the §4.9 PRAGMA setup
            // but must not skip the two PRAGMAs that apply regardless of
            // whether a key was supplied (skein-gg11.10: an unkeyed
            // `openWithKey(fileName, passphrase = null)` connection —
            // exactly what every unencrypted-`:memory:` contract test uses
            // — silently ran with FK enforcement OFF, so a document delete
            // never cascaded to `document_revisions` on the real driver).
            applyConnectionPragmas(handle)
            return finishOpen(handle, keySupplied)
        } catch (t: Throwable) {
            // Best-effort close; also zero the passphrase if we didn't
            // reach the finally above.
            passphrase?.fill(0)
            closeQuietly(handle)
            throw t
        }
    }

    /**
     * Applies the two key-only §4.9 PRAGMAs (`PRAGMA key`,
     * `PRAGMA cipher_memory_security`) using the raw vault key [k]. Only
     * meaningful when a key was actually supplied — SQLCipher's `PRAGMA
     * key` must be the very first statement run on a freshly-opened
     * connection when used, so this always runs before
     * [applyConnectionPragmas].
     */
    private fun applyKey(
        handle: Long,
        k: ByteArray,
    ) {
        val hex = k.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        native.nativeExec(handle, "PRAGMA key = \"x'$hex'\";")
        native.nativeExec(handle, "PRAGMA cipher_memory_security = ON;")
    }

    /**
     * Applies the two §4.9 PRAGMAs that are NOT key-specific
     * (`PRAGMA foreign_keys`, `PRAGMA journal_mode`) — called
     * unconditionally by both [open] and [openWithKey], whether or not a
     * key was supplied.
     *
     * These two must never be gated behind "was a key supplied" the way
     * [applyKey]'s pair are: `PRAGMA foreign_keys` defaults OFF per
     * SQLite connection regardless of encryption, and an unkeyed
     * connection (`:memory:` / dev harness, or the deprecated
     * [openWithKey] called with `passphrase = null`) is exactly the shape
     * every JVM-unreachable, real-SQLite contract test opens against
     * (skein-gg11.10: `VaultRepositoryImplContractTest` builds its
     * connection via `driver.openWithKey(":memory:", passphrase = null)`
     * — before this fix, that connection never ran `PRAGMA
     * foreign_keys = ON` at all, so `document_revisions`' `ON DELETE
     * CASCADE` from `documents` never fired and a document delete left
     * its revision rows behind on the real driver, exactly the failure
     * `deleting_a_document_cascades_its_revisions_ahead_of_any_sweep` /
     * `a_citation_into_a_deleted_document_does_not_match` caught on the
     * emulator lane). Production connections (`ConnectionPool.open`, via
     * the keyed `SkeinSQLiteDriver(key)` constructor and [open]) already
     * ran these two correctly — the gap was specific to the unkeyed path.
     */
    private fun applyConnectionPragmas(handle: Long) {
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
