package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * Registers `checkDependencyGuards`, plus one `checkDependencyGuards<Configuration>`
 * task per resolvable `*RuntimeClasspath` configuration, walking the resolved
 * dependency graph (direct + transitive) for banned groups (spec §2.2).
 *
 * Apply to `:app` — its runtime classpath is where a GMS/Firebase/ML Kit
 * dependency added anywhere in the graph would surface.
 */
class DependencyGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val aggregate = project.tasks.register("checkDependencyGuards") {
            group = "verification"
            description = "Fails if any runtime classpath resolves a banned dependency group " +
                "(Google Play Services, Firebase, Google Play Core, ML Kit) — spec §2.2."
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(aggregate) }

        project.configurations.configureEach {
            val configurationName = name
            if (!configurationName.endsWith("RuntimeClasspath")) return@configureEach
            if (!isCanBeResolved) return@configureEach

            val taskName = "checkDependencyGuards${configurationName.replaceFirstChar(Char::uppercaseChar)}"
            if (project.tasks.findByName(taskName) != null) return@configureEach

            val resolvedGroupsProvider = incoming.resolutionResult.rootComponent.map { root ->
                collectGroups(root)
            }

            val task = project.tasks.register(taskName, DependencyGuardTask::class.java) {
                group = "verification"
                description = "Checks '$configurationName' for banned dependency groups."
                this.configurationName.set(configurationName)
                resolvedGroups.set(resolvedGroupsProvider)
            }
            aggregate.configure { dependsOn(task) }
        }
    }

    private fun collectGroups(root: ResolvedComponentResult): Set<String> {
        val groups = mutableSetOf<String>()
        val visited = mutableSetOf<ResolvedComponentResult>()

        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component)) return
            component.moduleVersion?.let { groups += it.group }
            component.dependencies.forEach { dependency ->
                if (dependency is ResolvedDependencyResult && !dependency.isConstraint) {
                    visit(dependency.selected)
                }
            }
        }

        visit(root)
        return groups
    }
}
