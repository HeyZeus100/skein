// `E1.I11` (skein-4je) AC(c): `LlamaLogRedactor.redact("prompt: hello world")
// == "prompt: <redacted 11 chars>"`, plus the level-gating half of the
// contract (drop <= INFO, redact at WARN/ERROR) that `forward` implements
// ahead of the native `llama_log_set` wiring landing with skein-ca2/3aw.

package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaLogRedactorTest {
    @Test
    fun redact_replaces_content_after_a_single_prompt_marker() {
        // Act
        val result = LlamaLogRedactor.redact("prompt: hello world")

        // Assert
        assertEquals("prompt: <redacted 11 chars>", result)
    }

    @Test
    fun redact_replaces_content_after_a_single_text_marker() {
        // Act
        val result = LlamaLogRedactor.redact("text: the quick brown fox")

        // Assert
        assertEquals("text: <redacted 19 chars>", result)
    }

    @Test
    fun redact_handles_multiple_markers_in_one_message() {
        // Act
        val result = LlamaLogRedactor.redact("prompt: hello world text: goodbye")

        // Assert
        assertEquals("prompt: <redacted 12 chars>text: <redacted 7 chars>", result)
    }

    @Test
    fun redact_preserves_a_prefix_before_the_first_marker() {
        // Act
        val result = LlamaLogRedactor.redact("decode: prompt: hi")

        // Assert
        assertEquals("decode: prompt: <redacted 2 chars>", result)
    }

    @Test
    fun redact_returns_the_message_unchanged_when_no_marker_is_present() {
        // Act
        val result = LlamaLogRedactor.redact("model loaded in 42ms")

        // Assert
        assertEquals("model loaded in 42ms", result)
    }

    @Test
    fun redact_handles_a_marker_with_no_trailing_content() {
        // Act — no separating space in the input, so none is synthesized in
        // the output either; only what followed the marker is replaced.
        val result = LlamaLogRedactor.redact("prompt:")

        // Assert
        assertEquals("prompt:<redacted 0 chars>", result)
    }

    @Test
    fun redact_handles_a_marker_with_no_separating_space() {
        // Act
        val result = LlamaLogRedactor.redact("prompt:hi")

        // Assert
        assertEquals("prompt:<redacted 2 chars>", result)
    }

    @Test
    fun forward_drops_debug_level_messages() {
        // Act
        val result = LlamaLogRedactor.forward(LlamaLogLevel.DEBUG, "prompt: hello world")

        // Assert
        assertNull(result)
    }

    @Test
    fun forward_drops_info_level_messages() {
        // Act
        val result = LlamaLogRedactor.forward(LlamaLogLevel.INFO, "prompt: hello world")

        // Assert
        assertNull(result)
    }

    @Test
    fun forward_redacts_and_returns_warn_level_messages() {
        // Act
        val result = LlamaLogRedactor.forward(LlamaLogLevel.WARN, "prompt: hello world")

        // Assert
        assertEquals("prompt: <redacted 11 chars>", result)
    }

    @Test
    fun forward_redacts_and_returns_error_level_messages() {
        // Act
        val result = LlamaLogRedactor.forward(LlamaLogLevel.ERROR, "prompt: hello world")

        // Assert
        assertEquals("prompt: <redacted 11 chars>", result)
    }

    @Test
    fun forward_returns_non_sensitive_warn_messages_unchanged() {
        // Act
        val result = LlamaLogRedactor.forward(LlamaLogLevel.WARN, "context shifted by 128 tokens")

        // Assert
        assertEquals("context shifted by 128 tokens", result)
    }
}
