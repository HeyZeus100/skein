package app.skein.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers `checkManifestGuards`, plus one `checkManifestGuards<Variant>` task
 * per Android variant, wired via the Variant API so every variant's merged
 * manifest (`SingleArtifact.MERGED_MANIFEST`) is checked regardless of which
 * intermediate directory a given AGP version writes it to.
 *
 * Apply to `:app` only — it is the only module with `<service>` declarations.
 */
class ManifestGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error(
                "app.skein.guard.manifest requires the Android application (or library) plugin to be " +
                    "applied first in ${project.path}.",
            )

        val aggregate = project.tasks.register("checkManifestGuards") {
            group = "verification"
            description = "Fails if any variant's merged manifest contains a banned permission or " +
                "component, or the isolated services are missing isolation attributes (spec §2.1/§2.2/§2.6)."
        }

        androidComponents.onVariants { variant ->
            val variantTaskName = "checkManifestGuards${variant.name.replaceFirstChar(Char::uppercaseChar)}"
            val variantTask = project.tasks.register(variantTaskName, ManifestGuardTask::class.java) {
                group = "verification"
                description = "Checks the merged manifest for variant '${variant.name}'."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                variantName.set(variant.name)
            }
            aggregate.configure { dependsOn(variantTask) }
        }

        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(aggregate) }
    }
}
