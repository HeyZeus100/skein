package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

/**
 * `SQLiteDriver` implementation over Skein's SQLCipher+sqlite-vec+FTS5
 * bundle. Every connection is opened via SQLCipher, so passphrases MUST
 * be applied via [openWithKey]; the parameterless [open] is only
 * appropriate for temporary/in-memory DBs whose contents never survive
 * the process.
 *
 * On open, the driver runs three sanity probes and throws
 * [SkeinSQLiteException] if any of them fails to return / execute:
 *  1. `PRAGMA cipher_version;` — asserts SQLCipher is active. If the
 *     file exists and holds ciphertext but no key has been supplied,
 *     the read raises [EncryptedDatabaseWithoutKeyException].
 *  2. `SELECT vec_version();` — asserts sqlite-vec auto-registered.
 *  3. `CREATE VIRTUAL TABLE temp.__fts_probe USING fts5(x); DROP TABLE
 *     temp.__fts_probe;` — asserts FTS5 is compiled in.
 *
 * Passphrase handling: [openWithKey] copies the passphrase byte-for-byte
 * into SQLCipher via `sqlite3_key_v2` (through the JNI shim), then
 * zeroes the caller's `ByteArray` before the method returns — whether
 * the open succeeded or threw. Callers should NOT reuse the array
 * afterwards.
 */
public class SkeinSQLiteDriver internal constructor(
    private val native: SkeinSQLiteNative,
) : SQLiteDriver {
    /** Production constructor: uses the JNI-backed native bridge. */
    public constructor() : this(SkeinSQLiteNativeImpl)

    override fun open(fileName: String): SQLiteConnection = openWithKey(fileName, passphrase = null)

    /**
     * Open [fileName] with an optional SQLCipher [passphrase]. If null,
     * the DB is opened in plaintext mode (only appropriate for
     * `:memory:` and dev harnesses).
     *
     * The [passphrase] is zeroed on return; do not reuse it.
     */
    public fun openWithKey(
        fileName: String,
        passphrase: ByteArray?,
    ): SQLiteConnection {
        val handle = native.nativeOpen(fileName)
        try {
            if (passphrase != null) {
                try {
                    native.nativeKey(handle, passphrase, passphrase.size)
                } finally {
                    // Zero the caller's buffer whether keying succeeded or threw.
                    passphrase.fill(0)
                }
            }
            val connection = SkeinSQLiteConnection(native, handle)
            verifyExtensions(connection)
            return connection
        } catch (t: Throwable) {
            // Best-effort close; also zero the passphrase if we didn't
            // reach the finally above.
            passphrase?.fill(0)
            try {
                native.nativeClose(handle)
            } catch (_: Throwable) {
                // Swallow — outer failure is more informative.
            }
            throw t
        }
    }

    /**
     * Assert cipher/vec/FTS5 are all live on this connection. Order is
     * deterministic so error messages point at the specific missing
     * component.
     */
    private fun verifyExtensions(conn: SkeinSQLiteConnection) {
        // 1. cipher_version — errors on an encrypted DB opened without key.
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
            // NOTADB / errors on the first read of an encrypted file
            // usually mean "no key or wrong key".
            if (ex.resultCode == SQLITE_NOTADB) {
                throw EncryptedDatabaseWithoutKeyException(
                    "database appears encrypted; supply the passphrase via openWithKey()",
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
