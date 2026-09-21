package app.skein.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * `NoRawLogging` (spec §9 / `E1.I11`, skein-4je): fails the build if any
 * `main` Kotlin source file — other than one in [allowlistedFileNames] —
 * calls `android.util.Log.{v,d,i,w,e,wtf}(...)` or `println(...)`.
 * `SkeinLog` (`:core:model`) is the one facade allowed to reach logcat, so
 * every other call site must go through it.
 *
 * Implemented as a Gradle task, not an Android Lint `Detector`, for the same
 * reason `ManifestGuardTask`/`DependencyGuardTask`/`IsolationGuardTask`
 * (this module) are: several scanned modules (`:core:model`, `:testing`,
 * `:core:agent`) are pure Kotlin/JVM with no Android Gradle plugin applied,
 * so a `lintChecks(...)`-wired `Detector` would never run on them at all —
 * `com.android.tools.lint` only executes for Android library/application
 * modules. A plain source-text scan runs uniformly everywhere `check`
 * already runs, matching this repo's existing guard-task precedent instead
 * of adding a second, narrower enforcement mechanism.
 *
 * Only `src/main/kotlin` is scanned (see [NoRawLoggingGuardPlugin]): test
 * and `androidTest` sources are not, deliberately — this repo's existing
 * tests use `println` for developer-visible timing output
 * (`TokenizerThroughputTest`, `UnigramTokenizerTest`) and `android.util.Log`
 * directly in one `androidTest` acceptance test
 * (`IndexStoreImplAcceptanceTest`), neither of which reaches a release
 * build or a real device log a user would see; `E1.I11`'s brief explicitly
 * allows allowlisting tests in the rule design.
 *
 * The scanner blanks out string/char literal and comment contents (keeping
 * line numbers intact) before matching, so a call name that only appears
 * inside a string (e.g. a fixture asserting on generated `println(...)`
 * source text) or a KDoc comment is not a false positive.
 */
abstract class NoRawLoggingGuardTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val allowlistedFileNames: SetProperty<String>

    @TaskAction
    fun checkNoRawLogging() {
        val allowlist = allowlistedFileNames.get()
        val violations = mutableListOf<String>()

        sourceFiles.files
            .filter { it.name !in allowlist }
            .sortedBy { it.path }
            .forEach { file -> violations += scan(file) }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n"))
        }
    }

    private fun scan(file: File): List<String> {
        val scrubbed = stripCommentsAndLiterals(file.readText())
        val violations = mutableListOf<String>()

        scrubbed.lineSequence().forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (LOG_CALL.containsMatchIn(line)) {
                violations += violation(file, lineNumber, "android.util.Log")
            }
            if (PRINTLN_CALL.containsMatchIn(line)) {
                violations += violation(file, lineNumber, "println(...)")
            }
        }
        return violations
    }

    private fun violation(
        file: File,
        line: Int,
        what: String,
    ) = "GUARD VIOLATION: raw $what call at ${file.path}:$line — " +
        "use SkeinLog instead (NoRawLogging, spec §9 / E1.I11)."

    companion object {
        private val LOG_CALL = Regex("""\b(?:android\.util\.)?Log\.(?:v|d|i|w|e|wtf)\s*\(""")
        private val PRINTLN_CALL = Regex("""\bprintln\s*\(""")

        /**
         * Replaces the contents of comments and string/char literals with
         * spaces (newlines preserved) so matches never fire inside them,
         * while every non-literal line keeps its original line number.
         */
        internal fun stripCommentsAndLiterals(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            val n = source.length

            fun blank(from: Int, to: Int) {
                for (j in from until to) out.append(if (source[j] == '\n') '\n' else ' ')
            }

            while (i < n) {
                val c = source[i]
                when {
                    c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                        val start = i
                        while (i < n && source[i] != '\n') i++
                        blank(start, i)
                    }
                    c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                        val start = i
                        i += 2
                        while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) i++
                        i = if (i < n) i + 2 else n
                        blank(start, i)
                    }
                    c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        val start = i
                        i += 3
                        while (i < n &&
                            !(i + 2 < n && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')
                        ) {
                            i++
                        }
                        i = if (i + 2 < n) i + 3 else n
                        blank(start, i)
                    }
                    c == '"' -> {
                        val start = i
                        i++
                        while (i < n && source[i] != '"') {
                            i += if (source[i] == '\\' && i + 1 < n) 2 else 1
                        }
                        i = if (i < n) i + 1 else n
                        blank(start, i)
                    }
                    c == '\'' -> {
                        val start = i
                        i++
                        while (i < n && source[i] != '\'') {
                            i += if (source[i] == '\\' && i + 1 < n) 2 else 1
                        }
                        i = if (i < n) i + 1 else n
                        blank(start, i)
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
