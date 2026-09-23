// skein-qsux (E3.I14 cont'd, filed by skein-up0) — on-device coverage that a
// lock-policy setting Settings › Security writes through `SecurityPrefs`
// survives an Activity recreation, over a real `UnlockManager` collecting
// the change live (`combine(idleTimeoutMinutes, lockOnScreenOff,
// lockOnBackground) { ... }.collect { unlockManager.configure(it) }` —
// the exact shape `app.skein.vault.VaultServices.forDevice`'s private
// `wireLockPolicy` uses in production, and `app.skein.TestSkeinApplication`'s
// `wireLockPolicyForTest` mirrors for the JVM Robolectric suite
// (`MainActivityComposeTest`'s "changing the idle timeout in Settings..."
// test) — reproduced here rather than reused because it is a private
// implementation detail of `VaultServices`'s companion object.
//
// This does NOT drive `SettingsScreen`/`LockPolicyControls` through real taps
// the way the JVM Robolectric test does: `:app`'s `androidTestImplementation`
// configuration has no `compose.ui.test.junit4` dependency (only
// `testImplementation` does — see `app/build.gradle.kts`), so
// `createAndroidComposeRule`/semantics-tree assertions are unavailable in
// this source set. Filed as a follow-up (bd `skein-r5xy`) rather than added
// here, since `app/build.gradle.kts` is outside this task's allowed files.
// This test instead exercises the same real, on-device DataStore
// (`SecurityPrefs`) and real `UnlockManager.configure` collection that a tap
// on `LockOnScreenOffToggle` would ultimately call into
// (`SettingsViewModel.setLockOnScreenOff` → the `onSetLockOnScreenOff`
// setter `MainActivity` wires → `SecurityPrefs.setLockOnScreenOff`), and
// recreates the real, production-wired [MainActivity] (real `SkeinApplication`
// / `VaultServices.forDevice`, real Keystore + `BiometricPrompt`) around it,
// the same "real device Activity lifecycle" this bead's AC cares about.
//
// Compiled by `compileFossDebugAndroidTestKotlin`; the on-device run is
// gated on the emulator lane tracked by bd `skein-k3b2` (no provisioned
// emulator with an enrolled test biometric in this worktree), like every
// other `*InstrumentedTest` in this module (`FirstRunInstrumentedTest`,
// `VaultBootstrapInstrumentedTest`, …) — launching the real `MainActivity`
// here only proves it survives being torn down and recreated around this
// state; it is never unlocked or interacted with.

package app.skein.vault

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.MainActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.UnlockManager
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class LockPolicySettingsRecreationInstrumentedTest {
    /** Never asked to unlock/rewrap in this test — `UnlockManager.configure`/`.policy` don't touch it. */
    private class UnusedVaultKeyProvider : VaultKeyProvider {
        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = error("not exercised by this test")

        /** skein-v9g: the passphrase-import overload; not exercised by this test. */
        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            existingMaster: ByteArray,
        ): SetupResult = error("not exercised by this test")

        override fun isInitialised(): Boolean = error("not exercised by this test")

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult = error("not exercised by this test")

        override fun currentKey(): ByteArray? = null

        override fun lock() = Unit

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = error("not exercised by this test")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val securityPrefs = SecurityPrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unlockManager = UnlockManager(keyProvider = UnusedVaultKeyProvider(), scope = null)

    @Before
    fun resetPrefs() {
        grantPostNotifications()
        runBlocking { securityPrefs.clearAllForTest() }
        wireLockPolicy()
    }

    /**
     * skein-7yy2 — keeps the system's runtime-permission dialog from sitting
     * on top of the real [MainActivity] this test recreates.
     *
     * This is the only test in `:app`'s instrumented suite that calls
     * [SecurityPrefs.clearAllForTest], and that resets
     * `postNotificationsAsked` to its `false` default. The production
     * [MainActivity] reads that flag in `onCreate` and, while it is false,
     * calls `requestPermissions(POST_NOTIFICATIONS)` —
     * `android.permission.POST_NOTIFICATIONS` is declared in
     * `app/src/main/AndroidManifest.xml` and is a runtime permission at this
     * module's `targetSdk = 37` on the lane's API 35 image. The system's
     * grant dialog is an Activity of its own, so it lands ON TOP of
     * [MainActivity], which drops to PAUSED; `ActivityScenario.recreate()`
     * then spends its whole budget waiting for a RESUMED state that cannot
     * arrive — "Activity never becomes requested state [RESUMED] (last
     * lifecycle transition = PAUSED)", emulator run 35847825560, 45.5s.
     *
     * Pre-granting makes that `requestPermissions` call a silent no-op with
     * no window of its own, and also dismisses a dialog an earlier test in
     * the same run may have left standing. It changes nothing this test
     * asserts: the assertions below are entirely about
     * `SecurityPrefs.lockOnScreenOff` surviving a real [MainActivity]
     * recreation, and they are untouched.
     *
     * The keyguard is deliberately NOT handled here: this test never turns
     * the screen off, and `.github/workflows/emulator.yml` boots the lane
     * with `svc power stayon true`, a maximum `screen_off_timeout` and
     * `wm dismiss-keyguard`, so nothing re-arms it.
     */
    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { securityPrefs.clearAllForTest() }
    }

    /** Mirrors `VaultServices.forDevice`'s private `wireLockPolicy` — see this file's header. */
    private fun wireLockPolicy() {
        scope.launch {
            combine(
                securityPrefs.idleTimeoutMinutes,
                securityPrefs.lockOnScreenOff,
                securityPrefs.lockOnBackground,
            ) { minutes, lockOnScreenOff, lockOnBackground ->
                LockPolicy(
                    idleTimeout = Duration.ofMinutes(minutes.toLong()),
                    lockOnScreenOff = lockOnScreenOff,
                    lockOnBackground = lockOnBackground,
                )
            }.collect { policy -> unlockManager.configure(policy) }
        }
    }

    @Test
    fun changingLockOnScreenOffPersistsAndSurvivesActivityRecreation() {
        assertTrue(runBlocking { securityPrefs.lockOnScreenOff.first() })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The same call `SettingsViewModel.setLockOnScreenOff` (wired by
            // `MainActivity`'s `onSetLockOnScreenOff` setter) makes when the
            // "Lock when screen turns off" switch is tapped off.
            runBlocking { securityPrefs.setLockOnScreenOff(false) }

            waitUntilTrue { !unlockManager.policy.value.lockOnScreenOff }

            // Real device Activity lifecycle: destroy + recreate MainActivity
            // around the change, exactly the AC this test guards.
            scenario.recreate()

            assertFalse(
                "lockOnScreenOff must still read false from SecurityPrefs after recreation",
                runBlocking { securityPrefs.lockOnScreenOff.first() },
            )
            // A brand-new SecurityPrefs instance — the same shape
            // `MainActivity.onCreate` constructs on every recreation — reads
            // the persisted DataStore value, not anything cached in memory.
            assertFalse(runBlocking { SecurityPrefs(context).lockOnScreenOff.first() })
        }
    }

    private fun waitUntilTrue(
        timeoutMillis: Long = WAIT_MILLIS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
