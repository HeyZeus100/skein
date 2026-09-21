package app.skein.system

import android.util.Log
import app.skein.core.model.SkeinLog

/**
 * The only place `android.util.Log` may be called (the `NoRawLogging` guard's
 * one other exemption alongside `SkeinLog.kt` itself — see its KDoc). Installed
 * by [app.skein.SkeinApplication.onCreate] as [SkeinLog.sink] in every process
 * this `Application` runs in (`:app`, `:inference`, `:embedder` — spec §2.6),
 * so every process gets real logcat output through the one facade.
 */
object AndroidSkeinLogSink : SkeinLog.Sink {
    override fun log(
        level: SkeinLog.Level,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            SkeinLog.Level.DEBUG -> Log.d(tag, message, throwable)
            SkeinLog.Level.INFO -> Log.i(tag, message, throwable)
            SkeinLog.Level.WARN -> Log.w(tag, message, throwable)
            SkeinLog.Level.ERROR -> Log.e(tag, message, throwable)
        }
    }
}
