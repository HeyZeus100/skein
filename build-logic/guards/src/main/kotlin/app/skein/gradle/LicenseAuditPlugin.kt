package app.skein.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File

/**
 * Registers a `licenseAudit<Variant>RuntimeClasspath` task for each foss
 * variant, walking the runtime classpath to check licenses against the
 * allowlist (spec §10, E1.I7).
 *
 * The task:
 * - Resolves the foss variant's runtime classpath (`variant.runtimeConfiguration`)
 * - Downloads each resolved module's `.pom` (via `ArtifactResolutionQuery` —
 *   POM files are metadata, not a configuration "artifact" variant, so a
 *   `ConfigurationContainer.incoming.artifactView` cannot retrieve them) and
 *   extracts its `<licenses>` block with [PomLicenseExtractor]
 * - Normalizes declared license names to SPDX ids via
 *   `tools/licenses/spdx-aliases.json`, falling back to
 *   `tools/licenses/overrides.json` and finally `"UNKNOWN"`
 * - Fails for `foss` variants if any resulting license is outside
 *   `tools/licenses/allowlist.txt`
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

        // tools/licenses/ lives at the repo root, not under this module's
        // projectDir — resolve against rootProject so the allowlist, SPDX
        // alias table, and overrides are actually found regardless of which
        // module applies this plugin.
        val rootDir = project.rootProject.projectDir
        val allowlist = PomLicenseExtractor.loadAllowlist(File(rootDir, "tools/licenses/allowlist.txt"))
        val spdxAliases = PomLicenseExtractor.loadAliases(File(rootDir, "tools/licenses/spdx-aliases.json"))
        val overrides = PomLicenseExtractor.loadOverrides(File(rootDir, "tools/licenses/overrides.json"))
        val extractor = PomLicenseExtractor(spdxAliases, allowlist)

        androidComponents.onVariants { variant ->
            // Only the foss distribution flavor is subject to the allowlist
            // (spec §10, E1.I7); the dev flavor may pull in non-allowlisted
            // licenses (e.g. proprietary GMS-adjacent deps) intentionally.
            if (!variant.name.contains("foss", ignoreCase = true)) return@onVariants

            val runtimeConfiguration = variant.runtimeConfiguration
            val configurationName = runtimeConfiguration.name
            val taskName = "licenseAudit${configurationName.replaceFirstChar(Char::uppercaseChar)}"

            // Deferred to execution/task-graph time — same as the previous
            // `resolutionResult.rootComponent` provider this replaces.
            // Resolving `runtimeConfiguration` eagerly here (at configuration
            // time) trips AGP: it still mutates this configuration's
            // hierarchy later in configuration, and Gradle refuses to touch
            // an already-resolved configuration ("Cannot mutate the
            // hierarchy of configuration ... after the configuration was
            // resolved" / https://github.com/gradle/gradle/issues/2298).
            val artifactLicensesProvider = runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                val keys = mutableSetOf<String>()
                val componentIdsByKey = mutableMapOf<String, ModuleComponentIdentifier>()
                collectArtifactKeys(root, keys, componentIdsByKey)

                val pomFilesByKey = resolvePomFiles(project, componentIdsByKey.values)

                val licenses = mutableMapOf<String, String>()
                val urls = mutableMapOf<String, String>()
                for (key in keys) {
                    val resolution = extractor.resolve(key, pomFilesByKey[key], overrides)
                    licenses[key] = resolution.license
                    urls[key] = resolution.url
                }
                licenses to urls
            }

            val overridesFile = File(rootDir, "tools/licenses/overrides.json")
            val reportFile =
                project.file("build/reports/licenses/${configurationName.replace("RuntimeClasspath", "")}.md")

            val task = project.tasks.register(taskName, LicenseAuditTask::class.java) {
                group = "verification"
                description = "Audits $configurationName licenses against allowlist"
                this.variant.set(configurationName)
                this.artifactLicenses.set(artifactLicensesProvider.map { it.first })
                this.artifactUrls.set(artifactLicensesProvider.map { it.second })
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

    /**
     * Walks the resolved dependency graph, collecting `group:name:version`
     * keys — and, where the resolved component is an external Maven module,
     * its [ModuleComponentIdentifier] (needed to look up its POM) — for
     * every external module. Local project modules (e.g. `:core:model`)
     * have no `moduleVersion` and are skipped, matching the previous
     * behavior.
     */
    private fun collectArtifactKeys(
        component: ResolvedComponentResult,
        keys: MutableSet<String>,
        componentIdsByKey: MutableMap<String, ModuleComponentIdentifier>,
    ) {
        val visited = mutableSetOf<String>()

        fun visit(comp: ResolvedComponentResult) {
            val id = comp.moduleVersion?.toString() ?: return
            if (!visited.add(id)) return

            comp.moduleVersion?.let { moduleVersion ->
                val key = "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
                keys.add(key)
                (comp.id as? ModuleComponentIdentifier)?.let { componentIdsByKey[key] = it }
            }

            comp.dependencies.forEach { dep ->
                if (dep is ResolvedDependencyResult && !dep.isConstraint) {
                    visit(dep.selected)
                }
            }
        }

        visit(component)
    }

    /**
     * Resolves the `.pom` file for each of [componentIds] via
     * `ArtifactResolutionQuery` — the supported public API for fetching a
     * module's metadata artifact (POM) rather than one of its variant
     * artifacts (jar/aar/classes), which is what a `Configuration`'s
     * `artifactView` resolves. Missing/unresolvable POMs are simply absent
     * from the result map; [PomLicenseExtractor] treats that the same as a
     * POM with no `<licenses>` block.
     */
    private fun resolvePomFiles(
        project: Project,
        componentIds: Collection<ModuleComponentIdentifier>,
    ): Map<String, File> {
        if (componentIds.isEmpty()) return emptyMap()

        val result = project.dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()

        val pomFiles = mutableMapOf<String, File>()
        result.resolvedComponents.forEach { componentResult ->
            val id = componentResult.id as? ModuleComponentIdentifier ?: return@forEach
            val key = "${id.group}:${id.module}:${id.version}"
            componentResult.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull()
                ?.let { pomFiles[key] = it.file }
        }
        return pomFiles
    }
}
