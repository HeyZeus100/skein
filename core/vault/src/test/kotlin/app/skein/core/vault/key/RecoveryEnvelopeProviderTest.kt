package app.skein.core.vault.key

import app.skein.core.vault.key.recovery.AuthenticatedRecoveryPreparation
import app.skein.core.vault.key.recovery.ExistingVaultKeyProof
import app.skein.core.vault.key.recovery.RecoveryEnvelopeV2
import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.crypto.Cipher

/** Host crypto/orchestration only: the proof callback and per-use authentication are synthetic. */
class RecoveryEnvelopeProviderTest {
    @get:Rule val temp = TempDirRule()

    private val key = ByteArray(32) { (it + 10).toByte() }
    private val id = UUID.fromString("cf1c5a3d-84af-4f37-a7ae-794f3f1eefce")
    private val keystore = RecordingKeystore()

    private fun gate() = VaultRecoveryExclusion.forDirectory(temp.root)

    private fun active() = FileMasterKeyStorage.envelopeFileIn(temp.root)

    private fun storage() = FileMasterKeyStorage(active())

    private fun provider(store: MasterKeyStorage = storage()) =
        VaultKeyProviderImpl(keystore, FakeBiometricAuthenticator(), store, recoveryExclusion = gate())

    private data class Fixture(
        val old: MasterKeyRow,
        val recovery: RecoveryEnvelopeV2,
        val files: Map<File, ByteArray>,
        val mutations: List<String>,
    )

    private suspend fun fixture(activateForReaderTest: Boolean = true): Fixture {
        val original = provider()
        assertThat(original.setupNoUi(key)).isInstanceOf(SetupResult.Success::class.java)
        val old = storage().readActive()!!
        File(temp.root, "vault.db").writeBytes(ByteArray(80) { 9 })
        File(temp.root, "vault.db-wal").writeBytes(ByteArray(40) { 8 })
        File(temp.root, "vault.db-shm").writeBytes(ByteArray(16) { 7 })
        val preparation =
            AuthenticatedRecoveryPreparation(
                gate(),
                keystore,
                ExistingVaultKeyProof { _, candidate ->
                    assertThat(candidate).isEqualTo(key)
                    0
                },
                clock = { 200 },
                newId = { id },
            )
        assertThat(preparation.prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
            .isEqualTo(AuthenticatedRecoveryPreparation.Result.Prepared(id))
        val staged = File(temp.root, "keys/recovery-prepared/$id/new.envelope").readBytes()
        // Synthetic fixture only. Production has no route from Prepared to active-envelope replacement.
        if (activateForReaderTest) active().writeBytes(staged)
        keystore.decryptAliases.clear()
        return Fixture(
            old,
            RecoveryEnvelopeV2.decode(staged),
            temp.root.walkTopDown().filter(File::isFile).associateWith(File::readBytes),
            keystore.mutations.toList(),
        )
    }

    private fun assertPreserved(
        fixture: Fixture,
        activeBytes: ByteArray = fixture.files.getValue(active()),
    ) {
        fixture.files.forEach { (file, bytes) ->
            assertThat(file.readBytes()).isEqualTo(if (file == active()) activeBytes else bytes)
        }
        assertThat(temp.root.walkTopDown().filter(File::isFile).toList()).containsExactlyElementsIn(fixture.files.keys)
        assertThat(keystore.mutations).containsExactlyElementsIn(fixture.mutations).inOrder()
        for (factor in VaultKeyProvider.Factor.entries) {
            val biometric = factor == VaultKeyProvider.Factor.BIOMETRIC
            val alias = if (biometric) VaultKeyProviderImpl.ALIAS_BIOMETRIC else VaultKeyProviderImpl.ALIAS_CREDENTIAL
            val iv = if (biometric) fixture.old.wrapIvBiometric!! else fixture.old.wrapIvCredential!!
            val wrapped = if (biometric) fixture.old.wrappedBytesBiometric!! else fixture.old.wrappedBytesCredential!!
            assertThat(keystore.delegate.containsAlias(alias)).isTrue()
            assertThat(keystore.delegate.decryptCipher(alias, iv).doFinal(wrapped)).isEqualTo(key)
        }
    }

    @Test
    fun `production provider reads prepared v2 bytes through both factors without mutation`() =
        runTest {
            val fixture = fixture()
            for (factor in VaultKeyProvider.Factor.entries) {
                val provider = provider()
                val prompted = mutableListOf<VaultKeyProvider.Factor>()
                assertThat(
                    provider.unlockNoUi(factor) { requested, cipher ->
                        prompted += requested
                        AuthResult.Success(cipher)
                    },
                ).isInstanceOf(UnlockResult.Success::class.java)
                assertThat(prompted).containsExactly(factor)
                assertThat(provider.currentKey()).isEqualTo(key)
                assertThat(gate().acquireRecovery()).isNull()
                val retained = provider.currentKey()!!
                provider.lock()
                assertThat(retained).isEqualTo(ByteArray(32))
                assertThat(provider.currentKey()).isNull()
                gate().acquireRecovery()!!.close()
            }
            assertThat(keystore.decryptAliases)
                .containsExactly(fixture.recovery.alias(true), fixture.recovery.alias(false))
            assertPreserved(fixture)
        }

    @Test
    fun `prepared receipt leaves original active v1 reader and aliases in force`() =
        runTest {
            val fixture = fixture(activateForReaderTest = false)
            assertThat(storage().readActive()!!.recoveryRecord).isNull()
            val provider = provider()
            assertThat(provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC))
                .isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).isEqualTo(key)
            provider.lock()
            assertThat(keystore.decryptAliases).containsExactly(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
            assertPreserved(fixture)
        }

