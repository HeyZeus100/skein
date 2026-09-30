package app.skein.core.vault.key.recovery

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.AuthResult
import app.skein.core.vault.key.BiometricAuthenticator
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.KeystoreFacade
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher

/**
 * Preparation only: never changes the active envelope, original aliases, DB, WAL or session.
 * A staged envelope is NOT an activation capability. Restart/integration must repeat proof and
 * authentication; v2 reader/provider integration and encrypted/physical acceptance remain separate.
 */
internal class AuthenticatedRecoveryPreparation(
    private val exclusion: VaultRecoveryExclusion,
    private val keystore: KeystoreFacade,
    private val verifier: ExistingVaultKeyProof = ExistingVaultKeyProof(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> UUID = UUID::randomUUID,
    private val syncDirectory: (
        File,
    ) -> Unit = { FileChannel.open(it.toPath(), READ).use { channel -> channel.force(true) } },
    private val boundary: (Boundary) -> Unit = {},
) {
    enum class Boundary { OLD_SYNCED, NEW_SYNCED, DIRECTORY_SYNCED }

    sealed interface Result {
        /** Identifies preserved evidence only. There is deliberately no commit/apply method. */
        data class Prepared(
            val transactionId: UUID,
        ) : Result

        data class ProofRejected(
            val result: ExistingVaultKeyProofResult,
        ) : Result

        data object Unavailable : Result

        data object UserCancelled : Result
    }

    suspend fun prepare(
        activity: FragmentActivity,
        promptForFactor: (VaultKeyProvider.Factor) -> BiometricPrompt.PromptInfo,
        biometric: BiometricAuthenticator,
        candidateKey: ByteArray,
    ): Result =
        prepareWith(candidateKey) { factor, cipher ->
            biometric.authenticate(activity, promptForFactor(factor), factor, cipher)
        }

    /** Consumes caller input immediately; prompts never retain an alias to the candidate buffer. */
    internal suspend fun prepareWith(
        candidateKey: ByteArray,
        authenticate: suspend (VaultKeyProvider.Factor, Cipher) -> AuthResult,
    ): Result {
        val candidate = candidateKey.copyOf()
        candidateKey.fill(0)
        try {
            val lease = exclusion.acquireRecovery() ?: return Result.Unavailable
            lease.use {
                ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
                    val proof = verifier.verify(snapshot, candidate.copyOf())
                    if (proof != ExistingVaultKeyProofResult.VERIFIED) return Result.ProofRejected(proof)
                    val active = FileMasterKeyStorage.envelopeFileIn(lease.vaultDirectory)
                    val old = readEnvelope(active)
                    val row = FileMasterKeyStorage.decode(old)
                    snapshot.assertSourceUnchanged()
                    if (row.keyVersion == Int.MAX_VALUE) return Result.Unavailable
                    val id = newId()
                    val empty = RecoveryEnvelopeV2.Wrap(ByteArray(48), ByteArray(12))
                    val header = RecoveryEnvelopeV2(row.keyVersion + 1, clock(), row.strongBoxBacked, id, empty, empty)
                    val aliases = listOf(header.alias(true), header.alias(false))
                    // A collision is never permission to recreate/delete an existing alias or evidence.
                    if (aliases.any(keystore::containsAlias) ||
                        stageDirectory(lease, id).exists()
                    ) {
                        return Result.Unavailable
                    }
                    snapshot.assertSourceUnchanged()
                    val wraps = mutableListOf<RecoveryEnvelopeV2.Wrap>()
                    for (isBiometric in listOf(true, false)) {
                        currentCoroutineContext().ensureActive()
                        snapshot.assertSourceUnchanged()
                        val factor =
                            if (isBiometric) {
                                VaultKeyProvider.Factor.BIOMETRIC
                            } else {
                                VaultKeyProvider.Factor.DEVICE_CREDENTIAL
                            }
                        val alias = header.alias(isBiometric)
                        // Match the existing hardware policy; no automatic downgrade on failure.
                        keystore.createKey(alias, factor, row.strongBoxBacked)
                        val encrypt = keystore.encryptCipher(alias)
                        authenticateExact(encrypt, factor, snapshot, authenticate)
                        encrypt.updateAAD(header.authenticationData(isBiometric))
                        val wrap = RecoveryEnvelopeV2.Wrap(encrypt.doFinal(candidate), encrypt.iv.copyOf())
                        val decrypt = keystore.decryptCipher(alias, wrap.iv.copyOf())
                        authenticateExact(decrypt, factor, snapshot, authenticate)
                        decrypt.updateAAD(header.authenticationData(isBiometric))
                        val readback = decrypt.doFinal(wrap.ciphertext)
                        try {
                            if (!MessageDigest.isEqual(candidate, readback)) return Result.Unavailable
                        } finally {
                            readback.fill(0)
                        }
                        wraps += wrap
                    }
                    val proposed = header.copy(biometric = wraps[0], credential = wraps[1]).encode()
                    currentCoroutineContext().ensureActive()
                    snapshot.assertSourceUnchanged()
                    stage(lease, snapshot, id, old, proposed)
                    currentCoroutineContext().ensureActive()
                    return Result.Prepared(id)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthenticationCancelled) {
            return Result.UserCancelled
        } catch (_: Exception) {
            return Result.Unavailable
        } finally {
            candidate.fill(0)
        }
    }

    private suspend fun authenticateExact(
        cipher: Cipher,
        factor: VaultKeyProvider.Factor,
        snapshot: ClosedVaultRecoverySnapshot,
        authenticate: suspend (VaultKeyProvider.Factor, Cipher) -> AuthResult,
    ) {
        snapshot.assertSourceUnchanged()
        val result = authenticate(factor, cipher)
        currentCoroutineContext().ensureActive()
        snapshot.assertSourceUnchanged()
        when (result) {
            AuthResult.UserCancelled -> throw AuthenticationCancelled()
            is AuthResult.Success -> if (result.cipher !== cipher) throw RecoverySnapshotRefused()
            is AuthResult.Error -> throw RecoverySnapshotRefused()
        }
    }

    private fun stage(
        lease: ClosedVaultRecoveryLease,
        snapshot: ClosedVaultRecoverySnapshot,
        id: UUID,
        old: ByteArray,
        proposed: ByteArray,
    ) {
        val directory = stageDirectory(lease, id)
        val parent = checkNotNull(directory.parentFile)
        if (!parent.isDirectory && !parent.mkdir()) throw IOException("recovery staging unavailable")
        if (!directory.mkdir()) throw IOException("recovery staging unavailable")
        writeNew(File(directory, "old.envelope"), old)
        boundary(Boundary.OLD_SYNCED)
        snapshot.assertSourceUnchanged()
        writeNew(File(directory, "new.envelope"), proposed)
        boundary(Boundary.NEW_SYNCED)
        if (!readEnvelope(File(directory, "old.envelope")).contentEquals(old) ||
            !readEnvelope(File(directory, "new.envelope")).contentEquals(proposed)
        ) {
            throw IOException("recovery readback unavailable")
        }
        syncDirectory(directory)
        syncDirectory(parent)
        syncDirectory(checkNotNull(parent.parentFile))
        boundary(Boundary.DIRECTORY_SYNCED)
        snapshot.assertSourceUnchanged()
        // Prepared is only an evidence receipt. Never hold the lock-revocation monitor across
        // full ciphertext hashing or filesystem IO; a concurrent lock invalidates this final check.
        lease.whileExclusiveAndClosed { Unit }
    }

    private fun stageDirectory(
        lease: ClosedVaultRecoveryLease,
        id: UUID,
    ): File =
        File(lease.vaultDirectory, "keys/recovery-prepared/$id").also {
            if (it.canonicalFile != it.absoluteFile) throw RecoverySnapshotRefused()
        }

    private fun writeNew(
        file: File,
        bytes: ByteArray,
    ) {
        Files.createFile(file.toPath())
        FileOutputStream(file).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    private fun readEnvelope(file: File): ByteArray {
        if (!file.isFile || file.canonicalFile != file.absoluteFile || file.length() > RecoveryEnvelopeV2.MAX_BYTES) {
            throw IOException("recovery envelope unavailable")
        }
        return file.inputStream().use { input ->
            val bytes = ByteArray(RecoveryEnvelopeV2.MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count == 0 || count > RecoveryEnvelopeV2.MAX_BYTES) throw IOException("recovery envelope unavailable")
            bytes.copyOf(count)
        }
    }

    private class AuthenticationCancelled : Exception()
}
