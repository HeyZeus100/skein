// `E1.I11` (skein-4je): `SkeinLog` routes every call through the current
// `sink` (no-op by default, so JVM tests never touch `android.util.Log`),
// gates the test-only observation hook behind `testHook`, and substitutes a
// fixed placeholder for any message a content marker flags as sensitive —
// spec §9's "never log document/prompt/chunk/embedding content, at any
// level, in any build" enforced defensively, not just by convention.

package app.skein.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeinLogTest {
    private class RecordingSink : SkeinLog.Sink {
        data class Call(
            val level: SkeinLog.Level,
            val tag: String,
            val message: String,
            val throwable: Throwable?,
        )

        val calls = mutableListOf<Call>()

        override fun log(
            level: SkeinLog.Level,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            calls += Call(level, tag, message, throwable)
        }
    }

    private val defaultSink = SkeinLog.sink
    private val defaultHook = SkeinLog.testHook

    @After
    fun tearDown() {
        SkeinLog.sink = defaultSink
        SkeinLog.testHook = defaultHook
    }

    @Test
    fun d_routes_to_the_installed_sink_at_debug_level() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink

        // Act
        SkeinLog.d("Vault", "opened")

        // Assert
        assertEquals(1, sink.calls.size)
        assertEquals(SkeinLog.Level.DEBUG, sink.calls[0].level)
        assertEquals("Vault", sink.calls[0].tag)
        assertEquals("opened", sink.calls[0].message)
    }

    @Test
    fun i_routes_to_the_installed_sink_at_info_level() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink

        // Act
        SkeinLog.i("Vault", "unlocked")

        // Assert
        assertEquals(SkeinLog.Level.INFO, sink.calls[0].level)
    }

    @Test
    fun w_routes_to_the_installed_sink_with_an_optional_throwable() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink
        val thrown = IllegalStateException("boom")

        // Act
        SkeinLog.w("Vault", "retrying", thrown)

        // Assert
        assertEquals(SkeinLog.Level.WARN, sink.calls[0].level)
        assertEquals(thrown, sink.calls[0].throwable)
    }

    @Test
    fun e_routes_to_the_installed_sink_at_error_level() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink

        // Act
        SkeinLog.e("Vault", "failed")

        // Assert
        assertEquals(SkeinLog.Level.ERROR, sink.calls[0].level)
    }

    @Test
    fun a_sink_that_does_nothing_does_not_throw() {
        // Arrange
        SkeinLog.sink =
            object : SkeinLog.Sink {
                override fun log(
                    level: SkeinLog.Level,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) = Unit
            }

        // Act / Assert — must not throw.
        SkeinLog.d("Vault", "no-op sink")
    }

    @Test
    fun test_hook_receives_tag_message_and_sensitivity_for_every_call() {
        // Arrange
        SkeinLog.sink = RecordingSink()
        var observed: Triple<String, String, Boolean>? = null
        SkeinLog.testHook = { tag, message, sensitive -> observed = Triple(tag, message, sensitive) }

        // Act
        SkeinLog.i("Vault", "opened")

        // Assert
        assertEquals(Triple("Vault", "opened", false), observed)
    }

    @Test
    fun test_hook_is_never_invoked_when_null() {
        // Arrange
        SkeinLog.sink = RecordingSink()
        SkeinLog.testHook = null

        // Act / Assert — must not throw.
        SkeinLog.i("Vault", "opened")
    }

    @Test
    fun a_message_with_raw_content_after_a_marker_is_flagged_sensitive_to_the_test_hook() {
        // Arrange
        SkeinLog.sink = RecordingSink()
        var sensitive: Boolean? = null
        SkeinLog.testHook = { _, _, isSensitive -> sensitive = isSensitive }

        // Act
        SkeinLog.e("Inference", "prompt: hello world")

        // Assert
        assertTrue(sensitive == true)
    }

    @Test
    fun a_message_with_no_marker_is_not_flagged_sensitive() {
        // Arrange
        SkeinLog.sink = RecordingSink()
        var sensitive: Boolean? = null
        SkeinLog.testHook = { _, _, isSensitive -> sensitive = isSensitive }

        // Act
        SkeinLog.i("Inference", "model loaded")

        // Assert
        assertFalse(sensitive == true)
    }

    @Test
    fun a_message_flagged_sensitive_reaches_the_sink_as_the_redacted_placeholder() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink

        // Act
        SkeinLog.e("Inference", "prompt: hello world")

        // Assert
        assertEquals(REDACTED_PLACEHOLDER, sink.calls[0].message)
    }

    @Test
    fun an_already_redacted_marker_message_is_not_flagged_sensitive_again() {
        // Arrange
        val sink = RecordingSink()
        SkeinLog.sink = sink

        // Act
        SkeinLog.w("Inference", "prompt: <redacted 11 chars>")

        // Assert — passed through unchanged, not double-redacted.
        assertEquals("prompt: <redacted 11 chars>", sink.calls[0].message)
    }

    @Test
    fun is_sensitive_content_is_case_insensitive_on_the_marker() {
        assertTrue(isSensitiveContent("Prompt: hello"))
    }

    @Test
    fun is_sensitive_content_ignores_a_marker_with_no_content_after_it() {
        assertFalse(isSensitiveContent("prompt:"))
        assertFalse(isSensitiveContent("prompt:   "))
    }
}
