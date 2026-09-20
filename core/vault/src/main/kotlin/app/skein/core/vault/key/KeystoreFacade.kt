// skein-3el (E3.I2) — `AndroidKeyStore` abstraction.
//
// A thin abstraction over Layer-0 operations so `VaultKeyProviderImpl` is
// unit-testable on the JVM without an Android device. The real Android
// implementation lives in `AndroidKeystoreFacade`; JVM unit tests substitute
// `FakeKeystoreFacade` (test source set), which backs entries with plain
// JCE `AES/GCM/NoPadding` keys.
//
// The facade deliberately exposes `Cipher` objects (a JCE type available
// on both real Android and the host JVM) rather than plain byte APIs — this
// keeps the wrap/unwrap semantics identical between the real Keystore
// (where a `Cipher` from `KeyStore.getKey()` is bound to a
// `BiometricPrompt.CryptoObject` for per-use auth) and the fake (where a
// `Cipher` is simply a JCE object with an in-memory `SecretKey`). See
// `ATTACHMENT_ENCRYPTION.md` §2.3 for the real-Keystore `KeyGenParameterSpec`.

package app.skein.core.vault.key

import java.security.KeyStore
import javax.crypto.Cipher

/**
 * Abstraction over the `AndroidKeyStore` operations `VaultKeyProviderImpl`
 * needs. All methods MUST propagate
 * `android.security.keystore.KeyPermanentlyInvalidatedException` (or its
 * simulated fake analogue) unchanged so the caller can route to the rewrap
 * flow — swallowing or wrapping this exception would erase the recovery
 * signal.
 */
internal interface KeystoreFacade {
    /**
     * True iff the device's `PackageManager.hasSystemFeature(
     * PackageManager.FEATURE_STRONGBOX_KEYSTORE)`. Called ONCE at
     * `setup()` — the result is persisted alongside the wrapped master row
     * so a subsequent update that surfaces StrongBox does not silently
     * change the semantics for keys already in use.
     */
    fun hasStrongBox(): Boolean

    /**
     * Generates a fresh AES-256-GCM Layer-0 key at [alias] with the flags
     * required by `ATTACHMENT_ENCRYPTION.md` §2.3:
     *   • `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`
     *   • `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`
     *   • `setUserAuthenticationRequired(true)`
     *   • `setUserAuthenticationParameters(0, factor.authTypeMask)`
     *   • `setIsStrongBoxBacked(requireStrongBox)` — the caller is
     *     responsible for feature-detect + retry-without on
     *     `StrongBoxUnavailableException`.
     *   • `setInvalidatedByBiometricEnrollment(true)` for the biometric factor.
     *
     * On a `StrongBoxUnavailableException`, throws — the caller retries
     * with `requireStrongBox=false`.
     */
    fun createKey(
        alias: String,
        factor: VaultKeyProvider.Factor,
        requireStrongBox: Boolean,
    )

    /**
     * Deletes the Keystore entry at [alias]. Idempotent — missing entries
     * are a no-op.
     */
    fun deleteEntry(alias: String)

    /** True iff a Keystore entry exists at [alias]. */
    fun containsAlias(alias: String): Boolean

    /**
     * Prepares an ENCRYPT-mode `Cipher` bound to the Keystore key at
     * [alias] — the returned Cipher's IV is drawn from the Cipher's own
     * `.iv` after `init()` (real Keystore auto-generates a random 12-byte
     * IV for GCM). The returned Cipher is NOT authorised: on the real
     * Android backend the caller wraps it in a `CryptoObject` and passes
     * it through `BiometricPrompt` before `doFinal()`.
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException
     *     if the alias has been invalidated (new biometric enrolment,
     *     biometric reset).
     */
    fun encryptCipher(alias: String): Cipher

    /**
     * Prepares a DECRYPT-mode `Cipher` seeded with the caller-supplied
     * [iv] (which was captured at wrap time). Same authorisation rules as
     * [encryptCipher] apply — the returned Cipher must be authenticated
     * through `BiometricPrompt` before `doFinal()`.
     */
    fun decryptCipher(
        alias: String,
        iv: ByteArray,
    ): Cipher

    /**
     * Loads the raw `KeyStore` instance for callers that need to inspect
     * or delete entries directly (e.g. instrumented-test cleanup).
     * Returns `null` when the facade has no backing `KeyStore` (e.g. the
     * fake). Never used from `VaultKeyProviderImpl`'s hot path.
     */
    fun rawKeyStoreOrNull(): KeyStore? = null
}
