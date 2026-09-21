// skein-v3wb — instrumented tests for [VaultReset] against real files under
// the test app's `filesDir`/`cacheDir` and the real `AndroidKeyStore` (via
// [AndroidKeystoreFacade]), matching `VaultKeyProviderInstrumentedTest`'s
// shape. Compiled unconditionally; a full on-device run is gated by
// skein-k3b2 (emulator provisioning).

package app.skein.core.vault.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.key.AndroidKeystoreFacade
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviderImpl
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultResetInstrumentedTest {
    private lateinit var context: Context
    private lateinit var keystore: AndroidKeystoreFacade
    private lateinit var vaultDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keystore = AndroidKeystoreFacade(context)
        vaultDir = File(context.filesDir, "vault-reset-test").apply { mkdirs() }
        cleanUpAliases()
    }

    @After
    fun tearDown() {
        vaultDir.deleteRecursively()
        cleanUpAliases()
    }

    private fun cleanUpAliases() {
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_CREDENTIAL)
    }

    private fun newReset(
        attachmentsDir: File = File(vaultDir, "attachments"),
        isUnlocked: () -> Boolean = { false },
    ) = VaultReset(
        vaultDir = vaultDir,
        databaseFile = File(vaultDir, "vault.db"),
        attachmentsDir = attachmentsDir,
        stagingDir = null,
        keystore = keystore::deleteEntry,
        isUnlocked = isUnlocked,
    )

    @Test
    fun reset_deletes_real_files_under_filesDir() {
        // Arrange
        File(vaultDir, "vault.db").writeText("db")
        val envelope =
            File(File(vaultDir, "keys"), "key-envelope.v1").apply {
                parentFile?.mkdirs()
                writeText("envelope")
            }
        val attachments = File(vaultDir, "attachments").apply { mkdirs() }
        File(attachments, "a.bin").writeText("blob")
        val reset = newReset(attachmentsDir = attachments)

        // Act
        val result = reset.reset()

        // Assert
        assertThat(result).isEqualTo(VaultResetResult.Success)
        assertThat(envelope.exists()).isFalse()
        assertThat(File(vaultDir, "vault.db").exists()).isFalse()
        assertThat(attachments.exists()).isFalse()
    }

    @Test
    fun reset_removes_real_Keystore_aliases() {
        // Arrange: provision both Layer-0 aliases the way `setup()` would.
        keystore.createKey(
            alias = VaultKeyProviderImpl.ALIAS_BIOMETRIC,
            factor = VaultKeyProvider.Factor.BIOMETRIC,
            requireStrongBox = false,
        )
        keystore.createKey(
            alias = VaultKeyProviderImpl.ALIAS_CREDENTIAL,
            factor = VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
            requireStrongBox = false,
        )
        assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isTrue()
        assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isTrue()
        val reset = newReset()

        // Act
        reset.reset()

        // Assert
        assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
        assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isFalse()
    }

    @Test
    fun reset_refuses_while_unlocked_and_leaves_real_files_alone() {
        // Arrange
        val dbFile = File(vaultDir, "vault.db").apply { writeText("db") }
        val reset = newReset(isUnlocked = { true })

        // Act
        val result = reset.reset()

        // Assert
        assertThat(result).isEqualTo(VaultResetResult.RefusedUnlocked)
        assertThat(dbFile.exists()).isTrue()
    }
}
