// skein-v3wb — JVM unit tests for [VaultReset] over temp dirs and a fake
// [KeystoreAliasDeleter]. One behaviour per test, AAA structure, matching
// `VaultKeyProviderImplTest`'s style.

package app.skein.core.vault.lifecycle

import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.io.File

class VaultResetTest {
    @get:Rule
    val tempDir = TempDirRule()

    private class RecordingKeystore : KeystoreAliasDeleter {
        val deleted = mutableListOf<String>()

        override fun deleteEntry(alias: String) {
            deleted += alias
        }
    }

    private fun newReset(
        vaultDir: File = tempDir.root,
        databaseFile: File = File(vaultDir, "vault.db"),
        attachmentsDir: File = File(vaultDir, "attachments"),
        stagingDir: File? = null,
        keystore: RecordingKeystore = RecordingKeystore(),
        isUnlocked: () -> Boolean = { false },
    ) = VaultReset(
        vaultDir = vaultDir,
        databaseFile = databaseFile,
        attachmentsDir = attachmentsDir,
        stagingDir = stagingDir,
        keystore = keystore,
        isUnlocked = isUnlocked,
    )

    /** Seeds every path a real vault could have left behind, plus a decoy sibling outside [vaultDir]. */
    private class Fixture(
        val vaultDir: File,
        val databaseFile: File,
        val walFile: File,
        val shmFile: File,
        val attachmentsDir: File,
        val attachmentFile: File,
        val envelopeFile: File,
        val outsideDecoy: File,
    )

    private fun seedFixture(root: File): Fixture {
        val vaultDir = File(root, "vault").apply { mkdirs() }
        val databaseFile = File(vaultDir, "vault.db").apply { writeText("db") }
        val walFile = File(vaultDir, "vault.db-wal").apply { writeText("wal") }
        val shmFile = File(vaultDir, "vault.db-shm").apply { writeText("shm") }
        val attachmentsDir = File(vaultDir, "attachments").apply { mkdirs() }
        val attachmentFile = File(attachmentsDir, "some-uuid.bin").apply { writeText("blob") }
        val envelopeFile =
            FileMasterKeyStorage.envelopeFileIn(vaultDir).apply {
                parentFile?.mkdirs()
                writeText("envelope")
            }
        val outsideDecoy = File(root, "not-the-vault.txt").apply { writeText("leave me alone") }
        return Fixture(
            vaultDir,
            databaseFile,
            walFile,
            shmFile,
            attachmentsDir,
            attachmentFile,
            envelopeFile,
            outsideDecoy,
        )
    }

    // ---- happy path -----------------------------------------------------

    @Test
    fun `reset deletes the envelope, database, wal-shm, and attachments`() {
        // Arrange
        val f = seedFixture(tempDir.root)
        val reset = newReset(vaultDir = f.vaultDir, databaseFile = f.databaseFile, attachmentsDir = f.attachmentsDir)
        // Act
        val result = reset.reset()
        // Assert
        assertThat(result).isEqualTo(VaultResetResult.Success)
        assertThat(f.envelopeFile.exists()).isFalse()
        assertThat(f.databaseFile.exists()).isFalse()
        assertThat(f.walFile.exists()).isFalse()
        assertThat(f.shmFile.exists()).isFalse()
        assertThat(f.attachmentsDir.exists()).isFalse()
        assertThat(f.attachmentFile.exists()).isFalse()
    }

    @Test
    fun `reset deletes nothing outside the vault directory`() {
        // Arrange
        val f = seedFixture(tempDir.root)
        val reset = newReset(vaultDir = f.vaultDir, databaseFile = f.databaseFile, attachmentsDir = f.attachmentsDir)
        // Act
        reset.reset()
        // Assert
        assertThat(f.outsideDecoy.exists()).isTrue()
    }

    @Test
    fun `reset deletes both Keystore aliases via the facade`() {
        // Arrange
        val keystore = RecordingKeystore()
        val reset = newReset(keystore = keystore)
        // Act
        reset.reset()
        // Assert
        assertThat(keystore.deleted).containsExactly(
            "skein_master_bio_v1",
            "skein_master_cred_v1",
        )
    }

    @Test
    fun `reset also deletes an explicitly supplied staging directory`() {
        // Arrange
        val staging = tempDir.newDir("staging_export")
        File(staging, "spooled.pdf").writeText("pdf bytes")
        val reset = newReset(stagingDir = staging)
        // Act
        reset.reset()
        // Assert
        assertThat(staging.exists()).isFalse()
    }

    @Test
    fun `reset is a no-op success when nothing exists yet`() {
        // Arrange
        val reset = newReset(vaultDir = tempDir.newDir("empty-vault"))
        // Act
        val result = reset.reset()
        // Assert
        assertThat(result).isEqualTo(VaultResetResult.Success)
    }

    // ---- refusal while unlocked ------------------------------------------

