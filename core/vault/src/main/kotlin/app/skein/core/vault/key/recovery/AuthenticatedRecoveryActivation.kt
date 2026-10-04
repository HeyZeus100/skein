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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher

/**
 * Internal envelope activation only, with no provider/app entry point or session authority.
 * The UUID selects retained evidence, never permission: every invocation consumes a fresh candidate,
 * proves the current closed ciphertext and authenticates both exact staged decrypt operations.
 * No aliases are created/deleted. Later external key invalidation is a normal-unlock concern;
 * this facade cannot attest a hardware alias generation after an authenticated readback.
 */
internal class AuthenticatedRecoveryActivation(
    private val exclusion: VaultRecoveryExclusion,
    private val keystore: KeystoreFacade,
    private val verifier: ExistingVaultKeyProof = ExistingVaultKeyProof(),
    private val transaction: (ClosedVaultRecoveryLease) -> RecoveryEnvelopeTransaction = { RecoveryEnvelopeTransaction(it) },
) {
    sealed interface Result {
        /** File observation only, including uncertain durability; never a successful vault unlock. */
        data class Observed(
            val replacement: RecoveryEnvelopeTransaction.Result,
        ) : Result

        data class ProofRejected(
            val result: ExistingVaultKeyProofResult,
        ) : Result

        data object Unavailable : Result

        data object UserCancelled : Result
    }

    suspend fun activate(
        activity: FragmentActivity,
        promptForFactor: (VaultKeyProvider.Factor) -> BiometricPrompt.PromptInfo,
        biometric: BiometricAuthenticator,
        transactionId: UUID,
        candidateKey: ByteArray,
    ): Result =
        activateWith(transactionId, candidateKey) { factor, cipher ->
            biometric.authenticate(activity, promptForFactor(factor), factor, cipher)
        }

    /** Caller input is consumed before any admission, IO or suspension. */
    internal suspend fun activateWith(
        transactionId: UUID,
        candidateKey: ByteArray,
        authenticate: suspend (VaultKeyProvider.Factor, Cipher) -> AuthResult,
    ): Result {
        val candidate = candidateKey.copyOf()
        candidateKey.fill(0)
        var transactionEntered = false
        var observed: Result.Observed? = null
        try {
            if (candidate.size != 32) return Result.Unavailable
            currentCoroutineContext().ensureActive()
            val lease = exclusion.acquireRecovery() ?: return Result.Unavailable
            lease.use {
                val evidence = Evidence.read(lease, transactionId, keystore)
                ClosedVaultRecoverySnapshot.capture(lease).use { snapshot ->
                    evidence.assertUnchanged(lease, keystore)
                    val proof = verifier.verify(snapshot, candidate.copyOf())
                    if (proof != ExistingVaultKeyProofResult.VERIFIED) return Result.ProofRejected(proof)
                    for (isBiometric in listOf(true, false)) {
                        currentCoroutineContext().ensureActive()
                        snapshot.assertSourceUnchanged()
                        evidence.assertUnchanged(lease, keystore)
                        val factor =
                            if (isBiometric) {
                                VaultKeyProvider.Factor.BIOMETRIC
                            } else {
                                VaultKeyProvider.Factor.DEVICE_CREDENTIAL
                            }
                        val record = evidence.record
                        val wrap = if (isBiometric) record.biometric else record.credential
                        val cipher = keystore.decryptCipher(record.alias(isBiometric), wrap.iv.copyOf())
                        val authenticated = authenticate(factor, cipher)
                        currentCoroutineContext().ensureActive()
                        snapshot.assertSourceUnchanged()
                        evidence.assertUnchanged(lease, keystore)
                        when (authenticated) {
                            AuthResult.UserCancelled -> return Result.UserCancelled
                            is AuthResult.Error -> return Result.Unavailable
                            is AuthResult.Success -> if (authenticated.cipher !== cipher) return Result.Unavailable
                        }
                        cipher.updateAAD(record.authenticationData(isBiometric))
                        val readback = cipher.doFinal(wrap.ciphertext.copyOf())
                        try {
                            if (!MessageDigest.isEqual(candidate, readback)) return Result.Unavailable
                        } finally {
                            readback.fill(0)
                        }
                    }
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    snapshot.assertSourceUnchanged()
                    evidence.assertUnchanged(lease, keystore)
                    val replacement = transaction(lease)
                    // From this point, any unexpected escaping failure has an UNKNOWN file outcome.
                    // No cancellation check after replace may disguise an already-performed rename.
                    transactionEntered = true
                    observed =
                        Result.Observed(
                            replacement.replace(
                                evidence.old,
                                evidence.record,
                                checkCancellation = { context.ensureActive() },
                                validateBeforeCommit = {
                                    context.ensureActive()
                                    snapshot.assertSourceUnchanged()
                                    evidence.assertUnchanged(lease, keystore)
                                    context.ensureActive()
                                },
                            ),
                        )
                    return checkNotNull(observed)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!transactionEntered) throw cancelled
            return observed ?: unknown()
        } catch (_: Exception) {
            return observed ?: if (transactionEntered) unknown() else Result.Unavailable
        } finally {
            candidate.fill(0)
        }
    }

    private fun unknown() = Result.Observed(RecoveryEnvelopeTransaction.Result(RecoveryEnvelopeTransaction.State.UNKNOWN))

    /** Private decoded bytes are never shared with the caller, authenticator or a Prepared receipt. */
    private class Evidence(
        private val directory: File,
        val old: ByteArray,
        private val proposed: ByteArray,
        val record: RecoveryEnvelopeV2,
    ) {
        fun assertUnchanged(lease: ClosedVaultRecoveryLease, keystore: KeystoreFacade) {
            lease.assertExclusiveAndClosed()
            requireDirectory(directory)
            if (!readEnvelope(File(directory, "old.envelope")).contentEquals(old) ||
                !readEnvelope(File(directory, "new.envelope")).contentEquals(proposed) ||
                !readEnvelope(FileMasterKeyStorage.envelopeFileIn(lease.vaultDirectory)).contentEquals(old) ||
                !keystore.containsAlias(record.alias(true)) ||
                !keystore.containsAlias(record.alias(false))
            ) {
                throw RecoverySnapshotRefused()
            }
            lease.assertExclusiveAndClosed()
        }

        companion object {
            fun read(
                lease: ClosedVaultRecoveryLease,
                id: UUID,
                keystore: KeystoreFacade,
            ): Evidence {
                lease.assertExclusiveAndClosed()
                val directory = File(lease.vaultDirectory, "keys/recovery-prepared/$id")
                requireDirectory(directory)
                val old = readEnvelope(File(directory, "old.envelope"))
                // The unchanged legacy decoder deliberately rejects a v2 source generation.
                val previous = FileMasterKeyStorage.decode(old)
                val proposed = readEnvelope(File(directory, "new.envelope"))
                val record = RecoveryEnvelopeV2.decode(proposed)
                if (record.transactionId != id ||
                    previous.keyVersion == Int.MAX_VALUE ||
                    record.generation != previous.keyVersion + 1 ||
                    record.strongBoxBacked != previous.strongBoxBacked ||
                    !record.encode().contentEquals(proposed)
                ) {
                    throw RecoverySnapshotRefused()
                }
                return Evidence(directory, old, proposed, record).also { it.assertUnchanged(lease, keystore) }
            }

            private fun requireDirectory(directory: File) {
                if (directory.canonicalFile != directory.absoluteFile ||
                    !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)
                ) {
                    throw RecoverySnapshotRefused()
                }
            }

            private fun readEnvelope(file: File): ByteArray {
                if (file.canonicalFile != file.absoluteFile ||
                    !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
                    file.length() > MAX_ENVELOPE_BYTES
                ) {
                    throw IOException("recovery envelope unavailable")
                }
                return file.inputStream().use { input ->
                    val bytes = ByteArray(MAX_ENVELOPE_BYTES + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0 || count > MAX_ENVELOPE_BYTES) throw IOException("recovery envelope unavailable")
                    bytes.copyOf(count)
                }
            }

            // Legacy envelope read limit; v2's own decoder enforces its stricter 1024-byte cap.
            private const val MAX_ENVELOPE_BYTES = 32768
        }
    }
}
