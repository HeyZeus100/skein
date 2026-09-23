package app.skein.core.vault.lifecycle

import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-JVM unit tests for [ConnectionPool], wiring a fake
 * [FakeSkeinSQLiteNative] behind [SkeinSQLiteDriver] the same way
 * [VaultLifecycleTest] does — no real `.so`, no real SQLCipher.
 */
class ConnectionPoolTest {
    private fun healthyFake(): FakeSkeinSQLiteNative =
        FakeSkeinSQLiteNative().apply {
            fakeCipherVersion = "4.17.0 community"
            fakeVecVersion = "v0.1.9"
        }

    /** [fake] backs every connection this pool opens, modeling one on-disk database shared across connections. */
    private fun driverFactory(fake: FakeSkeinSQLiteNative): (ByteArray) -> SkeinSQLiteDriver =
        { key -> SkeinSQLiteDriver(fake, key) }

    private fun key(fill: Byte = 0x42): ByteArray = ByteArray(32) { fill }

    @Test
    fun `open hands out one writer and the configured number of readers`() {
        val pool = ConnectionPool.open(driverFactory(healthyFake()), "vault.db", key(), readerCount = 3)

        assertThat(pool.writer()).isNotNull()
        assertThat(pool.readers()).hasSize(3)
        // Writer and every reader are distinct connection instances.
        val all = listOf(pool.writer()) + pool.readers()
        assertThat(all.toSet()).hasSize(4)
    }

    @Test
    fun `open zeroes the caller's key exactly once, after every connection has its own copy`() {
        val theKey = key(7)
        ConnectionPool.open(driverFactory(healthyFake()), "vault.db", theKey, readerCount = 2)

        assertThat(theKey.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `open applies the requested busy_timeout on every connection`() {
        val fake = healthyFake()
        val pool = ConnectionPool.open(driverFactory(fake), "vault.db", key(), readerCount = 2, busyTimeoutMs = 9000L)

        // Every connection ran the SET pragma...
        val setCount = fake.prepareCalls.count { it.contains("busy_timeout", ignoreCase = true) && it.contains("9000") }
        assertThat(setCount).isEqualTo(3) // 1 writer + 2 readers

        // ...and the fake reports it back via a read-form PRAGMA on any of them.
        pool.writer().prepare("PRAGMA busy_timeout;").use { stmt ->
            assertThat(stmt.step()).isTrue()
            assertThat(stmt.getLong(0)).isEqualTo(9000L)
        }
    }

    @Test
    fun `open runs PRAGMA foreign_keys = ON on every connection, writer and every reader`() {
        // skein-gg11.10: PRAGMA foreign_keys is per-connection and defaults
        // OFF in SQLite — a pragma applied on the writer does not carry to
        // the reader connections `ConnectionPool.open` also opens. Each
        // connection here comes from its own `driverFactory` call (its own
        // fresh `SkeinSQLiteDriver`, per that factory's single-use
        // contract), so this pins that EVERY one of them — not just the
        // first opened — actually runs the pragma, the way
        // `document_revisions`' `ON DELETE CASCADE` needs it to on
        // whichever connection a delete lands on.
        val fake = healthyFake()
        ConnectionPool.open(driverFactory(fake), "vault.db", key(), readerCount = 2)

        val foreignKeysExecCount = fake.execCalls.count { it == "PRAGMA foreign_keys = ON;" }
        assertThat(foreignKeysExecCount).isEqualTo(3) // 1 writer + 2 readers
    }

    @Test
    fun `open uses the default busy_timeout when none is supplied`() {
        val fake = healthyFake()
        ConnectionPool.open(driverFactory(fake), "vault.db", key(), readerCount = 0)

        assertThat(
            fake.prepareCalls.any {
                it.contains("busy_timeout", ignoreCase = true) &&
                    it.contains(ConnectionPool.DEFAULT_BUSY_TIMEOUT_MS.toString())
            },
        ).isTrue()
    }

    @Test
    fun `closeAll closes every connection exactly once`() {
        val fake = healthyFake()
        val pool = ConnectionPool.open(driverFactory(fake), "vault.db", key(), readerCount = 2)
        fake.closedHandles.clear()

        pool.closeAll()

        // The fake dispenses the same DB_HANDLE for every open() call (see its
        // KDoc), so this can't distinguish *which* connection closed by handle
        // — but every one of the 3 connections (1 writer + 2 readers) must
        // have been closed exactly once. The close-ORDER guarantee (readers
        // before the writer) is [ConnectionPool.closeAll]'s documented
        // contract and is exercised for real against distinguishable
        // connections by the instrumented suite (skein-k3b2).
        assertThat(fake.closedHandles).hasSize(3)
    }

    @Test
    fun `closeAll is idempotent`() {
        val pool = ConnectionPool.open(driverFactory(healthyFake()), "vault.db", key(), readerCount = 1)

        pool.closeAll()
        pool.closeAll()

        assertThat(true).isTrue() // no exception thrown by the second call
    }

    @Test
    fun `writer throws a typed exception after closeAll`() {
        val pool = ConnectionPool.open(driverFactory(healthyFake()), "vault.db", key(), readerCount = 1)
        pool.closeAll()

        try {
            pool.writer()
            error("expected ConnectionPoolClosedException")
        } catch (_: ConnectionPoolClosedException) {
            // expected
        }
    }

    @Test
    fun `readers throws a typed exception after closeAll`() {
        val pool = ConnectionPool.open(driverFactory(healthyFake()), "vault.db", key(), readerCount = 1)
        pool.closeAll()

        try {
            pool.readers()
            error("expected ConnectionPoolClosedException")
        } catch (_: ConnectionPoolClosedException) {
            // expected
        }
    }

    @Test
    fun `a connection already closed directly is closed again by closeAll without error`() {
        val pool = ConnectionPool.open(driverFactory(healthyFake()), "vault.db", key(), readerCount = 1)
        pool.readers().first().close()

        pool.closeAll()

        assertThat(true).isTrue() // no exception thrown
    }
}
