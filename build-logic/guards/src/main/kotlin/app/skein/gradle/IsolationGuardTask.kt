package app.skein.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build (spec §2.6, plan §2.4 process assignment) if:
 *  - a module that must stay pure Kotlin/JVM (`:core:model`) applies an
 *    Android Gradle plugin, or
 *  - `:inference-service` / `:embedder-service` declare a project or external
 *    dependency outside their isolation allowlist (e.g. `:core:vault`,
 *    `:core:security`, `:app`).
 *
 * All inputs are plain properties so the check logic is unit-testable without
 * configuring a real multi-module project.
 */
abstract class IsolationGuardTask : DefaultTask() {

    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    @get:Optional
    abstract val forbidAndroidPlugin: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val hasAndroidPlugin: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val allowedProjectPaths: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val allowedExternalGroups: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val declaredProjectDependencies: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val declaredExternalDependencies: SetProperty<String>

    @TaskAction
    fun checkIsolation() {
        val module = modulePath.get()
        val violations = mutableListOf<String>()

        if (forbidAndroidPlugin.getOrElse(false) && hasAndroidPlugin.getOrElse(false)) {
            violations += "GUARD VIOLATION: module '$module' must remain pure Kotlin/JVM (no Android SDK " +
                "dependency) but applies an Android Gradle plugin."
        }

        val allowedProjects = allowedProjectPaths.getOrElse(emptySet())
        (declaredProjectDependencies.getOrElse(emptySet()) - allowedProjects).sorted().forEach { path ->
            violations += "GUARD VIOLATION: module '$module' declares a forbidden project dependency on " +
                "'$path'. Isolated service modules may depend only on ${allowedProjects.sorted()}."
        }

        val allowedGroups = allowedExternalGroups.getOrElse(emptySet())
        declaredExternalDependencies.getOrElse(emptySet()).sorted().forEach { groupAndName ->
            val group = groupAndName.substringBefore(':')
            val allowed = allowedGroups.any { allowedGroup -> group == allowedGroup || group.startsWith("$allowedGroup.") }
            if (!allowed) {
                violations += "GUARD VIOLATION: module '$module' declares a dependency outside its " +
                    "isolation allowlist: '$groupAndName'."
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n"))
        }
    }
}
