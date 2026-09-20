package app.skein.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a [TestDispatcher] for the duration of a test,
 * so `viewModelScope`/`Dispatchers.Main`-bound production code can run
 * deterministically under `kotlinx-coroutines-test` without an Android
 * Looper.
 *
 * ```kotlin
 * @get:Rule val mainDispatcherRule = MainDispatcherRule()
 * ```
 *
 * Defaults to an [UnconfinedTestDispatcher] (not tied to a `runTest`
 * scope's own scheduler) so `launch(Dispatchers.Main) { ... }` runs eagerly
 * without an explicit `advanceUntilIdle()`. Pure JVM: this is
 * `kotlinx-coroutines-test`'s `Dispatchers.setMain`, which does not require
 * Android's `Dispatchers.Main` implementation to be installed (unlike
 * `Dispatchers.Main` on a real Android runtime).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
