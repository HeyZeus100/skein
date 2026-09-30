package app.skein.core.vault.key.recovery

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

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
        assertThrows(RecoverySnapshotRefused::class.java) { transaction.replace(old, next) }
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

    private class Lease(
        override val vaultDirectory: File,
    ) : ClosedVaultRecoveryLease {
        var held = true

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
