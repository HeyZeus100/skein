// skein-v9g (E3.I11) — opt-in passphrase export and import of the Layer-1
// vault master key.
//
// WHAT IS EXPORTED. The 32-byte Layer-1 `master_key_material` of
// `docs/design/ATTACHMENT_ENCRYPTION.md` §1.3 — the same bytes
// `VaultKeyProvider.currentKey()` holds while unlocked, which key `vault.db`
// and, through HKDF, every attachment. NOT the Layer-0 Keystore-wrapped
// blobs: those are bound to this device's Keystore aliases and are useless
// anywhere else, which is exactly why §3.8's "both factors invalidated"
// case has no automated recovery without this file.
//
// WHY IT EXISTS. §3.8 records the residual risk verbatim: if both Layer-0
// factors are invalidated in the same event there is "no surviving factor
// to unwrap `master_key_material`" and "recovery is not possible". That
// paragraph names a v2 candidate — "a user-generated recovery code
// (PBKDF2/scrypt-derived third wrapped copy…)" — and defers it. `skein-v9g`
// is that work, narrowed: a passphrase-wrapped copy the user exports to a
// location THEY choose, on demand, and which the app never holds.
//
// WHAT THIS IS NOT. Not escrow. §7 of that document rules escrow out for
// v1 and nothing here walks it back: the app writes the file exactly once,
// to a `Storage Access Framework` destination the user picks
// (`POST_REVIEW_RESOLUTIONS.md` §4 — never app-external staging), keeps no
// copy, records no passphrase, and has no way to recover the file or the
// passphrase afterwards. The default posture is unchanged: a user who
// never taps "Export vault key" is in exactly the position §3.8 describes.
//
// KDF. `PBKDF2WithHmacSHA256`, 600 000 iterations, 16-byte random salt,
// 256-bit output — the parameters bd `skein-v9g` pins, and the reason it
// pins them: "Argon2 is not in the JDK and no dependency is added". Argon2id
// and scrypt would both be stronger against GPU/ASIC attackers, and both
// would have to be hand-rolled here (the project's own `Hkdf` is precedent
// that hand-rolling is permitted when the plan calls for it) — but PBKDF2
// is the one primitive the platform already provides in a reviewed,
// constant-time implementation, and 600 000 iterations is OWASP's 2023
// PBKDF2-HMAC-SHA256 recommendation. Choosing the platform primitive over a
// hand-rolled memory-hard one is the conservative call for a secret whose
// only other copy is in the user's head.
//
// AEAD. AES-256-GCM, 12-byte random IV, 128-bit tag, with the envelope
// header bound in as AAD (`RecoveryEnvelope.aad`). A fresh salt and a fresh
// IV per export mean no (key, nonce) pair is ever reused, even if the same
// passphrase is used for two exports — the GCM nonce-reuse hazard
// `ATTACHMENT_ENCRYPTION.md` §1.2 is built around.
//
// FAILURE INDISTINGUISHABILITY. A wrong passphrase and a tampered
// ciphertext both surface as the single `WRONG_PASSPHRASE` reason, from the
// same `doFinal` call, after the same full KDF run. There is no
// short-circuit before the tag check, the JCE's GCM tag comparison is
// constant-time, and the ~600 000-iteration derivation dominates the wall
// clock either way — so the observable timing of the two cases is the KDF's,
// not the comparison's. (An attacker who holds the file can of course test
// passphrases offline at their own pace; that is inherent to any
// passphrase-wrapped secret and is why the strength floor exists.)
//
// ZEROIZATION. Every buffer this file allocates that has touched key or
// passphrase material — the derived KEK, the `PBEKeySpec`'s internal
// passphrase copy, and, on a failed import, the partially recovered master
// — is zeroed before the call returns. The caller owns `master` (export)
// and the returned array (import), and owns wiping its own `CharArray`:
// this file never mutates the caller's passphrase.

package app.skein.core.vault.key

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps and unwraps the Layer-1 vault master key under a user passphrase,
 * in the `skein-recovery-<date>.json` envelope `RecoveryEnvelope` documents.
 *
 * Opt-in: nothing in the app calls [export] unless the user explicitly asks
 * for a recovery file, from an unlocked vault, after a fresh re-auth.
 */
public object PassphraseKeyExport {
    /** The envelope format this build writes and the only one it reads. */
    public const val FORMAT_VERSION: Int = 1

    /** The `SecretKeyFactory` algorithm, recorded in the envelope's `kdf` field. */
    public const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"

    /** PBKDF2 iteration count for new exports (OWASP 2023 for PBKDF2-HMAC-SHA256). */
    public const val ITERATIONS: Int = 600_000

    /**
     * The lowest iteration count [import] will honour. Equal to [ITERATIONS]:
     * an attacker who can rewrite the file cannot talk this build into a
     * cheap derivation, and the AAD binding means they cannot rewrite it
     * undetected anyway. Raising [ITERATIONS] in a later version must raise
     * this only together with a format-version bump, or old exports stop
     * importing.
     */
    public const val MINIMUM_ACCEPTED_ITERATIONS: Int = ITERATIONS

    /** Upper bound on a file's declared iteration count, so a hostile file cannot hang the UI. */
    public const val MAXIMUM_ACCEPTED_ITERATIONS: Int = 10_000_000

    internal const val SALT_LEN: Int = 16
    internal const val IV_LEN: Int = 12
    internal const val TAG_BITS: Int = 128
    internal const val KEK_BITS: Int = 256
    internal const val MASTER_KEY_LEN: Int = 32

