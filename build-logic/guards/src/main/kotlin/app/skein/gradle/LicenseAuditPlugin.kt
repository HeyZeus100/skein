package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers a `licenseAudit<Variant>` task for each foss variant, walking the
 * runtime classpath to check licenses against the allowlist (spec §10, E1.I7).
 *
 * The task:
 * - Resolves the foss runtime classpath
 * - Checks each artifact's license from metadata or overrides
 * - Maps to SPDX with overrides for ambiguous/missing licenses
 * - Fails for `foss` variants if any license is outside the allowlist
 * - Emits a report at build/reports/licenses/foss.md
 * - Generates app/src/main/assets/licenses.json for the in-app licenses screen
 *
 * Wire into check task to enforce on all builds.
 */
class LicenseAuditPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val aggregate = project.tasks.register("licenseAudit") {
            group = "verification"
            description = "Fails if any foss variant resolves a license outside the allowlist " +
                "(Apache-2.0, MIT, BSD-*, ISC, CC0, Unlicense, OFL-1.1) — spec §10, E1.I7."
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(aggregate) }

        project.configurations.configureEach {
            val configurationName = name
            // Only process runtime classpath configurations for foss variants
            if (!configurationName.endsWith("RuntimeClasspath")) return@configureEach
            if (!configurationName.contains("foss", ignoreCase = true)) return@configureEach
            if (!isCanBeResolved) return@configureEach

            val taskName = "licenseAudit${configurationName.replaceFirstChar(Char::uppercaseChar)}"
            if (project.tasks.findByName(taskName) != null) return@configureEach

            // Extract artifact license info from the configuration
            val artifactLicensesProvider = incoming.resolutionResult.rootComponent.map { root ->
                val licenses = mutableMapOf<String, String>()
                collectArtifactLicenses(root, licenses)
                licenses
            }

            val overridesFile = project.file("tools/licenses/overrides.json")
            val reportFile =
                project.file("build/reports/licenses/${configurationName.replace("RuntimeClasspath", "")}.md")
            val licensesJsonFile = project.layout.projectDirectory.file("src/main/assets/licenses.json")

            val task = project.tasks.register(taskName, LicenseAuditTask::class.java) {
                group = "verification"
                description = "Audits $configurationName licenses against allowlist"
                this.variant.set(configurationName)
                this.artifactLicenses.set(artifactLicensesProvider)
                if (overridesFile.exists()) {
                    this.overridesFile.set(overridesFile)
                }
                this.reportFile.set(reportFile)
                this.licensesJsonFile.set(licensesJsonFile)
            }

            aggregate.configure { dependsOn(task) }

            // The task writes app/src/main/assets/licenses.json, which is
            // part of the module's main source set. AGP-generated tasks in
            // this module (lint analysis, merge-assets, ...) read that
            // merged source set without Gradle knowing there is a
            // relationship to this task's output, which Gradle's task
            // validation flags as an "implicit dependency" and fails the
            // build on. Order every other task in the project after the
            // license audit so that ambiguity is resolved without turning
            // it into a hard `dependsOn` (see
            // https://docs.gradle.org/current/userguide/validation_problems.html#implicit_dependency).
            project.tasks.configureEach {
                if (name != taskName && name != "clean") {
                    mustRunAfter(task)
                }
            }
        }
    }

    private fun collectArtifactLicenses(
        component: org.gradle.api.artifacts.result.ResolvedComponentResult,
        licenses: MutableMap<String, String>
    ) {
        val visited = mutableSetOf<String>()

        fun visit(comp: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            val id = comp.moduleVersion?.toString() ?: return
            if (!visited.add(id)) return

            comp.moduleVersion?.let { moduleVersion ->
                val key = "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
                // TODO: Extract license from metadata
                // For now, default to UNKNOWN; overrides can provide the correct value
                licenses[key] = "UNKNOWN"
            }

            comp.dependencies.forEach { dep ->
                if (dep is org.gradle.api.artifacts.result.ResolvedDependencyResult && !dep.isConstraint) {
                    visit(dep.selected)
                }
            }
        }

        visit(component)
    }
}
