package app.skein.system

import app.skein.core.model.InferenceException
import app.skein.core.model.SkeinLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * skein-3yal: a compromised isolated `:inference`/`:embedder` process can
 * route arbitrary text into an `InferenceException`'s `detail` (via
 * `IInferenceCallback.onError`) or, in principle, into any other
 * `Throwable`'s message. `SkeinLog`'s own content-marker check
 * (`isSensitiveContent`, `SkeinLog.kt`) only ever inspects the log call's
 * `message` string parameter — never a `Throwable`'s message or stack trace
 * — so a hostile `Throwable` handed to `SkeinLog.w`/`.e` must be neutralised
 * here, at the one bridge to `android.util.Log`, or it reaches logcat raw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSkeinLogSinkTest {
    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    private fun loggedThrowableMessages(): List<String?> = ShadowLog.getLogs().map { it.throwable?.message }

    @Test
    fun `an InferenceException's hostile detail never reaches the sink raw`() {
        val hostileDetail = "leaked-document-body: the secret note contents\nsecond line"
        val exception = InferenceException.Internal(hostileDetail)

        AndroidSkeinLogSink.log(SkeinLog.Level.ERROR, "test", "generation failed", exception)

        val logged = loggedThrowableMessages()
        assertTrue("expected a log entry", logged.isNotEmpty())
        logged.forEach { message ->
            assertFalse(
                "hostile detail leaked into the logged throwable message: $message",
                message?.contains("leaked-document-body") == true,
            )
            assertFalse(
                "hostile detail leaked into the logged throwable message: $message",
                message?.contains("secret note contents") == true,
            )
        }
    }

    @Test
    fun `an InferenceException logs its class name, not its message`() {
        val exception = InferenceException.ModelInUse("tryLock returned null")

        AndroidSkeinLogSink.log(SkeinLog.Level.WARN, "test", "warn", exception)

        val logged = ShadowLog.getLogs().last()
        assertEquals(InferenceException.ModelInUse::class.java.simpleName, logged.throwable?.message)
    }

    @Test
    fun `a hostile plain throwable message is capped and stripped, not logged raw`() {
        val hostile = "\u0000\u001B[31m" + "x".repeat(10_000) + "\nsecond line"
        val throwable = RuntimeException(hostile)

        AndroidSkeinLogSink.log(SkeinLog.Level.ERROR, "test", "failure", throwable)

        val lastLog = ShadowLog.getLogs().last()
        val message = lastLog.throwable?.message
        assertTrue("expected a sanitized message", message != null)
        requireNotNull(message)
        assertTrue("must be capped", message.length <= 200)
        assertFalse("must not contain NUL", message.contains('\u0000'))
        assertFalse("must not contain ESC", message.contains('\u001B'))
        assertFalse("must not contain a newline", message.contains('\n'))
    }

    @Test
    fun `a throwable's stack trace is preserved for debugging`() {
        val throwable = RuntimeException("boom")

        AndroidSkeinLogSink.log(SkeinLog.Level.ERROR, "test", "failure", throwable)

        val logged = ShadowLog.getLogs().last().throwable
        assertTrue(logged?.stackTrace?.contentEquals(throwable.stackTrace) == true)
    }

    @Test
    fun `a null throwable is passed through as null`() {
        AndroidSkeinLogSink.log(SkeinLog.Level.INFO, "test", "no throwable here", null)

        val logged = ShadowLog.getLogs().last()
        assertEquals(null, logged.throwable)
    }

    @Test
    fun `the message string itself is still logged unchanged`() {
        AndroidSkeinLogSink.log(SkeinLog.Level.DEBUG, "test", "hello", null)

        val logged = ShadowLog.getLogs().last()
        assertEquals("hello", logged.msg)
    }
}
