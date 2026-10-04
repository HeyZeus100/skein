package app.skein.core.vault.key.recovery

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.key.AuthResult
import app.skein.core.vault.key.BiometricAuthenticator
import app.skein.core.vault.key.CanAuthenticate
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.KeystoreFacade
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviderImpl
import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Real native SQLCipher proof, closed-file snapshots, production exclusion and filesystem activation.
 * Factor keys and auth callbacks are synthetic in-memory AES: these tests establish neither per-use
 * AndroidKeyStore authentication nor StrongBox, public recovery wiring, power-loss or physical gates.
 * Every database, alias map and failure injection belongs only to a disposable test fixture.
 */
@RunWith(AndroidJUnit4::class)
class AuthenticatedRecoveryActivationInstrumentedTest {
    private val directories = mutableListOf<File>()
    private val fixtures = mutableListOf<Fixture>()
    private val key = ByteArray(32) { (it + 64).toByte() }

    @After
    fun cleanup() {
        fixtures.forEach { it.provider.lock() }
        directories.forEach { it.deleteRecursively() }
        key.fill(0)
    }

    @Test
    fun nativeProofAndBothFreshReadbacksActivateMetadataOnlyThenBothNormalFactorsRead() =
        runBlocking {
            val fixture = fixture()
            val input = key.copyOf()
            val prompts = mutableListOf<VaultKeyProvider.Factor>()
            val ciphers = mutableListOf<Cipher>()
            val result =
                fixture.activation().activateWith(fixture.id, input) { factor, cipher ->
                    assertThat(input).isEqualTo(ByteArray(32))
                    assertThat(fixture.gate.admit()).isNull()
                    assertThat(fixture.gate.acquireRecovery()).isNull()
                    prompts += factor
                    ciphers += cipher
                    AuthResult.Success(cipher)
                }
            assertObserved(result, RecoveryEnvelopeTransaction.State.COMMITTED, durable = true)
            assertThat(prompts).containsExactlyElementsIn(VaultKeyProvider.Factor.entries).inOrder()
            assertThat(ciphers[0]).isNotSameInstanceAs(ciphers[1])
            assertThat(fixture.store.decryptAliases)
                .containsExactly(fixture.record.alias(true), fixture.record.alias(false))
                .inOrder()
            assertThat(input).isEqualTo(ByteArray(32))
            assertPreserved(fixture, activeBytes = fixture.proposed)
            assertRetained(fixture, installExists = false)
            assertBothNormalFactorsRead(fixture)
            assertPreserved(fixture, activeBytes = fixture.proposed)
        }

    @Test
    fun wrongCandidateFailsFreshNativeProofBeforeAuthenticationOrTransaction() =
        runBlocking {
            val fixture = fixture()
            val wrong = ByteArray(32) { 9 }
            assertThat(fixture.activation().activateWith(fixture.id, wrong) { _, _ -> error("must not authenticate") })
                .isEqualTo(
                    AuthenticatedRecoveryActivation.Result.ProofRejected(
                        ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT,
                    ),
                )
            assertThat(wrong).isEqualTo(ByteArray(32))
            assertThat(fixture.store.decryptAliases).isEmpty()
            assertThat(fixture.transactionDirectory.exists()).isFalse()
            assertPreserved(fixture)
            assertBothNormalFactorsRead(fixture)
        }

    @Test
    fun substitutingEitherAuthenticatedCipherRefusesWithoutChangingEncryptedSources() =
        runBlocking {
            for (replacedFactor in VaultKeyProvider.Factor.entries) {
                val fixture = fixture()
                val input = key.copyOf()
                val result =
                    fixture.activation().activateWith(fixture.id, input) { factor, cipher ->
                        AuthResult.Success(
                            if (factor ==
                                replacedFactor
                            ) {
                                Cipher.getInstance("AES/GCM/NoPadding")
                            } else {
                                cipher
                            },
                        )
                    }
                assertThat(result).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertThat(input).isEqualTo(ByteArray(32))
                assertThat(fixture.transactionDirectory.exists()).isFalse()
                assertPreserved(fixture)
            }
        }

