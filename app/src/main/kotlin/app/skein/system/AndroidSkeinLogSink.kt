package app.skein.system

import android.util.Log
import app.skein.core.model.InferenceException
import app.skein.core.model.SkeinLog
import app.skein.core.model.sanitizeDiagnostic

/**
 * The only place `android.util.Log` may be called (the `NoRawLogging` guard's
 * one other exemption alongside `SkeinLog.kt` itself — see its KDoc). Installed
 * by [app.skein.SkeinApplication.onCreate] as [SkeinLog.sink] in every process
 * this `Application` runs in (`:app`, `:inference`, `:embedder` — spec §2.6),
 * so every process gets real logcat output through the one facade.
 *
 * `skein-3yal`: `SkeinLog`'s own content-marker check (`isSensitiveContent`,
 * `SkeinLog.kt`) only ever inspects the log call's `message` string — it never
 * sees `throwable`. That matters because `throwable` can be an
 * [InferenceException] whose `detail` came, via `IInferenceCallback.onError`,
 * from the UNTRUSTED isolated `:inference`/`:embedder` process (design spec
 * §9's threat model treats that process as compromisable), and
 * `android.util.Log.{d,i,w,e}(tag, msg, tr)` renders `tr` — its message and
 * full stack trace — with no redaction of its own. This sink therefore never
 * hands `throwable` to `android.util.Log` unchanged; see
 * [sanitizeThrowableForLogging].
 */
object AndroidSkeinLogSink : SkeinLog.Sink {
    override fun log(
        level: SkeinLog.Level,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val safeThrowable = throwable?.let(::sanitizeThrowableForLogging)
        when (level) {
            SkeinLog.Level.DEBUG -> Log.d(tag, message, safeThrowable)
            SkeinLog.Level.INFO -> Log.i(tag, message, safeThrowable)
            SkeinLog.Level.WARN -> Log.w(tag, message, safeThrowable)
            SkeinLog.Level.ERROR -> Log.e(tag, message, safeThrowable)
        }
    }

    /**
     * Builds a throwable safe to hand to `android.util.Log`: same runtime
     * class visible through [Throwable.stackTrace] frames (never
     * attacker-controlled — class/method/line info from this build's own
     * code) and the same [Throwable.stackTrace] array, but never the original
     * [Throwable.message] verbatim:
     *
     * - An [InferenceException]'s message is dropped entirely in favor of the
     *   subclass's simple name (e.g. `"Internal"`, `"ModelInUse"`) — even
     *   though `ErrorCodes.toException` already runs the wire `message`
     *   through [sanitizeDiagnostic] before building the exception, this is a
     *   second, independent layer: it does not rely on every construction
     *   site of [InferenceException] having sanitized first, and it keeps
     *   even a short, clean-looking detail (which could still be a fragment
     *   of note content an attacker chose to echo back) out of logcat
     *   entirely, not just out of exception messages.
     * - Any other `Throwable`'s message is capped and stripped via
     *   [sanitizeDiagnostic] rather than dropped, since ordinary (non-IPC)
     *   exceptions in this codebase are not expected to carry attacker text
     *   and the message is useful for debugging.
     *
     * The cause chain is dropped rather than sanitized recursively: an
     * attacker-constructed throwable could otherwise carry an arbitrarily
     * long or deep cause chain, and the immediate message plus preserved
     * stack trace is enough for diagnosis.
     */
    private fun sanitizeThrowableForLogging(throwable: Throwable): Throwable {
        val safeMessage =
            if (throwable is InferenceException) {
                throwable.javaClass.simpleName
            } else {
                throwable.message?.let(::sanitizeDiagnostic)
            }
        return Throwable(safeMessage).apply { stackTrace = throwable.stackTrace }
    }
}
