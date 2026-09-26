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

/**
 * `no-test-doubles-in-main` (skein-xtov.23.18, `docs/ux/UX_TEST_PLAN.md` §6.1
 * and §13.1): the fakes, the fixture corpus and the scenario engine may reach
 * tests and debug-only previews/lab harnesses, never production wiring.
 *
 * Fails the build when either:
 *  1. a declared dependency onto `:testing` / `:testing-*` sits in a
 *     configuration that is not test-only (`test…`, `androidTest…`) and, for
 *     `:testing-fakes`-style modules, not debug-only (`debug…`, `…Debug…`).
 *     `:testing` itself is test-only everywhere: it `api`-exposes JUnit 4
 *     (EPL-1.0, off the foss allowlist), which is why `:testing-fakes` exists
 *     (a `debugImplementation(project(":testing"))` edge once failed `:app`'s
 *     licence audit, skein-64y9);
 *  2. a `src/main/kotlin` file mentions the `app.skein.testing` package in
 *     code (comments and string literals are ignored). A `debugImplementation`
 *     edge puts the fakes on the debug compile classpath of `src/main` too, so
 *     rule 1 alone would let a production file import a fake and compile in
 *     every debug build CI runs; previews and harnesses belong in `src/debug`.
 *
 * All inputs are plain properties so the rule is unit-testable without a real
 * multi-module build (same shape as [IsolationGuardTask]).
 */
abstract class NoTestDoublesInMainGuardTask : DefaultTask() {

    /** `"<configuration> -> <project path>"` for every declared dependency onto a test-double module. */
    @get:Input
    abstract val testDoubleEdges: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun checkNoTestDoublesInMain() {
        val violations = mutableListOf<String>()

        testDoubleEdges.get().sorted().forEach { edge ->
            val configuration = edge.substringBefore(" -> ")
            val path = edge.substringAfter(" -> ")
            if (!isAllowed(configuration, path)) {
                violations += "GUARD VIOLATION: '$configuration(project(\"$path\"))' puts test doubles on a " +
                    "production classpath. Use testImplementation/androidTestImplementation" +
                    (if (path == ":testing") "" else ", or debugImplementation for a src/debug preview") + "."
            }
        }

        sourceFiles.files.sortedBy { it.path }.forEach { file ->
            NoRawLoggingGuardTask.stripCommentsAndLiterals(file.readText())
                .lineSequence()
                .forEachIndexed { index, line ->
                    if (TESTING_PACKAGE.containsMatchIn(line)) {
                        violations += "GUARD VIOLATION: ${file.path}:${index + 1} uses app.skein.testing from " +
                            "src/main. Test doubles belong in src/debug (previews, lab harnesses) or tests."
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n"))
        }
    }

    companion object {
        private val TESTING_PACKAGE = Regex("""\bapp\.skein\.testing\b""")

        internal fun isAllowed(
            configuration: String,
            path: String,
        ): Boolean {
            if (configuration.startsWith("test") || configuration.startsWith("androidTest")) return true
            val debugOnly = configuration.startsWith("debug") || configuration.contains("Debug")
            return debugOnly && path != ":testing"
        }
    }
}
