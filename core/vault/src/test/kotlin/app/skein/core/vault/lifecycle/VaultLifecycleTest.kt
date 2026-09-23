package app.skein.core.vault.lifecycle

import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.SkeinSQLiteException
import app.skein.core.vault.db.migrations.Migrator
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Host-JVM unit tests for [VaultLifecycle], wiring a fake
 * [FakeSkeinSQLiteNative] (no real `.so`, no real SQLCipher) behind
 * [SkeinSQLiteDriver] the same way [Migrator]'s own JVM tests do. The fake
 * accepts any SQL text without validating it, and DOES NOT write vault
 * bytes to disk on its own — [driverFactory] below touches the expected
 * database file when a connection is opened, standing in for what a real
 * `sqlite3_open` would do, so [VaultLifecycle]'s own `File.exists()` gates
 * are exercised honestly. Everything downstream of "the key was accepted"
 * (real SQLCipher decryption, real WAL/-shm files, real corruption) is
 * covered by the instrumented `VaultLifecycleInstrumentedTest` instead
 * (gated on skein-k3b2).
 */
class VaultLifecycleTest {
    @get:Rule
    val tempDir = TempDirRule()

    private fun healthyFake(): FakeSkeinSQLiteNative =
        FakeSkeinSQLiteNative().apply {
            fakeCipherVersion = "4.17.0 community"
            fakeVecVersion = "v0.1.9"
        }

    /**
     * [fake] backs every connection this [VaultLifecycle] opens — since
     * `userVersion` and the other simulated PRAGMA state live on the fake
     * instance itself (not the driver, which is single-use), reusing one
     * fake across multiple `driverFactory` calls models "the same
     * on-disk database" across separate opens, matching how a real vault
     * file persists its `user_version` between connections.
     */
    private fun newLifecycle(
        dir: File,
        fake: FakeSkeinSQLiteNative,
        readerCount: Int = 2,
    ): VaultLifecycle {
        val paths = VaultPaths(vaultDir = dir)
        val driverFactory: (ByteArray) -> SkeinSQLiteDriver = { key ->
            paths.databaseFile.parentFile?.mkdirs()
            if (!paths.databaseFile.exists()) paths.databaseFile.createNewFile()
            SkeinSQLiteDriver(fake, key)
        }
        return VaultLifecycle(
            driverFactory = driverFactory,
            migrator = { driver -> Migrator(driver) },
            paths = paths,
            readerCount = readerCount,
        )
    }

    private fun key(fill: Byte = 0x42): ByteArray = ByteArray(32) { fill }

