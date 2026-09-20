package app.skein.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers a `licenseAudit<Variant>RuntimeClasspath` task for each foss
 * variant, walking the runtime classpath to check licenses against the
 * allowlist (spec §10, E1.I7).
 *
 * The task:
 * - Resolves the foss variant's runtime classpath (`variant.runtimeConfiguration`)
 * - Checks each artifact's license from metadata or overrides
 * - Maps to SPDX with overrides for ambiguous/missing licenses
 * - Fails for `foss` variants if any license is outside the allowlist
 * - Emits a report at build/reports/licenses/<variant>.md
 * - Generates build/generated/licenses/<variant>/assets/licenses.json for
 *   the in-app licenses screen, registered as a generated asset source via
 *   the Variant API (see below) rather than written into `src/main/assets`
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

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error(
                "app.skein.guard.license requires the Android application (or library) plugin to be " +
                    "applied first in ${project.path}.",
            )

        androidComponents.onVariants { variant ->
            // Only the foss distribution flavor is subject to the allowlist
            // (spec §10, E1.I7); the dev flavor may pull in non-allowlisted
            // licenses (e.g. proprietary GMS-adjacent deps) intentionally.
            if (!variant.name.contains("foss", ignoreCase = true)) return@onVariants

            val runtimeConfiguration = variant.runtimeConfiguration
            val configurationName = runtimeConfiguration.name
            val taskName = "licenseAudit${configurationName.replaceFirstChar(Char::uppercaseChar)}"

            // Extract artifact license info from the resolved runtime classpath
            val artifactLicensesProvider =
                runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                    val licenses = mutableMapOf<String, String>()
                    collectArtifactLicenses(root, licenses)
                    licenses
                }

            val overridesFile = project.file("tools/licenses/overrides.json")
            val reportFile =
                project.file("build/reports/licenses/${configurationName.replace("RuntimeClasspath", "")}.md")

            val task = project.tasks.register(taskName, LicenseAuditTask::class.java) {
                group = "verification"
                description = "Audits $configurationName licenses against allowlist"
                this.variant.set(configurationName)
                this.artifactLicenses.set(artifactLicensesProvider)
                if (overridesFile.exists()) {
                    this.overridesFile.set(overridesFile)
                }
                this.reportFile.set(reportFile)
                // Build-generated output — deliberately NOT src/main/assets.
                // Each variant gets its own subdirectory: the directory is
                // declared as this task's @OutputDirectory and, below, is
                // registered as a *generated* asset source for exactly this
                // variant via the Variant API, so Gradle's task graph knows
                // this task is the producer that mergeAssets/lint model
                // tasks depend on. Writing straight into `src/main/assets`
                // (the previous approach) made that relationship invisible
                // to Gradle and tripped the "implicit dependency" task-graph
                // validation introduced in Gradle 9.7.1 (skein-iau5).
                outputDir.set(project.layout.buildDirectory.dir("generated/licenses/${variant.name}/assets"))
            }

            aggregate.configure { dependsOn(task) }

            variant.sources.assets?.addGeneratedSourceDirectory(task, LicenseAuditTask::outputDir)
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
