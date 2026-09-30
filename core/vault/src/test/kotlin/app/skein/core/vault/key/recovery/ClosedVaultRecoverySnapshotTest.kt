package app.skein.core.vault.key.recovery

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClosedVaultRecoverySnapshotTest {
    @get:Rule val tempDir = TempDirRule()

    @Test
    fun `capture copies ciphertext and WAL while leaving all source bytes untouched`() {
        val lease = lease()
        val originals = sourceBytes()
        ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
            assertThat(snapshot.database.readBytes().toList()).isEqualTo(originals.getValue("vault.db"))
            assertThat(
                File(snapshot.database.parentFile, "vault.db-wal").readBytes().toList(),
            ).isEqualTo(originals.getValue("vault.db-wal"))
            assertThat(File(snapshot.database.parentFile, "vault.db-shm").exists()).isFalse()
            snapshot.assertSourceUnchanged()
            assertThat(sourceBytes()).containsExactlyEntriesIn(originals)
        }
        assertThat(File(lease.vaultDirectory, "keys/recovery-proofs").listFiles()).isEmpty()
    }

    @Test
    fun `unestablished lease refuses before creating private copies`() {
        val lease = lease().apply { held = false }
        assertThrows(RecoverySnapshotRefused::class.java) { ClosedVaultRecoverySnapshot.capture(lease) }
        assertThat(File(lease.vaultDirectory, "keys/recovery-proofs").exists()).isFalse()
    }

    @Test
    fun `missing database envelope hot journal and reset refuse`() {
        val lease = lease()
        for (name in listOf("vault.db", "keys/key-envelope.v1")) {
            val file = File(lease.vaultDirectory, name)
            val bytes = file.readBytes()
            file.delete()
            assertThrows(RecoverySnapshotRefused::class.java) { ClosedVaultRecoverySnapshot.capture(lease) }
            file.writeBytes(bytes)
        }
        for (name in listOf("vault.db-journal", ".vault_reset_in_progress")) {
            val marker = File(lease.vaultDirectory, name).apply { writeText("pending") }
            assertThrows(RecoverySnapshotRefused::class.java) { ClosedVaultRecoverySnapshot.capture(lease) }
            marker.delete()
        }
    }

    @Test
    fun `lease loss source replacement and closed snapshot cannot authorize proof`() {
        val lease = lease()
        val snapshot = ClosedVaultRecoverySnapshot.capture(lease)
        lease.held = false
        assertThrows(RecoverySnapshotRefused::class.java) { snapshot.assertSourceUnchanged() }
        lease.held = true
        File(lease.vaultDirectory, "vault.db-wal").appendText("change")
        assertThrows(RecoverySnapshotRefused::class.java) { snapshot.assertSourceUnchanged() }
        snapshot.close()
        assertThrows(RecoverySnapshotRefused::class.java) { snapshot.assertSourceUnchanged() }
    }

    @Test
    fun `symlink source is refused and target is preserved`() {
        val lease = lease()
        val file = File(lease.vaultDirectory, "vault.db")
        val target = File(lease.vaultDirectory, "unrelated").apply { writeText("owner marker") }
        file.delete()
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertThrows(RecoverySnapshotRefused::class.java) { ClosedVaultRecoverySnapshot.capture(lease) }
        assertThat(target.readText()).isEqualTo("owner marker")
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
        listOf("vault.db", "vault.db-wal", "vault.db-shm", "keys/key-envelope.v1").forEachIndexed { index, name ->
            File(root, name).writeBytes(ByteArray(64) { (it + index).toByte() })
        }
        return Lease(root)
    }

    private fun sourceBytes() =
        listOf("vault.db", "vault.db-wal", "vault.db-shm", "keys/key-envelope.v1")
            .associateWith { File(tempDir.root, it).readBytes().toList() }
}
