package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeInferenceEngineTest {
    @Test
    fun `stream emits the scripted chunks for a known prompt`() =
        runTest {
            val engine = scriptedEngine("hello" to listOf("hi", "there"))

            val chunks = engine.stream("hello").toList()

            assertThat(chunks).containsExactly("hi", "there").inOrder()
        }

    @Test
    fun `stream falls back to the default response for an unscripted prompt`() =
        runTest {
            val engine = scriptedEngine("hello" to listOf("hi"))

            val chunks = engine.stream("unscripted").toList()

            assertThat(chunks).containsExactly("(no scripted response)")
        }

    @Test
    fun `load increments the call count and always succeeds`() {
        val engine = FakeInferenceEngine()

        val result = engine.load()

        assertThat(result.isSuccess).isTrue()
        assertThat(engine.loadCallCount).isEqualTo(1)
    }

    @Test
    fun `cancel is observable`() {
        val engine = FakeInferenceEngine()

        engine.cancel()

        assertThat(engine.cancelled).isTrue()
    }
}