    @Test
    fun `reset refuses while unlocked and deletes nothing`() {
        // Arrange
        val f = seedFixture(tempDir.root)
        val reset =
            newReset(
                vaultDir = f.vaultDir,
                databaseFile = f.databaseFile,
                attachmentsDir = f.attachmentsDir,
                isUnlocked = { true },
            )
        // Act
        val result = reset.reset()
        // Assert
        assertThat(result).isEqualTo(VaultResetResult.RefusedUnlocked)
        assertThat(f.envelopeFile.exists()).isTrue()
        assertThat(f.databaseFile.exists()).isTrue()
        assertThat(f.attachmentsDir.exists()).isTrue()
    }

    @Test
    fun `reset refuses while unlocked without writing the marker`() {
        // Arrange
        val vaultDir = tempDir.newDir("vault")
        val reset = newReset(vaultDir = vaultDir, isUnlocked = { true })
        // Act
        reset.reset()
        // Assert
        assertThat(File(vaultDir, VaultReset.MARKER_FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `reset does not touch the Keystore while unlocked`() {
        // Arrange
        val keystore = RecordingKeystore()
        val reset = newReset(keystore = keystore, isUnlocked = { true })
        // Act
        reset.reset()
        // Assert
        assertThat(keystore.deleted).isEmpty()
    }

    // ---- idempotence ------------------------------------------------------

    @Test
    fun `reset called twice in a row succeeds both times`() {
        // Arrange
        val f = seedFixture(tempDir.root)
        val reset = newReset(vaultDir = f.vaultDir, databaseFile = f.databaseFile, attachmentsDir = f.attachmentsDir)
        // Act
        val first = reset.reset()
        val second = reset.reset()
        // Assert
        assertThat(first).isEqualTo(VaultResetResult.Success)
        assertThat(second).isEqualTo(VaultResetResult.Success)
    }

    @Test
    fun `reset called twice deletes each Keystore alias twice, harmlessly`() {
        // Arrange
        val keystore = RecordingKeystore()
        val reset = newReset(keystore = keystore)
        // Act
        reset.reset()
        reset.reset()
        // Assert
        assertThat(keystore.deleted).hasSize(4)
    }

    // ---- crash-safety: marker + resume ------------------------------------

    @Test
    fun `reset leaves no marker behind after a clean run`() {
        // Arrange
        val vaultDir = tempDir.newDir("vault")
        val reset = newReset(vaultDir = vaultDir)
        // Act
        reset.reset()
        // Assert
        assertThat(File(vaultDir, VaultReset.MARKER_FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `resumeIfPending returns false and does nothing when no reset was interrupted`() {
        // Arrange
        val f = seedFixture(tempDir.root)
        val reset = newReset(vaultDir = f.vaultDir, databaseFile = f.databaseFile, attachmentsDir = f.attachmentsDir)
        // Act
        val resumed = reset.resumeIfPending()
        // Assert
        assertThat(resumed).isFalse()
        assertThat(f.envelopeFile.exists()).isTrue()
    }

    @Test
    fun `resumeIfPending finishes a half-done reset and clears the marker`() {
        // Arrange: simulate a process death mid-reset — the marker is on
        // disk but the envelope was never deleted (only step 1 landed).
        val f = seedFixture(tempDir.root)
        File(f.vaultDir, VaultReset.MARKER_FILE_NAME).writeBytes(ByteArray(0))
        val keystore = RecordingKeystore()
        val reset =
            newReset(
                vaultDir = f.vaultDir,
                databaseFile = f.databaseFile,
                attachmentsDir = f.attachmentsDir,
                keystore = keystore,
            )
        // Act
        val resumed = reset.resumeIfPending()
        // Assert
        assertThat(resumed).isTrue()
        assertThat(f.envelopeFile.exists()).isFalse()
        assertThat(f.databaseFile.exists()).isFalse()
        assertThat(f.attachmentsDir.exists()).isFalse()
        assertThat(keystore.deleted).containsExactly("skein_master_bio_v1", "skein_master_cred_v1")
        assertThat(File(f.vaultDir, VaultReset.MARKER_FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `resumeIfPending runs even while unlocked`() {
        // Arrange: resume is a cold-start-only path, before any unlock is
        // possible in production, so it deliberately ignores isUnlocked.
        val vaultDir = tempDir.newDir("vault")
        File(vaultDir, VaultReset.MARKER_FILE_NAME).writeBytes(ByteArray(0))
        val reset = newReset(vaultDir = vaultDir, isUnlocked = { true })
        // Act
        val resumed = reset.resumeIfPending()
        // Assert
        assertThat(resumed).isTrue()
    }

    // ---- construction safety ------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `construction refuses an attachments directory outside the vault directory`() {
        // Arrange / Act
        newReset(
            vaultDir = tempDir.newDir("vault"),
            attachmentsDir = tempDir.newDir("not-the-vault"),
        )
        // Assert — the constructor throws.
    }

    @Test(expected = IllegalArgumentException::class)
    fun `construction refuses a database file outside the vault directory`() {
        // Arrange / Act
        val vaultDir = tempDir.newDir("vault")
        newReset(
            vaultDir = vaultDir,
            databaseFile = File(tempDir.newDir("not-the-vault"), "vault.db"),
        )
        // Assert — the constructor throws.
    }
}