    @Test
    fun recomputedChecksumCannotAuthenticateChangedStagedHeader() =
        runBlocking {
            val fixture = fixture()
            val changed = fixture.record.copy(createdAt = fixture.record.createdAt + 1).encode()
            fixture.staged("new.envelope").writeBytes(changed)
            var prompts = 0
            val input = key.copyOf()
            val result =
                fixture.activation().activateWith(fixture.id, input) { _, cipher ->
                    prompts++
                    AuthResult.Success(cipher)
                }
            assertThat(result).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertThat(prompts).isEqualTo(1)
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(fixture.transactionDirectory.exists()).isFalse()
            assertPreserved(fixture, stagedBytes = changed)
            assertBothNormalFactorsRead(fixture)
        }

    @Test
    fun liveStageTamperDuringSecondAuthenticationCannotReachRename() =
        runBlocking {
            val fixture = fixture()
            val changed = fixture.record.copy(createdAt = fixture.record.createdAt + 1).encode()
            val input = key.copyOf()
            val result =
                fixture.activation().activateWith(fixture.id, input) { factor, cipher ->
                    if (factor ==
                        VaultKeyProvider.Factor.DEVICE_CREDENTIAL
                    ) {
                        fixture.staged("new.envelope").writeBytes(changed)
                    }
                    AuthResult.Success(cipher)
                }
            assertThat(result).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(fixture.transactionDirectory.exists()).isFalse()
            assertPreserved(fixture, stagedBytes = changed)
        }

    @Test
    fun eitherAuthenticatedWrapOfAnotherKeyCannotBorrowTheProvedDatabaseCandidate() =
        runBlocking {
            for (biometric in listOf(true, false)) {
                val fixture = fixture()
                val record = fixture.record
                val cipher = fixture.store.encryptCipher(record.alias(biometric))
                cipher.updateAAD(record.authenticationData(biometric))
                val otherKey = ByteArray(32) { 11 }
                val wrongWrap =
                    try {
                        RecoveryEnvelopeV2.Wrap(cipher.doFinal(otherKey), cipher.iv.copyOf())
                    } finally {
                        otherKey.fill(0)
                    }
                val changed =
                    (
                        if (biometric) {
                            record.copy(
                                biometric = wrongWrap,
                            )
                        } else {
                            record.copy(credential = wrongWrap)
                        }
                    ).encode()
                fixture.staged("new.envelope").writeBytes(changed)
                val prompts = mutableListOf<VaultKeyProvider.Factor>()
                val input = key.copyOf()
                assertThat(
                    fixture.activation().activateWith(fixture.id, input) { factor, authenticated ->
                        prompts += factor
                        AuthResult.Success(authenticated)
                    },
                ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertThat(prompts.size).isEqualTo(if (biometric) 1 else 2)
                assertThat(input).isEqualTo(ByteArray(32))
                assertThat(fixture.transactionDirectory.exists()).isFalse()
                assertPreserved(fixture, stagedBytes = changed)
                assertBothNormalFactorsRead(fixture)
            }
        }

    @Test
    fun cancellationDuringSecondAuthenticationZerosInputAndReleasesClosedVaultLease() =
        runBlocking {
            val fixture = fixture()
            val input = key.copyOf()
            val entered = CompletableDeferred<Unit>()
            val operation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.activation().activateWith(fixture.id, input) { factor, cipher ->
                        if (factor == VaultKeyProvider.Factor.DEVICE_CREDENTIAL) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                        AuthResult.Success(cipher)
                    }
                }
            withTimeout(10_000) { entered.await() }
            assertThat(fixture.gate.admit()).isNull()
            operation.cancel()
            withTimeout(10_000) { operation.join() }
            assertThat(operation.isCancelled).isTrue()
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(fixture.transactionDirectory.exists()).isFalse()
            assertPreserved(fixture)
        }

