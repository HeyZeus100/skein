// skein-3el (E3.I2) — JVM unit tests for the [VaultKeyProviderImpl]
// orchestration. Tests focus on the invariants that
// `ATTACHMENT_ENCRYPTION.md` calls load-bearing (§1.4 zero-on-lock,
// §3.6 rewrap-preserves-master-bytes, §3.8 both-factors-invalidated
// residual-risk surfacing) and on the epoch-based `AuthorizationToken`
// bookkeeping — one behaviour per test, AAA structure.

package app.skein.core.vault.key

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VaultKeyProviderImplTest {
    private fun newProvider(
        keystore: FakeKeystoreFacade = FakeKeystoreFacade(strongBoxAvailable = true),
        storage: FakeMasterKeyStorage = FakeMasterKeyStorage(),
    ) = VaultKeyProviderImpl(
        keystore = keystore,
        biometric = FakeBiometricAuthenticator(),
        storage = storage,
        clock = { 42L },
    )

    // ---- setup --------------------------------------------------------

    @Test
    fun `setup with StrongBox reports Success with strongBoxBacked=true`() =
        runTest {
            // Arrange
            val provider = newProvider(keystore = FakeKeystoreFacade(strongBoxAvailable = true))
            // Act
            val result = provider.setupNoUi()
            // Assert
            assertThat(result).isInstanceOf(SetupResult.Success::class.java)
            assertThat((result as SetupResult.Success).strongBoxBacked).isTrue()
        }

    @Test
    fun `setup without StrongBox reports StrongBoxUnavailableFallback`() =
        runTest {
            // Arrange
            val provider = newProvider(keystore = FakeKeystoreFacade(strongBoxAvailable = false))
            // Act
            val result = provider.setupNoUi()
            // Assert
            assertThat(result).isInstanceOf(SetupResult.StrongBoxUnavailableFallback::class.java)
        }

    @Test
    fun `setup with no enrolled biometric reports NoBiometricEnrolled`() =
        runTest {
            // Arrange
            val provider =
                VaultKeyProviderImpl(
                    keystore = FakeKeystoreFacade(),
                    biometric = FakeBiometricAuthenticator(canAuth = CanAuthenticate.NoneEnrolled),
                    storage = FakeMasterKeyStorage(),
                )
            // Act
            val result = provider.setupNoUi()
            // Assert
            assertThat(result).isEqualTo(SetupResult.NoBiometricEnrolled)
        }

    @Test
    fun `setup writes wrapped bytes to storage for both factors`() =
        runTest {
            // Arrange
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(storage = storage)
            // Act
            provider.setupNoUi()
            // Assert
            val row = storage.readActive()
            assertThat(row).isNotNull()
            assertThat(row!!.wrappedBytesBiometric).isNotNull()
            assertThat(row.wrappedBytesCredential).isNotNull()
            assertThat(row.wrapIvBiometric).isNotNull()
            assertThat(row.wrapIvCredential).isNotNull()
        }

    // ---- unlock / lock ------------------------------------------------

    @Test
    fun `unlock after setup makes currentKey return 32-byte master`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(result).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).hasLength(32)
        }

    @Test
    fun `unlock without setup returns NotInitialised`() =
        runTest {
            // Arrange
            val provider = newProvider()
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(result).isEqualTo(UnlockResult.NotInitialised)
        }

    @Test
    fun `lock zeroes the master ByteArray in place`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val before = provider.currentKey()!!
            assertThat(before.any { it != 0.toByte() }).isTrue()
            // Act
            provider.lock()
            // Assert
            // The SAME array instance held internally is now null AND was zeroed
            // in place before nulling — assert both properties.
            assertThat(provider.currentKey()).isNull()
            assertThat(provider.masterForTest()).isNull()
            // The reference we captured before lock() is the same buffer that
            // was zeroed in place per LOCK_POLICY_INDEXING.md §4.4 step 3.
            assertThat(before.all { it == 0.toByte() }).isTrue()
        }

    @Test
    fun `lock is idempotent`() =
        runTest {
            // Arrange
            val provider = newProvider()
            // Act — locking without an unlock must not throw.
            provider.lock()
            provider.lock()
            // Assert
            assertThat(provider.currentKey()).isNull()
        }

    // ---- rewrap invariant --------------------------------------------

    @Test
    fun `rewrap preserves the master key bytes byte-for-byte`() =
        runTest {
            // Arrange — setup, unlock, capture the master bytes.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val originalMaster = provider.currentKey()!!.copyOf()
            provider.lock()

            // Simulate biometric invalidation: the BIO alias is now dead.
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC

            // Act — recover via the surviving DEVICE_CREDENTIAL factor.
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)

            // Assert — rewrap succeeded AND unlocking via the new BIO factor
            // returns the SAME master bytes. Only the envelope changed.
            assertThat(result).isInstanceOf(RewrapResult.Success::class.java)
            val unlockAfter = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            assertThat(unlockAfter).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).isEqualTo(originalMaster)
        }

    @Test
    fun `rewrap when surviving factor is also invalidated returns BothFactorsInvalidated`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            // Both factors invalidated — the §3.8 residual-risk path.
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_CREDENTIAL
            // Act
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            // Assert
            assertThat(result).isEqualTo(RewrapResult.BothFactorsInvalidated)
        }

    @Test
    fun `rewrap bumps master key version in storage`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(keystore = keystore, storage = storage)
            provider.setupNoUi()
            val before = storage.readActive()!!.keyVersion
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL) as RewrapResult.Success
            // Assert
            assertThat(result.newKeyVersion).isEqualTo(before + 1)
            assertThat(storage.readActive()!!.keyVersion).isEqualTo(before + 1)
        }

    // ---- unlock through KeyPermanentlyInvalidated --------------------

    @Test
    fun `unlock returns KeyPermanentlyInvalidated when the alias is dead`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(result).isEqualTo(UnlockResult.KeyPermanentlyInvalidated(VaultKeyProvider.Factor.BIOMETRIC))
        }

    // ---- AuthorizationToken epoch -------------------------------------

    @Test
    fun `AuthorizationToken epoch changes across lock and unlock`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            // Act
            val firstToken = (provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) as UnlockResult.Success).token
            provider.lock()
            val secondToken = (provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) as UnlockResult.Success).token
            // Assert
            assertThat(secondToken).isNotEqualTo(firstToken)
            assertThat(secondToken.epoch).isGreaterThan(firstToken.epoch)
        }

    @Test
    fun `AuthorizationToken epoch changes across rewrap`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            val firstToken = (provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) as UnlockResult.Success).token
            provider.lock()
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            val newToken = (provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) as UnlockResult.Success).token
            // Assert
            assertThat(newToken.epoch).isGreaterThan(firstToken.epoch)
        }

    // ---- integration -------------------------------------------------

    @Test
    fun `setup then unlock via device credential factor also returns the same master`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            val bioResult = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) as UnlockResult.Success
            val masterViaBio = provider.currentKey()!!.copyOf()
            provider.lock()
            // Act
            val credResult = provider.unlockNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL) as UnlockResult.Success
            // Assert — both factors wrap the SAME master (§3.4 invariant).
            assertThat(provider.currentKey()).isEqualTo(masterViaBio)
            // Sanity — both tokens are valid but distinct (different epochs).
            assertThat(bioResult.token).isNotEqualTo(credResult.token)
        }

    @Test
    fun `unlock throwing outside KeyPermanentlyInvalidated surfaces as Failed`() =
        runTest {
            // Arrange — a corrupted iv column causes GCM tag mismatch on doFinal.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(keystore = keystore, storage = storage)
            provider.setupNoUi()
            val row = storage.readActive()!!
            // Corrupt the wrapped bytes to force an AEAD tag mismatch.
            storage.writeInitial(
                row.copy(wrappedBytesBiometric = row.wrappedBytesBiometric!!.copyOf().also { it[0] = it[0].inc() }),
            )
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — MUST NOT expose sensitive detail; only the exception class name.
            assertThat(result).isInstanceOf(UnlockResult.Failed::class.java)
            assertThat((result as UnlockResult.Failed).reason).doesNotContain("key")
        }

    @Test
    fun `unlock zeroes any pre-existing master before overwriting`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val first = provider.currentKey()!!
            // Act — a second unlock (without an intervening lock) still zeros
            // the prior buffer as a belt-and-suspenders defence.
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(first.all { it == 0.toByte() }).isTrue()
        }

    // ---- log-scrubbing -----------------------------------------------

    @Test
    fun `Failed reasons never carry key material`() =
        runTest {
            // Arrange — force a cipher-init failure by clearing the alias.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(keystore = keystore, storage = storage)
            provider.setupNoUi()
            val row = storage.readActive()!!
            val wrappedBytesHex = row.wrappedBytesBiometric!!.joinToString("") { "%02x".format(it) }
            keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — the reason names the exception class, not any bytes
            // from the wrapped ciphertext or the IV.
            assertThat(result).isInstanceOf(UnlockResult.Failed::class.java)
            val reason = (result as UnlockResult.Failed).reason
            assertThat(reason).doesNotContain(wrappedBytesHex)
            // Reason is a short diagnostic phrase — bounded, never carries payload.
            assertThat(reason.length).isLessThan(120)
        }

    @Test
    fun `KeyPermanentlyInvalidatedException can be constructed and caught under returnDefaultValues`() {
        // Arrange / Act — a small dependency-verification test: if this
        // fails to instantiate, the fake Keystore's invalidation simulation
        // won't work and the rewrap / KeyPermanentlyInvalidated tests
        // above become no-ops. The `testOptions.unitTests.isReturnDefaultValues`
        // build.gradle addition (skein-3el) is what makes this work.
        val e = KeyPermanentlyInvalidatedException("simulated")
        // Assert
        assertThat(e).isInstanceOf(java.security.InvalidKeyException::class.java)
    }
}
