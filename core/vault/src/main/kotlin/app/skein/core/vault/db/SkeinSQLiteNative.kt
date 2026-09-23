package app.skein.core.vault.db

/**
 * Bridge interface between the Kotlin driver and the JNI shim
 * (`libskein_sqlite_jni.so`, source at
 * `native/sqlite/androidx-jni/skein_jni.c`). Extracted as an interface so
 * unit tests can substitute a fake without loading the native library on
 * the host JVM.
 *
 * All handles (`dbHandle`, `stmtHandle`) are opaque `sqlite3*` /
 * `sqlite3_stmt*` pointers passed through as `Long`. All methods throw
 * [SkeinSQLiteException] on any non-`SQLITE_OK` result code from
 * SQLite/SQLCipher.
 *
 * Passphrase discipline: [nativeKey] reads the [passphrase] `ByteArray`
 * via `GetByteArrayElements(JNI_ABORT)`, so the JVM copy is not modified
 * from C. Callers MUST zero the array with `passphrase.fill(0)` as soon
 * as the call returns, whether it succeeded or threw.
 */
public interface SkeinSQLiteNative {
    public fun nativeOpen(fileName: String): Long

    public fun nativeClose(dbHandle: Long)

    public fun nativeKey(
        dbHandle: Long,
        passphrase: ByteArray,
        length: Int,
    )

    public fun nativeExec(
        dbHandle: Long,
        sql: String,
    )

    public fun nativeChanges(dbHandle: Long): Long

    public fun nativeLastInsertRowId(dbHandle: Long): Long

    public fun nativePrepare(
        dbHandle: Long,
        sql: String,
    ): Long

    public fun nativeFinalize(stmtHandle: Long)

    public fun nativeReset(stmtHandle: Long)

    public fun nativeClearBindings(stmtHandle: Long)

    public fun nativeStep(stmtHandle: Long): Boolean

    public fun nativeColumnCount(stmtHandle: Long): Int

    public fun nativeColumnName(
        stmtHandle: Long,
        index: Int,
    ): String?

    public fun nativeColumnType(
        stmtHandle: Long,
        index: Int,
    ): Int

    public fun nativeColumnText(
        stmtHandle: Long,
        index: Int,
    ): String?

    public fun nativeColumnLong(
        stmtHandle: Long,
        index: Int,
    ): Long

    public fun nativeColumnDouble(
        stmtHandle: Long,
        index: Int,
    ): Double

    public fun nativeColumnBlob(
        stmtHandle: Long,
        index: Int,
    ): ByteArray

    public fun nativeBindNull(
        stmtHandle: Long,
        index: Int,
    )

    public fun nativeBindLong(
        stmtHandle: Long,
        index: Int,
        value: Long,
    )

    public fun nativeBindDouble(
        stmtHandle: Long,
        index: Int,
        value: Double,
    )

    public fun nativeBindText(
        stmtHandle: Long,
        index: Int,
        value: String,
    )

    public fun nativeBindBlob(
        stmtHandle: Long,
        index: Int,
        value: ByteArray,
    )
}

/**
 * Production JNI-backed implementation. Loads `libskein_sqlite_jni.so`
 * (which itself dlopens `libskein_sqlite.so` via DT_NEEDED) on first
 * class access.
 *
 * Every method is `external`, resolved by name against the JNI shim built
 * from `native/sqlite/androidx-jni/skein_jni.c`. JNI resolves a native
 * method by the DECLARING class of the `external fun` — never by an
 * interface it overrides — so the exported C symbols must follow THIS
 * object's fully-qualified name, currently
 * `Java_app_skein_core_vault_db_SkeinSQLiteNativeImpl_<name>`. Renaming this
 * object requires renaming every symbol in `skein_jni.c` to match.
 * `tools/ci/sqlite-jni-symbols.sh` guards that the two stay in sync.
 */
internal object SkeinSQLiteNativeImpl : SkeinSQLiteNative {
    init {
        // Loads via android's linker; DT_NEEDED on libskein_sqlite.so is
        // resolved automatically since both .so files ship in lib/<abi>/.
        System.loadLibrary("skein_sqlite_jni")
    }

    external override fun nativeOpen(fileName: String): Long

    external override fun nativeClose(dbHandle: Long)

    external override fun nativeKey(
        dbHandle: Long,
        passphrase: ByteArray,
        length: Int,
    )

    external override fun nativeExec(
        dbHandle: Long,
        sql: String,
    )

    external override fun nativeChanges(dbHandle: Long): Long

    external override fun nativeLastInsertRowId(dbHandle: Long): Long

    external override fun nativePrepare(
        dbHandle: Long,
        sql: String,
    ): Long

    external override fun nativeFinalize(stmtHandle: Long)

    external override fun nativeReset(stmtHandle: Long)

    external override fun nativeClearBindings(stmtHandle: Long)

    external override fun nativeStep(stmtHandle: Long): Boolean

    external override fun nativeColumnCount(stmtHandle: Long): Int

    external override fun nativeColumnName(
        stmtHandle: Long,
        index: Int,
    ): String?

    external override fun nativeColumnType(
        stmtHandle: Long,
        index: Int,
    ): Int

    external override fun nativeColumnText(
        stmtHandle: Long,
        index: Int,
    ): String?

    external override fun nativeColumnLong(
        stmtHandle: Long,
        index: Int,
    ): Long

    external override fun nativeColumnDouble(
        stmtHandle: Long,
        index: Int,
    ): Double

    external override fun nativeColumnBlob(
        stmtHandle: Long,
        index: Int,
    ): ByteArray

    external override fun nativeBindNull(
        stmtHandle: Long,
        index: Int,
    )

    external override fun nativeBindLong(
        stmtHandle: Long,
        index: Int,
        value: Long,
    )

    external override fun nativeBindDouble(
        stmtHandle: Long,
        index: Int,
        value: Double,
    )

    external override fun nativeBindText(
        stmtHandle: Long,
        index: Int,
        value: String,
    )

    external override fun nativeBindBlob(
        stmtHandle: Long,
        index: Int,
        value: ByteArray,
    )
}
