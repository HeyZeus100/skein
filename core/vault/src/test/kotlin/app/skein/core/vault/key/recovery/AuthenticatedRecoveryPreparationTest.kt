package app.skein.core.vault.key.recovery

import app.skein.core.vault.key.AuthResult
import app.skein.core.vault.key.FakeKeystoreFacade
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.MasterKeyRow
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

class AuthenticatedRecoveryPreparationTest {
    @get:Rule val temp = TempDirRule()

    private val key = ByteArray(32) { 7 }
    private val id = UUID.fromString("a3a51b4d-eeb2-4625-9569-7cab8448f216")
    private val keystore = FakeKeystoreFacade()

    private fun gate() = VaultRecoveryExclusion.forDirectory(temp.root)

    private fun active() = File(temp.root, "keys/key-envelope.v1")

    private fun retained(name: String) = File(temp.root, "keys/recovery-prepared/$id/$name")

    private fun fixture(): Map<File, ByteArray> {
        File(temp.root, "vault.db").writeBytes(ByteArray(80) { 3 })
        File(temp.root, "vault.db-wal").writeBytes(ByteArray(40) { 4 })
        File(temp.root, "vault.db-shm").writeBytes(ByteArray(16) { 5 })
        FileMasterKeyStorage(active()).writeInitial(
            MasterKeyRow(1, ByteArray(48), ByteArray(12), null, ByteArray(48), ByteArray(12), null, 100, true),
        )
        return listOf(
            active(),
            File(temp.root, "vault.db"),
            File(temp.root, "vault.db-wal"),
            File(temp.root, "vault.db-shm"),
        ).associateWith { it.readBytes() }
    }

    private fun preparation(
        result: Int = 0,
        boundary: (AuthenticatedRecoveryPreparation.Boundary) -> Unit = {},
    ) = AuthenticatedRecoveryPreparation(
        gate(),
        keystore,
        ExistingVaultKeyProof { _, bytes ->
            assertThat(bytes).isEqualTo(key)
            result
        },
        clock = { 200 },
        newId = { id },
        boundary = boundary,
    )

    @Test
    fun `authenticated preparation binds exact proved candidate and preserves all original bytes`() =
        runTest {
            val original = fixture()
            val input = key.copyOf()
            val prompts = mutableListOf<VaultKeyProvider.Factor>()
            val result =
                preparation().prepareWith(input) { factor, cipher ->
                    assertThat(input).isEqualTo(ByteArray(32))
                    prompts += factor
                    AuthResult.Success(cipher)
                }
            assertThat(result).isEqualTo(AuthenticatedRecoveryPreparation.Result.Prepared(id))
            assertThat(prompts)
                .containsExactly(
                    VaultKeyProvider.Factor.BIOMETRIC,
                    VaultKeyProvider.Factor.BIOMETRIC,
                    VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
                    VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
                ).inOrder()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
            assertThat(retained("old.envelope").readBytes()).isEqualTo(original[active()])
            val envelope = RecoveryEnvelopeV2.decode(retained("new.envelope").readBytes())
            for (biometric in listOf(true, false)) {
                val wrap = if (biometric) envelope.biometric else envelope.credential
                val readback =
                    keystore
                        .decryptCipher(envelope.alias(biometric), wrap.iv)
                        .apply {
                            updateAAD(envelope.authenticationData(biometric))
                        }.doFinal(wrap.ciphertext)
                assertThat(readback).isEqualTo(key)
                readback.fill(0)
            }
            assertThat(keystore.createCalls.map { it.third }).containsExactly(true, true)
            gate().acquireRecovery()!!.close()
        }

    @Test
    fun `another vault key rejection never creates aliases or staged envelope`() =
        runTest {
            val original = fixture()
            val input = key.copyOf()
            assertThat(preparation(result = 1).prepareWith(input) { _, _ -> error("must not prompt") })
                .isEqualTo(
                    AuthenticatedRecoveryPreparation.Result.ProofRejected(
                        ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT,
                    ),
                )
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(keystore.createCalls).isEmpty()
            assertThat(retained("old.envelope").exists()).isFalse()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        }

