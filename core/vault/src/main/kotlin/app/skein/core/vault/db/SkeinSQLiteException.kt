package app.skein.core.vault.db

/**
 * Thrown when a call into [SkeinSQLiteNative] returns a non-`SQLITE_OK`
 * result code. The [resultCode] is the raw `sqlite3` return; [message] is
 * `sqlite3_errmsg(db)` at the moment the failure was observed.
 *
 * Never carries the passphrase or any bound parameter — the JNI shim
 * (native/sqlite/androidx-jni/skein_jni.c) constructs this with just the
 * result code and errmsg. Adding parameter values would risk secrets in
 * logs.
 */
public class SkeinSQLiteException(
    public val resultCode: Int,
    override val message: String,
) : RuntimeException(message)

/**
 * Thrown when opening what looks like an encrypted database without a
 * passphrase (SQLite reports `SQLITE_NOTADB` / `file is not a database`
 * on the first PRAGMA after open). Callers should treat this as
 * "prompt for passphrase" rather than "corrupt file".
 */
public class EncryptedDatabaseWithoutKeyException(
    message: String,
) : RuntimeException(message)
