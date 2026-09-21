package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers `checkNoRawLogging` (see [NoRawLoggingGuardTask]'s KDoc for why
 * this is a Gradle task, not an Android Lint `Detector`). Applied to every
 * subproject from the root `build.gradle.kts` — spec §9 / `E1.I11`'s
 * acceptance criterion is "every module except `SkeinLog.kt`" — the same
 * way `org.jlleitschuh.gradle.ktlint` already is.
 *
 * `SkeinLog.kt` is allowlisted everywhere (the AC's own exemption). `:app`
 * additionally allowlists `AndroidSkeinLogSink.kt`: `SkeinLog` itself
 * is pure Kotlin/JVM (`:core:model`, isolation-guarded — it cannot import
 * `android.util.Log` at all), so the actual `android.util.Log` call has to
 * live in a small Android-side sink `SkeinApplication` installs; forbidding
 * it entirely would leave `SkeinLog` with no real backing on-device. This is
 * a narrower reading than the AC's literal text but the same intent: no
 * *other* code reaches logcat directly.
 */
class NoRawLoggingGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val taskProvider = project.tasks.register("checkNoRawLogging", NoRawLoggingGuardTask::class.java) {
            group = "verification"
            description = "Fails if raw android.util.Log/println calls exist outside SkeinLog (spec §9 / E1.I11)."

            sourceFiles.setFrom(project.fileTree(project.projectDir.resolve("src/main/kotlin")) { include("**/*.kt") })
            allowlistedFileNames.set(BASE_ALLOWLIST + (EXTRA_ALLOWLIST[project.path] ?: emptySet()))
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(taskProvider) }
    }

    companion object {
        private val BASE_ALLOWLIST = setOf("SkeinLog.kt")

        private val EXTRA_ALLOWLIST = mapOf(
            ":app" to setOf("AndroidSkeinLogSink.kt"),
        )
    }
}
