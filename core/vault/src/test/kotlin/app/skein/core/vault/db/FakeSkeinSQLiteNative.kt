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

    /**
     * Simulated `PRAGMA busy_timeout`, mutated when [nativePrepare] sees a
     * `PRAGMA busy_timeout = N;` SET statement (`ConnectionPool.open`
     * applies this via `conn.prepare(...).use { it.step() }`, the same
     * prepare-based convention `Migrator` uses for `user_version` — see
     * the note above). Read back by [nativeColumnLong] for a subsequent
     * `PRAGMA busy_timeout;` read, so tests can assert the applied value
     * round-trips rather than only asserting on the raw SQL text.
     */
    var busyTimeoutMs: Long = 0L

    /** Hex payload of the last `PRAGMA key = "x'...'"` exec, if any. */
    var lastKeyPragmaHex: String? = null

    /** Bound parameter values, keyed by (stmtHandle, 1-based index). */
    private val boundValues = mutableMapOf<Pair<Long, Int>, Any>()

    private var nextStmtHandle = 100L

    /**
     * Simulated `sqlite_master`: object name -> its type (`"table"`,
     * `"index"` or `"trigger"`), built by watching every statement handed
     * to [nativePrepare] for the same `CREATE`/`DROP` DDL forms
     * `VaultLifecycle.applyStatement` matches against the migration files
     * themselves (skein-hctx) — this fake has no real SQL engine, so it is
     * the only way a JVM test can observe "what a real freshly-migrated
     * vault's `sqlite_master` would actually contain" without one.
     * [dependentsOf] tracks which table an index/trigger was declared `ON`
     * so a later `DROP TABLE` cascades to them, exactly as SQLite itself
     * does (and as `VaultLifecycle`'s own replay already accounts for).
     */
    private val schemaObjectType = mutableMapOf<String, String>()
    private val dependentsOf = mutableMapOf<String, String>()

    /** Per-statement `sqlite_master` query results not yet handed out via [nativeStep]. */
    private val sqliteMasterCursors = mutableMapOf<Long, MutableList<String>>()

    /** The `sqlite_master.name` value the last [nativeStep] on this handle produced. */
    private val sqliteMasterCurrentValue = mutableMapOf<Long, String>()

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
        BUSY_TIMEOUT_SET_REGEX.find(sql)?.let { match ->
            busyTimeoutMs = match.groupValues[1].toLong()
        }
        trackSchemaDdl(sql)
        return nextStmtHandle++
    }

    /**
     * Applies [sql] to [schemaObjectType] / [dependentsOf] the same way a
     * real engine would — see [schemaObjectType]'s doc. Statements arrive
     * here already split one-per-call (`Migrator`'s `execStatement`
     * convention), so `.find()` per DDL form is sufficient; a statement
     * matches at most one of these.
     *
     * [sql] is matched AFTER stripping its `--`-prefixed comment lines,
     * not the raw text: a `MigrationStatementSplitter` chunk is
     * "everything up to and including the next `--;` sentinel", so a
     * migration's multi-line header comment is glued onto the front of
     * the SAME chunk its real DDL ends (007's header illustrates the very
     * `DROP TABLE attachment_keys；DROP TABLE attachment_master_key`
     * statements it explains, as prose). A real SQL engine's tokenizer
     * ignores `--` comments unconditionally when it parses a statement,
     * so this fake must too, to actually model "what a real freshly
     * migrated vault's sqlite_master would contain" (skein-hctx) rather
     * than reproducing the exact bug this fake exists to catch.
     */
    private fun trackSchemaDdl(sql: String) {
        val ddl = stripCommentLines(sql)
        CREATE_TABLE_REGEX.find(ddl)?.let { schemaObjectType[it.groupValues[1]] = "table" }
        CREATE_INDEX_REGEX.find(ddl)?.let { match ->
            val (name, table) = match.destructured
            schemaObjectType[name] = "index"
            dependentsOf[name] = table
        }
        CREATE_TRIGGER_REGEX.find(ddl)?.let { match ->
            val (name, table) = match.destructured
            schemaObjectType[name] = "trigger"
            dependentsOf[name] = table
        }
        DROP_TABLE_REGEX.find(ddl)?.let { match ->
            val table = match.groupValues[1]
            schemaObjectType.remove(table)
            val cascaded = dependentsOf.filterValues { it == table }.keys.toList()
            cascaded.forEach {
                schemaObjectType.remove(it)
                dependentsOf.remove(it)
            }
        }
        DROP_INDEX_REGEX.find(ddl)?.let { match ->
            schemaObjectType.remove(match.groupValues[1])
            dependentsOf.remove(match.groupValues[1])
        }
        DROP_TRIGGER_REGEX.find(ddl)?.let { match ->
            schemaObjectType.remove(match.groupValues[1])
            dependentsOf.remove(match.groupValues[1])
        }
    }

    /**
     * Drops every line of [statement] that is (after trimming) a `--`
     * comment. Mirrors `Migrator.migrationWitness`'s and
     * `VaultLifecycle.applyStatement`'s identically-named,
     * identically-motivated helper (skein-hctx) — see [trackSchemaDdl]'s
     * doc.
     */
    private fun stripCommentLines(statement: String): String =
        statement
            .lineSequence()
            .filter { line -> !line.trimStart().startsWith("--") }
            .joinToString("\n")

    override fun nativeFinalize(stmtHandle: Long) = Unit

    override fun nativeReset(stmtHandle: Long) = Unit

    override fun nativeClearBindings(stmtHandle: Long) = Unit

    override fun nativeStep(stmtHandle: Long): Boolean {
        val sql = prepareCalls.getOrNull((stmtHandle - 100L).toInt()) ?: return false
        return when {
            "cipher_version" in sql -> true
            "vec_version" in sql -> vecProbeReturnsRow
            // The single-row PRAGMA reads this fake models state for.
            "journal_mode" in sql -> true
            "foreign_keys" in sql -> true
            "user_version" in sql -> true
            "busy_timeout" in sql -> true
            // skein-hctx: `SELECT name FROM sqlite_master WHERE type = '...'`
            // (SchemaInspector.tables()/indexes()/triggers(), the same
            // queries VaultLifecycle.integrityCheck() issues) is the one
            // multi-row query this fake models for real, against
            // [schemaObjectType] -- see that field's doc. One name is
            // queued per row and handed out across successive step()
            // calls on the SAME stmtHandle, exhausting (returning false)
            // once the queue built for this handle on its first step() is
            // empty.
            "sqlite_master" in sql -> {
                val rows =
                    sqliteMasterCursors.getOrPut(stmtHandle) {
                        val type = SQLITE_MASTER_TYPE_QUERY_REGEX.find(sql)?.groupValues?.get(1)
                        schemaObjectType.filterValues { it == type }.keys.toMutableList()
                    }
                if (rows.isEmpty()) {
                    false
                } else {
                    sqliteMasterCurrentValue[stmtHandle] = rows.removeAt(0)
                    true
                }
            }
            // skein-p8rn: everything else -- `schema_migrations` reads
            // beyond the ledger's own membership, `PRAGMA table_info`,
            // `PRAGMA integrity_check`, and any future generic multi-row
            // query -- has no modeled row data in this fake (it only
            // simulates a handful of PRAGMA scalars and the sqlite_master
            // catalogue above, never arbitrary table contents). Report "no
            // rows" rather than the previous unconditional `true`, which
            // made any `while (stmt.step())` loop over one of these queries
            // spin forever the moment a caller (skein-p8rn's
            // `Migrator.readLedgerVersions`/`columnExists`) started actually
            // looping instead of calling `step()` exactly once.
            else -> false
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
            "sqlite_master" in sql -> sqliteMasterCurrentValue[stmtHandle] ?: ""
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
            "busy_timeout" in sql -> busyTimeoutMs
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
        private val BUSY_TIMEOUT_SET_REGEX = Regex("""PRAGMA\s+busy_timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        // Mirrors VaultLifecycle's own CREATE/DROP matching (skein-hctx) --
        // see schemaObjectType's doc for why this fake needs its own copy
        // rather than sharing that private, production-side logic.
        private val SQLITE_MASTER_TYPE_QUERY_REGEX = Regex("""sqlite_master\s+WHERE\s+type\s*=\s*'(\w+)'""")
        private val CREATE_TABLE_REGEX =
            Regex("""(?i)CREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        private val CREATE_INDEX_REGEX =
            Regex(
                """(?i)CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?\s+ON\s+["`]?(\w+)["`]?""",
            )
        private val CREATE_TRIGGER_REGEX =
            Regex(
                """(?is)CREATE\s+TRIGGER\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?.*?\bON\s+["`]?(\w+)["`]?""",
            )
        private val DROP_TABLE_REGEX = Regex("""(?i)DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        private val DROP_INDEX_REGEX = Regex("""(?i)DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")
        private val DROP_TRIGGER_REGEX = Regex("""(?i)DROP\s+TRIGGER\s+(?:IF\s+EXISTS\s+)?["`]?(\w+)["`]?""")

        // From sqlite3.h: SQLITE_INTEGER=1, FLOAT=2, TEXT=3, BLOB=4, NULL=5.
        private const val SQLITE_TYPE_INTEGER = 1
        private const val SQLITE_TYPE_FLOAT = 2
        private const val SQLITE_TYPE_TEXT = 3
        private const val SQLITE_TYPE_BLOB = 4
        private const val SQLITE_TYPE_NULL = 5
    }
}
