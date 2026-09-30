package app.skein.core.vault.lifecycle

import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.key.recovery.RecoverySnapshotRefused
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VaultRecoveryExclusionTest {
    @get:Rule val temp = TempDirRule()

    private fun gate() = VaultRecoveryExclusion.forDirectory(temp.root)

    @Test
    fun `directory aliases and separate owners share exclusive admission`() {
        val gate = gate()
        val same = VaultRecoveryExclusion.forDirectory(File(temp.root, "nested/.."))
        gate.admit()!!.use {
            assertThat(same.acquireRecovery()).isNull()
            assertThat(same.acquireReset()).isNull()
        }
        gate.acquireRecovery()!!.use {
            assertThat(same.admit()).isNull()
            assertThat(same.acquireReset()).isNull()
            assertThat(same.acquireRecovery()).isNull()
        }
        same.acquireReset()!!.use { assertThat(gate.acquireRecovery()).isNull() }
        gate.acquireRecovery()!!.close()
    }

    @Test
    fun `revocation retains exclusion until the owner drains and stale close cannot release successor`() {
        val first = gate().acquireRecovery()!!
        gate().invalidateRecovery()
        assertThrows(RecoverySnapshotRefused::class.java) { first.assertExclusiveAndClosed() }
        assertThat(gate().admit()).isNull()
        assertThat(gate().acquireRecovery()).isNull()
        first.close()
        gate().acquireRecovery()!!.use { second ->
            first.close()
            second.assertExclusiveAndClosed()
            assertThat(gate().admit()).isNull()
        }
    }

    @Test
    fun `revocation and final action are serialized`() {
        gate().acquireRecovery()!!.use { lease ->
            val entered = CountDownLatch(1)
            val startRevocation = CountDownLatch(1)
            val revoked = CountDownLatch(1)
            val thread =
                Thread {
                    entered.await()
                    startRevocation.countDown()
                    gate().invalidateRecovery()
                    revoked.countDown()
                }
            thread.start()
            lease.whileExclusiveAndClosed {
                entered.countDown()
                assertThat(startRevocation.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(revoked.count).isEqualTo(1)
                lease.assertExclusiveAndClosed()
            }
            assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue()
            thread.join()
            assertThrows(RecoverySnapshotRefused::class.java) { lease.whileExclusiveAndClosed { error("revoked") } }
        }
    }

    @Test
    fun `production lifecycle admission lasts through live pool and excludes other instances`() =
        runTest {
            val paths = VaultPaths(temp.root)
            val native = FakeSkeinSQLiteNative()

            fun lifecycle() =
                VaultLifecycle(
                    driverFactory = { key ->
                        paths.databaseFile.createNewFile()
                        SkeinSQLiteDriver(native, key)
                    },
                    migrator = { Migrator(it) },
                    paths = paths,
                )
            val first = lifecycle()
            assertThat(first.create(ByteArray(32))).isInstanceOf(CreateResult.Success::class.java)
            assertThat(gate().acquireRecovery()).isNull()
            val second = lifecycle()
            assertThat(second.open(ByteArray(32))).isInstanceOf(OpenResult.Success::class.java)
            first.close()
            assertThat(gate().acquireRecovery()).isNull()
            second.close()
            gate().acquireRecovery()!!.use {
                val key = ByteArray(32) { 9 }
                assertThat(first.open(key)).isInstanceOf(OpenResult.Failed::class.java)
                assertThat(key).isEqualTo(ByteArray(32))
                assertThat(first.create(ByteArray(32))).isInstanceOf(CreateResult.Failed::class.java)
            }
        }

    @Test
    fun `failed open releases reservation and reset refuses recovery without touching data`() =
        runTest {
            val lifecycle = VaultLifecycle({ error("must not open") }, { Migrator(it) }, VaultPaths(temp.root))
            assertThat(lifecycle.open(ByteArray(32))).isEqualTo(OpenResult.NotFound)
            val db = File(temp.root, "vault.db").apply { writeText("ciphertext") }
            val reset =
                VaultReset(temp.root, db, File(temp.root, "attachments"), null, { error("must not delete") }, { false })
            gate().acquireRecovery()!!.use {
                assertThat(reset.reset()).isInstanceOf(VaultResetResult.Failed::class.java)
                assertThat(db.readText()).isEqualTo("ciphertext")
                assertThat(File(temp.root, VaultReset.MARKER_FILE_NAME).exists()).isFalse()
            }
        }

    @Test
    fun `outstanding statement poisons recovery across new owners even after late finalization`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val paths = VaultPaths(temp.root)
            val lifecycle =
                VaultLifecycle(
                    { key ->
                        paths.databaseFile.createNewFile()
                        SkeinSQLiteDriver(native, key)
                    },
                    { Migrator(it) },
                    paths,
                )
            assertThat(lifecycle.create(ByteArray(32))).isInstanceOf(CreateResult.Success::class.java)
            val statement =
                lifecycle
                    .connectionPool()
                    .readers()
                    .first()
                    .prepare("SELECT 1")
            lifecycle.close()
            assertThat(lifecycle.isOpen.value).isFalse()
            assertThat(gate().acquireRecovery()).isNull()
            statement.close()
            assertThat(gate().acquireRecovery()).isNull()
            assertThat(gate().acquireReset()).isNull()
            // Ordinary admission remains available; poison adds no new unlock/reset authority.
            gate().admit()!!.close()
        }

    @Test
    fun `failed open with uncertain native cleanup poisons recovery`() =
        runTest {
            File(temp.root, "vault.db").writeBytes(ByteArray(32))
            val lifecycle =
                VaultLifecycle({ error("injected factory failure") }, { Migrator(it) }, VaultPaths(temp.root))
            try {
                lifecycle.open(ByteArray(32))
                error("must fail")
            } catch (_: IllegalStateException) {
                assertThat(gate().acquireRecovery()).isNull()
                assertThat(gate().acquireReset()).isNull()
                gate().admit()!!.close()
            }
        }
}
