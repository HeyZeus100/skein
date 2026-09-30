package app.skein.core.vault.key.recovery

import app.skein.core.vault.db.RecoveryProofNative
import java.io.IOException

internal enum class ExistingVaultKeyProofResult {
    VERIFIED,
    WRONG_KEY_OR_CORRUPT,
    UNSUPPORTED_SCHEMA,
    UNAVAILABLE,
}

/** Unwired verifier. Success proves a candidate against a leased snapshot, never authorizes a session. */
internal class ExistingVaultKeyProof(
    private val nativeVerify: (String, ByteArray) -> Int = { path, key -> RecoveryProofNative.nativeVerify(path, key) },
) {
    /** Consumes and zeros [candidateKey], including on lease refusal or native failure. */
    fun verify(
        snapshot: ClosedVaultRecoverySnapshot,
        candidateKey: ByteArray,
    ): ExistingVaultKeyProofResult =
        try {
            if (candidateKey.size != 32) {
                ExistingVaultKeyProofResult.UNAVAILABLE
            } else {
                snapshot.assertSourceUnchanged()
                val result = nativeVerify(snapshot.database.absolutePath, candidateKey)
                snapshot.assertSourceUnchanged()
                when (result) {
                    0 -> ExistingVaultKeyProofResult.VERIFIED
                    1 -> ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT
                    2 -> ExistingVaultKeyProofResult.UNSUPPORTED_SCHEMA
                    else -> ExistingVaultKeyProofResult.UNAVAILABLE
                }
            }
        } catch (_: RecoverySnapshotRefused) {
            ExistingVaultKeyProofResult.UNAVAILABLE
        } catch (_: IOException) {
            ExistingVaultKeyProofResult.UNAVAILABLE
        } catch (_: LinkageError) {
            ExistingVaultKeyProofResult.UNAVAILABLE
        } finally {
            candidateKey.fill(0)
        }
}