    @Test
    fun `v2 legacy rewrap refuses both factors before authentication or alias mutation`() =
        runTest {
            val fixture = fixture()
            for (factor in VaultKeyProvider.Factor.entries) {
                assertThat(provider().rewrapNoUi(factor))
                    .isEqualTo(RewrapResult.Failed("recovery envelope requires staged recovery"))
            }
            assertThat(keystore.decryptAliases).isEmpty()
            assertPreserved(fixture)
        }

    @Test
    fun `existing v2 setup remains initialised and never replaces aliases or envelope`() =
        runTest {
            val fixture = fixture()
            val provider = provider()
            assertThat(provider.isInitialised()).isTrue()
            assertThat(provider.setupNoUi()).isEqualTo(SetupResult.AlreadyInitialised)
            assertThat(provider.currentKey()).isNull()
            assertPreserved(fixture)
        }

    @Test
    fun `recomputed checksum cannot bless changed generation time or StrongBox metadata`() =
        runTest {
            val fixture = fixture()
            for (changed in listOf(
                fixture.recovery.copy(generation = 3),
                fixture.recovery.copy(createdAt = 201),
                fixture.recovery.copy(strongBoxBacked = false),
            )) {
                val bytes = changed.encode()
                active().writeBytes(bytes)
                for (factor in VaultKeyProvider.Factor.entries) {
                    val provider = provider()
                    val result = provider.unlockNoUi(factor)
                    assertThat(result).isInstanceOf(UnlockResult.Failed::class.java)
                    assertThat((result as UnlockResult.Failed).reason).startsWith("unwrap failed:")
                    assertThat(provider.currentKey()).isNull()
                }
                assertPreserved(fixture, bytes)
            }
        }

