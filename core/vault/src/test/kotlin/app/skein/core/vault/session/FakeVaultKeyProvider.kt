// skein-pya (E3.I3a) — deterministic fake VaultKeyProvider for the JVM
// UnlockManagerTest. The real Android implementation lives in
// `VaultKeyProviderImpl` (skein-3el); this fake reproduces just the pieces
// UnlockManager exercises and captures the ordering the tests need to
// verify (state.value at the moment `lock()` was invoked).

package app.skein.core.vault.session

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import us.aherrera.skein.core.model.AuthorizationToken
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class FakeVaultKeyProvider(
    private val stateAtLockSink: () -> UnlockState? = { null },
) : VaultKeyProvider {
    private val epochSource = AtomicLong(0)
    private var master: ByteArray? = null

    val authCallCount = AtomicInteger(0)
    val lockCallCount = AtomicInteger(0)
    val rewrapCallCount = AtomicInteger(0)

    /** State captured at the moment [lock] was invoked; `null` until lock ran. */
    @Volatile
    var stateAtLock: UnlockState? = null
        private set

    // Scripted next results. Tests set these before running the flow.
    var nextUnlockResult: () -> UnlockResult = {
        UnlockResult.Success(AuthorizationToken(epochSource.incrementAndGet()))
    }
    var nextRewrapResult: () -> RewrapResult = {
        RewrapResult.Success(newKeyVersion = 2)
    }

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult {
        authCallCount.incrementAndGet()
        val r = nextUnlockResult()
        if (r is UnlockResult.Success) master = ByteArray(32) { 1 }
        return r
    }

    override fun currentKey(): ByteArray? = master

    override fun lock() {
        stateAtLock = stateAtLockSink()
        lockCallCount.incrementAndGet()
        master?.fill(0)
        master = null
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult {
        rewrapCallCount.incrementAndGet()
        val r = nextRewrapResult()
        if (r is RewrapResult.Success) master = ByteArray(32) { 2 }
        return r
    }
}
