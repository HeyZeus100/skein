package app.skein.core.vault.repository

import app.skein.core.model.DocumentKind
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KindLookupTest {
    @Test
    fun `batch selects only kind and id with one bound JSON parameter`() =
        runTest {
            val probe = KindProbe(listOf(listOf("note", "note"), listOf("future", "unknown")))
            val repo =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(probe, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            val ids = (0..1_100).mapTo(linkedSetOf()) { "missing-$it" } + setOf("note", "future", "quote\"'\\")

            assertEquals(mapOf("note" to DocumentKind.NOTE), repo.kindsOf(ids))
            assertEquals(
                listOf("SELECT id, kind FROM documents WHERE id IN (SELECT value FROM json_each(?))"),
                probe.sql,
            )
            assertEquals(
                ids,
                Json
                    .parseToJsonElement(probe.bound)
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet(),
            )
            assertTrue(probe.finalized)
        }

    @Test
    fun `empty set does not touch the database and unavailable database fails closed`() =
        runTest {
            val probe = KindProbe(emptyList())
            val connection = SkeinSQLiteConnection(probe, 1)
            val repo =
                VaultRepositoryImpl(connection, InMemoryAttachmentStore(), io = StandardTestDispatcher(testScheduler))
            assertTrue(repo.kindsOf(emptySet()).isEmpty())
            assertTrue(probe.sql.isEmpty())
            connection.close()
            assertTrue(repo.kindsOf(setOf("secret-id")).isEmpty())
        }

    @Test
    fun `cancellation is not converted into a successful empty lookup`() =
        runTest {
            val native =
                object : SkeinSQLiteNative by FakeSkeinSQLiteNative() {
                    override fun nativePrepare(
                        dbHandle: Long,
                        sql: String,
                    ): Long = throw CancellationException("cancelled")
                }
            val repo =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            val failure = runCatching { repo.kindsOf(setOf("id")) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }

    private class KindProbe(
        private val rows: List<List<String>>,
    ) : SkeinSQLiteNative by FakeSkeinSQLiteNative() {
        val sql = mutableListOf<String>()
        var bound = ""
        var finalized = false
        private var row = -1

        override fun nativePrepare(
            dbHandle: Long,
            sql: String,
        ): Long {
            this.sql += sql
            return 100
        }

        override fun nativeBindText(
            stmtHandle: Long,
            index: Int,
            value: ByteArray,
        ) {
            assertEquals(1, index)
            bound = value.toString(Charsets.UTF_8)
        }

        override fun nativeStep(stmtHandle: Long): Boolean = ++row < rows.size

        override fun nativeColumnText(
            stmtHandle: Long,
            index: Int,
        ): String = rows[row][index]

        override fun nativeFinalize(stmtHandle: Long) {
            finalized = true
        }
    }
}
