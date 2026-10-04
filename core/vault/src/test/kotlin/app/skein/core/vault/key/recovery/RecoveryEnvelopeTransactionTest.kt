package app.skein.core.vault.key.recovery

import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RecoveryEnvelopeTransactionTest {
    @get:Rule val tempDir = TempDirRule()

    @Test
    fun `replacement retains exact old and new bytes and restart recognizes active generation`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val transaction = RecoveryEnvelopeTransaction(lease)
        assertThat(transaction.replace(old, next).state).isEqualTo(RecoveryEnvelopeTransaction.State.COMMITTED)
        assertThat(active().readBytes()).isEqualTo(next.encode())
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
        assertThat(
            transaction.reconcile(next.transactionId).state,
        ).isEqualTo(RecoveryEnvelopeTransaction.State.COMMITTED)
    }

    @Test
    fun `failure at every persisted boundary preserves both restart choices`() {
        val lease = lease()
        val old = active().readBytes()
        for (boundary in RecoveryEnvelopeTransaction.Boundary.entries) {
            active().writeBytes(old)
            val next = envelope()
            val transaction = RecoveryEnvelopeTransaction(lease) { if (it == boundary) throw IOException("injected") }
            val result = transaction.replace(old, next)
            val expected =
                when (boundary) {
                    RecoveryEnvelopeTransaction.Boundary.OLD_SYNCED -> RecoveryEnvelopeTransaction.State.UNKNOWN
                    RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME -> RecoveryEnvelopeTransaction.State.COMMITTED
                    else -> RecoveryEnvelopeTransaction.State.NOT_COMMITTED
                }
            assertThat(result.state).isEqualTo(expected)
            assertThat(result.directorySynced).isFalse()
            assertThat(active().readBytes()).isEqualTo(
                if (boundary ==
                    RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME
                ) {
                    next.encode()
                } else {
                    old
                },
            )
            assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
            assertThat(RecoveryEnvelopeTransaction(lease).reconcile(next.transactionId).state).isEqualTo(expected)
        }
    }

    @Test
    fun `failure publishing new backup parent prevents active replacement`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val synced = mutableListOf<File>()
        var renameReached = false
        val transaction =
            RecoveryEnvelopeTransaction(
                lease,
                syncDirectory = { directory ->
                    synced += directory
                    if (directory == active().parentFile) throw IOException("injected parent publication failure")
                },
            ) { if (it == RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME) renameReached = true }
        val result = transaction.replace(old, next)
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
        assertThat(renameReached).isFalse()
        assertThat(
            synced.map {
                it.name
            },
        ).containsExactly(next.transactionId.toString(), "recovery-envelopes", "keys").inOrder()
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
    }

    @Test
    fun `stale active generation never gets overwritten and unknown bytes remain retained`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val other = "another committed generation".toByteArray()
        val transaction =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME) active().writeBytes(other)
            }
        assertThat(transaction.replace(old, next).state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(other)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
    }

    @Test
    fun `lease cancellation before rename cannot change active envelope`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val transaction =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME) lease.held = false
            }
        assertThat(transaction.replace(old, next).state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        lease.held = true
        assertThat(
            RecoveryEnvelopeTransaction(lease).reconcile(next.transactionId).state,
        ).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
    }

    @Test
    fun `reused transaction or missing receipt is unknown and never erased`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val transaction = RecoveryEnvelopeTransaction(lease)
        transaction.replace(old, next)
        assertThat(transaction.replace(next.encode(), next).state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        retained(next, "old.envelope").delete()
        assertThat(transaction.reconcile(next.transactionId).state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
        assertThat(active().readBytes()).isEqualTo(next.encode())
    }

    @Test
    fun `guarded reset marker refuses before any new files`() {
        val lease = lease()
        val old = active().readBytes()
        File(lease.vaultDirectory, ".vault_reset_in_progress").writeText("")
        assertThrows(
            RecoverySnapshotRefused::class.java,
        ) { RecoveryEnvelopeTransaction(lease).replace(old, envelope()) }
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(File(lease.vaultDirectory, "keys/recovery-envelopes").exists()).isFalse()
    }

    @Test
    fun `callback follows durable readbacks outside guard and cheap cancellation precedes guarded move`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val seen = mutableListOf<String>()
        val transaction =
            RecoveryEnvelopeTransaction(
                lease,
                syncDirectory = {
                    assertThat(lease.inGuard).isFalse()
                    sync(it)
                    seen += "sync:${it.name}"
                },
                atomicMove = { from, to ->
                    assertThat(lease.inGuard).isTrue()
                    assertThat(seen.last()).isEqualTo("cancellation")
                    move(from, to)
                    seen += "move"
                },
                boundary = { seen += it.name },
            )
        val result =
            transaction.replace(
                old,
                next,
                checkCancellation = {
                    assertThat(lease.inGuard).isTrue()
                    seen += "cancellation"
                },
                validateBeforeCommit = {
                    assertThat(lease.inGuard).isFalse()
                    assertThat(seen.last()).isEqualTo("BEFORE_RENAME")
                    assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
                    assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
                    assertThat(retained(next, "install.envelope").readBytes()).isEqualTo(next.encode())
                    seen += "validated"
                },
            )
        assertThat(result).isEqualTo(
            RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.COMMITTED, true),
        )
        assertThat(seen.indexOf("validated")).isLessThan(seen.indexOf("cancellation"))
        assertThat(seen.filter { it.startsWith("sync:") }).hasSize(4)
    }

    @Test
    fun `caller buffer and mutable proposed wraps cannot change frozen installation bytes`() {
        val lease = lease()
        val old = active().readBytes()
        val originalOld = old.copyOf()
        val next = envelope()
        val intended = next.encode()
        val result =
            RecoveryEnvelopeTransaction(lease).replace(old, next) {
                old.fill(99)
                next.biometric.ciphertext.fill(77)
                next.credential.iv.fill(88)
            }
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.COMMITTED)
        assertThat(active().readBytes()).isEqualTo(intended)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(originalOld)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(intended)
    }

    @Test
    fun `each corrupted retained or install readback refuses active replacement`() {
        for (name in listOf("old.envelope", "new.envelope", "install.envelope")) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            var moves = 0
            val transaction =
                RecoveryEnvelopeTransaction(
                    lease,
                    atomicMove = { from, to ->
                        moves++
                        move(from, to)
                    },
                ) {
                    if (it == RecoveryEnvelopeTransaction.Boundary.INSTALL_SYNCED) {
                        retained(next, name).writeText("corrupt retained evidence")
                    }
                }
            val result = transaction.replace(old, next)
            assertThat(result.state).isEqualTo(
                if (name == "install.envelope") {
                    RecoveryEnvelopeTransaction.State.NOT_COMMITTED
                } else {
                    RecoveryEnvelopeTransaction.State.UNKNOWN
                },
            )
            assertThat(moves).isEqualTo(0)
            assertThat(active().readBytes()).isEqualTo(old)
            assertThat(retained(next, name).readText()).isEqualTo("corrupt retained evidence")
        }
    }

    @Test
    fun `final callback tampering is caught by repeated retained and install readbacks`() {
        for (name in listOf("old.envelope", "new.envelope", "install.envelope")) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            var moves = 0
            val result =
                RecoveryEnvelopeTransaction(
                    lease,
                    atomicMove = { from, to ->
                        moves++
                        move(from, to)
                    },
                ).replace(old, next) { retained(next, name).writeText("changed after initial readback") }
            assertThat(result.state).isNotEqualTo(RecoveryEnvelopeTransaction.State.COMMITTED)
            assertThat(moves).isEqualTo(0)
            assertThat(active().readBytes()).isEqualTo(old)
            assertThat(retained(next, name).readText()).isEqualTo("changed after initial readback")
        }
    }

    @Test
    fun `install symlink is refused without altering its target or old active bytes`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val unrelated = File(tempDir.root, "unrelated-envelope").apply { writeBytes(next.encode()) }
        val transaction =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.INSTALL_SYNCED) {
                    Files.delete(retained(next, "install.envelope").toPath())
                    Files.createSymbolicLink(retained(next, "install.envelope").toPath(), unrelated.toPath())
                }
            }
        assertThat(transaction.replace(old, next).state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(unrelated.readBytes()).isEqualTo(next.encode())
        assertThat(Files.isSymbolicLink(retained(next, "install.envelope").toPath())).isTrue()
    }

    @Test
    fun `oversized install readback is refused and retained without truncation`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val oversized = ByteArray(32769) { 44 }
        val result =
            RecoveryEnvelopeTransaction(lease).replace(old, next) {
                retained(next, "install.envelope").writeBytes(oversized)
            }
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(retained(next, "install.envelope").readBytes()).isEqualTo(oversized)
    }

    @Test
    fun `partial attempt is never reused or erased after install failure`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val transaction =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.PARENT_SYNCED) {
                    retained(next, "install.envelope").writeText("preserved partial install")
                }
            }
        assertThat(transaction.replace(old, next).state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
        val directory = checkNotNull(retained(next, "old.envelope").parentFile)
        val before =
            directory
                .walkTopDown()
                .filter(File::isFile)
                .associate { it.name to it.readBytes().toList() }
        assertThat(
            RecoveryEnvelopeTransaction(lease).replace(old, next).state,
        ).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(
            directory
                .walkTopDown()
                .filter(File::isFile)
                .associate { it.name to it.readBytes().toList() },
        ).isEqualTo(before)
        assertThat(active().readBytes()).isEqualTo(old)
    }

    @Test
    fun `callback IO runtime and cancellation failures cannot rename and preserve all evidence`() {
        val failures =
            listOf(
                IOException("validation"),
                IllegalStateException("validation"),
                CancellationException("validation"),
            )
        for (failure in failures) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            val result = RecoveryEnvelopeTransaction(lease).replace(old, next) { throw failure }
            assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
            assertThat(result.directorySynced).isFalse()
            assertThat(active().readBytes()).isEqualTo(old)
            assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
            assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
            assertThat(retained(next, "install.envelope").readBytes()).isEqualTo(next.encode())
        }
    }

    @Test
    fun `cheap cancellation check inside final guard prevents rename after callback`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        var validated = false
        val result =
            RecoveryEnvelopeTransaction(lease).replace(
                old,
                next,
                checkCancellation = {
                    assertThat(lease.inGuard).isTrue()
                    assertThat(validated).isTrue()
                    throw CancellationException("cancelled after full validation")
                },
                validateBeforeCommit = { validated = true },
            )
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
        assertThat(active().readBytes()).isEqualTo(old)
    }

    @Test
    fun `move exceptions before changing active bytes report old active not durable`() {
        for (failure in listOf(IOException("move"), IllegalStateException("move"), CancellationException("move"))) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            val result = RecoveryEnvelopeTransaction(lease, atomicMove = { _, _ -> throw failure }).replace(old, next)
            assertThat(result).isEqualTo(
                RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.NOT_COMMITTED),
            )
            assertThat(active().readBytes()).isEqualTo(old)
            assertThat(retained(next, "install.envelope").readBytes()).isEqualTo(next.encode())
        }
    }

    @Test
    fun `move exceptions after changing active bytes report new active durability uncertain`() {
        for (failure in listOf(IOException("move"), IllegalStateException("move"), CancellationException("move"))) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            val result =
                RecoveryEnvelopeTransaction(
                    lease,
                    atomicMove = { from, to ->
                        move(from, to)
                        throw failure
                    },
                ).replace(old, next)
            assertThat(result).isEqualTo(
                RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.COMMITTED),
            )
            assertThat(active().readBytes()).isEqualTo(next.encode())
            assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
            assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
        }
    }

    @Test
    fun `postrename IO runtime and cancellation failures retain truthful new active state`() {
        for (failure in listOf(IOException("after"), IllegalStateException("after"), CancellationException("after"))) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            val transaction =
                RecoveryEnvelopeTransaction(lease) {
                    if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) throw failure
                }
            assertThat(transaction.replace(old, next)).isEqualTo(
                RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.COMMITTED),
            )
            assertThat(active().readBytes()).isEqualTo(next.encode())
            assertThat(RecoveryEnvelopeTransaction(lease).reconcile(next.transactionId).directorySynced).isFalse()
        }
    }

    @Test
    fun `each directory sync failure distinguishes old active from visible new uncertain generation`() {
        for (failAt in 1..4) {
            val lease = lease()
            val old = active().readBytes()
            val next = envelope()
            var count = 0
            val result =
                RecoveryEnvelopeTransaction(
                    lease,
                    syncDirectory = {
                        if (++count == failAt) throw IOException("sync $failAt")
                        sync(it)
                    },
                ).replace(old, next)
            assertThat(result.state).isEqualTo(
                if (failAt == 4) {
                    RecoveryEnvelopeTransaction.State.COMMITTED
                } else {
                    RecoveryEnvelopeTransaction.State.NOT_COMMITTED
                },
            )
            assertThat(result.directorySynced).isFalse()
            assertThat(active().readBytes()).isEqualTo(if (failAt == 4) next.encode() else old)
            assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
            assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
        }
    }

    @Test
    fun `postrename revocation returns unknown and fresh observation finds new active without durability authority`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val result =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) {
                    lease.held = false
                    throw CancellationException("locked after rename")
                }
            }.replace(old, next)
        assertThat(result).isEqualTo(RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.UNKNOWN))
        assertThat(active().readBytes()).isEqualTo(next.encode())
        lease.held = true
        assertThat(RecoveryEnvelopeTransaction(lease).reconcile(next.transactionId)).isEqualTo(
            RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.COMMITTED),
        )
    }

    @Test
    fun `active readback after successful move must equal intended bytes`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val result =
            RecoveryEnvelopeTransaction(
                lease,
                atomicMove = { from, to ->
                    move(from, to)
                    to.writeText("unexpected active bytes")
                },
            ).replace(old, next)
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(result.directorySynced).isFalse()
        assertThat(active().readText()).isEqualTo("unexpected active bytes")
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
    }

    @Test
    fun `live observation cannot bless tampered retained new bytes matching active`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val changed = next.copy(createdAt = next.createdAt + 1).encode()
        val result =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) {
                    retained(next, "new.envelope").writeBytes(changed)
                    active().writeBytes(changed)
                    throw IOException("changed after rename")
                }
            }.replace(old, next)
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(changed)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        // Restart can only identify which retained bytes are visible; it cannot approve their wraps.
        assertThat(RecoveryEnvelopeTransaction(lease).reconcile(next.transactionId)).isEqualTo(
            RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.COMMITTED),
        )
    }

    @Test
    fun `live observation cannot bless tampered retained old bytes matching active`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val changed = "unrelated prior bytes".toByteArray()
        val result =
            RecoveryEnvelopeTransaction(lease).replace(old, next) {
                retained(next, "old.envelope").writeBytes(changed)
                active().writeBytes(changed)
                throw IOException("changed before rename")
            }
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(changed)
    }

    @Test
    fun `known completed rename followed by old byte restoration is unknown not no mutation`() {
        val lease = lease()
        val old = active().readBytes()
        val next = envelope()
        val result =
            RecoveryEnvelopeTransaction(lease) {
                if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) {
                    active().writeBytes(old)
                    throw IOException("unexpected restoration")
                }
            }.replace(old, next)
        assertThat(result.state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(old)
        assertThat(retained(next, "new.envelope").readBytes()).isEqualTo(next.encode())
    }

    @Test
    fun `revocation is prompt while final validation is blocked outside production guard`() {
        assertPromptRevocation(blockDirectorySync = false)
    }

    @Test
    fun `revocation is prompt while durable directory IO is blocked outside production guard`() {
        assertPromptRevocation(blockDirectorySync = true)
    }

    @Test
    fun `revocation is prompt during postrename sync and returns unknown with new bytes preserved`() {
        assertPromptRevocation(blockDirectorySync = true, afterRename = true)
    }

    private fun assertPromptRevocation(
        blockDirectorySync: Boolean,
        afterRename: Boolean = false,
    ) {
        lease()
        val exclusion = VaultRecoveryExclusion.forDirectory(tempDir.root.canonicalFile)
        val lease = checkNotNull(exclusion.acquireRecovery())
        val old = active().readBytes()
        val next = envelope()
        val entered = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val result = AtomicReference<RecoveryEnvelopeTransaction.Result>()
        val error = AtomicReference<Throwable>()

        fun block() {
            entered.countDown()
            check(continueWork.await(5, TimeUnit.SECONDS))
        }
        var syncCount = 0
        val blockAt = if (afterRename) 4 else 1
        val worker =
            thread {
                try {
                    result.set(
                        RecoveryEnvelopeTransaction(
                            lease,
                            syncDirectory = {
                                syncCount++
                                if (blockDirectorySync && syncCount == blockAt) {
                                    block()
                                }
                                sync(it)
                            },
                        ).replace(old, next) { if (!blockDirectorySync) block() },
                    )
                } catch (failure: Throwable) {
                    error.set(failure)
                }
            }
        var revoker: Thread? = null
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(exclusion.admit()).isNull()
            revoker =
                thread {
                    exclusion.invalidateRecovery()
                    revoked.countDown()
                }
            assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(exclusion.admit()).isNull()
        } finally {
            continueWork.countDown()
            worker.join(5000)
            revoker?.join(5000)
            lease.close()
        }
        assertThat(worker.isAlive).isFalse()
        assertThat(error.get()).isNull()
        assertThat(result.get().state).isEqualTo(RecoveryEnvelopeTransaction.State.UNKNOWN)
        assertThat(active().readBytes()).isEqualTo(if (afterRename) next.encode() else old)
        assertThat(retained(next, "old.envelope").readBytes()).isEqualTo(old)
        checkNotNull(exclusion.admit()).close()
    }

    private fun sync(directory: File) {
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    }

    private fun move(
        from: File,
        to: File,
    ) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private class Lease(
        override val vaultDirectory: File,
    ) : ClosedVaultRecoveryLease {
        var held = true
        var inGuard = false

        override fun <T> whileExclusiveAndClosed(action: () -> T): T {
            assertExclusiveAndClosed()
            inGuard = true
            return try {
                action()
            } finally {
                inGuard = false
            }
        }

        override fun assertExclusiveAndClosed() {
            if (!held) throw RecoverySnapshotRefused()
        }
    }

    private fun lease(): Lease {
        val root = tempDir.root.canonicalFile
        File(root, "keys").mkdirs()
        active().writeText("exact original envelope")
        return Lease(root)
    }

    private fun active() = File(tempDir.root.canonicalFile, "keys/key-envelope.v1")

    private fun retained(
        next: RecoveryEnvelopeV2,
        name: String,
    ) = File(tempDir.root.canonicalFile, "keys/recovery-envelopes/${next.transactionId}/$name")

    private fun envelope() =
        RecoveryEnvelopeV2(
            2,
            1234,
            false,
            UUID.randomUUID(),
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 1 }, ByteArray(12) { 2 }),
            RecoveryEnvelopeV2.Wrap(ByteArray(48) { 3 }, ByteArray(12) { 4 }),
        )
}
