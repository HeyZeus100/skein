package app.skein.core.vault.key.recovery

import app.skein.core.vault.key.AuthResult
import app.skein.core.vault.key.FakeBiometricAuthenticator
import app.skein.core.vault.key.FakeKeystoreFacade
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.KeystoreFacade
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviderImpl
import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.spec.SecretKeySpec

/** Real host GCM and production admission; native proof and per-use authentication are synthetic. */
class AuthenticatedRecoveryActivationTest {
    @get:Rule val temp = TempDirRule()

    private val key = ByteArray(32) { (it + 11).toByte() }
    private val id = UUID.fromString("0c8f155b-3d5f-4dad-b9d9-328ce55ea3dc")
    private var keystore = RecordingKeystore()
    private lateinit var vault: File

    private fun gate() = VaultRecoveryExclusion.forDirectory(vault)

    private fun active() = FileMasterKeyStorage.envelopeFileIn(vault)

    private fun staged(name: String) = File(vault, "keys/recovery-prepared/$id/$name")

    private fun retained(name: String) = File(vault, "keys/recovery-envelopes/$id/$name")

    private fun provider() =
        VaultKeyProviderImpl(
            keystore,
            FakeBiometricAuthenticator(),
            FileMasterKeyStorage(active()),
            recoveryExclusion = gate(),
        )

    private data class Fixture(
        val files: Map<File, ByteArray>,
        val mutations: List<String>,
    )

    private suspend fun fixture(): Fixture {
        vault = Files.createTempDirectory(temp.root.toPath(), "vault-").toFile()
        keystore = RecordingKeystore()
        val setupProvider = provider()
        assertThat(setupProvider.setupNoUi(key)).isInstanceOf(SetupResult.Success::class.java)
        setupProvider.lock()
        File(vault, "vault.db").writeBytes(ByteArray(80) { 3 })
        File(vault, "vault.db-wal").writeBytes(ByteArray(40) { 4 })
        File(vault, "vault.db-shm").writeBytes(ByteArray(16) { 5 })
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
        keystore.recordReadbacks = true
        keystore.readbacks.clear()
        keystore.decryptAliases.clear()
        return Fixture(
            vault.walkTopDown().filter(File::isFile).associateWith(File::readBytes),
            keystore.mutations.toList(),
        )
    }

    private fun activation(
        proof: ExistingVaultKeyProof =
            ExistingVaultKeyProof {
                _,
                candidate,
                ->
                if (candidate.contentEquals(key)) 0 else 1
            },
        transaction: (
            ClosedVaultRecoveryLease,
        ) -> RecoveryEnvelopeTransaction = { RecoveryEnvelopeTransaction(it) },
    ) = AuthenticatedRecoveryActivation(gate(), keystore, proof, transaction)

