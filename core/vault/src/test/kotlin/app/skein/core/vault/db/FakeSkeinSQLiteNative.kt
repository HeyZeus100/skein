package app.skein.core.vault.db

/**
 * Host-JVM fake for [SkeinSQLiteNative]. Records every call so tests can
 * assert on ordering, argument values, and passphrase zeroing. Handles
 * are dispensed as small fixed longs so the tests can check
 * open/close balance.
 */
internal class FakeSkeinSQLiteNative : SkeinSQLiteNative {
    val prepareCalls = mutableListOf<String>()
    val execCalls = mutableListOf<String>()
    val closedHandles = mutableListOf<Long>()

    /** The bytes seen by nativeKey BEFORE the caller zeroed them. */
    var nativeKeyBytesSeen: List<Byte>? = null

    var throwOnKey = false
    var cipherProbeError: SkeinSQLiteException? = null

    var fakeCipherVersion: String = "4.17.0 community"
    var fakeVecVersion: String = "v0.1.9"

    /** When false, the vec_version prepare returns a stmt that reports no rows. */
    var vecProbeReturnsRow: Boolean = true

    /**
     * Simulated PRAGMA state, mutated by [nativeExec] when it sees the
     * corresponding `PRAGMA ... = ...;` set statement, and read back by
     * [nativeColumnText] / [nativeColumnLong] for a subsequent read-only
     * `PRAGMA journal_mode` / `PRAGMA foreign_keys` prepare+step.
     */
    var journalMode: String = "delete"
    var foreignKeysEnabled: Long = 0L

    /**
     * Simulated `PRAGMA user_version`, mutated when [nativePrepare] sees a
     * `PRAGMA user_version = N;` SET statement (skein-yrp / `E2.I13`:
     * `Migrator.applyMigration` runs that PRAGMA as a prepared statement —
     * via `conn.prepare(sql).use { it.step() }` — rather than through
     * [nativeExec], unlike the key-setup pragmas above). Read back by
     * [nativeColumnLong] for a subsequent `PRAGMA user_version;` read.
     */
    var userVersion: Long = 0L

    /** Hex payload of the last `PRAGMA key = "x'...'"` exec, if any. */
    var lastKeyPragmaHex: String? = null

    /** Bound parameter values, keyed by (stmtHandle, 1-based index). */
    private val boundValues = mutableMapOf<Pair<Long, Int>, Any>()

    private var nextStmtHandle = 100L

    override fun nativeOpen(fileName: String): Long = DB_HANDLE

    override fun nativeClose(dbHandle: Long) {
        closedHandles += dbHandle
    }

    override fun nativeKey(
        dbHandle: Long,
        passphrase: ByteArray,
        length: Int,
    ) {
        // Snapshot the bytes we saw so the test can verify the caller
        // zeroed the array AFTER we returned.
        nativeKeyBytesSeen = passphrase.copyOf().toList()
        if (throwOnKey) throw SkeinSQLiteException(1, "fake nativeKey failure")
    }

    override fun nativeExec(
        dbHandle: Long,
        sql: String,
    ) {
        execCalls += sql
        when {
            sql.startsWith("PRAGMA journal_mode = WAL", ignoreCase = true) -> journalMode = "wal"
            sql.startsWith("PRAGMA foreign_keys = ON", ignoreCase = true) -> foreignKeysEnabled = 1L
            sql.startsWith("PRAGMA key", ignoreCase = true) ->
                lastKeyPragmaHex = Regex("x'([0-9a-fA-F]+)'").find(sql)?.groupValues?.get(1)
        }
    }

    override fun nativeChanges(dbHandle: Long): Long = 0

    override fun nativeLastInsertRowId(dbHandle: Long): Long = 0

    override fun nativePrepare(
        dbHandle: Long,
        sql: String,
    ): Long {
        prepareCalls += sql
        if ("cipher_version" in sql && cipherProbeError != null) {
            throw cipherProbeError!!
        }
        USER_VERSION_SET_REGEX.find(sql)?.let { match ->
            userVersion = match.groupValues[1].toLong()
        }
        return nextStmtHandle++
    }