    /**
     * Wraps [master] (exactly [MASTER_KEY_LEN] bytes) under a key derived
     * from [passphrase] and returns the complete recovery file's bytes,
     * ready to be written to the user's chosen SAF destination.
     *
     * Neither [master] nor [passphrase] is mutated; the caller owns wiping
     * both. Refuses a passphrase below [PassphraseStrength.MINIMUM_LENGTH]
     * with [PassphraseTooShortException] — the floor is enforced here, not
     * only in the UI.
     */
    public fun export(
        master: ByteArray,
        passphrase: CharArray,
    ): ByteArray = exportWith(master, passphrase, ITERATIONS, SecureRandom())

    /**
     * Recovers the master key from a recovery file's [bytes] using
     * [passphrase]. The returned array is the caller's to own and to zero.
     *
     * @throws RecoveryFailedException with
     *   [RecoveryFailedException.Reason.WRONG_PASSPHRASE] for a wrong
     *   passphrase OR any tampering of the envelope (deliberately the same
     *   outcome — see this file's header), and with the other reasons for a
     *   file that is not a readable recovery export at all. Never throws
     *   with key or passphrase material in the message.
     */
    public fun import(
        bytes: ByteArray,
        passphrase: CharArray,
    ): ByteArray = importWith(bytes, passphrase, MINIMUM_ACCEPTED_ITERATIONS)

    // ---- internal seams --------------------------------------------------
    //
    // JVM unit tests drive these with a low iteration count (600 000 real
    // iterations per assertion would put the suite into the minutes) and a
    // deterministic `SecureRandom`. Production callers use the public pair
    // above, which pins both.

    internal fun exportWith(
        master: ByteArray,
        passphrase: CharArray,
        iterations: Int,
        random: SecureRandom,
    ): ByteArray {
        require(master.size == MASTER_KEY_LEN) { "master key must be $MASTER_KEY_LEN bytes" }
        require(iterations > 0) { "iterations must be positive" }
        if (passphrase.size < PassphraseStrength.MINIMUM_LENGTH) {
            throw PassphraseTooShortException(PassphraseStrength.MINIMUM_LENGTH)
        }

        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val kek = deriveKek(passphrase, salt, iterations)
        try {
            // The envelope is built first, with an empty ciphertext, purely
            // so the AAD is computed from the same fields the parser will
            // later read back — one definition, no chance of drift.
            val header = RecoveryEnvelope(FORMAT_VERSION, KDF_ALGORITHM, iterations, salt, iv, EMPTY)
            val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header.aad())
            val ciphertext = cipher.doFinal(master)
            return header.copy(ciphertext = ciphertext).toJsonBytes()
        } finally {
            kek.fill(0)
        }
    }

    internal fun importWith(
        bytes: ByteArray,
        passphrase: CharArray,
        minimumIterations: Int,
    ): ByteArray {
        val envelope = RecoveryEnvelope.parse(bytes)
        if (envelope.kdf != KDF_ALGORITHM) {
            throw RecoveryFailedException(RecoveryFailedException.Reason.MALFORMED)
        }
        if (envelope.salt.size != SALT_LEN || envelope.iv.size != IV_LEN) {
            throw RecoveryFailedException(RecoveryFailedException.Reason.MALFORMED)
        }
        if (envelope.iterations < minimumIterations || envelope.iterations > MAXIMUM_ACCEPTED_ITERATIONS) {
            throw RecoveryFailedException(RecoveryFailedException.Reason.WEAK_PARAMETERS)
        }

        val kek = deriveKek(passphrase, envelope.salt, envelope.iterations)
        val recovered =
            try {
                val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(kek, "AES"),
                    GCMParameterSpec(TAG_BITS, envelope.iv),
                )
                cipher.updateAAD(envelope.aad())
                try {
                    cipher.doFinal(envelope.ciphertext)
                } catch (_: GeneralSecurityException) {
                    // AEADBadTagException and every other JCE failure at this
                    // point mean the same thing to the user and must not be
                    // told apart: the passphrase is wrong, or the file has
                    // been altered.
                    throw RecoveryFailedException(RecoveryFailedException.Reason.WRONG_PASSPHRASE)
                }
            } finally {
                kek.fill(0)
            }
        if (recovered.size != MASTER_KEY_LEN) {
            // Authenticated, so this is a well-formed file that simply does
            // not hold a Skein master key — not a passphrase problem. Wipe
            // the plaintext we are about to drop on the floor anyway.
            recovered.fill(0)
            throw RecoveryFailedException(RecoveryFailedException.Reason.MALFORMED)
        }
        return recovered
    }

    /**
     * PBKDF2-HMAC-SHA256 over [passphrase] and [salt]. The [PBEKeySpec]
     * copies the passphrase internally; `clearPassword()` zeroes that copy.
     * The caller's own array is untouched.
     */
    private fun deriveKek(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEK_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"
    private val EMPTY = ByteArray(0)
}

/**
 * A recovery file could not be turned back into a master key. [reason] is
 * the only thing callers switch on; the message is a short, fixed
 * diagnostic that never carries key or passphrase material.
 */
public class RecoveryFailedException internal constructor(
    public val reason: Reason,
) : Exception(reason.message) {
    /** Why the recovery failed. */
    public enum class Reason(
        internal val message: String,
    ) {
        /** Not a Skein recovery export, or structurally damaged beyond parsing. */
        MALFORMED("recovery file is not a readable Skein recovery export"),

        /** A recovery export written by a newer Skein than this build understands. */
        UNSUPPORTED_VERSION("recovery file was written by a newer version of Skein"),

        /** The file asks for weaker key stretching than this build accepts. */
        WEAK_PARAMETERS("recovery file declares key-stretching parameters this version will not accept"),

        /**
         * The passphrase did not decrypt the file, OR the file was altered.
         * Deliberately one reason for both: telling them apart would tell an
         * attacker which of the two they got wrong.
         */
        WRONG_PASSPHRASE("passphrase did not unlock the recovery file"),
    }
}
