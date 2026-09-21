package app.skein.core.model

/**
 * The one logging facade allowed anywhere in this codebase (spec §9; enforced
 * by the `NoRawLogging` guard in `build-logic/guards`, which errors on raw
 * `android.util.Log`/`println` calls in every module except this file).
 *
 * Lives in `:core:model` (pure Kotlin/JVM, per `E1.I2`'s isolation guard) so
 * every process — `:app`, `:inference-service`, `:embedder-service` — can
 * call it without pulling in `android.util.Log` directly. [sink] defaults to
 * a no-op so JVM unit tests and tooling never touch `android.util.Log`
 * (which would crash under plain JUnit without Robolectric); `:app` installs
 * an `android.util.Log`-backed sink from `SkeinApplication.onCreate`.
 *
 * [d]/[i] are compiled out of release builds via R8's `-assumenosideeffects`
 * (`app/proguard-rules.pro`) — AC(a) of `E1.I11`/skein-4je. [w]/[e] are not
 * stripped (they carry real diagnostics), but every level runs the same
 * [isSensitiveContent] check and substitutes [REDACTED_PLACEHOLDER] for the
 * message if it fires: spec §9 forbids logging document/prompt/chunk/
 * embedding content *at any level, in any build*, not just at d/i.
 */
object SkeinLog {
    /** Where a log call ends up. [NoOpSink] on the JVM; Android installs its own. */
    fun interface Sink {
        fun log(
            level: Level,
            tag: String,
            message: String,
            throwable: Throwable?,
        )
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: Sink = NoOpSink

    /**
     * Test-only observation hook, set by `:testing`'s `SkeinLogCaptureRule`
     * for the duration of a test (`:testing` depends on `:core:model`, never
     * the reverse, so the wiring lives on that side). Null outside of a test
     * using that rule, which costs production code one volatile read and a
     * null check per log call — see `SkeinLogCapture`'s KDoc for the
     * contract this fulfils.
     */
    @Volatile
    var testHook: ((tag: String, message: String, isSensitive: Boolean) -> Unit)? = null

    // @JvmStatic: compiles d/i to plain `public static void` methods (rather
    // than instance methods on the INSTANCE singleton), which is what
    // `app/proguard-rules.pro`'s `-assumenosideeffects class ...SkeinLog`
    // member spec matches — AC(a) of E1.I11/skein-4je.
    @JvmStatic
    fun d(
        tag: String,
        message: String,
    ) = log(Level.DEBUG, tag, message, null)

    @JvmStatic
    fun i(
        tag: String,
        message: String,
    ) = log(Level.INFO, tag, message, null)

    @JvmStatic
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = log(Level.WARN, tag, message, throwable)

    @JvmStatic
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = log(Level.ERROR, tag, message, throwable)

    private fun log(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val sensitive = isSensitiveContent(message)
        testHook?.invoke(tag, message, sensitive)
        sink.log(level, tag, if (sensitive) REDACTED_PLACEHOLDER else message, throwable)
    }

    /** No-op default: the JVM (tests, tooling) never touches `android.util.Log`. */
    private object NoOpSink : Sink {
        override fun log(
            level: Level,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}

/** What a [SkeinLog] call becomes when [isSensitiveContent] flags it. */
internal const val REDACTED_PLACEHOLDER = "<redacted: content marker present>"

/**
 * Defense-in-depth heuristic for spec §9 ("never log document, prompt,
 * chunk, or embedding content, at any level, in any build"): a message is
 * sensitive if one of [CONTENT_MARKERS] is followed by non-blank text that
 * doesn't already look redacted (so [LlamaLogRedactor]'s own
 * `"prompt: <redacted N chars>"` output is not flagged a second time here).
 * This is a safety net for accidental raw content reaching [SkeinLog], not
 * the primary control — callers must never build such a message in the
 * first place.
 */
internal fun isSensitiveContent(message: String): Boolean {
    val lower = message.lowercase()
    return CONTENT_MARKERS.any { marker ->
        val idx = lower.indexOf(marker)
        if (idx < 0) {
            false
        } else {
            val rest = lower.substring(idx + marker.length).trimStart()
            rest.isNotEmpty() && !rest.startsWith("<redacted")
        }
    }
}

private val CONTENT_MARKERS = listOf("prompt:", "text:", "chunk:", "document:", "embedding:")