    override fun nativeFinalize(stmtHandle: Long) = Unit

    override fun nativeReset(stmtHandle: Long) = Unit

    override fun nativeClearBindings(stmtHandle: Long) = Unit

    override fun nativeStep(stmtHandle: Long): Boolean {
        val sql = prepareCalls.getOrNull((stmtHandle - 100L).toInt()) ?: return false
        return when {
            "cipher_version" in sql -> true
            "vec_version" in sql -> vecProbeReturnsRow
            else -> true
        }
    }

    override fun nativeColumnCount(stmtHandle: Long): Int = 1

    override fun nativeColumnName(
        stmtHandle: Long,
        index: Int,
    ): String? = "value"

    override fun nativeColumnType(
        stmtHandle: Long,
        index: Int,
    ): Int =
        when (boundValues[stmtHandle to (index + 1)]) {
            NullValue -> SQLITE_TYPE_NULL
            is Long -> SQLITE_TYPE_INTEGER
            is Double -> SQLITE_TYPE_FLOAT
            is ByteArray -> SQLITE_TYPE_BLOB
            else -> SQLITE_TYPE_TEXT
        }

    override fun nativeColumnText(
        stmtHandle: Long,
        index: Int,
    ): String? {
        (boundValues[stmtHandle to (index + 1)] as? String)?.let { return it }
        val sql = prepareCalls.getOrNull((stmtHandle - 100L).toInt()) ?: return null
        return when {
            "cipher_version" in sql -> fakeCipherVersion
            "vec_version" in sql -> fakeVecVersion
            "journal_mode" in sql -> journalMode
            else -> ""
        }
    }

    override fun nativeColumnLong(
        stmtHandle: Long,
        index: Int,
    ): Long {
        (boundValues[stmtHandle to (index + 1)] as? Long)?.let { return it }
        val sql = prepareCalls.getOrNull((stmtHandle - 100L).toInt()) ?: return 0
        return when {
            "foreign_keys" in sql -> foreignKeysEnabled
            "user_version" in sql -> userVersion
            else -> 0
        }
    }

    override fun nativeColumnDouble(
        stmtHandle: Long,
        index: Int,
    ): Double = (boundValues[stmtHandle to (index + 1)] as? Double) ?: 0.0

    override fun nativeColumnBlob(
        stmtHandle: Long,
        index: Int,
    ): ByteArray = (boundValues[stmtHandle to (index + 1)] as? ByteArray) ?: ByteArray(0)

    override fun nativeBindNull(
        stmtHandle: Long,
        index: Int,
    ) {
        boundValues[stmtHandle to index] = NullValue
    }

    override fun nativeBindLong(
        stmtHandle: Long,
        index: Int,
        value: Long,
    ) {
        boundValues[stmtHandle to index] = value
    }

    override fun nativeBindDouble(
        stmtHandle: Long,
        index: Int,
        value: Double,
    ) {
        boundValues[stmtHandle to index] = value
    }

    override fun nativeBindText(
        stmtHandle: Long,
        index: Int,
        value: String,
    ) {
        boundValues[stmtHandle to index] = value
    }

    override fun nativeBindBlob(
        stmtHandle: Long,
        index: Int,
        value: ByteArray,
    ) {
        boundValues[stmtHandle to index] = value
    }

    /** Sentinel stored in [boundValues] to distinguish "bound NULL" from "not bound". */
    private object NullValue

    companion object {
        const val DB_HANDLE: Long = 42L
        private val USER_VERSION_SET_REGEX = Regex("""PRAGMA\s+user_version\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        // From sqlite3.h: SQLITE_INTEGER=1, FLOAT=2, TEXT=3, BLOB=4, NULL=5.
        private const val SQLITE_TYPE_INTEGER = 1
        private const val SQLITE_TYPE_FLOAT = 2
        private const val SQLITE_TYPE_TEXT = 3
        private const val SQLITE_TYPE_BLOB = 4
        private const val SQLITE_TYPE_NULL = 5
    }
}
