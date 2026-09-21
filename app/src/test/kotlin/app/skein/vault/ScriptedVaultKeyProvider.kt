package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import us.aherrera.skein.core.model.AuthorizationToken
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM [VaultKeyProvider] for `:app` tests: scripted unlock outcomes, a
 * throwaway in-memory "key" (constant bytes — never real material), and an
 * optional [events] log that records `"keyLock"` when [lock] zeroes it, so
 * ordering tests can place zeroization relative to the bootstrap's steps.
 */
class ScriptedVaultKeyProvider(
    private val events: MutableList<String>? = null,
) : VaultKeyProvider {
    private val epoch = AtomicLong(0L)

    @Volatile
    private var master: ByteArray? = null

    /** Next [unlock] outcome; defaults to success with a fresh token. */
    var nextUnlock: () -> UnlockResult = { UnlockResult.Success(AuthorizationToken(epoch.incrementAndGet())) }

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult {
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