    @Test
    fun `caller mutation during prompts cannot substitute another candidate`() =
        runTest {
            fixture()
            val input = key.copyOf()
            assertThat(
                preparation().prepareWith(input) { _, cipher ->
                    input.fill(99)
                    AuthResult.Success(cipher)
                },
            ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Prepared(id))
            val envelope = RecoveryEnvelopeV2.decode(retained("new.envelope").readBytes())
            val decrypt = keystore.decryptCipher(envelope.alias(true), envelope.biometric.iv)
            decrypt.updateAAD(envelope.authenticationData(true))
            assertThat(decrypt.doFinal(envelope.biometric.ciphertext)).isEqualTo(key)
        }

    @Test
    fun `cancel at each authenticated wrap or readback preserves originals and stages nothing`() =
        runTest {
            val original = fixture()
            for (cancelAt in 1..4) {
                val uniqueId = UUID.randomUUID()
                val recovery =
                    AuthenticatedRecoveryPreparation(
                        gate(),
                        keystore,
                        ExistingVaultKeyProof {
                            _,
                            _,
                            ->
                            0
                        },
                        newId = { uniqueId },
                    )
                var count = 0
                val input = key.copyOf()
                assertThat(
                    recovery.prepareWith(input) { _, cipher ->
                        if (++count == cancelAt) AuthResult.UserCancelled else AuthResult.Success(cipher)
                    },
                ).isEqualTo(AuthenticatedRecoveryPreparation.Result.UserCancelled)
                assertThat(input).isEqualTo(ByteArray(32))
                assertThat(File(temp.root, "keys/recovery-prepared/$uniqueId").exists()).isFalse()
                original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
            }
        }

    @Test
    fun `substituted authenticated Cipher is refused even if it encrypts under the same alias`() =
        runTest {
            val original = fixture()
            assertThat(
                preparation().prepareWith(key.copyOf()) { _, _ ->
                    AuthResult.Success(keystore.encryptCipher(RecoveryEnvelopeV2.alias(id, true)))
                },
            ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(retained("new.envelope").exists()).isFalse()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        }

    @Test
    fun `wrong readback key fails before staging`() =
        runTest {
            val original = fixture()
            var prompts = 0
            assertThat(
                preparation().prepareWith(key.copyOf()) { factor, cipher ->
                    if (++prompts == 1) keystore.createKey(RecoveryEnvelopeV2.alias(id, true), factor, true)
                    AuthResult.Success(cipher)
                },
            ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(retained("new.envelope").exists()).isFalse()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        }

    @Test
    fun `lock while prompt suspended revokes proof and holds exclusion until completion`() =
        runTest {
            val original = fixture()
            val finish = CompletableDeferred<Unit>()
            val pending =
                async {
                    preparation().prepareWith(key.copyOf()) { _, cipher ->
                        finish.await()
                        AuthResult.Success(cipher)
                    }
                }
            runCurrent()
            gate().invalidateRecovery()
            assertThat(gate().admit()).isNull()
            assertThat(gate().acquireRecovery()).isNull()
            finish.complete(Unit)
            assertThat(pending.await()).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(retained("new.envelope").exists()).isFalse()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
            gate().acquireRecovery()!!.close()
        }

    @Test
    fun `coroutine cancellation releases lease while preserving aliases and originals`() =
        runTest {
            val original = fixture()
            val pending =
                async {
                    preparation().prepareWith(key.copyOf()) { _, _ -> CompletableDeferred<AuthResult>().await() }
                }
            runCurrent()
            pending.cancel()
            pending.join()
            assertThat(keystore.containsAlias(RecoveryEnvelopeV2.alias(id, true))).isTrue()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
            gate().acquireRecovery()!!.close()
        }

    @Test
    fun `source changes during authentication invalidate proof without overwriting the changed source`() =
        runTest {
            fixture()
            val changed = "changed by external actor".toByteArray()
            assertThat(
                preparation().prepareWith(key.copyOf()) { _, cipher ->
                    active().writeBytes(changed)
                    AuthResult.Success(cipher)
                },
            ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(active().readBytes()).isEqualTo(changed)
            assertThat(retained("new.envelope").exists()).isFalse()
        }

    @Test
    fun `failure at each staging boundary preserves originals and retained partial evidence`() =
        runTest {
            val original = fixture()
            for (at in AuthenticatedRecoveryPreparation.Boundary.entries) {
                val unique = UUID.randomUUID()
                val recovery =
                    AuthenticatedRecoveryPreparation(
                        gate(),
                        keystore,
                        ExistingVaultKeyProof { _, _ -> 0 },
                        newId = { unique },
                        boundary = { if (it == at) throw IOException("injected") },
                    )
                assertThat(recovery.prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
                    .isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
                original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
                assertThat(
                    File(temp.root, "keys/recovery-prepared/$unique/old.envelope").readBytes(),
                ).isEqualTo(original[active()])
            }
        }

    @Test
    fun `staged readback mismatch is retained and never reported prepared`() =
        runTest {
            val original = fixture()
            assertThat(
                preparation {
                    if (it ==
                        AuthenticatedRecoveryPreparation.Boundary.NEW_SYNCED
                    ) {
                        retained("new.envelope").writeText("corrupted")
                    }
                }.prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) },
            ).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(retained("new.envelope").readText()).isEqualTo("corrupted")
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        }

    @Test
    fun `retry cannot overwrite transaction aliases or staged evidence`() =
        runTest {
            fixture()
            val recovery = preparation()
            assertThat(recovery.prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) })
                .isEqualTo(AuthenticatedRecoveryPreparation.Result.Prepared(id))
            val bytes = retained("new.envelope").readBytes()
            assertThat(recovery.prepareWith(key.copyOf()) { _, _ -> error("must not prompt") })
                .isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(retained("new.envelope").readBytes()).isEqualTo(bytes)
            assertThat(keystore.createCalls).hasSize(2)
        }

