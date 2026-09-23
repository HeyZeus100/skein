// skein-3yal — `sanitizeDiagnostic` is the one place `ErrorCodes.toException`
// (`:core:ipc`) and `AndroidSkeinLogSink` (`:app`) both neutralise the
// `IInferenceCallback.onError` wire `message` before it can reach an
// exception message or a log line. The wire `message` originates in the
// UNTRUSTED isolated `:inference`/`:embedder` process (design spec §9's
// threat model: a compromised service can send an arbitrary, unbounded
// string), so this is a security boundary, not a cosmetic cleanup — these
// tests pin its exact behavior down.

package app.skein.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizationTest {
    @Test
    fun `blank input stays blank`() {
        assertEquals("", sanitizeDiagnostic(""))
    }

    @Test
    fun `clean short input passes through unchanged`() {
        assertEquals("role=tokenizer", sanitizeDiagnostic("role=tokenizer"))
    }

    @Test
    fun `input longer than the cap is truncated to the cap`() {
        val raw = "a".repeat(10_000)

        val sanitized = sanitizeDiagnostic(raw)

        assertEquals(MAX_SANITIZED_DIAGNOSTIC_LENGTH, sanitized.length)
        assertEquals("a".repeat(MAX_SANITIZED_DIAGNOSTIC_LENGTH), sanitized)
    }

    @Test
    fun `a 10 KB hostile message with newlines, ANSI escapes and NUL is capped and single-line`() {
        val hostile =
            buildString {
                repeat(200) {
                    append("evil-line-$it\n")
                    append("\u001B[31mred\u001B[0m")
                    append("\u0000")
                }
            }
        check(hostile.length > 5_000) { "fixture must exceed a few KB to exercise the cap" }

        val sanitized = sanitizeDiagnostic(hostile)

        assertTrue(sanitized.length <= MAX_SANITIZED_DIAGNOSTIC_LENGTH)
        assertFalse("must not contain a newline", sanitized.contains('\n'))
        assertFalse("must not contain a carriage return", sanitized.contains('\r'))
        assertFalse("must not contain the ANSI escape introducer", sanitized.contains('\u001B'))
        assertFalse("must not contain NUL", sanitized.contains('\u0000'))
        sanitized.forEach { assertFalse("no ISO control character: $it", it.isISOControl()) }
    }

    @Test
    fun `every C0 and C1 control character is stripped`() {
        val controls = ((0x00..0x1F) + 0x7F + (0x80..0x9F)).map { it.toChar() }
        val raw = "before" + controls.joinToString(separator = "") + "after"

        val sanitized = sanitizeDiagnostic(raw)

        assertEquals("beforeafter", sanitized)
    }
}
