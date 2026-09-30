package app.skein.core.vault.key.recovery

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExistingVaultKeyProofTest {
    @get:Rule val tempDir = TempDirRule()

    @Test
    fun `proof consumes key and passes only private copy to native verifier`() {
        val lease = lease()
        val key = ByteArray(32) { 42 }
        ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
            val proof =
                ExistingVaultKeyProof { path, candidate ->
                    assertThat(path).isEqualTo(snapshot.database.absolutePath)
                    assertThat(path).isNotEqualTo(File(lease.vaultDirectory, "vault.db").absolutePath)
                    assertThat(candidate).isEqualTo(ByteArray(32) { 42 })
                    0
                }
            assertThat(proof.verify(snapshot, key)).isEqualTo(ExistingVaultKeyProofResult.VERIFIED)
        }
        assertThat(key).isEqualTo(ByteArray(32))
    }

    @Test
    fun `native refusal statuses never count as proof and zero every key`() {
        ClosedVaultRecoverySnapshot.capture(lease()).use { snapshot ->
            val codes =
                mapOf(
                    1 to ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT,
                    2 to ExistingVaultKeyProofResult.UNSUPPORTED_SCHEMA,
                    3 to ExistingVaultKeyProofResult.UNAVAILABLE,
                    99 to ExistingVaultKeyProofResult.UNAVAILABLE,
                )
            codes.forEach { (code, expected) ->
                val key = ByteArray(32) { 42 }
                assertThat(ExistingVaultKeyProof { _, _ -> code }.verify(snapshot, key)).isEqualTo(expected)
                assertThat(key).isEqualTo(ByteArray(32))
            }
        }
    }

    @Test
    fun `lease lost before or during native work refuses even nominal native success`() {
        val lease = lease()
        ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
            val key = ByteArray(32) { 42 }
            val proof =
                ExistingVaultKeyProof { _, _ ->
                    lease.held = false
                    0
                }
            assertThat(proof.verify(snapshot, key)).isEqualTo(ExistingVaultKeyProofResult.UNAVAILABLE)
            assertThat(key).isEqualTo(ByteArray(32))
            val another = ByteArray(32) { 42 }
            assertThat(ExistingVaultKeyProof { _, _ -> error("must not run") }.verify(snapshot, another))
                .isEqualTo(ExistingVaultKeyProofResult.UNAVAILABLE)
            assertThat(another).isEqualTo(ByteArray(32))
        }
    }

    @Test
    fun `private ciphertext replacement before proof refuses without native call`() {
        val lease = lease()
        val original = File(lease.vaultDirectory, "vault.db").readBytes()
        ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
            snapshot.database.writeBytes(ByteArray(64) { 77 })
            val key = ByteArray(32) { 42 }
            val result = ExistingVaultKeyProof { _, _ -> error("native must not run") }.verify(snapshot, key)
            assertThat(result).isEqualTo(ExistingVaultKeyProofResult.UNAVAILABLE)
            assertThat(key).isEqualTo(ByteArray(32))
            assertThat(File(lease.vaultDirectory, "vault.db").readBytes()).isEqualTo(original)
        }
    }

    @Test
    fun `private database WAL mutation or new WAL cannot turn native success into proof`() {
        val lease = lease()
        val database = File(lease.vaultDirectory, "vault.db")
        val original = database.readBytes()
        for (mutation in listOf("database", "existing WAL", "previously absent WAL")) {
            val sourceWal = File(lease.vaultDirectory, "vault.db-wal")
            if (mutation == "existing WAL") sourceWal.writeBytes(ByteArray(64) { 11 }) else sourceWal.delete()
            val originalWal = sourceWal.takeIf { it.exists() }?.readBytes()?.toList()
            ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
                val key = ByteArray(32) { 42 }
                val proof =
                    ExistingVaultKeyProof { _, _ ->
                        val file =
                            if (mutation ==
                                "database"
                            ) {
                                snapshot.database
                            } else {
                                File(snapshot.database.parentFile, "vault.db-wal")
                            }
                        file.writeBytes(ByteArray(64) { 77 })
                        0
                    }
                assertThat(proof.verify(snapshot, key)).isEqualTo(ExistingVaultKeyProofResult.UNAVAILABLE)
                assertThat(key).isEqualTo(ByteArray(32))
                assertThat(database.readBytes()).isEqualTo(original)
                assertThat(sourceWal.takeIf { it.exists() }?.readBytes()?.toList()).isEqualTo(originalWal)
            }
        }
    }

    @Test
    fun `native exception and invalid key length still zero consumed buffers`() {
        ClosedVaultRecoverySnapshot.capture(lease()).use { snapshot ->
            val key = ByteArray(32) { 42 }
            assertThrows(IllegalStateException::class.java) {
                ExistingVaultKeyProof {
                    _,
                    _,
                    ->
                    error("native unavailable")
                }.verify(snapshot, key)
            }
            assertThat(key).isEqualTo(ByteArray(32))
            val short = ByteArray(31) { 42 }
            assertThat(ExistingVaultKeyProof { _, _ -> error("must not run") }.verify(snapshot, short))
                .isEqualTo(ExistingVaultKeyProofResult.UNAVAILABLE)
            assertThat(short).isEqualTo(ByteArray(31))
        }
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
        File(root, "keys/key-envelope.v1").writeText("original wrapped envelope")
        File(root, "vault.db").writeBytes(ByteArray(64) { it.toByte() })
        return Lease(root)
    }
}