    private fun assertPreserved(
        fixture: Fixture,
        changed: Map<File, ByteArray> = emptyMap(),
        checkAliases: Boolean = true,
    ) {
        fixture.files.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(changed[file] ?: bytes) }
        if (checkAliases) assertThat(keystore.mutations).containsExactlyElementsIn(fixture.mutations).inOrder()
        assertThat(provider().currentKey()).isNull()
    }

    private fun assertObserved(
        result: AuthenticatedRecoveryActivation.Result,
        state: RecoveryEnvelopeTransaction.State,
        synced: Boolean = false,
    ) {
        assertThat(result).isEqualTo(
            AuthenticatedRecoveryActivation.Result.Observed(RecoveryEnvelopeTransaction.Result(state, synced)),
        )
    }

    private fun alterNew(change: (RecoveryEnvelopeV2) -> RecoveryEnvelopeV2) {
        staged(
            "new.envelope",
        ).writeBytes(change(RecoveryEnvelopeV2.decode(staged("new.envelope").readBytes())).encode())
    }

    @Test
    fun `fresh proof and both exact authenticated readbacks activate metadata without unlocking`() =
        runTest {
            val fixture = fixture()
            val input = key.copyOf()
            var proofBytes: ByteArray? = null
            var proofCalls = 0
            val prompts = mutableListOf<VaultKeyProvider.Factor>()
            val originalProvider = provider()
            val recovery =
                activation(
                    ExistingVaultKeyProof { path, candidate ->
                        proofCalls++
                        proofBytes = candidate
                        assertThat(input).isEqualTo(ByteArray(32))
                        assertThat(candidate).isEqualTo(key)
                        assertThat(path).isNotEqualTo(File(vault, "vault.db").absolutePath)
                        assertThat(File(path).readBytes()).isEqualTo(File(vault, "vault.db").readBytes())
                        0
                    },
                )
            val result =
                recovery.activateWith(id, input) { factor, cipher ->
                    assertThat(input).isEqualTo(ByteArray(32))
                    prompts += factor
                    assertThat(originalProvider.currentKey()).isNull()
                    AuthResult.Success(cipher)
                }
            assertObserved(result, RecoveryEnvelopeTransaction.State.COMMITTED, synced = true)
            assertThat(proofCalls).isEqualTo(1)
            assertThat(proofBytes).isEqualTo(ByteArray(32))
            assertThat(prompts)
                .containsExactly(VaultKeyProvider.Factor.BIOMETRIC, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
                .inOrder()
            assertThat(keystore.readbacks).hasSize(2)
            keystore.readbacks.forEach { assertThat(it).isEqualTo(ByteArray(32)) }
            assertThat(originalProvider.currentKey()).isNull()
            val legacy = FileMasterKeyStorage.decode(fixture.files.getValue(active()))
            assertThat(
                keystore.delegate
                    .decryptCipher(
                        VaultKeyProviderImpl.ALIAS_BIOMETRIC,
                        legacy.wrapIvBiometric!!,
                    ).doFinal(legacy.wrappedBytesBiometric!!),
            ).isEqualTo(key)
            assertThat(
                keystore.delegate
                    .decryptCipher(
                        VaultKeyProviderImpl.ALIAS_CREDENTIAL,
                        legacy.wrapIvCredential!!,
                    ).doFinal(legacy.wrappedBytesCredential!!),
            ).isEqualTo(key)
            assertPreserved(fixture, mapOf(active() to fixture.files.getValue(staged("new.envelope"))))
            assertThat(retained("old.envelope").readBytes()).isEqualTo(fixture.files.getValue(active()))
            assertThat(retained("new.envelope").readBytes()).isEqualTo(staged("new.envelope").readBytes())
            for (factor in VaultKeyProvider.Factor.entries) {
                var normalAuth = 0
                assertThat(
                    originalProvider.unlockNoUi(factor) { requested, cipher ->
                        assertThat(requested).isEqualTo(factor)
                        normalAuth++
                        AuthResult.Success(cipher)
                    },
                ).isInstanceOf(UnlockResult.Success::class.java)
                assertThat(normalAuth).isEqualTo(1)
                assertThat(originalProvider.currentKey()).isEqualTo(key)
                originalProvider.lock()
            }
            gate().acquireRecovery()!!.close()
        }

    @Test
    fun `prepared UUID and wrong candidate cannot reuse preparation proof or authentication`() =
        runTest {
            val fixture = fixture()
            val input = ByteArray(32) { 99 }
            var proofInput: ByteArray? = null
            val result =
                activation(
                    ExistingVaultKeyProof { _, candidate ->
                        proofInput = candidate
                        assertThat(candidate).isEqualTo(ByteArray(32) { 99 })
                        1
                    },
                ).activateWith(id, input) { _, _ -> error("wrong candidate must not authenticate") }
            assertThat(result).isEqualTo(
                AuthenticatedRecoveryActivation.Result.ProofRejected(ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT),
            )
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(proofInput).isEqualTo(ByteArray(32))
            assertThat(retained("old.envelope").exists()).isFalse()
            assertThat(keystore.decryptAliases).isEmpty()
            assertPreserved(fixture)
        }

    @Test
    fun `invalid candidate length is consumed before proof or authentication`() =
        runTest {
            val fixture = fixture()
            for (size in listOf(0, 31, 33)) {
                val input = ByteArray(size) { 9 }
                assertThat(
                    activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                        .activateWith(id, input) { _, _ -> error("must not authenticate") },
                ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertThat(input).isEqualTo(ByteArray(size))
            }
            assertPreserved(fixture)
        }

    @Test
    fun `caller mutation during authentication cannot substitute the proved candidate`() =
        runTest {
            val fixture = fixture()
            val input = key.copyOf()
            assertObserved(
                activation().activateWith(id, input) { _, cipher ->
                    input.fill(99)
                    AuthResult.Success(cipher)
                },
                RecoveryEnvelopeTransaction.State.COMMITTED,
                synced = true,
            )
            assertPreserved(fixture, mapOf(active() to fixture.files.getValue(staged("new.envelope"))))
        }

    @Test
    fun `UUID generation StrongBox and legacy source guards reject before fresh proof`() =
        runTest {
            val changes: List<(RecoveryEnvelopeV2) -> RecoveryEnvelopeV2> =
                listOf(
                    { it.copy(transactionId = UUID.randomUUID()) },
                    { it.copy(generation = it.generation + 1) },
                    { it.copy(strongBoxBacked = !it.strongBoxBacked) },
                )
            for (change in changes) {
                val fixture = fixture()
                alterNew(change)
                val changed = staged("new.envelope").readBytes()
                assertThat(
                    activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                        .activateWith(id, key.copyOf()) { _, _ -> error("must not authenticate") },
                ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertPreserved(fixture, mapOf(staged("new.envelope") to changed))
            }
            val fixture = fixture()
            val v2 = staged("new.envelope").readBytes()
            staged("old.envelope").writeBytes(v2)
            active().writeBytes(v2)
            assertThat(
                activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                    .activateWith(id, key.copyOf()) { _, _ -> error("must not authenticate") },
            ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertPreserved(fixture, mapOf(active() to v2, staged("old.envelope") to v2))
        }

    @Test
    fun `old generation overflow cannot authorize a v2 replacement`() =
        runTest {
            val fixture = fixture()
            val row = FileMasterKeyStorage.decode(active().readBytes()).copy(keyVersion = Int.MAX_VALUE)
            val encoded = FileMasterKeyStorage.encode(row)
            active().writeBytes(encoded)
            staged("old.envelope").writeBytes(encoded)
            assertThat(
                activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                    .activateWith(id, key.copyOf()) { _, _ -> error("must not authenticate") },
            ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertPreserved(fixture, mapOf(active() to encoded, staged("old.envelope") to encoded))
        }

    @Test
    fun `recomputed checksum cannot bless header or either factor ciphertext tampering`() =
        runTest {
            val changes: List<(RecoveryEnvelopeV2) -> RecoveryEnvelopeV2> =
                listOf(
                    { it.copy(createdAt = it.createdAt + 1) },
                    {
                        it.copy(
                            biometric =
                                it.biometric.copy(
                                    ciphertext =
                                        it.biometric.ciphertext
                                            .copyOf()
                                            .apply { this[0]++ },
                                ),
                        )
                    },
                    {
                        it.copy(
                            credential =
                                it.credential.copy(
                                    ciphertext =
                                        it.credential.ciphertext
                                            .copyOf()
                                            .apply { this[0]++ },
                                ),
                        )
                    },
                    { it.copy(biometric = it.credential, credential = it.biometric) },
                )
            for (change in changes) {
                val fixture = fixture()
                alterNew(change)
                val changed = staged("new.envelope").readBytes()
                assertThat(activation().activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
                    .isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertThat(retained("old.envelope").exists()).isFalse()
                assertPreserved(fixture, mapOf(staged("new.envelope") to changed))
            }
        }

    @Test
    fun `valid wraps without factor AAD or with wrong candidate cannot authorize activation`() =
        runTest {
            for (factor in listOf(true, false)) {
                for (mode in listOf("no-aad", "opposite-aad", "wrong-key")) {
                    val fixture = fixture()
                    alterNew { record ->
                        val encrypt = keystore.delegate.encryptCipher(record.alias(factor))
                        if (mode !=
                            "no-aad"
                        ) {
                            encrypt.updateAAD(
                                record.authenticationData(
                                    if (mode ==
                                        "opposite-aad"
                                    ) {
                                        !factor
                                    } else {
                                        factor
                                    },
                                ),
                            )
                        }
                        val candidate = if (mode == "wrong-key") ByteArray(32) { 90 } else key
                        val wrap = RecoveryEnvelopeV2.Wrap(encrypt.doFinal(candidate), encrypt.iv)
                        if (factor) record.copy(biometric = wrap) else record.copy(credential = wrap)
                    }
                    val changed = staged("new.envelope").readBytes()
                    assertThat(activation().activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
                        .isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                    keystore.readbacks.forEach { assertThat(it).isEqualTo(ByteArray(32)) }
                    assertPreserved(fixture, mapOf(staged("new.envelope") to changed))
                }
            }
        }

    @Test
    fun `substituted Cipher is rejected for either factor even with identical alias and IV`() =
        runTest {
            for (at in 1..2) {
                val fixture = fixture()
                val record = RecoveryEnvelopeV2.decode(staged("new.envelope").readBytes())
                var count = 0
                assertThat(
                    activation().activateWith(id, key.copyOf()) { factor, cipher ->
                        if (++count == at) {
                            val bio = factor == VaultKeyProvider.Factor.BIOMETRIC
                            val wrap = if (bio) record.biometric else record.credential
                            AuthResult.Success(keystore.decryptCipher(record.alias(bio), wrap.iv))
                        } else {
                            AuthResult.Success(cipher)
                        }
                    },
                ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertPreserved(fixture)
            }
        }

    @Test
    fun `cancellation and authentication error at either fresh factor preserve every original`() =
        runTest {
            for (at in 1..2) {
                for (cancel in listOf(true, false)) {
                    val fixture = fixture()
                    var count = 0
                    assertThat(
                        activation().activateWith(id, key.copyOf()) { _, cipher ->
                            if (++count !=
                                at
                            ) {
                                AuthResult.Success(cipher)
                            } else if (cancel) {
                                AuthResult.UserCancelled
                            } else {
                                AuthResult.Error(7, "synthetic")
                            }
                        },
                    ).isEqualTo(
                        if (cancel) {
                            AuthenticatedRecoveryActivation.Result.UserCancelled
                        } else {
                            AuthenticatedRecoveryActivation.Result.Unavailable
                        },
                    )
                    assertThat(retained("old.envelope").exists()).isFalse()
                    assertPreserved(fixture)
                }
            }
        }

    @Test
    fun `missing or invalidated staged alias cannot fall back or regenerate material`() =
        runTest {
            for (bio in listOf(true, false)) {
                for (missing in listOf(true, false)) {
                    val fixture = fixture()
                    val alias = RecoveryEnvelopeV2.alias(id, bio)
                    if (missing) keystore.delegate.deleteEntry(alias) else keystore.delegate.invalidatedAliases += alias
                    assertThat(activation().activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
                        .isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                    assertPreserved(fixture)
                }
            }
        }

    @Test
    fun `source or staged mutation during either suspended factor cannot reach commit`() =
        runTest {
            for (at in 1..2) {
                for (relative in listOf(
                    "vault.db",
                    "vault.db-wal",
                    "vault.db-shm",
                    "keys/key-envelope.v1",
                    "keys/recovery-prepared/$id/old.envelope",
                    "keys/recovery-prepared/$id/new.envelope",
                )) {
                    val fixture = fixture()
                    val file = File(vault, relative)
                    val changed = file.readBytes() + 42.toByte()
                    var count = 0
                    assertThat(
                        activation().activateWith(id, key.copyOf()) { _, cipher ->
                            if (++count == at) file.writeBytes(changed)
                            AuthResult.Success(cipher)
                        },
                    ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                    assertThat(retained("old.envelope").exists()).isFalse()
                    assertPreserved(fixture, mapOf(file to changed))
                }
            }
        }

    @Test
    fun `proof callback cannot replace private ciphertext and still claim verification`() =
        runTest {
            val fixture = fixture()
            var copiedCandidate: ByteArray? = null
            assertThat(
                activation(
                    ExistingVaultKeyProof { path, candidate ->
                        copiedCandidate = candidate
                        File(path).appendBytes(byteArrayOf(4))
                        0
                    },
                ).activateWith(id, key.copyOf()) { _, _ -> error("must not authenticate") },
            ).isEqualTo(AuthenticatedRecoveryActivation.Result.ProofRejected(ExistingVaultKeyProofResult.UNAVAILABLE))
            assertThat(copiedCandidate).isEqualTo(ByteArray(32))
            assertPreserved(fixture)
        }

    @Test
    fun `oversized and symbolic linked evidence is refused without fresh proof`() =
        runTest {
            for (symlink in listOf(false, true)) {
                val fixture = fixture()
                val original = staged("new.envelope").readBytes()
                if (symlink) {
                    val target = File(vault, "outside-stage.envelope").apply { writeBytes(original) }
                    Files.delete(staged("new.envelope").toPath())
                    Files.createSymbolicLink(staged("new.envelope").toPath(), target.toPath())
                } else {
                    staged("new.envelope").writeBytes(ByteArray(32769))
                }
                assertThat(
                    activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                        .activateWith(id, key.copyOf()) { _, _ -> error("must not authenticate") },
                ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertPreserved(fixture, mapOf(staged("new.envelope") to staged("new.envelope").readBytes()))
            }
        }

    @Test
    fun `poison reset marker journal and admitted ordinary owner refuse before proof`() =
        runTest {
            for (condition in listOf("poison", "reset", "journal", "ordinary")) {
                val fixture = fixture()
                val admission = if (condition == "ordinary") gate().admit() else null
                if (condition == "poison") gate().poisonRecovery()
                if (condition == "reset") File(vault, ".vault_reset_in_progress").writeText("synthetic")
                if (condition == "journal") File(vault, "vault.db-journal").writeText("synthetic")
                try {
                    val input = key.copyOf()
                    assertThat(
                        activation(ExistingVaultKeyProof { _, _ -> error("must not prove") })
                            .activateWith(id, input) { _, _ -> error("must not authenticate") },
                    ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                    assertThat(input).isEqualTo(ByteArray(32))
                    assertThat(keystore.decryptAliases).isEmpty()
                    assertPreserved(fixture)
                } finally {
                    admission?.close()
                }
            }
        }

    @Test
    fun `production provider lock during either factor revokes late success and retains exclusion`() =
        runTest {
            for (at in 1..2) {
                val fixture = fixture()
                val finish = CompletableDeferred<Unit>()
                val other = provider()
                var count = 0
                val pending =
                    async {
                        activation().activateWith(id, key.copyOf()) { _, cipher ->
                            if (++count == at) finish.await()
                            AuthResult.Success(cipher)
                        }
                    }
                runCurrent()
                other.lock()
                assertThat(gate().admit()).isNull()
                assertThat(gate().acquireRecovery()).isNull()
                assertThat(gate().acquireReset()).isNull()
                finish.complete(Unit)
                assertThat(pending.await()).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                assertPreserved(fixture)
                gate().acquireRecovery()!!.close()
            }
        }

    @Test
    fun `distinct coordinator and provider cannot enter during fresh authentication`() =
        runTest {
            val fixture = fixture()
            val finish = CompletableDeferred<Unit>()
            val pending =
                async {
                    activation().activateWith(id, key.copyOf()) { _, cipher ->
                        finish.await()
                        AuthResult.Success(cipher)
                    }
                }
            runCurrent()
            val input = key.copyOf()
            assertThat(
                activation(ExistingVaultKeyProof { _, _ -> error("second coordinator must not prove") })
                    .activateWith(id, input) { _, _ -> error("must not authenticate") },
            ).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(
                provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC) { _, _ -> error("ordinary prompt excluded") },
            ).isInstanceOf(UnlockResult.Failed::class.java)
            assertThat(gate().acquireReset()).isNull()
            finish.complete(Unit)
            assertObserved(pending.await(), RecoveryEnvelopeTransaction.State.COMMITTED, synced = true)
            assertPreserved(fixture, mapOf(active() to fixture.files.getValue(staged("new.envelope"))))
        }

    @Test
    fun `coroutine cancellation during auth preserves evidence and releases closed lease`() =
        runTest {
            val fixture = fixture()
            val input = key.copyOf()
            val pending =
                async {
                    activation().activateWith(id, input) { _, _ -> CompletableDeferred<AuthResult>().await() }
                }
            runCurrent()
            pending.cancel()
            pending.join()
            assertThat(input).isEqualTo(ByteArray(32))
            assertPreserved(fixture)
            gate().acquireRecovery()!!.close()
        }

    @Test
    fun `final transaction validation rejects changed source and staged bytes after durable writes`() =
        runTest {
            for (relative in listOf(
                "vault.db",
                "vault.db-wal",
                "vault.db-shm",
                "keys/recovery-prepared/$id/old.envelope",
                "keys/recovery-prepared/$id/new.envelope",
            )) {
                val fixture = fixture()
                val changedFile = File(vault, relative)
                val changed = changedFile.readBytes() + 43.toByte()
                val recovery =
                    activation(transaction = { lease ->
                        RecoveryEnvelopeTransaction(lease, boundary = {
                            if (it ==
                                RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME
                            ) {
                                changedFile.writeBytes(changed)
                            }
                        })
                    })
                assertObserved(
                    recovery.activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
                    RecoveryEnvelopeTransaction.State.NOT_COMMITTED,
                )
                assertPreserved(fixture, mapOf(changedFile to changed))
                assertThat(retained("old.envelope").readBytes()).isEqualTo(fixture.files.getValue(active()))
            }
        }

    @Test
    fun `revocation does not wait for blocked transaction IO and prevents rename`() =
        runTest {
            val fixture = fixture()
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val revoked = CountDownLatch(1)
            val recovery =
                activation(transaction = { lease ->
                    RecoveryEnvelopeTransaction(lease, syncDirectory = {
                        entered.countDown()
                        check(finish.await(15, TimeUnit.SECONDS))
                    })
                })
            val pending =
                async(
                    Dispatchers.Default,
                ) { recovery.activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) } }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                val other = provider()
                val thread =
                    Thread {
                        other.lock()
                        revoked.countDown()
                    }
                thread.start()
                try {
                    assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue()
                    assertThat(gate().admit()).isNull()
                } finally {
                    finish.countDown()
                    thread.join(5000)
                }
                assertObserved(pending.await(), RecoveryEnvelopeTransaction.State.UNKNOWN)
                assertPreserved(fixture)
                gate().acquireRecovery()!!.close()
            } finally {
                finish.countDown()
            }
        }

    @Test
    fun `postrename IO or cancellation reports committed uncertain durability without unlocking`() =
        runTest {
            for (cancel in listOf(false, true)) {
                val fixture = fixture()
                val recovery =
                    activation(transaction = { lease ->
                        RecoveryEnvelopeTransaction(lease, boundary = {
                            if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) {
                                if (cancel) {
                                    throw CancellationException(
                                        "injected after rename",
                                    )
                                } else {
                                    throw IOException("injected after rename")
                                }
                            }
                        })
                    })
                assertObserved(
                    recovery.activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
                    RecoveryEnvelopeTransaction.State.COMMITTED,
                )
                assertPreserved(fixture, mapOf(active() to fixture.files.getValue(staged("new.envelope"))))
                assertThat(retained("old.envelope").readBytes()).isEqualTo(fixture.files.getValue(active()))
            }
        }

    @Test
    fun `postrename lock reports unknown instead of generic cancellation and never rolls back`() =
        runTest {
            val fixture = fixture()
            val other = provider()
            val recovery =
                activation(transaction = { lease ->
                    RecoveryEnvelopeTransaction(lease, boundary = {
                        if (it == RecoveryEnvelopeTransaction.Boundary.AFTER_RENAME) {
                            other.lock()
                            throw CancellationException("late lock")
                        }
                    })
                })
            assertObserved(
                recovery.activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
                RecoveryEnvelopeTransaction.State.UNKNOWN,
            )
            assertPreserved(fixture, mapOf(active() to fixture.files.getValue(staged("new.envelope"))))
            assertThat(other.currentKey()).isNull()
            gate().acquireRecovery()!!.use { lease ->
                assertThat(
                    RecoveryEnvelopeTransaction(lease).reconcile(id).state,
                ).isEqualTo(RecoveryEnvelopeTransaction.State.COMMITTED)
            }
            assertThat(other.currentKey()).isNull()
        }

    @Test
    fun `failed transaction attempt is retained and cannot be reused by fresh proof`() =
        runTest {
            val fixture = fixture()
            val recovery =
                activation(transaction = { lease ->
                    RecoveryEnvelopeTransaction(lease, boundary = {
                        if (it == RecoveryEnvelopeTransaction.Boundary.OLD_SYNCED) throw IOException("injected")
                    })
                })
            assertObserved(
                recovery.activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
                RecoveryEnvelopeTransaction.State.UNKNOWN,
            )
            val retainedOld = retained("old.envelope").readBytes()
            assertObserved(
                activation().activateWith(id, key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
                RecoveryEnvelopeTransaction.State.UNKNOWN,
            )
            assertThat(retained("old.envelope").readBytes()).isEqualTo(retainedOld)
            assertThat(retained("new.envelope").exists()).isFalse()
            assertPreserved(fixture)
        }

    @Test
    fun `missing earlier factor alias during later auth or final validation cannot commit`() =
        runTest {
            for (duringCommit in listOf(false, true)) {
                val fixture = fixture()
                val remove = { keystore.delegate.deleteEntry(RecoveryEnvelopeV2.alias(id, true)) }
                val recovery =
                    activation(transaction = { lease ->
                        RecoveryEnvelopeTransaction(lease, boundary = {
                            if (duringCommit && it == RecoveryEnvelopeTransaction.Boundary.BEFORE_RENAME) remove()
                        })
                    })
                val result =
                    recovery.activateWith(id, key.copyOf()) { factor, cipher ->
                        if (!duringCommit && factor == VaultKeyProvider.Factor.DEVICE_CREDENTIAL) remove()
                        AuthResult.Success(cipher)
                    }
                if (duringCommit) {
                    assertObserved(result, RecoveryEnvelopeTransaction.State.NOT_COMMITTED)
                } else {
                    assertThat(result).isEqualTo(AuthenticatedRecoveryActivation.Result.Unavailable)
                }
                assertThat(keystore.containsAlias(RecoveryEnvelopeV2.alias(id, true))).isFalse()
                assertPreserved(fixture)
            }
        }

    private class RecordingKeystore : KeystoreFacade {
        val delegate = FakeKeystoreFacade()
        val mutations = mutableListOf<String>()
        val decryptAliases = mutableListOf<String>()
        val readbacks = mutableListOf<ByteArray>()
        var recordReadbacks = false

        override fun hasStrongBox() = delegate.hasStrongBox()

        override fun createKey(
            alias: String,
            factor: VaultKeyProvider.Factor,
            requireStrongBox: Boolean,
        ) {
            mutations += "create:$alias:$factor:$requireStrongBox"
            delegate.createKey(alias, factor, requireStrongBox)
        }

        override fun deleteEntry(alias: String) {
            mutations += "delete:$alias"
            delegate.deleteEntry(alias)
        }

        override fun containsAlias(alias: String) = delegate.containsAlias(alias)

        override fun encryptCipher(alias: String) = delegate.encryptCipher(alias)

        override fun decryptCipher(
            alias: String,
            iv: ByteArray,
        ): Cipher {
            decryptAliases += alias
            val cipher = delegate.decryptCipher(alias, iv)
            return if (recordReadbacks) RecordingCipher(cipher, readbacks) else cipher
        }
    }

    /** Observes the exact returned plaintext buffer so finally-zeroing is asserted, not inferred. */
    private class RecordingCipher(
        delegate: Cipher,
        readbacks: MutableList<ByteArray>,
    ) : Cipher(RecordingSpi(delegate, readbacks), TestProvider, "AES/GCM/NoPadding") {
        init {
            init(DECRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"))
        }
    }

    private object TestProvider : Provider("recovery-test", "1.0", "host plaintext buffer observation")

    private class RecordingSpi(
        private val delegate: Cipher,
        private val readbacks: MutableList<ByteArray>,
    ) : CipherSpi() {
        override fun engineSetMode(mode: String) = Unit

        override fun engineSetPadding(padding: String) = Unit

        override fun engineGetBlockSize() = delegate.blockSize

        override fun engineGetOutputSize(inputLen: Int) = delegate.getOutputSize(inputLen)

        override fun engineGetIV() = delegate.iv

        override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

        override fun engineInit(
            opmode: Int,
            key: Key,
            random: SecureRandom?,
        ) = Unit

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) = Unit

        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameters?,
            random: SecureRandom?,
        ) = Unit

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray = delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ) = delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineUpdateAAD(
            src: ByteArray,
            offset: Int,
            len: Int,
        ) = delegate.updateAAD(src, offset, len)

        override fun engineDoFinal(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
        ): ByteArray = delegate.doFinal(input, inputOffset, inputLen).also { readbacks += it }

        override fun engineDoFinal(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ) = delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)
    }
}
