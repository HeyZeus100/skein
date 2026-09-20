package app.skein.core.vault.db

import androidx.sqlite.SQLiteStatement

/**
 * `SQLiteStatement` backed by a `sqlite3_stmt*` handle managed by
 * [SkeinSQLiteNative]. One-to-one wrapper: no batching, no result-set
 * caching. Callers are expected to `close()` (or use the connection's
 * `prepare {}` helper when we add one).
 */
public class SkeinSQLiteStatement internal constructor(
    private val native: SkeinSQLiteNative,
    private val stmtHandle: Long,
) : SQLiteStatement {
    private var closed = false

    override fun bindBlob(
        index: Int,
        value: ByteArray,
    ) {
        checkOpen()
        native.nativeBindBlob(stmtHandle, index, value)
    }

    override fun bindDouble(
        index: Int,
        value: Double,
    ) {
        checkOpen()
        native.nativeBindDouble(stmtHandle, index, value)
    }

    override fun bindLong(
        index: Int,
        value: Long,
    ) {
        checkOpen()
        native.nativeBindLong(stmtHandle, index, value)
    }

    override fun bindText(
        index: Int,
        value: String,
    ) {
        checkOpen()
        native.nativeBindText(stmtHandle, index, value)
    }

    override fun bindNull(index: Int) {
        checkOpen()
        native.nativeBindNull(stmtHandle, index)
    }

    override fun getBlob(index: Int): ByteArray {
        checkOpen()
        return native.nativeColumnBlob(stmtHandle, index)
    }

    override fun getDouble(index: Int): Double {
        checkOpen()
        return native.nativeColumnDouble(stmtHandle, index)
    }

    override fun getLong(index: Int): Long {
        checkOpen()
        return native.nativeColumnLong(stmtHandle, index)
    }

    override fun getText(index: Int): String {
        checkOpen()
        return native.nativeColumnText(stmtHandle, index) ?: ""
    }

    override fun isNull(index: Int): Boolean {
        checkOpen()
        // SQLITE_NULL == 5
        return native.nativeColumnType(stmtHandle, index) == SQLITE_TYPE_NULL
    }

    override fun getColumnCount(): Int {
        checkOpen()
        return native.nativeColumnCount(stmtHandle)
    }

    override fun getColumnName(index: Int): String {
        checkOpen()
        return native.nativeColumnName(stmtHandle, index) ?: ""
    }

    override fun getColumnType(index: Int): Int {
        checkOpen()
        return native.nativeColumnType(stmtHandle, index)
    }

    override fun step(): Boolean {
        checkOpen()
        return native.nativeStep(stmtHandle)
    }

    override fun reset() {
        checkOpen()
        native.nativeReset(stmtHandle)
    }

    override fun clearBindings() {
        checkOpen()
        native.nativeClearBindings(stmtHandle)
    }

    override fun close() {
        if (!closed) {
            closed = true
            native.nativeFinalize(stmtHandle)
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("SkeinSQLiteStatement is closed")
    }

    private companion object {
        // From sqlite3.h: SQLITE_INTEGER=1, FLOAT=2, TEXT=3, BLOB=4, NULL=5.
        const val SQLITE_TYPE_NULL = 5
    }
}
