package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection

/**
 * `SQLiteConnection` backed by a `sqlite3*` handle. Prepared statements
 * are owned by the caller after `prepare()` returns — this connection
 * does NOT track them for auto-close.
 *
 * `close()` is idempotent. After close, [prepare] throws
 * [IllegalStateException].
 */
public class SkeinSQLiteConnection internal constructor(
    private val native: SkeinSQLiteNative,
    private val dbHandle: Long,
) : SQLiteConnection {
    private var closed = false

    override fun prepare(sql: String): SkeinSQLiteStatement {
        checkOpen()
        val stmtHandle = native.nativePrepare(dbHandle, sql)
        return SkeinSQLiteStatement(native, stmtHandle)
    }

    override fun close() {
        if (!closed) {
            closed = true
            native.nativeClose(dbHandle)
        }
    }

    /**
     * Execute a statement that returns no rows (PRAGMA, DDL). Convenience
     * wrapper around `sqlite3_exec`, used by [SkeinSQLiteDriver] to run
     * `PRAGMA key` / extension probes.
     */
    internal fun exec(sql: String) {
        checkOpen()
        native.nativeExec(dbHandle, sql)
    }

    /**
     * Number of rows changed by the last modifying statement on this
     * connection. Delegates to `sqlite3_changes64`.
     */
    public fun changes(): Long {
        checkOpen()
        return native.nativeChanges(dbHandle)
    }

    /** Delegates to `sqlite3_last_insert_rowid`. */
    public fun lastInsertRowId(): Long {
        checkOpen()
        return native.nativeLastInsertRowId(dbHandle)
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("SkeinSQLiteConnection is closed")
    }
}
