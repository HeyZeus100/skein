package app.skein.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails the build (spec §2.1, §2.2, §2.6) if a variant's merged manifest:
 *  - requests a banned permission (`INTERNET`, `ACCESS_NETWORK_STATE`), or
 *  - declares a `com.google.android.gms` component, or
 *  - is missing `android:isolatedProcess="true"` / `android:exported="false"`
 *    on the isolated inference/embedder services.
 *
 * The failure message always starts with `GUARD VIOLATION` so CI logs and
 * TestKit assertions can grep for it (plan E1.I2, step 1).
 */
abstract class ManifestGuardTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun checkManifest() {
        val manifestFile = mergedManifest.get().asFile
        val variant = variantName.get()
        val violations = mutableListOf<String>()
        val rawText = manifestFile.readText()

        BANNED_PERMISSIONS.forEach { permission ->
            if (rawText.contains(permission)) {
                violations += "GUARD VIOLATION: banned permission '$permission' found in the merged " +
                    "manifest for variant '$variant' (${manifestFile.path}). Spec §2.1/§2.2 forbid it."
            }
        }
        if (rawText.contains(BANNED_COMPONENT_PREFIX)) {
            violations += "GUARD VIOLATION: a '$BANNED_COMPONENT_PREFIX' component/reference was found in " +
                "the merged manifest for variant '$variant' (${manifestFile.path}). Spec §2.2 forbids " +
                "Google Play Services."
        }

        violations += checkIsolatedServices(manifestFile, variant)

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n"))
        }
    }

    private fun checkIsolatedServices(manifestFile: java.io.File, variant: String): List<String> {
        val violations = mutableListOf<String>()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)

        val services = document.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val name = service.getAttributeNS(ANDROID_NS, "name")
            if (ISOLATED_SERVICE_SIMPLE_NAMES.none { name.endsWith(it) }) continue

            val isolatedProcess = service.getAttributeNS(ANDROID_NS, "isolatedProcess")
            val exported = service.getAttributeNS(ANDROID_NS, "exported")

            if (isolatedProcess != "true") {
                violations += "GUARD VIOLATION: service '$name' must declare " +
                    "android:isolatedProcess=\"true\" (spec §2.6) in variant '$variant', found " +
                    "'${isolatedProcess.ifEmpty { "<absent>" }}'."
            }
            if (exported != "false") {
                violations += "GUARD VIOLATION: service '$name' must declare android:exported=\"false\" " +
                    "in variant '$variant', found '${exported.ifEmpty { "<absent>" }}'."
            }
        }
        return violations
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val BANNED_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )

        const val BANNED_COMPONENT_PREFIX = "com.google.android.gms"

        val ISOLATED_SERVICE_SIMPLE_NAMES = listOf(
            "InferenceService",
            "EmbedderService",
        )
    }
}
