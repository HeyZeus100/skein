package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import us.aherrera.skein.core.model.AuthorizationToken
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM [VaultKeyProvider] for `:app` tests: scripted setup / unlock outcomes,
 * a throwaway in-memory "key" (constant bytes — never real material), and
 * an optional [events] log that records `"keyLock"` when [lock] zeroes it,
 * so ordering tests can place zeroization relative to the bootstrap's steps.
 *
 * skein-ank2: models the key envelope with [initialised] — `true` by
 * default (a provisioned device). [setup] records a call, returns
 * [nextSetup], and marks the envelope present on any outcome that means
 * one exists; [unlock] reports `NotInitialised` while it does not, exactly
 * like the real provider.
 */
class ScriptedVaultKeyProvider(
    private val events: MutableList<String>? = null,
) : VaultKeyProvider {
    private val epoch = AtomicLong(0L)

    @Volatile
    private var master: ByteArray? = null

    /** Whether a key envelope "exists"; what [isInitialised] reports. */
    @Volatile
    var initialised: Boolean = true

    /** Next [setup] outcome; defaults to a plain (non-StrongBox) success. */
    var nextSetup: () -> SetupResult = { SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false) }

    /** Number of [setup] calls so far — the gate must never call it while an envelope exists. */
    val setupCalls: AtomicInteger = AtomicInteger(0)

    /** Next [unlock] outcome; defaults to success with a fresh token. */
    var nextUnlock: () -> UnlockResult = { UnlockResult.Success(AuthorizationToken(epoch.incrementAndGet())) }

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult {
        setupCalls.incrementAndGet()
        val result = nextSetup()
        when (result) {
            is SetupResult.Success,
            is SetupResult.StrongBoxUnavailableFallback,
            SetupResult.AlreadyInitialised,
            -> initialised = true
            SetupResult.UserCancelled,
            SetupResult.NoBiometricEnrolled,
            is SetupResult.Failed,
            -> Unit
        }
        return result
    }

    /**
     * skein-v9g: the passphrase-import setup. Scripted from the same
     * [nextSetup] as the generating overload, and records the adopted bytes
     * in [importedMaster] so a gate test can assert the provider was handed
     * the master the recovery file produced — never a fresh one.
     */
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult {
        importedMaster = existingMaster.copyOf()
        return setup(activity, prompt)
    }

    /** The bytes the last [setup] import adopted, or `null` if none has run. */
    @Volatile
    var importedMaster: ByteArray? = null
        private set

    override fun isInitialised(): Boolean = initialised

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult {
        if (!initialised) return UnlockResult.NotInitialised
        val result = nextUnlock()
        if (result is UnlockResult.Success) master = ByteArray(KEY_LENGTH) { FILL }
        return result
    }

    override fun currentKey(): ByteArray? = master

    override fun lock() {
        events?.add("keyLock")
        master?.fill(0)
        master = null
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = RewrapResult.Failed("not scripted")

    private companion object {
        const val KEY_LENGTH = 32
        const val FILL: Byte = 7
    }
}
