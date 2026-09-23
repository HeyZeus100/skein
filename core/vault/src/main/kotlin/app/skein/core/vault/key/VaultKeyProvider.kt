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
//   • `isInitialised()` (skein-ank2) is the prompt-free "has `setup()` run
//     on this device?" probe the first-run gate switches on, so the shell
//     can route to setup or unlock WITHOUT issuing a biometric prompt to
//     find out.
//
// Namespace note (`skein-0j1`): package `app.skein.core.vault.key`
// matches the `app.skein.core.vault.db` namespace introduced by
// `skein-e2ki`. A separate coordinator task (`skein-0j1`) will reconcile
// the whole module tree to `app.skein.*` at once; this file
// deliberately does not do that reconciliation piecemeal.

package app.skein.core.vault.key

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.model.AuthorizationToken

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
     * Layer-0 key, and persists the wrapped bytes to the key-envelope file
     * (`keys/key-envelope.v1`, `VAULT_FORMAT.md` §1 — outside the SQLCipher
     * database this master keys; `ATTACHMENT_ENCRYPTION.md` §3.4 amendment).
     *
     * Prompts the biometric flow at least once against [activity] with
     * [prompt]. A user cancel of either wrap prompt aborts setup cleanly —
     * no partial state is left behind. Refused with
     * [SetupResult.AlreadyInitialised] when a wrapped master is already
     * persisted: setup replaces the Layer-0 aliases, so running it twice
     * would strand the existing vault. Refused with [SetupResult.Failed]
     * when an envelope exists but cannot be read — only a user-initiated
     * reset may discard it.
     */
    public suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult

    /**
     * Recovery / device-migration variant of [setup] (skein-v9g, `E3.I11`).
     *
     * Behaves exactly like [setup] — fresh Layer-0 aliases, fresh wraps, a
     * new envelope — EXCEPT that it adopts [existingMaster] instead of
     * generating a new `master_key_material`. That is the whole point: an
     * existing `vault.db` and its attachments are keyed by those 32 bytes,
     * so reusing them is what makes the vault readable again after both
     * Layer-0 factors were invalidated (`ATTACHMENT_ENCRYPTION.md` §3.8) or
     * after a move to a new device. Generating a fresh master here would
     * strand exactly the data the user is trying to recover.
     *
     * [existingMaster] is normally the output of
     * [PassphraseKeyExport.import]. It must be exactly 32 bytes. The
     * provider copies what it needs and does NOT take ownership: the caller
     * still owns zeroing the array it passed in.
     *
     * Every [SetupResult] variant means the same thing as it does for
     * [setup] — including [SetupResult.AlreadyInitialised], which is
     * returned whenever an envelope already exists. Importing over a live
     * vault is therefore impossible by construction; a user-initiated reset
     * is the only way to reach this call on a provisioned device.
     */
    public suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult

    /**
     * Whether [setup] has run on this device — i.e. whether a key envelope
     * (`keys/key-envelope.v1`) exists. Cheap and prompt-free: reads the
     * envelope, never touches the Keystore, never presents a prompt.
     *
     * Returns `true` for an envelope that exists but cannot be read
     * (corrupt / I/O failure): [setup] is refused in that state and only a
     * user-initiated, explicitly destructive reset may discard it, so the
     * caller must route to [unlock] — which reports the typed reason —
     * rather than offer a re-setup. A `false` therefore always means "no
     * envelope at all" (the state in which [unlock] would report
     * [UnlockResult.NotInitialised]).
     *
     * Added by skein-ank2 for the first-run gate. Performs one small file
     * read; call it off the main thread.
     */
    public fun isInitialised(): Boolean

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
     * SAME bytes under the new entry, atomically bumps the envelope's
     * `key_version`, and only then deletes the dead alias. See
     * `ATTACHMENT_ENCRYPTION.md` §3.5 for the failure-mode enumeration.
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

    /**
     * A wrapped master is already persisted, so setup was refused before
     * touching the Keystore (it would recreate the Layer-0 aliases and
     * strand the `vault.db` the existing master keys). The caller routes
     * to [VaultKeyProvider.unlock] instead. Typed (skein-ank2) so the UI
     * never string-matches a [Failed] reason for this branch.
     */
    public object AlreadyInitialised : SetupResult()

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

    /** No key envelope exists yet (`keys/key-envelope.v1`) — call [VaultKeyProvider.setup] first. */
    public object NotInitialised : UnlockResult()

    /**
     * skein-9psb: `Cipher.init` on the Layer-0 alias reported that the
     * device is currently locked (or, equivalently, that the user has not
     * authenticated within the key's validity window) —
     * `setUnlockedDeviceRequired`/per-use-auth enforcement, NOT a corrupt or
     * missing key. Hardware-verified on the Pixel 9 Pro Fold: on screen-on
     * with a keyguard, the host activity resumes ~100ms before the keystore
     * itself learns the device is unlocked, so a prompt presented in that
     * window hits this exact race. Typed (like `SetupResult.AlreadyInitialised`,
     * skein-ank2) so the UI can wait-and-retry silently instead of showing a
     * failure — matched in [VaultKeyProviderImpl] by exception type/error
     * code only, never by message text.
     */
    public object DeviceLocked : UnlockResult()

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