    @Test
    fun `create then open then close then open then close round trip`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())

            val createKey = key(1)
            val createResult = lifecycle.create(createKey)
            assertThat(createResult).isInstanceOf(CreateResult.Success::class.java)
            assertThat(createKey.all { it == 0.toByte() }).isTrue()
            assertThat(lifecycle.isOpen.value).isTrue()

            // open() while already open is idempotent — no-op, no exception.
            val idempotentOpenKey = key(2)
            val idempotentOpen = lifecycle.open(idempotentOpenKey)
            assertThat(idempotentOpen).isInstanceOf(OpenResult.Success::class.java)
            assertThat(idempotentOpenKey.all { it == 0.toByte() }).isTrue()
            assertThat(lifecycle.isOpen.value).isTrue()

            lifecycle.close()
            assertThat(lifecycle.isOpen.value).isFalse()

            val reopenKey = key(3)
            val reopenResult = lifecycle.open(reopenKey)
            assertThat(reopenResult).isInstanceOf(OpenResult.Success::class.java)
            assertThat((reopenResult as OpenResult.Success).migration.toVersion).isAtLeast(1)
            assertThat(reopenKey.all { it == 0.toByte() }).isTrue()
            assertThat(lifecycle.isOpen.value).isTrue()

            lifecycle.close()
            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun `create twice fails with AlreadyExists and still zeroes the second key`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())
            lifecycle.create(key(1))

            val secondKey = key(2)
            val secondCreate = lifecycle.create(secondKey)

            assertThat(secondCreate).isEqualTo(CreateResult.AlreadyExists)
            assertThat(secondKey.all { it == 0.toByte() }).isTrue()
        }

    @Test
    fun `open on a nonexistent vault fails with NotFound and zeroes the key`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())

            val openKey = key(9)
            val result = lifecycle.open(openKey)

            assertThat(result).isEqualTo(OpenResult.NotFound)
            assertThat(openKey.all { it == 0.toByte() }).isTrue()
            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun `open with the wrong key returns typed WrongKey, not an exception`() =
        runTest {
            val fake =
                healthyFake().apply {
                    cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
                }
            val paths = VaultPaths(vaultDir = tempDir.root)
            // Pre-create the file: from VaultLifecycle's point of view a vault
            // already exists here, so `open` proceeds to the keyed connection
            // attempt instead of short-circuiting on NotFound.
            paths.databaseFile.createNewFile()
            val lifecycle =
                VaultLifecycle(
                    driverFactory = { key -> SkeinSQLiteDriver(fake, key) },
                    migrator = { driver -> Migrator(driver) },
                    paths = paths,
                )

            val wrongKey = key(0xEE.toByte())
            val result = lifecycle.open(wrongKey)

            assertThat(result).isEqualTo(OpenResult.WrongKey)
            assertThat(wrongKey.all { it == 0.toByte() }).isTrue()
            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun `integrityCheck before open returns NotOpen`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())

            val result = lifecycle.integrityCheck()

            assertThat(result).isEqualTo(IntegrityResult.NotOpen)
        }

    @Test
    fun `close before any open is a harmless no-op`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())

            lifecycle.close()

            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun `connectionPool throws while not open`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())

            try {
                lifecycle.connectionPool()
                error("expected IllegalStateException")
            } catch (_: IllegalStateException) {
                // expected
            }
        }

    @Test
    fun `create exposes a live connection pool with a writer and the configured reader count`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake(), readerCount = 3)

            lifecycle.create(key(1))

            val pool = lifecycle.connectionPool()
            assertThat(pool.readers()).hasSize(3)
            // The writer connection is live — a statement against it doesn't throw.
            pool.writer().prepare("PRAGMA journal_mode;").use { it.step() }
        }

    @Test
    fun `open exposes a live connection pool too`() =
        runTest {
            val fake = healthyFake()
            val lifecycle = newLifecycle(tempDir.root, fake, readerCount = 2)
            lifecycle.create(key(1))
            lifecycle.close()

            lifecycle.open(key(2))

            val pool = lifecycle.connectionPool()
            assertThat(pool.readers()).hasSize(2)
        }

    @Test
    fun `close closes every pool connection and connectionPool throws afterward`() =
        runTest {
            val fake = healthyFake()
            val lifecycle = newLifecycle(tempDir.root, fake, readerCount = 2)
            lifecycle.create(key(1))
            val pool = lifecycle.connectionPool()

            lifecycle.close()

            try {
                lifecycle.connectionPool()
                error("expected IllegalStateException")
            } catch (_: IllegalStateException) {
                // expected
            }
            try {
                pool.writer()
                error("expected ConnectionPoolClosedException")
            } catch (_: ConnectionPoolClosedException) {
                // expected
            }
            try {
                pool.readers()
                error("expected ConnectionPoolClosedException")
            } catch (_: ConnectionPoolClosedException) {
                // expected
            }
        }

    // ---- skein-1bx4: close is total, and only ever closes its own pool ----

    /**
     * The device P0: `app.skein.vault.VaultSession.close` closed its
     * services — and with them the pool's WRITER connection — before calling
     * this method, so the WAL checkpoint's `prepare()` hit a closed
     * `SkeinSQLiteConnection` and threw. The throw escaped `close()` before
     * it could clear `pool` / `lastOpen` / `isOpen`, and the lifecycle was
     * then permanently stuck claiming to be open over a dead pool.
     */
    @Test
    fun `close still closes the lifecycle when the writer was already closed under it`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())
            lifecycle.create(key(1))
            // Exactly what VaultRepositoryImpl.close() does to the pool's writer.
            lifecycle.connectionPool().writer().close()

            lifecycle.close()

            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun `a vault whose writer was closed under it reopens onto a fresh pool`() =
        runTest {
            val fake = healthyFake()
            val lifecycle = newLifecycle(tempDir.root, fake)
            lifecycle.create(key(1))
            val deadPool = lifecycle.connectionPool()
            deadPool.writer().close()
            lifecycle.close()

            lifecycle.open(key(2))

            // Not the stale pool handed back by the already-open branch of
            // open(): a live one, whose writer accepts statements.
            val reopened = lifecycle.connectionPool()
            assertThat(reopened).isNotSameInstanceAs(deadPool)
            reopened.writer().prepare("PRAGMA journal_mode;").use { it.step() }
        }

    @Test
    fun `a stale close after a reopen does not close the new pool`() =
        runTest {
            // One vault file, two lifecycles: `stale` stands in for the
            // VaultLifecycle a previous VaultSession opened, `fresh` for the
            // one the re-unlock opened. A leftover close on the first — the
            // onLocked backstop's retry, or a close that overran the observer
            // budget — must not reach the second one's connections.
            val fake = healthyFake()
            val stale = newLifecycle(tempDir.root, fake)
            stale.create(key(1))
            stale.close()
            val fresh = newLifecycle(tempDir.root, fake)
            fresh.open(key(2))
            val freshPool = fresh.connectionPool()

            stale.close()

            assertThat(fresh.isOpen.value).isTrue()
            assertThat(freshPool.readers()).hasSize(2)
            freshPool.writer().prepare("PRAGMA journal_mode;").use { it.step() }
        }

    @Test
    fun `close is idempotent and never throws on a second call`() =
        runTest {
            val lifecycle = newLifecycle(tempDir.root, healthyFake())
            lifecycle.create(key(1))

            lifecycle.close()
            lifecycle.close()

            assertThat(lifecycle.isOpen.value).isFalse()
        }
}
