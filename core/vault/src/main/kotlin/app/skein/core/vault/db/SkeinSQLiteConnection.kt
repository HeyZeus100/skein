package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection
import app.skein.core.model.LexicalQueryLimits

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

    /** Must be called under the same connection ownership as prepared statements. */
    internal fun lexicalTerms(text: String): List<String> {
        checkOpen()
        if (text.length > LexicalQueryLimits.MAX_TEXT_UTF8_BYTES) return emptyList()
        val utf8 = text.toByteArray(Charsets.UTF_8)
        try {
            if (utf8.size > LexicalQueryLimits.MAX_TEXT_UTF8_BYTES) return emptyList()
            val packed = native.nativeLexicalTerms(dbHandle, utf8)
            return try {
                packed.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
            } finally {
                packed.fill(0)
            }
        } finally {
            utf8.fill(0)
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("SkeinSQLiteConnection is closed")
    }
}