    @Test
    fun revocationDuringSecondAuthenticationRefusesAndPreservesBothOriginalWraps() =
        runBlocking {
            val fixture = fixture()
            val input = key.copyOf()
            val result =
                fixture.activation().activateWith(fixture.id, input) { factor, cipher ->
                    if (factor == VaultKeyProvider.Factor.DEVICE_CREDENTIAL) {
                        fixture.gate.invalidateRecovery()
                        assertThat(fixture.gate.admit()).isNull()
                        assertThat(fixture.gate.acquireRecovery()).isNull()
                    }
                    AuthResult.Success(cipher)
                }
            assertThat(result).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(fixture.transactionDirectory.exists()).isFalse()
            assertPreserved(fixture)
            assertBothNormalFactorsRead(fixture)
        }

    @Test
    fun injectedBeforeRenameIoRetainsExactEvidenceAndFreshInspectionObservesOldOnly() =
        runBlocking {
            val fixture = fixture()
            val activation =
                fixture.activation { lease ->
                    RecoveryEnvelopeTransaction(lease, boundary = { boundary ->
                        if (boundary ==
                            RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME
                        ) {
                            throw IOException("injected before rename")
                        }
                    })
                }
            assertObserved(activate(fixture, activation), RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
            assertPreserved(fixture)
            assertRetained(fixture, installExists = true)
            assertInspection(fixture, RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
            assertBothNormalFactorsRead(fixture)
        }

    @Test
    fun injectedPostRenameDirectorySyncIoReportsVisibleNewWithUncertainDurability() =
        runBlocking {
            val fixture = fixture()
            var renamed = false
            val activation =
                fixture.activation { lease ->
                    RecoveryEnvelopeTransaction(
                        lease,
                        syncDirectory = { directory ->
                            if (renamed) throw IOException("injected directory sync after rename")
                            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
                        },
                        boundary = { if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) renamed = true },
                    )
                }
            assertObserved(activate(fixture, activation), RecoveryEnvelopeTransaction.State.COMMITTED)
            assertPreserved(fixture, activeBytes = fixture.proposed)
            assertRetained(fixture, installExists = false)
            assertInspection(fixture, RecoveryEnvelopeTransaction.State.COMMITTED)
            assertBothNormalFactorsRead(fixture)
        }

    @Test
    fun postRenameRevocationIsUnknownUntilFreshLeaseObservationWithoutUnlockAuthority() =
        runBlocking {
            val fixture = fixture()
            val activation =
                fixture.activation { lease ->
                    RecoveryEnvelopeTransaction(lease, boundary = { boundary ->
                        if (boundary ==
                            RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME
                        ) {
                            fixture.gate.invalidateRecovery()
                        }
                    })
                }
            assertObserved(activate(fixture, activation), RecoveryEnvelopeTransaction.State.UNKNOWN)
            assertPreserved(fixture, activeBytes = fixture.proposed)
            assertRetained(fixture, installExists = false)
            assertInspection(fixture, RecoveryEnvelopeTransaction.State.COMMITTED)
            assertBothNormalFactorsRead(fixture)
        }

    private suspend fun activate(
        fixture: Fixture,
        activation: AuthenticatedRecoveryActivation,
    ): AuthenticatedRecoveryActivation.Result {
        val input = key.copyOf()
        return activation.activateWith(fixture.id, input) { _, cipher -> AuthResult.Success(cipher) }.also {
            assertThat(input).isEqualTo(ByteArray(32))
        }
    }

    private fun assertObserved(
        result: AuthenticatedRecoveryActivation.Result,
        state: RecoveryEnvelopeTransaction.State,
        durable: Boolean = false,
    ) {
        assertThat(result).isEqualTo(
            AuthenticatedRecoveryActivation.Result.Observed(RecoveryEnvelopeTransaction.Result(state, durable)),
        )
    }

    private fun assertInspection(
        fixture: Fixture,
        state: RecoveryEnvelopeTransaction.State,
    ) {
        val before =
            fixture.root.walkTopDown().filter(File::isFile).associate {
                it.relativeTo(fixture.root).path to
                    it.readBytes().toList()
            }
        checkNotNull(fixture.gate.acquireRecovery()).use { freshLease ->
            assertThat(RecoveryEnvelopeTransaction(freshLease).reconcile(fixture.id))
                .isEqualTo(RecoveryEnvelopeTransaction.Result(state))
        }
        assertThat(
            fixture.root.walkTopDown().filter(File::isFile).associate {
                it.relativeTo(fixture.root).path to
                    it.readBytes().toList()
            },
        ).containsExactlyEntriesIn(before)
        assertThat(fixture.provider.currentKey()).isNull()
    }

    private fun assertRetained(
        fixture: Fixture,
        installExists: Boolean,
    ) {
        assertThat(File(fixture.transactionDirectory, "old.envelope").readBytes()).isEqualTo(fixture.old)
        assertThat(File(fixture.transactionDirectory, "new.envelope").readBytes()).isEqualTo(fixture.proposed)
        val install = File(fixture.transactionDirectory, "install.envelope")
        assertThat(install.exists()).isEqualTo(installExists)
        if (installExists) assertThat(install.readBytes()).isEqualTo(fixture.proposed)
    }

    private fun assertPreserved(
        fixture: Fixture,
        activeBytes: ByteArray = fixture.old,
        stagedBytes: ByteArray = fixture.proposed,
    ) {
        assertThat(ciphertextFiles(fixture.root)).containsExactlyEntriesIn(fixture.ciphertext)
        assertThat(FileMasterKeyStorage.envelopeFileIn(fixture.root).readBytes()).isEqualTo(activeBytes)
        assertThat(fixture.staged("old.envelope").readBytes()).isEqualTo(fixture.old)
        assertThat(fixture.staged("new.envelope").readBytes()).isEqualTo(stagedBytes)
        assertThat(fixture.store.mutations).containsExactlyElementsIn(fixture.mutations).inOrder()
        assertThat(fixture.provider.currentKey()).isNull()
        checkNotNull(fixture.gate.acquireRecovery()).close()
    }

    private suspend fun assertBothNormalFactorsRead(fixture: Fixture) {
        for (factor in VaultKeyProvider.Factor.entries) {
            val prompts = mutableListOf<VaultKeyProvider.Factor>()
            assertThat(
                fixture.provider.unlockNoUi(factor) { requested, cipher ->
                    prompts += requested
                    AuthResult.Success(cipher)
                },
            ).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(prompts).containsExactly(factor)
            val held = checkNotNull(fixture.provider.currentKey())
            assertThat(held).isEqualTo(key)
            assertThat(fixture.gate.acquireRecovery()).isNull()
            // Read only a separate closed ciphertext copy; ordinary DB-open sidecars cannot obscure
            // preservation of the original activation fixture's DB/WAL/SHM bytes or their absence.
            val readRoot = root()
            for (name in listOf("vault.db", "vault.db-wal")) {
                File(fixture.root, name).takeIf(File::exists)?.copyTo(File(readRoot, name))
            }
            SkeinSQLiteDriver(held.copyOf()).open(File(readRoot, "vault.db").path).use { connection ->
                connection.prepare("SELECT id FROM messages").use { query ->
                    assertThat(query.step()).isTrue()
                    assertThat(query.getText(0)).isEqualTo("disposable messages")
                    assertThat(query.step()).isFalse()
                }
            }
            fixture.provider.lock()
            assertThat(held).isEqualTo(ByteArray(32))
            assertThat(fixture.provider.currentKey()).isNull()
        }
        assertThat(ciphertextFiles(fixture.root)).containsExactlyEntriesIn(fixture.ciphertext)
    }

    private suspend fun fixture(): Fixture {
        val root = root()
        val gate = VaultRecoveryExclusion.forDirectory(root)
        val store = SyntheticKeystore()
        val provider =
            VaultKeyProviderImpl(
                store,
                NoPlatformAuthentication,
                FileMasterKeyStorage(FileMasterKeyStorage.envelopeFileIn(root)),
                recoveryExclusion = gate,
            )
        assertThat(provider.setupNoUi(key.copyOf())).isInstanceOf(SetupResult.Success::class.java)
        provider.lock()
        checkNotNull(gate.admit()).use {
            SkeinSQLiteDriver(key.copyOf()).open(File(root, "vault.db").path).use { raw ->
                val connection = raw as SkeinSQLiteConnection
                connection.exec("PRAGMA user_version = 11")
                for (name in listOf("documents", "messages", "personas", "attachment_keys")) {
                    connection.exec("CREATE TABLE $name(id TEXT)")
                    connection.exec("INSERT INTO $name VALUES('disposable $name')")
                }
            }
        }
        val old = FileMasterKeyStorage.envelopeFileIn(root).readBytes()
        val before = ciphertextFiles(root)
        val id = UUID.randomUUID()
        val preparationInput = key.copyOf()
        val prompts = mutableListOf<VaultKeyProvider.Factor>()
        assertThat(
            AuthenticatedRecoveryPreparation(
                gate,
                store,
                newId = { id },
            ).prepareWith(preparationInput) { factor, cipher ->
                prompts += factor
                AuthResult.Success(cipher)
            },
        ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Prepared(id))
        assertThat(preparationInput).isEqualTo(ByteArray(32))
        assertThat(prompts)
            .containsExactly(
                VaultKeyProvider.Factor.BIOMETRIC,
                VaultKeyProvider.Factor.BIOMETRIC,
                VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
                VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
            ).inOrder()
        assertThat(ciphertextFiles(root)).containsExactlyEntriesIn(before)
        assertThat(FileMasterKeyStorage.envelopeFileIn(root).readBytes()).isEqualTo(old)
        val proposed = File(root, "keys/recovery-prepared/$id/new.envelope").readBytes()
        store.decryptAliases.clear()
        return Fixture(root, gate, store, provider, id, old, proposed, before, store.mutations.toList()).also {
            fixtures +=
                it
        }
    }

    private fun root(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Files.createTempDirectory(cache.toPath(), "recovery-activation-test-").toFile().canonicalFile.also {
            directories +=
                it
        }
    }

    private fun ciphertextFiles(root: File) =
        listOf("vault.db", "vault.db-wal", "vault.db-shm").associateWith { name ->
            File(root, name).takeIf(File::exists)?.readBytes()?.toList()
        }

    private data class Fixture(
        val root: File,
        val gate: VaultRecoveryExclusion,
        val store: SyntheticKeystore,
        val provider: VaultKeyProviderImpl,
        val id: UUID,
        val old: ByteArray,
        val proposed: ByteArray,
        val ciphertext: Map<String, List<Byte>?>,
        val mutations: List<String>,
    ) {
        val record get() = RecoveryEnvelopeV2.decode(proposed)
        val transactionDirectory get() = File(root, "keys/recovery-envelopes/$id")

        fun staged(name: String) = File(root, "keys/recovery-prepared/$id/$name")

        fun activation(
            transaction: (
                ClosedVaultRecoveryLease,
            ) -> RecoveryEnvelopeTransaction = { RecoveryEnvelopeTransaction(it) },
        ) = AuthenticatedRecoveryActivation(gate, store, transaction = transaction)
    }

    private class SyntheticKeystore : KeystoreFacade {
        private val keys = mutableMapOf<String, SecretKey>()
        val mutations = mutableListOf<String>()
        val decryptAliases = mutableListOf<String>()

        override fun hasStrongBox() = false

        override fun createKey(
            alias: String,
            factor: VaultKeyProvider.Factor,
            requireStrongBox: Boolean,
        ) {
            check(!requireStrongBox)
            check(alias !in keys)
            keys[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            mutations += "create:$alias:$factor"
        }

        override fun deleteEntry(alias: String) {
            mutations += "delete:$alias"
            keys.remove(alias)
        }

        override fun containsAlias(alias: String) = alias in keys

        override fun encryptCipher(alias: String): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.getValue(alias)) }

        override fun decryptCipher(
            alias: String,
            iv: ByteArray,
        ): Cipher {
            decryptAliases += alias
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keys.getValue(alias), GCMParameterSpec(128, iv))
            }
        }
    }

    private object NoPlatformAuthentication : BiometricAuthenticator {
        override fun canAuthenticate(factor: VaultKeyProvider.Factor) = CanAuthenticate.Yes

        override suspend fun authenticate(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
            cipher: Cipher,
        ): AuthResult = error("only explicit synthetic test callbacks are permitted")
    }
}
