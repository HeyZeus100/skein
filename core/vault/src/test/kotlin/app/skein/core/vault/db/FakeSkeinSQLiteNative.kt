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
    ): Int = 3 // SQLITE_TEXT

    override fun nativeColumnText(
        stmtHandle: Long,
        index: Int,
    ): String? {
        val sql = prepareCalls.getOrNull((stmtHandle - 100L).toInt()) ?: return null
        return when {
            "cipher_version" in sql -> fakeCipherVersion
            "vec_version" in sql -> fakeVecVersion
            else -> ""
        }
    }

    override fun nativeColumnLong(
        stmtHandle: Long,
        index: Int,
    ): Long = 0

    override fun nativeColumnDouble(
        stmtHandle: Long,
        index: Int,
    ): Double = 0.0

    override fun nativeColumnBlob(
        stmtHandle: Long,
        index: Int,
    ): ByteArray = ByteArray(0)

    override fun nativeBindNull(
        stmtHandle: Long,
        index: Int,
    ) = Unit

    override fun nativeBindLong(
        stmtHandle: Long,
        index: Int,
        value: Long,
    ) = Unit

    override fun nativeBindDouble(
        stmtHandle: Long,
        index: Int,
        value: Double,
    ) = Unit

    override fun nativeBindText(
        stmtHandle: Long,
        index: Int,
        value: String,
    ) = Unit

    override fun nativeBindBlob(
        stmtHandle: Long,
        index: Int,
        value: ByteArray,
    ) = Unit

    companion object {
        const val DB_HANDLE: Long = 42L
    }
}
