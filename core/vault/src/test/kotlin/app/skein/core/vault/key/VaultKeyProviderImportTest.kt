// skein-v9g (E3.I11) — `VaultKeyProvider.setup(…, existingMaster)`, the
// passphrase-import / device-migration variant, and the end-to-end recovery
// scenario `ATTACHMENT_ENCRYPTION.md` §3.8 leaves open.
//
// The load-bearing assertion is `importingAfterAliasLossRecoversTheSameKey`:
// a vault is provisioned under master M; both Layer-0 aliases AND the key
// envelope are then destroyed (exactly what a simultaneous biometric +
// credential invalidation followed by a reset leaves behind — the data is
// intact, the wrapping is gone); M is exported under a passphrase,
// re-imported, and adopted by a fresh `setup(existingMaster)`. The next
// `unlock()` must surface byte-identical M — which is what makes the
// EXISTING `vault.db` and its attachments (both keyed by M) open again.
// The master's bytes are the whole property: `VaultLifecycle` derives the
// SQLCipher passphrase from them and `FileAttachmentStore` HKDFs per-file
// keys from them, so identical bytes means an identical vault.
//
// One behaviour per test, AAA structure.

package app.skein.core.vault.key

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class VaultKeyProviderImportTest {
    @get:Rule
    val tempDir = TempDirRule()

    private val passphrase = "recover-this-vault-please".toCharArray()
    private val testIterations = 1_000

    private fun fileStorage(): FileMasterKeyStorage =
        FileMasterKeyStorage(FileMasterKeyStorage.envelopeFileIn(tempDir.root))

    private fun provider(
        keystore: FakeKeystoreFacade,
        storage: MasterKeyStorage,
    ) = VaultKeyProviderImpl(
        keystore = keystore,
        biometric = FakeBiometricAuthenticator(),
        storage = storage,
        clock = { 1_700_000_000_000L },
    )

    // ---- setup(existingMaster) -----------------------------------------

    @Test
    fun `setup with an existing master adopts exactly those bytes`() =
        runTest {
            // Arrange
            val imported = ByteArray(32) { (it + 1).toByte() }
            val provider = provider(FakeKeystoreFacade(), FakeMasterKeyStorage())
            // Act
            provider.setupNoUi(existingMaster = imported)
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — the fake Keystore really unwraps back to the supplied bytes
            assertThat(provider.currentKey()).isEqualTo(imported)
        }

    @Test
    fun `setup with an existing master reports Success`() =
        runTest {
            // Arrange
            val provider = provider(FakeKeystoreFacade(strongBoxAvailable = true), FakeMasterKeyStorage())
            // Act
            val result = provider.setupNoUi(existingMaster = ByteArray(32) { 9 })
            // Assert
            assertThat(result).isInstanceOf(SetupResult.Success::class.java)
        }

    @Test
    fun `setup with an existing master creates both Layer-0 aliases`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade()
            val provider = provider(keystore, FakeMasterKeyStorage())
            // Act
            provider.setupNoUi(existingMaster = ByteArray(32) { 5 })
            // Assert
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isTrue()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isTrue()
        }

    @Test
    fun `setup does not take ownership of the caller's master buffer`() =
        runTest {
            // Arrange
            val imported = ByteArray(32) { (it + 1).toByte() }
            val before = imported.copyOf()
            val provider = provider(FakeKeystoreFacade(), FakeMasterKeyStorage())
            // Act
            provider.setupNoUi(existingMaster = imported)
            // Assert
            assertThat(imported).isEqualTo(before)
        }

    @Test
    fun `setup with a wrong-length master is refused`() =
        runTest {
            // Arrange
            val provider = provider(FakeKeystoreFacade(), FakeMasterKeyStorage())
            // Act
            val result = provider.setupNoUi(existingMaster = ByteArray(16))
            // Assert
            assertThat(result).isInstanceOf(SetupResult.Failed::class.java)
        }

    @Test
    fun `setup with a wrong-length master persists nothing`() =
        runTest {
            // Arrange
            val storage = FakeMasterKeyStorage()
            val provider = provider(FakeKeystoreFacade(), storage)
            // Act
            provider.setupNoUi(existingMaster = ByteArray(16))
            // Assert
            assertThat(storage.readActive()).isNull()
        }

    @Test
    fun `importing over a live vault is refused as AlreadyInitialised`() =
        runTest {
            // Arrange — a provisioned device; an import must never restrand it
            val storage = FakeMasterKeyStorage()
            val provider = provider(FakeKeystoreFacade(), storage)
            provider.setupNoUi()
            // Act
            val result = provider.setupNoUi(existingMaster = ByteArray(32) { 3 })
            // Assert
            assertThat(result).isEqualTo(SetupResult.AlreadyInitialised)
        }

    // ---- the §3.8 recovery scenario -------------------------------------

    @Test
    fun `importing after alias and envelope loss recovers the same master key`() =
        runTest {
            // Arrange — a real vault provisioned under master M, over the real
            // key-envelope file backend.
            val firstKeystore = FakeKeystoreFacade()
            val first = provider(firstKeystore, fileStorage())
            first.setupNoUi()
            first.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val originalMaster = requireNonNull(first.currentKey()).copyOf()

            // …the user exports a recovery file while unlocked…
            val recoveryFile =
                PassphraseKeyExport.exportWith(originalMaster, passphrase, testIterations, SecureRandom())

            // …then both factors are invalidated and the envelope is discarded
            // by a user-initiated reset: the Layer-0 wrapping is gone, the
            // vault.db keyed by M is not.
            destroyKeyMaterial(firstKeystore)

            // Act — a fresh device-side provider imports M and adopts it.
            val secondKeystore = FakeKeystoreFacade()
            val second = provider(secondKeystore, fileStorage())
            val recovered = PassphraseKeyExport.importWith(recoveryFile, passphrase, minimumIterations = 1)
            val setupResult = second.setupNoUi(existingMaster = recovered)
            second.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)

            // Assert — the unlocked master is byte-for-byte the original, so the
            // existing vault.db and attachments decrypt exactly as before.
            assertThat(setupResult).isInstanceOf(SetupResult.Success::class.java)
            assertThat(second.currentKey()).isEqualTo(originalMaster)
        }

    @Test
    fun `a recovery import under the wrong passphrase never reaches setup`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade()
            val first = provider(keystore, fileStorage())
            first.setupNoUi()
            first.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val recoveryFile =
                PassphraseKeyExport.exportWith(
                    requireNonNull(first.currentKey()).copyOf(),
                    passphrase,
                    testIterations,
                    SecureRandom(),
                )
            destroyKeyMaterial(keystore)
            // Act
            val outcome =
                runCatching {
                    PassphraseKeyExport.importWith(recoveryFile, "not-the-passphrase".toCharArray(), 1)
                }
            // Assert
            assertThat(outcome.exceptionOrNull()).isInstanceOf(RecoveryFailedException::class.java)
        }

    @Test
    fun `a fresh setup after alias loss produces a DIFFERENT master`() =
        runTest {
            // Arrange — the negative control the import path exists to avoid:
            // re-running plain setup() mints new bytes and strands the vault.
            val keystore = FakeKeystoreFacade()
            val first = provider(keystore, fileStorage())
            first.setupNoUi()
            first.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val originalMaster = requireNonNull(first.currentKey()).copyOf()
            destroyKeyMaterial(keystore)
            // Act
            val second = provider(FakeKeystoreFacade(), fileStorage())
            second.setupNoUi()
            second.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(second.currentKey()).isNotEqualTo(originalMaster)
        }

    // ---- helpers ---------------------------------------------------------

    /** Simulates the §3.8 end state: both Layer-0 aliases gone AND the envelope discarded by a reset. */
    private fun destroyKeyMaterial(keystore: FakeKeystoreFacade) {
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_CREDENTIAL)
        envelopeFile().delete()
    }

    private fun envelopeFile(): File = FileMasterKeyStorage.envelopeFileIn(tempDir.root)

    private fun requireNonNull(bytes: ByteArray?): ByteArray = requireNotNull(bytes) { "expected an unlocked master" }
}