    @Test
    fun `metadata or factor tampering fails GCM authentication despite recomputed checksum`() =
        runTest {
            fixture()
            preparation().prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) }
            val envelope = RecoveryEnvelopeV2.decode(retained("new.envelope").readBytes())
            for (altered in listOf(
                envelope.copy(generation = 3),
                envelope.copy(createdAt = 201),
                envelope.copy(strongBoxBacked = false),
            )) {
                val decoded = RecoveryEnvelopeV2.decode(altered.encode())
                val decrypt = keystore.decryptCipher(decoded.alias(true), decoded.biometric.iv)
                decrypt.updateAAD(decoded.authenticationData(true))
                assertThrows(AEADBadTagException::class.java) { decrypt.doFinal(decoded.biometric.ciphertext) }
            }
            val decrypt = keystore.decryptCipher(envelope.alias(true), envelope.biometric.iv)
            decrypt.updateAAD(envelope.authenticationData(false))
            assertThrows(AEADBadTagException::class.java) { decrypt.doFinal(envelope.biometric.ciphertext) }
        }

    @Test
    fun `poisoned closure refuses before proof or alias creation`() =
        runTest {
            val original = fixture()
            gate().poisonRecovery()
            val recovery =
                AuthenticatedRecoveryPreparation(
                    gate(),
                    keystore,
                    ExistingVaultKeyProof {
                        _,
                        _,
                        ->
                        error("must not prove")
                    },
                )
            val input = key.copyOf()
            assertThat(recovery.prepareWith(input) { _, _ -> error("must not prompt") })
                .isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
            assertThat(input).isEqualTo(ByteArray(32))
            assertThat(keystore.createCalls).isEmpty()
            original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        }

    @Test
    fun `lock revocation completes while staging IO is blocked and invalidates prepared receipt`() =
        runTest {
            val original = fixture()
            val enteredSync = CountDownLatch(1)
            val finishSync = CountDownLatch(1)
            val revoked = CountDownLatch(1)
            val recovery =
                AuthenticatedRecoveryPreparation(
                    gate(),
                    keystore,
                    ExistingVaultKeyProof { _, _ -> 0 },
                    newId = { id },
                    syncDirectory = {
                        enteredSync.countDown()
                        check(finishSync.await(15, TimeUnit.SECONDS))
                    },
                )
            val pending =
                async(Dispatchers.Default) {
                    recovery.prepareWith(key.copyOf()) { _, cipher -> AuthResult.Success(cipher) }
                }
            try {
                assertThat(enteredSync.await(5, TimeUnit.SECONDS)).isTrue()
                val revoke =
                    Thread {
                        gate().invalidateRecovery()
                        revoked.countDown()
                    }
                revoke.start()
                try {
                    assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue()
                    assertThat(gate().admit()).isNull()
                } finally {
                    finishSync.countDown()
                    revoke.join(5000)
                }
                assertThat(pending.await()).isEqualTo(AuthenticatedRecoveryPreparation.Result.Unavailable)
                original.forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
                assertThat(retained("old.envelope").readBytes()).isEqualTo(original[active()])
                assertThat(retained("new.envelope").isFile).isTrue()
                gate().acquireRecovery()!!.close()
            } finally {
                finishSync.countDown()
            }
        }
}
