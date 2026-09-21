package app.skein.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsViewModel] is a plain state holder (like `NavState`/`TabsState`
 * in `:feature:shell`), not an `androidx.lifecycle.ViewModel` — it never
 * touches `SecurityPrefs` directly (see the class doc for why
 * `:feature:settings` can't depend on `:app`), so a fake flow/setter is
 * enough to exercise it without Robolectric.
 *
 * Every instance is built with `backgroundScope` (not `this`/the test's own
 * coroutine scope): [SettingsViewModel]'s `init` block launches a collector
 * that runs for the object's whole lifetime and never completes on its own
 * (a real `StateFlow` never completes), so `runTest` would otherwise fail
 * every test with `UncompletedCoroutinesError` at the end of the test body.
 * `backgroundScope` is `kotlinx-coroutines-test`'s scope for exactly this
 * "background work that outlives the test body" shape — it shares the same
 * `TestScheduler`/virtual time as `this`, and is cancelled automatically
 * when the test ends.
 */
class SettingsViewModelTest {
    @Test
    fun `initial value reflects the source flow's current emission`() =
        runTest {
            val source = MutableStateFlow(true)
            val viewModel =
                SettingsViewModel(scope = backgroundScope, flagSecureEnabledFlow = source, onSetFlagSecureEnabled = {})
            testScheduler.runCurrent()

            assertTrue(viewModel.flagSecureEnabled)
        }

    @Test
    fun `defaults to secure before the source flow's first emission is collected`() =
        runTest {
            val source = MutableStateFlow(false)

            val viewModel =
                SettingsViewModel(scope = backgroundScope, flagSecureEnabledFlow = source, onSetFlagSecureEnabled = {})

            // No `runCurrent()`/suspension yet: the background collector hasn't run.
            assertTrue(viewModel.flagSecureEnabled)
        }

    @Test
    fun `tracks later emissions from the source flow`() =
        runTest {
            val source = MutableStateFlow(true)
            val viewModel =
                SettingsViewModel(scope = backgroundScope, flagSecureEnabledFlow = source, onSetFlagSecureEnabled = {})
            testScheduler.runCurrent()

            source.value = false
            testScheduler.runCurrent()

            assertFalse(viewModel.flagSecureEnabled)
        }

    @Test
    fun `setFlagSecureEnabled updates state optimistically`() =
        runTest {
            val source = MutableStateFlow(true)
            val viewModel =
                SettingsViewModel(scope = backgroundScope, flagSecureEnabledFlow = source, onSetFlagSecureEnabled = {})
            testScheduler.runCurrent()

            viewModel.setFlagSecureEnabled(false)

            assertFalse(viewModel.flagSecureEnabled)
        }

    @Test
    fun `setFlagSecureEnabled persists the new value via the setter`() =
        runTest {
            val persisted = mutableListOf<Boolean>()
            val source = MutableStateFlow(true)
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = source,
                    onSetFlagSecureEnabled = { persisted += it },
                )
            testScheduler.runCurrent()

            viewModel.setFlagSecureEnabled(false)
            testScheduler.runCurrent()

            assertEquals(listOf(false), persisted)
        }

    // ---- E3.I14 (skein-up0) — lock policy settings ------------------------

    @Test
    fun `idleTimeoutMinutes defaults to 5 before the source flow's first emission`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    idleTimeoutMinutesFlow = MutableStateFlow(30),
                )

            assertEquals(5, viewModel.idleTimeoutMinutes)
        }

    @Test
    fun `idleTimeoutMinutes reflects the source flow's current emission`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    idleTimeoutMinutesFlow = MutableStateFlow(30),
                )
            testScheduler.runCurrent()

            assertEquals(30, viewModel.idleTimeoutMinutes)
        }

    @Test
    fun `setIdleTimeoutMinutes updates state optimistically and persists`() =
        runTest {
            val persisted = mutableListOf<Int>()
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    idleTimeoutMinutesFlow = MutableStateFlow(5),
                    onSetIdleTimeoutMinutes = { persisted += it },
                )
            testScheduler.runCurrent()

            viewModel.setIdleTimeoutMinutes(15)
            testScheduler.runCurrent()

            assertEquals(15, viewModel.idleTimeoutMinutes)
            assertEquals(listOf(15), persisted)
        }

    @Test
    fun `lockOnScreenOff defaults to true before the source flow's first emission`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    lockOnScreenOffFlow = MutableStateFlow(false),
                )

            assertTrue(viewModel.lockOnScreenOff)
        }

    @Test
    fun `setLockOnScreenOff updates state optimistically and persists`() =
        runTest {
            val persisted = mutableListOf<Boolean>()
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    lockOnScreenOffFlow = MutableStateFlow(true),
                    onSetLockOnScreenOff = { persisted += it },
                )
            testScheduler.runCurrent()

            viewModel.setLockOnScreenOff(false)
            testScheduler.runCurrent()

            assertFalse(viewModel.lockOnScreenOff)
            assertEquals(listOf(false), persisted)
        }

    @Test
    fun `lockOnBackground defaults to false before the source flow's first emission`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    lockOnBackgroundFlow = MutableStateFlow(true),
                )

            assertFalse(viewModel.lockOnBackground)
        }

    @Test
    fun `setLockOnBackground updates state optimistically and persists`() =
        runTest {
            val persisted = mutableListOf<Boolean>()
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    lockOnBackgroundFlow = MutableStateFlow(false),
                    onSetLockOnBackground = { persisted += it },
                )
            testScheduler.runCurrent()

            viewModel.setLockOnBackground(true)
            testScheduler.runCurrent()

            assertTrue(viewModel.lockOnBackground)
            assertEquals(listOf(true), persisted)
        }

    @Test
    fun `strongBoxUnavailableFallback reflects the source flow and has no setter`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    scope = backgroundScope,
                    flagSecureEnabledFlow = MutableStateFlow(true),
                    onSetFlagSecureEnabled = {},
                    strongBoxUnavailableFallbackFlow = MutableStateFlow(true),
                )
            testScheduler.runCurrent()

            assertTrue(viewModel.strongBoxUnavailableFallback)
        }
}
