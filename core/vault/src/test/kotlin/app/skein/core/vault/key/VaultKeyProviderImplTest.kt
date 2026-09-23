// skein-3el (E3.I2) — JVM unit tests for the [VaultKeyProviderImpl]
// orchestration. Tests focus on the invariants that
// `ATTACHMENT_ENCRYPTION.md` calls load-bearing (§1.4 zero-on-lock,
// §3.6 rewrap-preserves-master-bytes, §3.8 both-factors-invalidated
// residual-risk surfacing) and on the epoch-based `AuthorizationToken`
// bookkeeping — one behaviour per test, AAA structure.

package app.skein.core.vault.key

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
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

    @Test
    fun `setup when already initialised is refused and leaves the aliases untouched`() =
        runTest {
            // Arrange — a persisted master exists; the aliases wrap it.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(keystore = keystore, storage = storage)
            provider.setupNoUi()
            val createCallsBefore = keystore.createCalls.size
            val rowBefore = storage.readActive()
            // Act
            val result = provider.setupNoUi()
            // Assert — typed refusal (skein-ank2), no alias churn, the wrapped master intact.
            assertThat(result).isEqualTo(SetupResult.AlreadyInitialised)
            assertThat(keystore.createCalls).hasSize(createCallsBefore)
            assertThat(storage.readActive()).isEqualTo(rowBefore)
            val unlock = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            assertThat(unlock).isInstanceOf(UnlockResult.Success::class.java)
        }

    // ---- isInitialised (skein-ank2) -------------------------------------

    @Test
    fun `isInitialised is false before setup`() {
        // Arrange
        val provider = newProvider()
        // Act / Assert
        assertThat(provider.isInitialised()).isFalse()
    }

    @Test
    fun `isInitialised is true after setup`() =
        runTest {
            // Arrange
            val provider = newProvider()
            provider.setupNoUi()
            // Act / Assert
            assertThat(provider.isInitialised()).isTrue()
        }

    @Test
    fun `isInitialised is true over a corrupt envelope so nothing offers a re-setup`() {
        // Arrange — an envelope exists but cannot be decoded.
        val storage =
            FakeMasterKeyStorage().apply {
                failReadsWith = MasterKeyStorageException(MasterKeyStorageException.Kind.CORRUPT, "corrupt")
            }
        val provider = newProvider(storage = storage)
        // Act / Assert — the gate must route to unlock (which reports the
        // typed reason), never to setup, which would be refused anyway.
        assertThat(provider.isInitialised()).isTrue()
    }

    @Test
    fun `isInitialised is true over an unreadable envelope`() {
        // Arrange
        val storage =
            FakeMasterKeyStorage().apply {
                failReadsWith = MasterKeyStorageException(MasterKeyStorageException.Kind.IO, "io")
            }
        val provider = newProvider(storage = storage)
        // Act / Assert
        assertThat(provider.isInitialised()).isTrue()
    }

    @Test
    fun `isInitialised never touches the Keystore`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            // Act
            provider.isInitialised()
            // Assert — a pure envelope probe: no alias created, nothing unwrapped.
            assertThat(keystore.createCalls).isEmpty()
            assertThat(provider.currentKey()).isNull()
        }

    @Test
    fun `setup over a corrupt envelope is refused without touching the Keystore`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val storage =
                FakeMasterKeyStorage().apply {
                    failReadsWith = MasterKeyStorageException(MasterKeyStorageException.Kind.CORRUPT, "corrupt")
                }
            val provider = newProvider(keystore = keystore, storage = storage)
            // Act
            val result = provider.setupNoUi()
            // Assert
            assertThat(result).isEqualTo(SetupResult.Failed("key envelope corrupt"))
            assertThat(keystore.createCalls).isEmpty()
        }

    // ---- wrap rejection cleanup (skein-f9ls) --------------------------

    @Test
    fun `setup cleans up both aliases and reports Failed when the biometric wrap is rejected`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            keystore.rejectWrapForAlias += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            val provider = newProvider(keystore = keystore)
            // Act
            val result = provider.setupNoUi()
            // Assert — no partial state left behind, per the setup() contract.
            assertThat(result).isInstanceOf(SetupResult.Failed::class.java)
            assertThat((result as SetupResult.Failed).reason)
                .isEqualTo("biometric wrap rejected by keystore: IllegalStateException")
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isFalse()
        }

    @Test
    fun `setup cleans up both aliases and reports Failed when the device credential wrap is rejected`() =
        runTest {
            // Arrange — the biometric wrap succeeds; only the second (device
            // credential) wrap is rejected by the keystore.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            keystore.rejectWrapForAlias += VaultKeyProviderImpl.ALIAS_CREDENTIAL
            val provider = newProvider(keystore = keystore)
            // Act
            val result = provider.setupNoUi()
            // Assert
            assertThat(result).isInstanceOf(SetupResult.Failed::class.java)
            assertThat((result as SetupResult.Failed).reason)
                .isEqualTo("device credential wrap rejected by keystore: IllegalStateException")
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isFalse()
        }

    @Test
    fun `setup rejection does not persist an envelope`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            keystore.rejectWrapForAlias += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            val storage = FakeMasterKeyStorage()
            val provider = newProvider(keystore = keystore, storage = storage)
            // Act
            provider.setupNoUi()
            // Assert — a subsequent setup is not refused with AlreadyInitialised.
            assertThat(storage.readActive()).isNull()
        }

    @Test
    fun `rewrap reports Failed when the re-wrap is rejected by the keystore`() =
        runTest {
            // Arrange — setup succeeds, then the surviving factor is used to
            // recover, but re-wrapping the dead factor under its fresh alias
            // is rejected.
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            keystore.rejectWrapForAlias += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            // Assert
            assertThat(result).isInstanceOf(RewrapResult.Failed::class.java)
            assertThat((result as RewrapResult.Failed).reason)
                .isEqualTo("biometric wrap rejected by keystore: IllegalStateException")
        }

    // ---- unlock / lock ------------------------------------------------

    @Test
    fun `unlock over a corrupt envelope returns Failed with the bounded reason`() =
        runTest {
            // Arrange
            val storage =
                FakeMasterKeyStorage().apply {
                    failReadsWith = MasterKeyStorageException(MasterKeyStorageException.Kind.CORRUPT, "corrupt")
                }
            val provider = newProvider(storage = storage)
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — not NotInitialised (that would invite a destructive re-setup).
            assertThat(result).isEqualTo(UnlockResult.Failed("key envelope corrupt"))
            assertThat(provider.currentKey()).isNull()
        }

    @Test
    fun `rewrap over a corrupt envelope returns Failed`() =
        runTest {
            // Arrange
            val storage =
                FakeMasterKeyStorage().apply {
                    failReadsWith = MasterKeyStorageException(MasterKeyStorageException.Kind.IO, "io")
                }
            val provider = newProvider(storage = storage)
            // Act
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            // Assert
            assertThat(result).isEqualTo(RewrapResult.Failed("key envelope io failure"))
        }

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

    /**
     * skein-7yy2 — the property two `:app` instrumented fakes got wrong (they
     * minted fresh random key material on every `unlock`, so the second
     * unlock could not open the vault file the first one created).
     * `lock()` zeroes the LIVE buffer only; the wrapped master it was
     * unwrapped from is untouched, so the next unlock hands back the same
     * bytes and the same vault file reopens. Capturing `firstMaster` as a
     * copy is what makes this an aliasing check too: if `unlock` handed back
     * the very buffer `lock()` zeroed, `currentKey()` would now be all-zero
     * and unequal to it.
     */
    @Test
    fun `a second unlock after lock unwraps the same master bytes`() =
        runTest {
            // Arrange — setup, unlock, snapshot the master, lock.
            val provider = newProvider()
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val firstMaster = provider.currentKey()!!.copyOf()
            check(firstMaster.any { it != 0.toByte() }) { "the fake keystore produced an all-zero master" }
            provider.lock()
            // Act
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(provider.currentKey()).isEqualTo(firstMaster)
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
    fun rewrapAfterInvalidation_leaves_masterKey_accessible() =
        runTest {
            // Arrange — setup, unlock, capture the master bytes, then lock
            // (so `master` is null going into the rewrap, matching the real
            // RECOVERY_REQUIRED entry path).
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val originalMaster = provider.currentKey()!!.copyOf()
            provider.lock()
            keystore.invalidatedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC

            // Act
            val result = provider.rewrapNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)

            // Assert — skein-22su: currentKey() is populated immediately
            // after a successful rewrap, with no intervening unlock() call.
            assertThat(result).isInstanceOf(RewrapResult.Success::class.java)
            val current = provider.currentKey()
            assertThat(current).isNotNull()
            assertThat(current).hasLength(32)
            assertThat(current).isEqualTo(originalMaster)
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

    // ---- device-locked cipher-init (skein-9psb) ------------------------

    @Test
    fun `unlock returns DeviceLocked when the keystore reports the device is locked`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            keystore.deviceLockedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — a distinct, typed outcome, never the generic Failed(reason).
            assertThat(result).isEqualTo(UnlockResult.DeviceLocked)
        }

    @Test
    fun `unlock does not leave a master key behind when the device is locked`() =
        runTest {
            // Arrange
            val keystore = FakeKeystoreFacade(strongBoxAvailable = true)
            val provider = newProvider(keystore = keystore)
            provider.setupNoUi()
            keystore.deviceLockedAliases += VaultKeyProviderImpl.ALIAS_BIOMETRIC
            // Act
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert
            assertThat(provider.currentKey()).isNull()
        }

    @Test
    fun `isDeviceLockedFailure recognises UserNotAuthenticatedException by type`() {
        // Arrange
        val exception = UserNotAuthenticatedException("simulated: device locked")
        // Act / Assert
        assertThat(isDeviceLockedFailure(exception)).isTrue()
    }

    @Test
    fun `isDeviceLockedFailure never matches on message text alone`() {
        // Arrange — an ordinary failure whose message happens to mention "locked".
        val exception = IllegalStateException("device locked (not really — just a coincidental message)")
        // Act / Assert
        assertThat(isDeviceLockedFailure(exception)).isFalse()
    }

    @Test
    fun `isDeviceLockedFailure does not match KeyPermanentlyInvalidatedException`() {
        // Arrange — a different typed recovery path; must not be conflated.
        val exception = KeyPermanentlyInvalidatedException("simulated")
        // Act / Assert
        assertThat(isDeviceLockedFailure(exception)).isFalse()
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
