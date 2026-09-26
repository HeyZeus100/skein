package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Registers `checkNoTestDoublesInMain` (skein-xtov.23.18, `docs/ux/UX_TEST_PLAN.md`
 * §6.1) and wires it into `check`. Applied to every subproject from the root
 * `build.gradle.kts`, the same way `app.skein.guard.logging` is.
 *
 * The test-double modules themselves (`:testing`, `:testing-fakes`, any future
 * `:testing-*`) are skipped: depending on each other is their job.
 *
 * See [NoTestDoublesInMainGuardTask] for the rule.
 */
class NoTestDoublesInMainGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (isTestDoubleModule(project.path)) return

        val taskProvider = project.tasks.register("checkNoTestDoublesInMain", NoTestDoublesInMainGuardTask::class.java) {
            group = "verification"
            description = "Fails if production code (src/main, or a non-debug, non-test configuration) " +
                "reaches the :testing/:testing-fakes test doubles (UX_TEST_PLAN.md §6.1)."
            testDoubleEdges.set(project.provider { collectTestDoubleEdges(project) })
            sourceFiles.setFrom(project.fileTree(project.projectDir.resolve("src/main/kotlin")) { include("**/*.kt") })
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(taskProvider) }
    }

    /** `"<configuration> -> <project path>"` for every declared dependency onto a test-double module. */
    private fun collectTestDoubleEdges(project: Project): Set<String> =
        project.configurations.flatMapTo(sortedSetOf()) { configuration ->
            configuration.dependencies
                .withType(ProjectDependency::class.java)
                .filter { isTestDoubleModule(it.path) }
                .map { "${configuration.name} -> ${it.path}" }
        }

    private fun isTestDoubleModule(path: String): Boolean = path == ":testing" || path.startsWith(":testing-")
}