    @Test
    fun `factor AAD is mandatory even when alias and ciphertext decrypt correctly without it`() =
        runTest {
            val fixture = fixture()
            val cipher = keystore.delegate.encryptCipher(fixture.recovery.alias(true))
            val bad = fixture.recovery.copy(biometric = RecoveryEnvelopeV2.Wrap(cipher.doFinal(key), cipher.iv))
            active().writeBytes(bad.encode())
            val provider = provider()
            assertThat(provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC))
                .isInstanceOf(UnlockResult.Failed::class.java)
            assertThat(provider.currentKey()).isNull()
            assertPreserved(fixture, bad.encode())
        }

    @Test
    fun `wrap authenticated for opposite factor is rejected by provider`() =
        runTest {
            val fixture = fixture()
            val cipher = keystore.delegate.encryptCipher(fixture.recovery.alias(true))
            cipher.updateAAD(fixture.recovery.authenticationData(false))
            val bad = fixture.recovery.copy(biometric = RecoveryEnvelopeV2.Wrap(cipher.doFinal(key), cipher.iv))
            active().writeBytes(bad.encode())
            assertThat(provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC))
                .isInstanceOf(UnlockResult.Failed::class.java)
            assertPreserved(fixture, bad.encode())
        }

    @Test
    fun `substituted Cipher cannot stand in for the authenticated CryptoObject`() =
        runTest {
            val fixture = fixture()
            val provider = provider()
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ ->
                    AuthResult.Success(
                        keystore.delegate.decryptCipher(fixture.recovery.alias(true), fixture.recovery.biometric.iv),
                    )
                },
            ).isEqualTo(UnlockResult.Failed("authenticated cipher mismatch"))
            assertThat(provider.currentKey()).isNull()
            assertPreserved(fixture)
        }

    @Test
    fun `missing v2 factor never falls back to the surviving original v1 alias`() =
        runTest {
            val fixture = fixture()
            keystore.delegate.deleteEntry(fixture.recovery.alias(true))
            var prompts = 0
            assertThat(
                provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, cipher ->
                    prompts++
                    AuthResult.Success(cipher)
                },
            ).isEqualTo(UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC))
            assertThat(prompts).isEqualTo(0)
            assertThat(keystore.decryptAliases).containsExactly(fixture.recovery.alias(true))
            assertPreserved(fixture)
        }

    @Test
    fun `invalidated v2 alias and device lock keep their distinct nonmutating results`() =
        runTest {
            val fixture = fixture()
            val alias = fixture.recovery.alias(true)
            keystore.delegate.invalidatedAliases += alias
            assertThat(provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC))
                .isEqualTo(UnlockResult.KeyPermanentlyInvalidated(VaultKeyProvider.Factor.BIOMETRIC))
            keystore.delegate.invalidatedAliases.clear()
            keystore.delegate.deviceLockedAliases += alias
            assertThat(provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)).isEqualTo(UnlockResult.DeviceLocked)
            assertPreserved(fixture)
        }

    @Test
    fun `corrupted or removed active envelope during authentication cannot publish a key`() =
        runTest {
            val fixture = fixture()
            for (remove in listOf(false, true)) {
                active().writeBytes(fixture.files.getValue(active()))
                val provider = provider()
                assertThat(
                    provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, cipher ->
                        if (remove) active().delete() else active().writeBytes(byteArrayOf(1))
                        AuthResult.Success(cipher)
                    },
                ).isInstanceOf(UnlockResult.Failed::class.java)
                assertThat(provider.currentKey()).isNull()
                if (remove) assertThat(active().exists()).isFalse()
            }
            // The synthetic external mutation is restored only by the test; the provider never writes it.
            active().writeBytes(fixture.files.getValue(active()))
            assertPreserved(fixture)
        }

    @Test
    fun `changed active record during authentication is refused without overwriting external bytes`() =
        runTest {
            val fixture = fixture()
            val changed = fixture.recovery.copy(generation = 3).encode()
            val provider = provider()
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, cipher ->
                    active().writeBytes(changed)
                    AuthResult.Success(cipher)
                },
            ).isEqualTo(UnlockResult.Failed("key envelope changed during authentication"))
            assertThat(provider.currentKey()).isNull()
            assertPreserved(fixture, changed)
        }

    @Test
    fun `legacy row array mutation cannot change frozen v2 metadata or wraps during authentication`() =
        runTest {
            val fixture = fixture()
            val row = storage().readActive()!!.copy(keyVersion = 99, createdAt = 999, strongBoxBacked = false)
            val frozenStorage = object : MasterKeyStorage by storage() {
                override fun readActive() = row
            }
            val provider = provider(frozenStorage)
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, cipher ->
                    row.wrappedBytesBiometric!!.fill(0)
                    row.wrapIvBiometric!!.fill(0)
                    row.recoveryRecord!!.decode().biometric.ciphertext.fill(0)
                    AuthResult.Success(cipher)
                },
            ).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).isEqualTo(key)
            provider.lock()
            assertPreserved(fixture)
        }

    @Test
    fun `cancelled or failed factor authentication cannot publish a key`() =
        runTest {
            val fixture = fixture()
            val provider = provider()
            assertThat(provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ -> AuthResult.UserCancelled })
                .isEqualTo(UnlockResult.UserCancelled)
            assertThat(provider.currentKey()).isNull()
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ -> AuthResult.Error(7, "synthetic") },
            )
                .isInstanceOf(UnlockResult.Failed::class.java)
            assertThat(provider.currentKey()).isNull()
            gate().acquireRecovery()!!.close()
            assertPreserved(fixture)
        }

    @Test
    fun `lock during v2 authentication rejects late success and queued authentication`() =
        runTest {
            val fixture = fixture()
            val provider = provider()
            val finish = CompletableDeferred<Unit>()
            val pending =
                async {
                    provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, cipher ->
                        finish.await()
                        AuthResult.Success(cipher)
                    }
                }
            runCurrent()
            val queued = async {
                provider.unlockNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL) { _, _ -> error("stale queued prompt") }
            }
            runCurrent()
            provider.lock()
            assertThat(gate().acquireRecovery()).isNull()
            finish.complete(Unit)
            assertThat(pending.await()).isEqualTo(UnlockResult.UserCancelled)
            assertThat(queued.await()).isEqualTo(UnlockResult.UserCancelled)
            assertThat(provider.currentKey()).isNull()
            assertThat(provider.masterForTest()).isNull()
            gate().acquireRecovery()!!.close()
            assertPreserved(fixture)
        }

    @Test
    fun `coroutine cancellation while authenticating preserves all evidence and releases admission`() =
        runTest {
            val fixture = fixture()
            val provider = provider()
            val pending = async {
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ ->
                    CompletableDeferred<AuthResult>().await()
                }
            }
            runCurrent()
            pending.cancel()
            pending.join()
            assertThat(provider.currentKey()).isNull()
            gate().acquireRecovery()!!.close()
            assertPreserved(fixture)
        }

    @Test
    fun `active recovery lease refuses unlock before any cipher or authentication request`() =
        runTest {
            val fixture = fixture()
            gate().acquireRecovery()!!.use {
                assertThat(
                    provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ -> error("must not prompt") },
                )
                    .isInstanceOf(UnlockResult.Failed::class.java)
            }
            assertThat(keystore.decryptAliases).isEmpty()
            assertPreserved(fixture)
        }

    private class RecordingKeystore(
        val delegate: FakeKeystoreFacade = FakeKeystoreFacade(),
    ) : KeystoreFacade by delegate {
        val mutations = mutableListOf<String>()
        val decryptAliases = mutableListOf<String>()

        override fun createKey(
            alias: String,
            factor: VaultKeyProvider.Factor,
            requireStrongBox: Boolean,
        ) {
            mutations += "create:$alias"
            delegate.createKey(alias, factor, requireStrongBox)
        }

        override fun deleteEntry(alias: String) {
            mutations += "delete:$alias"
            delegate.deleteEntry(alias)
        }

        override fun decryptCipher(
            alias: String,
            iv: ByteArray,
        ): Cipher {
            decryptAliases += alias
            return delegate.decryptCipher(alias, iv)
        }
    }
}
