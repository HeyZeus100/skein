package app.skein.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class SkeinLogCaptureTest {
    /** Runs [rule] around a [body] the way JUnit would, without needing a nested test runner. */
    private fun runUnder(
        rule: SkeinLogCaptureRule,
        body: () -> Unit,
    ) {
        val statement =
            object : Statement() {
                override fun evaluate() = body()
            }
        rule.apply(statement, Description.EMPTY).evaluate()
    }

    @Test
    fun `a non-sensitive log entry does not fail the test`() {
        val rule = SkeinLogCaptureRule()

        runUnder(rule) {
            SkeinLogCapture.record(tag = "Vault", message = "opened", isSensitive = false)
        }

        assertThat(rule.captured()).hasSize(1)
    }

    @Test
    fun `a sensitive log entry fails the test`() {
        val rule = SkeinLogCaptureRule()

        val error =
            assertThrows(AssertionError::class.java) {
                runUnder(rule) {
                    SkeinLogCapture.record(tag = "Vault", message = "sha256=deadbeef", isSensitive = true)
                }
            }

        assertThat(error.message).contains("sensitive")
    }

    @Test
    fun `the hook is a no-op with no rule installed`() {
        // Must not throw even though nothing is listening.
        SkeinLogCapture.record(tag = "Vault", message = "no rule active", isSensitive = true)
    }

    @Test
    fun `the previous hook is restored after the rule finishes`() {
        var outerCaptured: SkeinLogCapture.Entry? = null
        SkeinLogCapture.hook = { outerCaptured = it }

        runUnder(SkeinLogCaptureRule()) {
            SkeinLogCapture.record(tag = "Inner", message = "inside the rule", isSensitive = false)
        }
        SkeinLogCapture.record(tag = "Outer", message = "after the rule", isSensitive = false)

        assertThat(outerCaptured?.tag).isEqualTo("Outer")
        SkeinLogCapture.hook = null
    }

    private fun <T : Throwable> assertThrows(
        type: Class<T>,
        block: () -> Unit,
    ): T {
        try {
            block()
        } catch (t: Throwable) {
            if (type.isInstance(t)) {
                @Suppress("UNCHECKED_CAST")
                return t as T
            }
            throw t
        }
        throw AssertionError("expected ${type.name} to be thrown, but nothing was")
    }
}
