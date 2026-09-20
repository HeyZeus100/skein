package app.skein.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only capture point for the production `SkeinLog` facade (spec §9:
 * nothing sensitive may reach logcat). `SkeinLog` is not implemented yet
 * (it lands with a later issue); when it does, its internal `isSensitive`
 * hook must call [SkeinLogCapture.record] so tests can observe what would
 * have been logged without needing `android.util.Log`/Robolectric.
 *
 * [record] is a no-op ([hook] is `null`) outside of a test using
 * [SkeinLogCaptureRule], so production code pays no cost for calling it.
 */
object SkeinLogCapture {
    /** One would-be log call, as `SkeinLog` would have emitted it. */
    data class Entry(
        val tag: String,
        val message: String,
        val isSensitive: Boolean,
    )

    @Volatile
    internal var hook: ((Entry) -> Unit)? = null

    /** Called by `SkeinLog`'s test hook for every log call. No-op if no rule is installed. */
    fun record(
        tag: String,
        message: String,
        isSensitive: Boolean,
    ) {
        hook?.invoke(Entry(tag, message, isSensitive))
    }
}

/**
 * JUnit rule that installs [SkeinLogCapture]'s hook for the duration of a
 * test and fails the test if any captured [SkeinLogCapture.Entry] is tagged
 * [SkeinLogCapture.Entry.isSensitive]. Nothing sensitive should ever be
 * logged, in any code path, so this rule has no allowlist escape hatch.
 *
 * ```kotlin
 * @get:Rule val logCapture = SkeinLogCaptureRule()
 * ```
 */
class SkeinLogCaptureRule : TestRule {
    private val entries = CopyOnWriteArrayList<SkeinLogCapture.Entry>()

    /** All entries captured so far in the running test (for positive assertions). */
    fun captured(): List<SkeinLogCapture.Entry> = entries.toList()

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val previousHook = SkeinLogCapture.hook
                SkeinLogCapture.hook = { entries.add(it) }
                try {
                    base.evaluate()
                } finally {
                    SkeinLogCapture.hook = previousHook
                }
                val sensitive = entries.filter { it.isSensitive }
                if (sensitive.isNotEmpty()) {
                    throw AssertionError(
                        "SkeinLogCaptureRule: ${sensitive.size} sensitive log entr" +
                            (if (sensitive.size == 1) "y was" else "ies were") +
                            " emitted during this test: $sensitive",
                    )
                }
            }
        }
}
