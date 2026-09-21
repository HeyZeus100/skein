// skein-3el (E3.I2) — orchestrates Layer-0 Keystore aliases + Layer-1
// wrapped-master storage into the [VaultKeyProvider] contract.
//
// Only the pieces documented in `ATTACHMENT_ENCRYPTION.md` §1.4 and §3.5
// live in this file. Wire-up (real AndroidKeystoreFacade / real
// AndroidBiometricAuthenticator / the key-envelope-file MasterKeyStorage)
// is `VaultKeyProviders.forDevice` (skein-txrh) — the module's only public
// factory; `:app` calls it from `VaultServices.forDevice`.
//
// Storage failures (skein-txrh): the backend reports a corrupt or
// unwritable envelope as a typed `MasterKeyStorageException`; every entry
// point maps it onto its own `Failed(reason)` variant with the bounded,
// payload-free `Kind.reason` phrase. `UnlockResult` gains no new variant —
// `UnlockManager`'s exhaustive `when` over it is owned elsewhere.
//
// Zeroing discipline: the master `ByteArray` is stored in a single
// volatile-visible field. `lock()` zeros the array in place BEFORE
// nulling the reference — see `LOCK_POLICY_INDEXING.md` §4.4 for why the
// zero-then-null order matters (the reference itself is hygiene; the
// zero-fill is the security property). The reference is `@Volatile` so a
// concurrent reader observes the null promptly on modern Android.
//
// Test bypass: the public API takes `FragmentActivity` +
// `BiometricPrompt.PromptInfo`, which are Android types not available on
// the host JVM. The orchestration is expressed in terms of an internal
// `suspend (Cipher) -> AuthResult` lambda; the public methods pass a
// lambda that calls the real `BiometricAuthenticator`, and JVM unit tests
// call `internal fun *NoUi(...)` variants that pass a lambda returning
// `AuthResult.Success(cipher)` unchanged. This keeps the crypto path
// (wrap / unwrap / rewrap / zero) unit-testable without Robolectric.

package app.skein.core.vault.key

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import us.aherrera.skein.core.model.AuthorizationToken
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

/**
 * Real-Android implementation of [VaultKeyProvider]. Constructed with
 * three injected abstractions — [keystore], [biometric], [storage] — so
 * the orchestration is unit-testable on the JVM without a real Keystore.
 *
 * Not thread-safe against concurrent `unlock()` / `lock()` / `rewrap()`
 * calls — the caller (typically `UnlockManager`, `E3.I3`) serialises the
 * lifecycle transitions on its own coroutine.
 */
