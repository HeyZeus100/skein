package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Registers `checkIsolationGuards`, enforcing the module boundaries from the
 * plan's §2.4 process assignment:
 *  - `:core:model` and `:core:markdown` stay pure Kotlin/JVM (no Android
 *    Gradle plugin), per E1.I2 and E7.I2 respectively.
 *  - `:core:agent` also stays pure Kotlin/JVM (no Android Gradle plugin), per
 *    skein-fvne and `docs/design/VAULT_TOOL_PRIMITIVES.md`. Vault tool
 *    primitives must be consumable from every surface (editor, chat, future
 *    skill dispatcher) and unit-tested on the JVM.
 *  - `:testing` also stays pure Kotlin/JVM (no Android Gradle plugin), per
 *    E10.I1, so `:core:*` and other pure-JVM modules can depend on it
 *    (`testImplementation`) without pulling the Android SDK onto their
 *    classpath.
 *  - `:inference-service` / `:embedder-service` may depend only on
 *    `:core:ipc`, `:core:model`, the Kotlin stdlib/coroutines, and (embedder
 *    only) `onnxruntime-android` — never `:core:vault`, `:core:security`, or
 *    `:app`.
 *
 * Apply to `:core:model`, `:core:agent`, `:testing`, `:inference-service`,
 * and `:embedder-service`. The
 * allowlist is keyed by [Project.getPath] rather than exposed as a DSL
 * extension because the isolated module set is a fixed, non-negotiable part
 * of the architecture (spec §2.6, plan §2.4), not something a module author
 * should be able to loosen from its own build file.
 */
class IsolationGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val taskProvider = project.tasks.register("checkIsolationGuards", IsolationGuardTask::class.java) {
            group = "verification"
            description = "Verifies module isolation boundaries (spec §2.6 / plan §2.4)."
            modulePath.set(project.path)

            if (project.path in PURE_JVM_MODULES) {
                forbidAndroidPlugin.set(true)
                hasAndroidPlugin.set(
                    project.provider {
                        project.pluginManager.hasPlugin("com.android.library") ||
                            project.pluginManager.hasPlugin("com.android.application")
                    },
                )
            }

            SERVICE_ALLOWLISTS[project.path]?.let { allowlist ->
                allowedProjectPaths.set(allowlist.projectPaths)
                allowedExternalGroups.set(allowlist.externalGroups)

                val declared = project.provider { collectDeclaredDependencies(project) }
                declaredProjectDependencies.set(declared.map { it.projectPaths })
                declaredExternalDependencies.set(declared.map { it.externalGroupAndNames })
            }
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(taskProvider) }
    }

    private fun collectDeclaredDependencies(project: Project): DeclaredDependencies {
        val projectPaths = mutableSetOf<String>()
        val externalGroupAndNames = mutableSetOf<String>()

        MAIN_DEPENDENCY_CONFIGURATIONS.forEach { configurationName ->
            project.configurations.findByName(configurationName)?.dependencies?.forEach { dependency ->
                if (dependency is ProjectDependency) {
                    projectPaths += dependency.path
                } else if (dependency.group != null) {
                    externalGroupAndNames += "${dependency.group}:${dependency.name}"
                }
            }
        }

        return DeclaredDependencies(projectPaths, externalGroupAndNames)
    }

    private data class DeclaredDependencies(
        val projectPaths: Set<String>,
        val externalGroupAndNames: Set<String>,
    )

    private data class ServiceAllowlist(
        val projectPaths: Set<String>,
        val externalGroups: Set<String>,
    )

    companion object {
        private val MAIN_DEPENDENCY_CONFIGURATIONS = listOf("implementation", "api", "compileOnly", "runtimeOnly")

        private val PURE_JVM_MODULES = setOf(":core:model", ":core:markdown", ":core:agent", ":testing")

        private val COMMON_SERVICE_PROJECT_ALLOWLIST = setOf(":core:ipc", ":core:model")
        private val COMMON_SERVICE_EXTERNAL_ALLOWLIST = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")

        private val SERVICE_ALLOWLISTS = mapOf(
            ":inference-service" to ServiceAllowlist(
                projectPaths = COMMON_SERVICE_PROJECT_ALLOWLIST,
                externalGroups = COMMON_SERVICE_EXTERNAL_ALLOWLIST,
            ),
            ":embedder-service" to ServiceAllowlist(
                projectPaths = COMMON_SERVICE_PROJECT_ALLOWLIST,
                externalGroups = COMMON_SERVICE_EXTERNAL_ALLOWLIST + "com.microsoft.onnxruntime",
            ),
        )
    }
}
