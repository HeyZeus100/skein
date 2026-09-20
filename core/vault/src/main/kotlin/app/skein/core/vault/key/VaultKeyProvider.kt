// skein-3el (E3.I2) — `VaultKeyProvider` public contract.
//
// This module owns Layer 0 (Keystore-backed wrapping keys) and Layer 1
// (`master_key_material`) of the three-layer hierarchy in
// `docs/design/ATTACHMENT_ENCRYPTION.md` §1.3. Layer 2 (per-attachment
// content keys) lives in the write-once attachment store (`skein-1nr`,
// `E2.I5`) and is not part of this file's scope.
//
// The API is deliberately narrow:
//
//   • `setup()` runs once at first-run vault initialisation.
//   • `unlock()` transitions the provider from a locked to an unlocked
//     session by unwrapping `master_key_material` under a biometric- or
//     device-credential-backed Keystore alias.
//   • `currentKey()` returns the in-memory master **while** unlocked; the
//     caller is required to `.copyOf()` the returned array and zero the
//     copy after use — the buffer surfaced here is the same one that will
//     be zeroed on `lock()` (§6 "Master key material never on disk in
//     plaintext" — the transient in-memory copy is the only unwrapped
//     form, and callers must not stash it).
//   • `lock()` zeroes the in-memory master.
//   • `rewrapAfterInvalidation()` recovers from
//     `KeyPermanentlyInvalidatedException` by unwrapping the master via a
//     surviving factor and re-wrapping the SAME 32 bytes under a fresh
//     Keystore entry for the dead factor. The master's bytes never change
//     across a rewrap — that is the load-bearing invariant of §3.6.
//
// Namespace note (`skein-0j1`): package `app.skein.core.vault.key`
// matches the `app.skein.core.vault.db` namespace introduced by
// `skein-e2ki`. A separate coordinator task (`skein-0j1`) will reconcile
// the whole module tree to `us.aherrera.skein.*` at once; this file
// deliberately does not do that reconciliation piecemeal.

package app.skein.core.vault.key

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import us.aherrera.skein.core.model.AuthorizationToken

/**
 * Owns the vault's Layer 0 → Layer 1 unlock/rewrap lifecycle.
 *
 * Implementations MUST guarantee, per
 * `docs/design/ATTACHMENT_ENCRYPTION.md` §6:
 *  - `master_key_material` is never persisted in unwrapped form;
 *  - a lock event zeros the in-memory master `ByteArray` before returning;
 *  - a rewrap (biometric-invalidation recovery) preserves the master's
 *    32 bytes byte-for-byte — only the wrapping envelope changes.
 *
 * All log output and thrown-exception messages MUST be free of key
 * material — implementations return typed result variants for every
 * observable outcome instead of throwing with sensitive payloads.
 */
public interface VaultKeyProvider {
    /**
     * First-run initialisation. Generates two independent Layer-0 Keystore
     * entries (biometric-bound and device-credential-bound, per
     * `ATTACHMENT_ENCRYPTION.md` §3.2), generates a 32-byte
     * `master_key_material` via `SecureRandom`, wraps it under each
     * Layer-0 key, and writes the wrapped bytes to
     * `attachment_master_key` (`001_initial.sql` §"Attachment key hierarchy").
     *
     * Prompts the biometric flow at least once against [activity] with
     * [prompt]. A user cancel of either wrap prompt aborts setup cleanly —
     * no partial state is left in `attachment_master_key`.
     */
    public suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult

    /**
     * Transitions from locked to unlocked. Presents [prompt] on [activity]
     * for [factor], unwraps `master_key_material` via the corresponding
     * Layer-0 alias, and retains the bytes in memory until [lock] is called.
     *
     * On `KeyPermanentlyInvalidatedException` returns
     * [UnlockResult.KeyPermanentlyInvalidated] — the caller (typically the
     * unlock-manager UI) then routes to [rewrapAfterInvalidation] with the
     * OTHER factor.
     */
    public suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: Factor,
    ): UnlockResult

    /**
     * Returns the in-memory master key or `null` when locked. Callers MUST
     * `.copyOf()` the returned array and zero their copy in a `finally`
     * block — the buffer surfaced here is the SAME one that [lock] will
     * zero, so it must never be stashed past the operation that consumes it.
     */
    public fun currentKey(): ByteArray?

    /**
     * Zeros the in-memory master `ByteArray`, invalidates the current
     * [AuthorizationToken], and transitions to the locked state.
     * Idempotent: calling `lock()` while already locked is a no-op.
     */
    public fun lock()

    /**
     * Recovery from `KeyPermanentlyInvalidatedException` on a Layer-0 key.
     * Unwraps `master_key_material` via [survivingFactor], generates a
     * fresh Layer-0 entry for the OTHER (dead) factor, rewraps the
     * SAME bytes under the new entry, atomically bumps
     * `attachment_master_key.key_version`, and only then deletes the
     * dead alias. See `ATTACHMENT_ENCRYPTION.md` §3.5 for the failure-mode
     * enumeration.
     *
     * On success, the master key material remains in memory as
     * `currentKey`. Caller is responsible for `lock()`-triggered
     * zeroization.
     */
    public suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: Factor,
    ): RewrapResult

    /** Which Layer-0 alias to use for a given `unlock()` / rewrap call. */
    public enum class Factor {
        /** `skein_master_bio_v1` — `AUTH_BIOMETRIC_STRONG`. */
        BIOMETRIC,

        /** `skein_master_cred_v1` — `AUTH_DEVICE_CREDENTIAL`. */
        DEVICE_CREDENTIAL,
    }
}

/** Outcome of [VaultKeyProvider.setup]. */
public sealed class SetupResult {
    /** Both Layer-0 entries and the wrapped master row were persisted. */
    public data class Success(
        public val masterKeyVersion: Int,
        public val strongBoxBacked: Boolean,
    ) : SetupResult()

    /**
     * StrongBox was not available on this device and setup fell back to a
     * TEE-backed key. Persisted just like [Success] — the caller surfaces
     * the flag in Settings › Security (per skein-3el's bd description).
     */
    public data class StrongBoxUnavailableFallback(
        public val masterKeyVersion: Int,
    ) : SetupResult()

    /** User dismissed the biometric prompt during a wrap. No partial state persisted. */
    public object UserCancelled : SetupResult()

    /**
     * `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` reported no
     * enrolled biometric. The device credential factor is still required
     * for `AUTH_DEVICE_CREDENTIAL`, so setup cannot complete until the user
     * enrols a biometric.
     */
    public object NoBiometricEnrolled : SetupResult()

    /** Any other failure — message deliberately does NOT include key material. */
    public data class Failed(
        public val reason: String,
    ) : SetupResult()
}

/** Outcome of [VaultKeyProvider.unlock]. */
public sealed class UnlockResult {
    /** [token] is the fresh authorisation handle for downstream writes. */
    public data class Success(
        public val token: AuthorizationToken,
    ) : UnlockResult()

    public object UserCancelled : UnlockResult()

    /**
     * The Layer-0 alias for [factor] threw `KeyPermanentlyInvalidatedException`.
     * The caller MUST prompt the other factor and route to
     * [VaultKeyProvider.rewrapAfterInvalidation].
     */
    public data class KeyPermanentlyInvalidated(
        public val factor: VaultKeyProvider.Factor,
    ) : UnlockResult()

    /** `attachment_master_key` has no active row — call [VaultKeyProvider.setup] first. */
    public object NotInitialised : UnlockResult()

    public data class Failed(
        public val reason: String,
    ) : UnlockResult()
}

/** Outcome of [VaultKeyProvider.rewrapAfterInvalidation]. */
public sealed class RewrapResult {
    /** The dead factor now has a fresh Layer-0 entry wrapping the SAME 32 bytes. */
    public data class Success(
        public val newKeyVersion: Int,
    ) : RewrapResult()

    public object UserCancelled : RewrapResult()

    /**
     * The `survivingFactor` also threw `KeyPermanentlyInvalidatedException`
     * during the recovery unwrap — recovery is not possible under the v1
     * design (`ATTACHMENT_ENCRYPTION.md` §3.8 residual risk).
     */
    public object BothFactorsInvalidated : RewrapResult()

    public data class Failed(
        public val reason: String,
    ) : RewrapResult()
}