public class VaultKeyProviderImpl internal constructor(
    private val keystore: KeystoreFacade,
    private val biometric: BiometricAuthenticator,
    private val storage: MasterKeyStorage,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) : VaultKeyProvider {
    @Volatile
    private var master: ByteArray? = null

    private val epoch = AtomicLong(0L)

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult =
        setupWith(existingMaster = null) { factor, cipher ->
            biometric.authenticate(activity, prompt, factor, cipher)
        }

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult =
        setupWith(existingMaster = existingMaster) { factor, cipher ->
            biometric.authenticate(activity, prompt, factor, cipher)
        }

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = unlockWith(factor) { f, cipher -> biometric.authenticate(activity, prompt, f, cipher) }

    override fun currentKey(): ByteArray? = master

    override fun isInitialised(): Boolean =
        try {
            storage.readActive() != null
        } catch (_: MasterKeyStorageException) {
            // An envelope is present but corrupt / unreadable. `setup()` is
            // refused in that state (below) and only a user-initiated reset
            // may discard it, so report "initialised": the gate then routes
            // to `unlock()`, which surfaces the typed, non-destructive
            // `Failed(kind.reason)` instead of inviting a re-setup.
            true
        }

    override fun lock() {
        zero(master)
        master = null
        // Epoch is bumped on lock too so any AuthorizationToken captured
        // pre-lock does not equal the next unlock's token.
        epoch.incrementAndGet()
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = rewrapWith(survivingFactor) { f, cipher -> biometric.authenticate(activity, prompt, f, cipher) }

    // ---- test-only entry points ---------------------------------------
    //
    // Same orchestration as the public API but with the biometric prompt
    // bypassed — the injected auth lambda returns `AuthResult.Success(cipher)`
    // unchanged. Called from `VaultKeyProviderImplTest` on the JVM.

    internal suspend fun setupNoUi(existingMaster: ByteArray? = null): SetupResult =
        setupWith(existingMaster) { _, cipher -> AuthResult.Success(cipher) }

    internal suspend fun unlockNoUi(factor: VaultKeyProvider.Factor): UnlockResult =
        unlockWith(factor) { _, cipher -> AuthResult.Success(cipher) }

    internal suspend fun rewrapNoUi(survivingFactor: VaultKeyProvider.Factor): RewrapResult =
        rewrapWith(survivingFactor) { _, cipher -> AuthResult.Success(cipher) }

    // ---- shared orchestration -----------------------------------------

    /**
     * [existingMaster] non-null (skein-v9g) adopts those 32 bytes as
     * `master_key_material` instead of generating fresh ones — the
     * passphrase-import / device-migration path. Everything else about the
     * flow is identical, including the refusal once an envelope exists, so
     * an import can never overwrite a live vault's Layer-0 aliases. The
     * array is copied, never retained: the caller keeps ownership of the
     * buffer it passed in, and the copy this method makes is zeroed in the
     * same `finally` that zeroes a generated master.
     */
    private suspend fun setupWith(
        existingMaster: ByteArray?,
        auth: AuthenticateFn,
    ): SetupResult {
        if (existingMaster != null && existingMaster.size != MASTER_KEY_LEN) {
            return SetupResult.Failed("imported master key has the wrong length")
        }
        // skein-txrh: setup is destructive for the Layer-0 aliases (deleted
        // and recreated below), so refuse it outright once a wrapped master
        // exists — a second setup would strand the vault.db that master
        // keys. A corrupt envelope is refused too: only a user-initiated
        // reset may discard it.
        val existing =
            try {
                storage.readActive()
            } catch (e: MasterKeyStorageException) {
                return SetupResult.Failed(e.kind.reason)
            }
        if (existing != null) return SetupResult.AlreadyInitialised

        // §3.2: BOTH factors must be provisioned from day one so recovery
        // never depends on lazily-created state. NoBiometricEnrolled is
        // surfaced early — DEVICE_CREDENTIAL alone is not a valid setup
        // (biometric enrolment is an OS precondition for enrolling the
        // credential factor on modern Android anyway).
        when (biometric.canAuthenticate(VaultKeyProvider.Factor.BIOMETRIC)) {
            is CanAuthenticate.NoneEnrolled -> return SetupResult.NoBiometricEnrolled
            is CanAuthenticate.HardwareUnavailable ->
                return SetupResult.Failed("biometric hardware unavailable")
            is CanAuthenticate.Unknown -> Unit
            CanAuthenticate.Yes -> Unit
        }

        val strongBoxAvailable = keystore.hasStrongBox()
        val strongBoxBacked =
            tryCreateBothKeys(strongBoxAvailable)
                ?: return SetupResult.Failed("failed to create Layer-0 Keystore entries")

        // A copy either way, so the `finally` below can zero it unconditionally
        // without ever touching a buffer the caller still owns.
        val newMaster = existingMaster?.copyOf() ?: ByteArray(MASTER_KEY_LEN).also(random::nextBytes)
        try {
            val bioWrap =
                wrapUnder(VaultKeyProvider.Factor.BIOMETRIC, newMaster, auth) ?: run {
                    cleanupOnSetupFail()
                    return SetupResult.UserCancelled
                }
            val credWrap =
                wrapUnder(VaultKeyProvider.Factor.DEVICE_CREDENTIAL, newMaster, auth) ?: run {
                    cleanupOnSetupFail()
                    return SetupResult.UserCancelled
                }

            val version =
                try {
                    storage.writeInitial(
                        MasterKeyRow(
                            keyVersion = 1,
                            wrappedBytesBiometric = bioWrap.wrappedBytes,
                            wrapIvBiometric = bioWrap.iv,
                            wrapTagBiometric = null,
                            wrappedBytesCredential = credWrap.wrappedBytes,
                            wrapIvCredential = credWrap.iv,
                            wrapTagCredential = null,
                            createdAt = clock(),
                            strongBoxBacked = strongBoxBacked,
                        ),
                    )
                } catch (e: MasterKeyStorageException) {
                    // Nothing persisted: the freshly created aliases wrap a
                    // master that is about to be zeroed, so drop them too.
                    cleanupOnSetupFail()
                    return SetupResult.Failed(e.kind.reason)
                }
            return if (strongBoxBacked) {
                SetupResult.Success(version, strongBoxBacked = true)
            } else {
                SetupResult.StrongBoxUnavailableFallback(version)
            }
        } finally {
            zero(newMaster)
        }
    }

    private suspend fun unlockWith(
        factor: VaultKeyProvider.Factor,
        auth: AuthenticateFn,
    ): UnlockResult {
        val row =
            (
                try {
                    storage.readActive()
                } catch (e: MasterKeyStorageException) {
                    return UnlockResult.Failed(e.kind.reason)
                }
            ) ?: return UnlockResult.NotInitialised
        val alias = aliasFor(factor)
        val iv =
            ivFor(row, factor)
                ?: return UnlockResult.Failed("no wrapped bytes for requested factor")
        val wrapped =
            wrappedBytesFor(row, factor)
                ?: return UnlockResult.Failed("no wrapped bytes for requested factor")

        val cipher =
            try {
                keystore.decryptCipher(alias, iv)
            } catch (_: KeyPermanentlyInvalidatedException) {
                return UnlockResult.KeyPermanentlyInvalidated(factor)
            } catch (t: Throwable) {
                return UnlockResult.Failed("cipher init failed: ${t.javaClass.simpleName}")
            }
        val authorized =
            when (val r = auth(factor, cipher)) {
                is AuthResult.Success -> r.cipher
                AuthResult.UserCancelled -> return UnlockResult.UserCancelled
                is AuthResult.Error -> return UnlockResult.Failed("biometric error code=${r.code}")
            }
        val bytes =
            try {
                authorized.doFinal(wrapped)
            } catch (_: KeyPermanentlyInvalidatedException) {
                return UnlockResult.KeyPermanentlyInvalidated(factor)
            } catch (t: Throwable) {
                return UnlockResult.Failed("unwrap failed: ${t.javaClass.simpleName}")
            }
        zero(master)
        master = bytes
        return UnlockResult.Success(AuthorizationToken(epoch.incrementAndGet()))
    }

    private suspend fun rewrapWith(
        survivingFactor: VaultKeyProvider.Factor,
        auth: AuthenticateFn,
    ): RewrapResult {
        val row =
            (
                try {
                    storage.readActive()
                } catch (e: MasterKeyStorageException) {
                    return RewrapResult.Failed(e.kind.reason)
                }
            ) ?: return RewrapResult.Failed("no active master row")
        val survivingIv =
            ivFor(row, survivingFactor)
                ?: return RewrapResult.Failed("no wrapped bytes for surviving factor")
        val survivingWrapped =
            wrappedBytesFor(row, survivingFactor)
                ?: return RewrapResult.Failed("no wrapped bytes for surviving factor")

        val cipher =
            try {
                keystore.decryptCipher(aliasFor(survivingFactor), survivingIv)
            } catch (_: KeyPermanentlyInvalidatedException) {
                return RewrapResult.BothFactorsInvalidated
            } catch (t: Throwable) {
                return RewrapResult.Failed("cipher init failed: ${t.javaClass.simpleName}")
            }
        val authorized =
            when (val r = auth(survivingFactor, cipher)) {
                is AuthResult.Success -> r.cipher
                AuthResult.UserCancelled -> return RewrapResult.UserCancelled
                is AuthResult.Error -> return RewrapResult.Failed("biometric error code=${r.code}")
            }

        val recoveredMaster: ByteArray =
            try {
                authorized.doFinal(survivingWrapped)
            } catch (_: KeyPermanentlyInvalidatedException) {
                return RewrapResult.BothFactorsInvalidated
            } catch (t: Throwable) {
                return RewrapResult.Failed("unwrap failed: ${t.javaClass.simpleName}")
            }
        // Ownership of `recoveredMaster` transfers to the `master` field on
        // success (skein-22su) — the same in-memory slot `unlock()`
        // populates. Only zero it here on a path that does NOT reach
        // `RewrapResult.Success`; the caller (UnlockManager) zeroes it via
        // `lock()` once ownership has transferred.
        var ownershipTransferred = false
        try {
            val deadFactor = otherFactor(survivingFactor)
            keystore.deleteEntry(aliasFor(deadFactor))
            keystore.createKey(aliasFor(deadFactor), deadFactor, row.strongBoxBacked)

            val newWrap =
                wrapUnder(deadFactor, recoveredMaster, auth)
                    ?: return RewrapResult.UserCancelled

            val newVersion =
                try {
                    storage.rewrap(
                        currentVersion = row.keyVersion,
                        rewrappedFactor = deadFactor,
                        wrappedBytes = newWrap.wrappedBytes,
                        iv = newWrap.iv,
                        tag = null,
                        now = clock(),
                    )
                } catch (e: MasterKeyStorageException) {
                    // §3.7 row 2: the envelope still holds the previous
                    // generation, whose surviving factor keeps working; the
                    // next recovery attempt starts over from step 1.
                    return RewrapResult.Failed(e.kind.reason)
                }
            epoch.incrementAndGet()
            zero(master)
            master = recoveredMaster
            ownershipTransferred = true
            return RewrapResult.Success(newVersion)
        } finally {
            if (!ownershipTransferred) {
                zero(recoveredMaster)
            }
        }
    }

    private fun tryCreateBothKeys(strongBoxAvailable: Boolean): Boolean? {
        val aliases =
            listOf(
                ALIAS_BIOMETRIC to VaultKeyProvider.Factor.BIOMETRIC,
                ALIAS_CREDENTIAL to VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
            )
        var strongBox = strongBoxAvailable
        aliases.forEach { (alias, factor) ->
            keystore.deleteEntry(alias)
            try {
                keystore.createKey(alias, factor, requireStrongBox = strongBox)
            } catch (_: StrongBoxUnavailableException) {
                strongBox = false
                keystore.createKey(alias, factor, requireStrongBox = false)
            } catch (_: Throwable) {
                return null
            }
        }
        return strongBox
    }

    private fun cleanupOnSetupFail() {
        listOf(ALIAS_BIOMETRIC, ALIAS_CREDENTIAL).forEach(keystore::deleteEntry)
    }

    private suspend fun wrapUnder(
        factor: VaultKeyProvider.Factor,
        masterBytes: ByteArray,
        auth: AuthenticateFn,
    ): Wrap? {
        val cipher: Cipher =
            try {
                keystore.encryptCipher(aliasFor(factor))
            } catch (_: KeyPermanentlyInvalidatedException) {
                return null
            }
        val authorized =
            when (val r = auth(factor, cipher)) {
                is AuthResult.Success -> r.cipher
                AuthResult.UserCancelled -> return null
                is AuthResult.Error -> return null
            }
        val wrapped = authorized.doFinal(masterBytes)
        val iv = authorized.iv ?: error("Cipher did not expose an IV after doFinal")
        return Wrap(wrapped, iv)
    }

    private fun aliasFor(factor: VaultKeyProvider.Factor): String =
        when (factor) {
            VaultKeyProvider.Factor.BIOMETRIC -> ALIAS_BIOMETRIC
            VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> ALIAS_CREDENTIAL
        }

    private fun ivFor(
        row: MasterKeyRow,
        factor: VaultKeyProvider.Factor,
    ): ByteArray? =
        when (factor) {
            VaultKeyProvider.Factor.BIOMETRIC -> row.wrapIvBiometric
            VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> row.wrapIvCredential
        }

    private fun wrappedBytesFor(
        row: MasterKeyRow,
        factor: VaultKeyProvider.Factor,
    ): ByteArray? =
        when (factor) {
            VaultKeyProvider.Factor.BIOMETRIC -> row.wrappedBytesBiometric
            VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> row.wrappedBytesCredential
        }

    private fun otherFactor(f: VaultKeyProvider.Factor): VaultKeyProvider.Factor =
        when (f) {
            VaultKeyProvider.Factor.BIOMETRIC -> VaultKeyProvider.Factor.DEVICE_CREDENTIAL
            VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> VaultKeyProvider.Factor.BIOMETRIC
        }

    private fun zero(bytes: ByteArray?) {
        if (bytes == null) return
        java.util.Arrays.fill(bytes, 0)
    }

    private data class Wrap(
        val wrappedBytes: ByteArray,
        val iv: ByteArray,
    )

    /** Test-only accessor for asserting the master `ByteArray` was zeroed on `lock()`. */
    internal fun masterForTest(): ByteArray? = master

    /** Test-only accessor for the current epoch counter. */
    internal fun epochForTest(): Long = epoch.get()

    public companion object {
        internal const val ALIAS_BIOMETRIC: String = "skein_master_bio_v1"
        internal const val ALIAS_CREDENTIAL: String = "skein_master_cred_v1"
        internal const val MASTER_KEY_LEN: Int = 32
    }
}

/**
 * Signature of the biometric-authorisation step. Real code passes
 * [BiometricAuthenticator.authenticate] (curried on activity+prompt);
 * tests pass a lambda that returns `AuthResult.Success(cipher)` unchanged.
 */
private typealias AuthenticateFn = suspend (VaultKeyProvider.Factor, Cipher) -> AuthResult
