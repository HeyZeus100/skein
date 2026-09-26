// skein-xtov.23.19 (UT-5): self-test for RecompositionCounter, including the
// documented pitfall (a content lambda whose own state read Compose can
// re-run independently of Track's call site) so the KDoc's caveat is
// verified, not just asserted in prose.
package app.skein.testing.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecompositionCounterTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `Track counts the first composition`() {
        val counter = RecompositionCounter()

        composeRule.setContent {
            counter.Track { BasicText("static") }
        }

        assertThat(counter.count).isEqualTo(1)
    }

    @Test
    fun `Track counts every recomposition when the caller forces its own call site to re-run`() {
        val counter = RecompositionCounter()
        lateinit var bump: () -> Unit

        composeRule.setContent {
            var n by remember { mutableStateOf(0) }
            bump = { n++ }
            // key(n) forces THIS call site — including Track's SideEffect,
            // not just the content below it — to re-run whenever n changes.
            key(n) {
                counter.Track { BasicText("n=$n") }
            }
        }

        repeat(3) {
            composeRule.runOnIdle { bump() }
        }
        composeRule.waitForIdle()

        assertThat(counter.count).isEqualTo(4) // 1 initial + 3 recompositions
    }

    @Test
    fun `Track undercounts when only the content lambda reads the changing state — the documented pitfall`() {
        val counter = RecompositionCounter()
        lateinit var bump: () -> Unit

        composeRule.setContent {
            var n by remember { mutableStateOf(0) }
            bump = { n++ }
            // No key(n): Track's own scope reads nothing that changes, so
            // Compose can (and does) skip it even though the nested
            // content() call below re-runs and the screen updates fine.
            // This is exactly the shape of miscount Track's KDoc warns about.
            counter.Track { BasicText("n=$n") }
        }

        repeat(3) {
            composeRule.runOnIdle { bump() }
        }
        composeRule.waitForIdle()

        assertThat(counter.count).isEqualTo(1)
    }
}
