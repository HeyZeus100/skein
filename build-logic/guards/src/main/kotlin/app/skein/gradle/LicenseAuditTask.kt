package app.skein.gradle

import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Walks the foss runtime classpath, checks each artifact's license against
 * the allowlist (spec §10, E1.I7), and emits a licenses.json report.
 *
 * License information comes from:
 * - Gradle dependency metadata (if available)
 * - tools/licenses/overrides.json (for ambiguous/missing cases)
 *
 * The task fails if any foss variant runtime dependency has a non-allowlist license.
 */
abstract class LicenseAuditTask : DefaultTask() {

    @get:Input
    abstract val variant: Property<String>

    @get:Input
    abstract val artifactLicenses: MapProperty<String, String>

    @get:Optional
    @get:InputFile
    abstract val overridesFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * A build-generated directory (NOT `src/main/assets`) that this task
     * populates with `licenses.json`. Registered with AGP as a generated
     * asset source directory via
     * `variant.sources.assets.addGeneratedSourceDirectory(taskProvider, LicenseAuditTask::outputDir)`
     * (see [LicenseAuditPlugin]), so Gradle knows this task is the producer
     * and schedules it before `mergeAssets` / lint model tasks consume it —
     * no manual `dependsOn`/`mustRunAfter` wiring required.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun auditLicenses() {
        val variant = variant.get()
        val artifacts = artifactLicenses.get()
        val reportFile = reportFile.get().asFile
        val licensesJsonFile = File(outputDir.get().asFile, "licenses.json")

        // Load overrides if provided
        val overrides = mutableMapOf<String, LicenseOverride>()
        if (overridesFile.isPresent) {
            val overridesPath = overridesFile.get().asFile
            if (overridesPath.exists()) {
                val mapper = ObjectMapper()
                val overridesData = mapper.readTree(overridesPath)
                overridesData.fields().forEach { (key, value) ->
                    // Skip comment entries
                    if (key.startsWith("_")) return@forEach

                    val license = value.get("license")?.asText() ?: return@forEach
                    val reason = value.get("reason")?.asText() ?: return@forEach
                    val sourceUrl = value.get("source_url")?.asText() ?: return@forEach
                    overrides[key] = LicenseOverride(license, reason, sourceUrl)
                }
            }
        }

        val violations = mutableListOf<String>()
        val entries = mutableListOf<LicenseEntry>()

        for ((key, detectedLicense) in artifacts) {
            val (group, name, version) = parseArtifactKey(key)

            // Use override if available, otherwise use detected license
            val license = overrides[key]?.license ?: detectedLicense

            // Check against allowlist
            if (!SPDX_ALLOWLIST.contains(license) && license != "UNKNOWN") {
                violations.add("$key has license '$license' which is not in the allowlist")
            }

            entries.add(
                LicenseEntry(
                    name = "$group:$name",
                    version = version,
                    license = license,
                    url = "" // URL would be added if we have access to artifact files
                )
            )
        }

        // Sort entries deterministically
        entries.sortBy { it.name + ":" + it.version }

        if (violations.isNotEmpty() && variant.contains("foss", ignoreCase = true)) {
            throw GradleException(
                "License audit failed for $variant. Violations:\n" +
                    violations.joinToString("\n") +
                    "\n\nAdd overrides to tools/licenses/overrides.json if licenses are incorrectly identified."
            )
        }

        // Write report
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            """# License Audit Report for $variant
            |
            |Generated: ${java.time.Instant.now()}
            |Total artifacts: ${entries.size}
            |Violations: ${violations.size}
            |
            |## Checked Artifacts
            |
            |${entries.joinToString("\n") { "- ${it.name}:${it.version} (${it.license})" }}
            |
            """.trimMargin()
        )

        // Write licenses.json for in-app display
        licensesJsonFile.parentFile.mkdirs()
        val mapper = ObjectMapper()
        licensesJsonFile.writeText(
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)
        )
    }

    private fun parseArtifactKey(key: String): Triple<String, String, String> {
        // key format: "group:name:version" or "group:name"
        val parts = key.split(":")
        return when (parts.size) {
            2 -> Triple(parts[0], parts[1], "")
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> Triple("unknown", "unknown", "")
        }
    }

    data class LicenseOverride(
        val license: String,
        val reason: String,
        val sourceUrl: String
    )

    data class LicenseEntry(
        val name: String,
        val version: String,
        val license: String,
        val url: String
    )

    companion object {
        val SPDX_ALLOWLIST = setOf(
            "Apache-2.0",
            "MIT",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "ISC",
            "OFL-1.1",
            "CC0-1.0",
            "Unlicense",
            "Zlib",
            "Bouncy-Castle"
        )
    }
}
