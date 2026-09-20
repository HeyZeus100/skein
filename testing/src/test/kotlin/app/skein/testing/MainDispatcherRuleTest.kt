package app.skein.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MainDispatcherRuleTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `Dispatchers Main is backed by the rule's test dispatcher during a test`() {
        assertThat(Dispatchers.Main.immediate).isNotNull()
    }

    @Test
    fun `code launched on Dispatchers Main runs on the test dispatcher`() =
        runTest {
            var ran = false

            launch(Dispatchers.Main) { ran = true }

            assertThat(ran).isTrue()
        }
}
